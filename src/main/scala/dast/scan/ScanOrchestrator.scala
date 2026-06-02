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
import dast.FindingKind
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
      // Deterministic HTTP probes over the URL's query params (browser-free).
      redirectScan: String => Future[Vector[Finding]] =
        _ => Future.successful(Vector.empty),
      sqlScan: String => Future[Vector[Finding]] =
        _ => Future.successful(Vector.empty),
  )

  private final case class SnapshotReady(snapshot: ClientStateSnapshot)
      extends Command
  private final case class CaptureFailed(reason: String) extends Command
  private final case class SinkScanReady(
      snapshot: ClientStateSnapshot,
      sinks: Set[String],
  ) extends Command
  private final case class HttpScanReady(
      snapshot: ClientStateSnapshot,
      findings: Vector[Finding],
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
        ctx.log.info(
          "Captured {}: {} cookie(s) [{}], {} localStorage, {} sessionStorage key(s)",
          target,
          snapshot.cookies.size,
          snapshot.cookies.map(_.name).mkString(", "),
          snapshot.localStorage.size,
          snapshot.sessionStorage.size,
        )
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
      // Deterministic, browser-free HTTP probes run together before the model
      // loop. Each fails soft to no findings.
      val http = effects.redirectScan(target).zip(effects.sqlScan(target))
        .map((r, s) => r ++ s)
      ctx.pipeToSelf(http) {
        case Success(fs) => HttpScanReady(snapshot, tier1 ++ dom ++ fs)
        case Failure(_) => HttpScanReady(snapshot, tier1 ++ dom)
      }
      awaitingHttpScan
    }

  private def awaitingHttpScan: Behavior[Command] = Behaviors
    .receiveMessagePartial { case HttpScanReady(snapshot, findings) =>
      val redirects = findings.count(_.kind == FindingKind.OpenRedirect)
      val sqli = findings.count(_.kind == FindingKind.SqlInjection)
      if redirects > 0 || sqli > 0 then
        ctx.log.info(
          "HTTP probes on {}: {} open-redirect, {} SQLi",
          target,
          redirects,
          sqli,
        )
      step(snapshot, findings, maxSteps, Set.empty)
    }

  private def step(
      snapshot: ClientStateSnapshot,
      findings: Vector[Finding],
      budget: Int,
      attempted: Set[(String, String)],
  ): Behavior[Command] =
    if budget <= 0 then finish(findings)
    else
      val points = injectionPointsOf(target)
      ctx.log.info(
        "Asking analyzer (step {}/{}) for {}: injection points [{}]",
        maxSteps - budget + 1,
        maxSteps,
        target,
        points.mkString(", "),
      )
      val context = AnalyzerContext
        .fromSnapshot(snapshot, injectionPointIds = points)
      ctx.pipeToSelf(effects.analyze(context)) {
        case Success(d) => DecisionReady(d)
        case Failure(_) => DecisionReady(LlmDecision.Done) // fail closed
      }
      awaitingDecision(snapshot, findings, budget, attempted)

  private def awaitingDecision(
      snapshot: ClientStateSnapshot,
      findings: Vector[Finding],
      budget: Int,
      attempted: Set[(String, String)],
  ): Behavior[Command] = Behaviors.receiveMessagePartial {
    case DecisionReady(Done) =>
      ctx.log.info("Analyzer decided: Done for {}", target)
      finish(findings)
    case DecisionReady(Probe(injectionPointId, payloadId)) =>
      ctx.log.info(
        "Analyzer decided: Probe param '{}' with payload '{}' on {}",
        injectionPointId,
        payloadId,
        target,
      )
      val key = (injectionPointId, payloadId)
      if attempted.contains(key) then
        // The analyzer re-selected a probe we already ran; it has converged
        // with nothing new to try, so finish rather than burn the budget
        // re-confirming the same finding.
        ctx.log.info(
          "Probe '{}'/'{}' already attempted on {}; finishing",
          injectionPointId,
          payloadId,
          target,
        )
        finish(findings)
      else
        ConsentGate.decide(auth, ActionClass.Active, target) match
          case GateDecision.Permit =>
            val point = InjectionPoint.QueryParam(injectionPointId)
            ctx.pipeToSelf(effects.probe(target, point, payloadId, freshMarker())) {
              case Success(f) => ProbeResult(f)
              case Failure(_) => ProbeResult(None)
            }
            awaitingProbe(snapshot, findings, budget, attempted + key)
          case GateDecision.Deny(reason) =>
            ctx.log.info("Probe denied for {}: {}", target, reason)
            step(snapshot, findings, budget - 1, attempted + key)
    case DecisionReady(other) => // Navigate / Classify: acknowledged, no state change this slice
      ctx.log.info(
        "Analyzer decided: {} for {} (no action this slice)",
        other,
        target,
      )
      step(snapshot, findings, budget - 1, attempted)
  }

  private def awaitingProbe(
      snapshot: ClientStateSnapshot,
      findings: Vector[Finding],
      budget: Int,
      attempted: Set[(String, String)],
  ): Behavior[Command] = Behaviors
    .receiveMessagePartial { case ProbeResult(found) =>
      found match
        case Some(f) => ctx.log
            .info("Probe CONFIRMED on {}: {}", target, f.evidence)
        case None => ctx.log
            .info("Probe not confirmed on {} (no execution)", target)
      // Dedupe by replay handle so the same finding is never recorded twice.
      val merged = found.toVector.foldLeft(findings)((acc, f) =>
        if acc.exists(_.replay == f.replay) then acc else acc :+ f,
      )
      step(snapshot, merged, budget - 1, attempted)
    }

  private def finish(findings: Vector[Finding]): Behavior[Command] =
    ctx.log.info("Scan complete for {}: {} finding(s)", target, findings.size)
    replyTo ! ScanComplete(target, findings)
    Behaviors.stopped
