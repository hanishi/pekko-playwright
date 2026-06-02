package dast.scan

import scala.concurrent.ExecutionContext
import scala.util.Try

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import dast.AccessControlCheck
import dast.AccessControlCheck.AccessSpec
import dast.AccessControlProbe
import dast.Authorization
import dast.DastConfig
import dast.Finding

/** Runnable, spec-driven access-control / IDOR scanner.
  *
  * Reads a JSON spec (path arg, or `DAST_ACCESS_SPEC`) describing captured
  * identities and assertion cases, then runs [[AccessControlProbe]] and prints a
  * findings report. Active by nature, so every case is gated against
  * `DAST_AUTHORIZED_HOSTS`; with none set, all cases are skipped (observe-only).
  * Wiring around tested components; exercised only by a live run.
  *
  * Usage: sbt "runMain dast.scan.AccessScannerMain access-spec.json"
  */
object AccessScannerMain:

  def main(args: Array[String]): Unit =
    args.headOption.filter(_.nonEmpty).orElse(DastConfig.get("DAST_ACCESS_SPEC"))
    match
      case None =>
        Console.err.println("usage: AccessScannerMain <spec.json>")
        sys.exit(2)
      case Some(path) => readFile(path) match
          case Left(err) =>
            Console.err.println(s"cannot read spec '$path': $err")
            sys.exit(2)
          case Right(content) => AccessControlCheck.parseSpec(content) match
              case Left(err) =>
                Console.err.println(s"invalid spec: $err")
                sys.exit(2)
              case Right(spec) =>
                ActorSystem(guardian(spec, authorization), "dast-access-scanner")

  private def authorization: Authorization = DastConfig
    .get("DAST_AUTHORIZED_HOSTS") match
    case Some(hosts) => Authorization
        .active(hosts.split(",").map(_.trim).toIndexedSeq*)
    case None => Authorization.ObserveOnly

  private def readFile(path: String): Either[String, String] = Try {
    val src = scala.io.Source.fromFile(path, "UTF-8")
    try src.mkString
    finally src.close()
  }.toEither.left.map(e => Option(e.getMessage).getOrElse(e.toString))

  private def guardian(
      spec: AccessSpec,
      auth: Authorization,
  ): Behavior[Vector[Finding]] = Behaviors.setup { ctx =>
    given ExecutionContext = ctx.executionContext
    given ActorSystem[?] = ctx.system

    ctx.log.info(
      "Access-control scan: {} case(s), active scope: {}",
      spec.cases.size,
      if auth.allowActive then auth.authorizedHosts.mkString(",")
      else "observe-only (all cases skipped)",
    )
    ctx.pipeToSelf(AccessControlProbe.scan(spec, auth)) {
      case scala.util.Success(fs) => fs
      case scala.util.Failure(_) => Vector.empty
    }

    Behaviors.receiveMessage { findings =>
      println(FindingsReport.render("access-control", findings))
      ctx.log.info("Done; {} finding(s).", findings.size)
      Behaviors.stopped
    }
  }
