package dast.scan

import scala.concurrent.ExecutionContext

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.ActorContext

import crawler.BrowserResource
import crawler.pool.ResourcePool
import crawler.pool.ResourcePool.asPool
import crawler.pool.ResourcePool.submit
import dast.Authorization
import dast.CaptureOp
import dast.ProbeOp
import dast.analyzer.ClaudeAnalyzer

/** Assembles the real, pool- and Claude-backed [[ScanOrchestrator.Effects]] and
  * spawns an orchestrator. This is wiring, not logic: it is exercised only by a
  * live scan (a consenting target + `ANTHROPIC_API_KEY`), so it is not unit
  * tested — the orchestrator loop is tested with stubbed effects instead.
  *
  * The browser pool is a [[crawler.pool.ResourcePool]] of [[BrowserResource]]
  * on the `session-pinned-dispatcher`, exactly as the crawler builds it; all
  * capture and probe work goes through `pool.submit` to stay on the pinned
  * thread (CLAUDE.md section 0.1).
  */
object Scanner:

  /** Spawn a browser pool and a scan orchestrator wired to it. `auth` defaults
    * to observe-only, so a scan does capture + Tier 1 only unless the caller
    * passes an authorization with active scope.
    */
  def spawn(
      ctx: ActorContext[?],
      auth: Authorization = Authorization.ObserveOnly,
      poolSize: Int = 2,
      navTimeoutMs: Int = 30000,
  )(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): ActorRef[ScanOrchestrator.Command] =
    val poolRef = ctx.spawn(
      ResourcePool[BrowserResource](
        size = poolSize,
        make = i =>
          new BrowserResource(
            i,
            None,
            BrowserResource.Settings(navigationTimeoutMs = navTimeoutMs),
          ),
      ),
      "dast-browser-pool",
    )
    val pool = poolRef.asPool[BrowserResource]

    val effects = ScanOrchestrator.Effects(
      capture = url => pool.submit(r => CaptureOp.capture(r, url)),
      analyze = context => ClaudeAnalyzer.analyze(context),
      probe = (baseUrl, point, payloadId, marker) =>
        pool.submit(r =>
          ProbeOp.probe(r, auth, baseUrl, point, payloadId, marker, navTimeoutMs),
        ).map(_.toOption.flatten),
    )

    ctx.spawn(ScanOrchestrator(auth, effects), "dast-scan-orchestrator")
