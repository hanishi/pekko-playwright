package dast.scan

import scala.concurrent.ExecutionContext
import scala.util.Try

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import dast.AccessControlCheck
import dast.AccessControlCheck.Identity
import dast.Authorization
import dast.DastConfig
import dast.Finding

/** Runnable IDOR scanner for SPA targets.
  *
  * Loads the app in an authenticated browser (login or cookie from the identity
  * spec), captures the same-host requests its JavaScript makes (XHR / fetch),
  * and runs the LLM-planned IDOR check on the captured API endpoints. The
  * browser observes the API surface a link crawl cannot see. Active, gated
  * against `DAST_AUTHORIZED_HOSTS`; needs `ANTHROPIC_API_KEY` for the planner.
  *
  * Usage: sbt "runMain dast.scan.SpaIdorScannerMain <app-url> <identity-spec.json>"
  */
object SpaIdorScannerMain:

  def main(args: Array[String]): Unit =
    val url = args.headOption.filter(_.nonEmpty)
    val specPath = args.drop(1).headOption.filter(_.nonEmpty)
      .orElse(DastConfig.get("DAST_ACCESS_SPEC"))
    (url, specPath) match
      case (Some(target), Some(path)) => loadIdentity(path) match
          case Right(identity) => ActorSystem(
              guardian(target, identity, authorization),
              "dast-spa-idor-scanner",
            )
          case Left(err) =>
            Console.err.println(err)
            sys.exit(2)
      case _ =>
        Console.err.println("usage: SpaIdorScannerMain <app-url> <identity-spec.json>")
        sys.exit(2)

  private def loadIdentity(path: String): Either[String, Identity] = Try {
    val src = scala.io.Source.fromFile(path, "UTF-8")
    try src.mkString
    finally src.close()
  }.toEither.left.map(e => s"cannot read spec '$path': ${e.getMessage}")
    .flatMap(AccessControlCheck.parseSpec(_).left.map(e => s"invalid spec: $e"))
    .flatMap(_.identities.values.headOption.toRight("spec has no identities"))

  private def navTimeoutMs: Int = DastConfig.getInt("DAST_NAV_TIMEOUT_MS", 30000)

  private def authorization: Authorization = DastConfig
    .get("DAST_AUTHORIZED_HOSTS") match
    case Some(hosts) => Authorization
        .active(hosts.split(",").map(_.trim).toIndexedSeq*)
    case None => Authorization.ObserveOnly

  private def guardian(
      url: String,
      identity: Identity,
      auth: Authorization,
  ): Behavior[Vector[Finding]] = Behaviors.setup { ctx =>
    given ExecutionContext = ctx.executionContext
    given ActorSystem[?] = ctx.system

    ctx.log.info(
      "SPA IDOR scan of {} (active scope: {})",
      url,
      if auth.allowActive then auth.authorizedHosts.mkString(",")
      else "observe-only (skipped)",
    )
    ctx.pipeToSelf(Scanner.runSpaIdor(ctx, url, identity, auth, navTimeoutMs)) {
      case scala.util.Success(fs) => fs
      case scala.util.Failure(t) =>
        ctx.log.error("SPA scan failed: {}", t.toString)
        Vector.empty
    }

    Behaviors.receiveMessage { findings =>
      println(FindingsReport.render(url, findings))
      ctx.log.info("Done; {} finding(s).", findings.size)
      Behaviors.stopped
    }
  }
