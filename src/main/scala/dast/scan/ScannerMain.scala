package dast.scan

import scala.concurrent.ExecutionContext

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import dast.Authorization
import dast.DastConfig
import dast.scan.ScanOrchestrator.ScanComplete
import dast.scan.ScanOrchestrator.Start

/** Runnable DAST scanner. Scans one target and prints a findings report.
  *
  * Observe-only by default (capture + Tier 1, no active probing). To authorize
  * active probing, set `DAST_AUTHORIZED_HOSTS` to a comma-separated host list;
  * the analyzer additionally needs `ANTHROPIC_API_KEY` (it fails closed to a
  * no-op otherwise). This is wiring around already-tested components and is
  * exercised only by a live run against a consenting target.
  *
  * Usage: sbt "runMain dast.scan.ScannerMain https://example.com/path?q=1"
  * DAST_AUTHORIZED_HOSTS=example.com ANTHROPIC_API_KEY=sk-... \ sbt "runMain
  * dast.scan.ScannerMain https://example.com/path?q=1"
  */
object ScannerMain:

  def main(args: Array[String]): Unit = args.headOption.filter(_.nonEmpty) match
    case None =>
      Console.err.println("usage: ScannerMain <target-url>")
      sys.exit(2)
    case Some(target) =>
      ActorSystem(guardian(target, authorization), "dast-scanner")

  /** Per-navigation timeout in ms (env or .env.local: DAST_NAV_TIMEOUT_MS). */
  private def navTimeoutMs: Int = DastConfig.getInt("DAST_NAV_TIMEOUT_MS", 30000)

  /** Active scope from DAST_AUTHORIZED_HOSTS (env or .env.local); observe-only
    * when unset.
    */
  private def authorization: Authorization = DastConfig
    .get("DAST_AUTHORIZED_HOSTS") match
    case Some(hosts) => Authorization
        .active(hosts.split(",").map(_.trim).toIndexedSeq*)
    case None => Authorization.ObserveOnly

  private def guardian(
      target: String,
      auth: Authorization,
  ): Behavior[ScanComplete] = Behaviors.setup { ctx =>
    given ExecutionContext = ctx.executionContext
    given ActorSystem[?] = ctx.system

    ctx.log.info(
      "Scanning {} (active scope: {})",
      target,
      if auth.allowActive then auth.authorizedHosts.mkString(",")
      else "observe-only",
    )
    val scanner = Scanner.spawn(ctx, auth, navTimeoutMs = navTimeoutMs)
    scanner ! Start(target, ctx.self)

    Behaviors.receiveMessage { case ScanComplete(scanned, findings) =>
      println(FindingsReport.render(scanned, findings))
      ctx.log.info("Done; {} finding(s).", findings.size)
      Behaviors.stopped
    }
  }
