package dast.scan

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import dast.ActionClass
import dast.Authorization
import dast.ClientStateSnapshot
import dast.ConsentGate
import dast.Finding
import dast.GateDecision
import dast.InjectionPoint
import dast.LlmDecision
import dast.LlmDecision.*
import dast.Markers
import dast.SinkScanOp
import dast.Tier1
import dast.analyzer.AnalyzerContext

/** Runs a bounded scan of one authorized target by composing the DAST pieces.
  *
  * Capture client state once, run the deterministic Tier 1 checks (always),
  * then loop up to `maxSteps`: ask the analyzer for one decision and act on it.
  * `Probe` decisions are re-checked against the [[ConsentGate]] here (so an
  * observe-only authorization yields a capture + Tier 1 scan with no active
  * work) and, when permitted, run [[dast.ProbeOp]]. `Done` or budget exhaustion
  * finishes the scan with all findings.
  *
  * Effects (capture / analyze / probe) are injected so the loop is testable
  * with stubs and no browser or model. All futures are delivered via
  * `ctx.pipeToSelf`, so the actor never blocks and every failure folds to a
  * safe step (the analyzer already fails closed to `Done`).
  */
object ScanOrchestrator:

  sealed trait Command

  /** Begin a scan of `target`; the result is sent to `replyTo`. */
  final case class Start(target: String, replyTo: ActorRef[ScanComplete])
      extends Command

  /** The scan result: every finding (Tier 1 + confirmed probes). */
  final case class ScanComplete(target: String, findings: Vector[Finding])

  /** The browser/model effects, injected for testability. `sinkScan` delivers a
    * benign marker through a source and returns the DOM sinks it reached.
    */
  final case class Effects(
      capture: String => Future[ClientStateSnapshot],
      analyze: AnalyzerContext => Future[LlmDecision],
      probe: (String, InjectionPoint, String, String) => Future[Option[Finding]],
      sinkScan: (String, InjectionPoint, String) => Future[Set[String]],
  )

  private final case class SnapshotReady(snapshot: ClientStateSnapshot)
      extends Command
  private final case class CaptureFailed(reason: String) extends Command
  private final case class SinkScanReady(
      snapshot: ClientStateSnapshot,
      sinks: Set[String],
  ) extends Command
  private final case class DecisionReady(decision: LlmDecision) extends Command
  private final case class ProbeResult(finding: Option[Finding]) extends Command

  /** Injection-point candidates derived from a URL's query-param names. Pure.
    */
  def injectionPointsOf(url: String): Seq[String] = Try(
    new java.net.URI(url).getRawQuery,
  ).toOption.flatMap(Option(_))
    .map(_.split("&").toSeq.filter(_.nonEmpty).map(_.split("=", 2)(0)).distinct)
    .getOrElse(Seq.empty)

  def apply(
      auth: Authorization,
      effects: Effects,
      maxSteps: Int = 8,
      freshMarker: () => String = () => Markers.fresh(),
  ): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.receiveMessagePartial { case Start(target, replyTo) =>
      new ScanOrchestrator(
        ctx,
        auth,
        effects,
        maxSteps,
        freshMarker,
        target,
        replyTo,
      ).begin()
    }
  }

private class ScanOrchestrator(
    ctx: ActorContext[ScanOrchestrator.Command],
    auth: Authorization,
    effects: ScanOrchestrator.Effects,
    maxSteps: Int,
    freshMarker: () => String,
    target: String,
    replyTo: ActorRef[ScanOrchestrator.ScanComplete],
):
  import ScanOrchestrator.*

  private given ExecutionContext = ctx.executionContext

  def begin(): Behavior[Command] =
    ctx.pipeToSelf(effects.capture(target)) {
      case Success(s) => SnapshotReady(s)
      case Failure(e) => CaptureFailed(Option(e.getMessage).getOrElse(e.toString))
    }
    awaitingCapture

  private def awaitingCapture: Behavior[Command] = Behaviors
    .receiveMessagePartial {
      case SnapshotReady(snapshot) =>
        val tier1 = Tier1.run(snapshot).toVector
        // A DOM sink-scan is active work (it injects a marker), so it only runs
        // under an authorized active scope; otherwise go straight to the loop.
        ConsentGate.decide(auth, ActionClass.Active, target) match
          case GateDecision.Permit =>
            ctx.pipeToSelf(
              effects.sinkScan(target, InjectionPoint.Fragment, freshMarker()),
            ) {
              case Success(sinks) => SinkScanReady(snapshot, sinks)
              case Failure(_) => SinkScanReady(snapshot, Set.empty)
            }
            awaitingSinkScan(tier1)
          case GateDecision.Deny(_) =>
            // Observe-only: capture + Tier 1 only. No sink-scan, and no analyzer
            // call (the model is active-path machinery), so it stays free and
            // fully deterministic.
            finish(tier1)
      case CaptureFailed(reason) =>
        ctx.log.warn("Capture failed for {}: {}", target, reason)
        finish(Vector.empty)
    }

  private def awaitingSinkScan(tier1: Vector[Finding]): Behavior[Command] =
    Behaviors.receiveMessagePartial { case SinkScanReady(snapshot, sinks) =>
      val dom = SinkScanOp.toFindings(InjectionPoint.Fragment, sinks).toVector
      step(snapshot, tier1 ++ dom, maxSteps)
    }

  private def step(
      snapshot: ClientStateSnapshot,
      findings: Vector[Finding],
      budget: Int,
  ): Behavior[Command] =
    if budget <= 0 then finish(findings)
    else
      val context = AnalyzerContext
        .fromSnapshot(snapshot, injectionPointIds = injectionPointsOf(target))
      ctx.pipeToSelf(effects.analyze(context)) {
        case Success(d) => DecisionReady(d)
        case Failure(_) => DecisionReady(LlmDecision.Done) // fail closed
      }
      awaitingDecision(snapshot, findings, budget)

  private def awaitingDecision(
      snapshot: ClientStateSnapshot,
      findings: Vector[Finding],
      budget: Int,
  ): Behavior[Command] = Behaviors.receiveMessagePartial {
    case DecisionReady(Done) => finish(findings)
    case DecisionReady(Probe(injectionPointId, payloadId)) =>
      ConsentGate.decide(auth, ActionClass.Active, target) match
        case GateDecision.Permit =>
          val point = InjectionPoint.QueryParam(injectionPointId)
          ctx.pipeToSelf(effects.probe(target, point, payloadId, freshMarker())) {
            case Success(f) => ProbeResult(f)
            case Failure(_) => ProbeResult(None)
          }
          awaitingProbe(snapshot, findings, budget)
        case GateDecision.Deny(reason) =>
          ctx.log.info("Probe denied for {}: {}", target, reason)
          step(snapshot, findings, budget - 1)
    case DecisionReady(_) => // Navigate / Classify: acknowledged, no state change this slice
      step(snapshot, findings, budget - 1)
  }

  private def awaitingProbe(
      snapshot: ClientStateSnapshot,
      findings: Vector[Finding],
      budget: Int,
  ): Behavior[Command] = Behaviors
    .receiveMessagePartial { case ProbeResult(found) =>
      step(snapshot, findings ++ found.toVector, budget - 1)
    }

  private def finish(findings: Vector[Finding]): Behavior[Command] =
    ctx.log.info("Scan complete for {}: {} finding(s)", target, findings.size)
    replyTo ! ScanComplete(target, findings)
    Behaviors.stopped
