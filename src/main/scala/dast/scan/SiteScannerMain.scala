package dast.scan

import scala.concurrent.ExecutionContext

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import dast.Authorization
import dast.DastConfig
import dast.scan.SiteScanOrchestrator.SiteScanComplete
import dast.scan.SiteScanOrchestrator.Start

/** Runnable site scanner: crawls in-scope URLs from a seed and scans each,
  * printing a grouped findings report.
  *
  * Observe-only by default (capture + Tier 1 per page). Active probing /
  * sink-scan is opt-in via `DAST_AUTHORIZED_HOSTS` and needs
  * `ANTHROPIC_API_KEY` for the analyzer (fails closed otherwise).
  * `DAST_MAX_PAGES` (default 20), `DAST_MAX_DEPTH` (default 2),
  * `DAST_NAV_TIMEOUT_MS` (default 30000) tune the crawl. Wiring around tested
  * components; exercised only by a live run against a consenting target.
  *
  * Usage: sbt "runMain dast.scan.SiteScannerMain https://example.com/"
  */
object SiteScannerMain:

  def main(args: Array[String]): Unit = args.headOption.filter(_.nonEmpty) match
    case None =>
      Console.err.println("usage: SiteScannerMain <seed-url>")
      sys.exit(2)
    case Some(seed) =>
      ActorSystem(guardian(seed, authorization), "dast-site-scanner")

  private def envInt(name: String, default: Int): Int = DastConfig
    .getInt(name, default)

  private def authorization: Authorization = DastConfig
    .get("DAST_AUTHORIZED_HOSTS") match
    case Some(hosts) => Authorization
        .active(hosts.split(",").map(_.trim).toIndexedSeq*)
    case None => Authorization.ObserveOnly

  private def guardian(
      seed: String,
      auth: Authorization,
  ): Behavior[SiteScanComplete] = Behaviors.setup { ctx =>
    given ExecutionContext = ctx.executionContext
    given ActorSystem[?] = ctx.system

    ctx.log.info(
      "Site-scanning {} (active scope: {}, maxPages={}, maxDepth={})",
      seed,
      if auth.allowActive then auth.authorizedHosts.mkString(",")
      else "observe-only",
      envInt("DAST_MAX_PAGES", 20),
      envInt("DAST_MAX_DEPTH", 2),
    )
    val site = Scanner.spawnSite(
      ctx,
      auth,
      navTimeoutMs = envInt("DAST_NAV_TIMEOUT_MS", 30000),
      maxDepth = envInt("DAST_MAX_DEPTH", 2),
      maxPages = envInt("DAST_MAX_PAGES", 20),
    )
    site ! Start(seed, ctx.self)

    Behaviors.receiveMessage { case SiteScanComplete(scanned, results) =>
      println(FindingsReport.renderSite(scanned, results))
      ctx.log.info(
        "Done; {} url(s), {} finding(s).",
        results.size,
        results.map(_._2.size).sum,
      )
      Behaviors.stopped
    }
  }
