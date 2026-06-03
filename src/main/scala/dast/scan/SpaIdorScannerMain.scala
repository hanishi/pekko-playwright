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
  * Usage: sbt "runMain dast.scan.SpaIdorScannerMain <app-url>
  * <identity-spec.json>"
  */
object SpaIdorScannerMain:

  def main(args: Array[String]): Unit =
    val url = args.headOption.filter(_.nonEmpty)
    val specPath = args.drop(1).headOption.filter(_.nonEmpty)
      .orElse(DastConfig.get("DAST_ACCESS_SPEC"))
    (url, specPath) match
      case (Some(target), Some(path)) => loadIdentities(path) match
          case Right((attacker, victim)) => ActorSystem(
              guardian(target, attacker, victim, authorization),
              "dast-spa-idor-scanner",
            )
          case Left(err) =>
            Console.err.println(err)
            sys.exit(2)
      case _ =>
        Console.err
          .println("usage: SpaIdorScannerMain <app-url> <identity-spec.json>")
        sys.exit(2)

  /** Attacker + optional victim. Two-identity IDOR uses identities named
    * `attacker` and `victim`; a single unnamed identity is the attacker (no
    * cross-account candidates).
    */
  private def loadIdentities(
      path: String,
  ): Either[String, (Identity, Option[Identity])] = Try {
    val src = scala.io.Source.fromFile(path, "UTF-8")
    try src.mkString
    finally src.close()
  }.toEither.left.map(e => s"cannot read spec '$path': ${e.getMessage}")
    .flatMap(AccessControlCheck.parseSpec(_).left.map(e => s"invalid spec: $e"))
    .flatMap { spec =>
      val ids = spec.identities
      val attacker = ids.get("attacker")
        .orElse(if ids.size == 1 then ids.values.headOption else None)
      attacker
        .toRight("spec needs an 'attacker' identity (and optional 'victim')")
        .map(a => (a, ids.get("victim")))
    }

  private def navTimeoutMs: Int = DastConfig.getInt("DAST_NAV_TIMEOUT_MS", 30000)
  private def maxHops: Int = DastConfig.getInt("DAST_MAX_HOPS", 6)
  private def postBudget: Int = DastConfig.getInt("DAST_POST_BUDGET", 3)

  private def authorization: Authorization = DastConfig
    .get("DAST_AUTHORIZED_HOSTS") match
    case Some(hosts) => Authorization
        .active(hosts.split(",").map(_.trim).toIndexedSeq*)
    case None => Authorization.ObserveOnly

  private def guardian(
      url: String,
      attacker: Identity,
      victim: Option[Identity],
      auth: Authorization,
  ): Behavior[Vector[Finding]] = Behaviors.setup { ctx =>
    given ExecutionContext = ctx.executionContext
    given ActorSystem[?] = ctx.system

    ctx.log.info(
      "SPA IDOR scan of {} (active scope: {}, victim identity: {})",
      url,
      if auth.allowActive then auth.authorizedHosts.mkString(",")
      else "observe-only (skipped)",
      victim.isDefined,
    )
    ctx.pipeToSelf(Scanner.runSpaIdor(
      ctx,
      url,
      attacker,
      victim,
      auth,
      navTimeoutMs,
      maxHops,
      postBudget,
    )) {
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
