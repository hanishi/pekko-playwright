package dast.scan

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import dast.Finding

/** Scans a whole site: discover the in-scope URLs from a seed, then run the
  * single-URL scan ([[ScanOrchestrator]], via the injected `scanOne`) on each,
  * aggregating into one per-URL report.
  *
  * Discovery is read-only; per-URL active probing stays gated by `ConsentGate`
  * inside `scanOne`. Effects are injected so this crawl-then-scan-each loop is
  * testable with stubs. URLs are scoped/deduped/capped by [[Scope]]; a failed
  * `scanOne` contributes an empty result rather than aborting the site scan.
  */
object SiteScanOrchestrator:

  sealed trait Command

  /** Begin a site scan from `seed`; the aggregate goes to `replyTo`. */
  final case class Start(seed: String, replyTo: ActorRef[SiteScanComplete]) extends Command

  /** Findings grouped per scanned URL (seed first). */
  final case class SiteScanComplete(seed: String, results: Vector[(String, Vector[Finding])])

  /** Injected effects, for testability. */
  final case class Effects(
      discover: String => Future[Seq[String]],
      scanOne: String => Future[Vector[Finding]],
  )

  private final case class Discovered(urls: Seq[String]) extends Command
  private final case class DiscoverFailed(reason: String) extends Command
  private final case class Scanned(url: String, findings: Vector[Finding]) extends Command

  def apply(effects: Effects, maxPages: Int = 20): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.receiveMessagePartial { case Start(seed, replyTo) =>
        new SiteScanOrchestrator(ctx, effects, maxPages, seed, replyTo).begin()
      }
    }

private class SiteScanOrchestrator(
    ctx: ActorContext[SiteScanOrchestrator.Command],
    effects: SiteScanOrchestrator.Effects,
    maxPages: Int,
    seed: String,
    replyTo: ActorRef[SiteScanOrchestrator.SiteScanComplete],
):
  import SiteScanOrchestrator.*

  private given ExecutionContext = ctx.executionContext

  def begin(): Behavior[Command] =
    ctx.pipeToSelf(effects.discover(seed)) {
      case Success(urls) => Discovered(urls)
      case Failure(e)    => DiscoverFailed(Option(e.getMessage).getOrElse(e.toString))
    }
    awaitingDiscovery

  private def awaitingDiscovery: Behavior[Command] = Behaviors.receiveMessagePartial {
    case Discovered(urls) =>
      scanNext(Scope.frontier(seed, urls, maxPages), Vector.empty)
    case DiscoverFailed(reason) =>
      // Fall back to scanning just the seed rather than aborting.
      ctx.log.warn("Discovery failed for {}: {}; scanning seed only", seed, reason)
      scanNext(Scope.frontier(seed, Seq.empty, maxPages), Vector.empty)
  }

  private def scanNext(
      remaining: Seq[String],
      acc: Vector[(String, Vector[Finding])],
  ): Behavior[Command] =
    remaining match
      case Seq() => finish(acc)
      case url +: rest =>
        ctx.pipeToSelf(effects.scanOne(url)) {
          case Success(fs) => Scanned(url, fs)
          case Failure(_)  => Scanned(url, Vector.empty) // fail soft
        }
        awaitingScan(rest, acc)

  private def awaitingScan(
      remaining: Seq[String],
      acc: Vector[(String, Vector[Finding])],
  ): Behavior[Command] = Behaviors.receiveMessagePartial { case Scanned(url, findings) =>
    scanNext(remaining, acc :+ (url -> findings))
  }

  private def finish(results: Vector[(String, Vector[Finding])]): Behavior[Command] =
    val total = results.map(_._2.size).sum
    ctx.log.info("Site scan complete for {}: {} url(s), {} finding(s)", seed, results.size, total)
    replyTo ! SiteScanComplete(seed, results)
    Behaviors.stopped
