package dast

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory

/** Browser-free, HTTP-level SQL-injection probe over a URL's query parameters.
  *
  * Per parameter, against a [[SqlInjectionCheck]] baseline:
  *   1. Error-based: inject a quote; confirm if a DB error signature appears
  *      that was absent from the baseline.
  *   2. Time-based: try DB-specific sleep payloads; confirm only when the
  *      injected request is slower than baseline by the threshold AND a re-test
  *      is slow too (one slow response could be noise; two is the payload).
  *
  * First confirming technique per parameter wins (at most one finding each).
  * Runs only under an authorized active scope (the orchestrator gates it) and
  * identifies itself with the scanner User-Agent. The HTTP/timing calls are
  * exercised only live; the verdicts they compose are unit tested.
  */
object SqlInjectionProbe:

  private val log = LoggerFactory.getLogger("dast.SqlInjectionProbe")

  private val UserAgent =
    "pekko-dast-scanner/0.1 (+authorized security testing)"

  def scan(target: String)(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Vector[Finding]] =
    val params = SqlInjectionCheck.paramNames(target)
    Future.sequence(params.map(p => probeParam(target, p)))
      .map(_.flatten.toVector)

  private def probeParam(target: String, name: String)(using
      ActorSystem[?],
      ExecutionContext,
  ): Future[Option[Finding]] =
    val point = InjectionPoint.QueryParam(name)
    val original = paramValue(target, name)

    fetch(target).flatMap {
      case None => Future.successful(None) // no baseline -> cannot judge
      case Some((baselineMs, baselineBody)) =>
        val errUrl = point
          .placeInto(target, SqlInjectionCheck.errorPayload(original))
        fetch(errUrl).flatMap { errResult =>
          errorFinding(point, baselineBody, errResult) match
            case some @ Some(_) => Future.successful(some)
            case None => timeBased(target, point, original, baselineMs)
        }
    }

  /** Error-based verdict: an error signature present now but not in baseline.
    */
  private def errorFinding(
      point: InjectionPoint,
      baselineBody: String,
      errResult: Option[(Long, String)],
  ): Option[Finding] = errResult.flatMap { case (_, body) =>
    SqlInjectionCheck.detectError(body)
      .filter(_ => SqlInjectionCheck.detectError(baselineBody).isEmpty)
      .map(db => SqlInjectionCheck.errorFinding(point, db))
  }

  /** Time-based: first payload whose injected request is slow twice wins. */
  private def timeBased(
      target: String,
      point: InjectionPoint,
      original: String,
      baselineMs: Long,
  )(using ActorSystem[?], ExecutionContext): Future[Option[Finding]] =
    SqlInjectionCheck.timePayloads(original)
      .foldLeft(Future.successful(Option.empty[Finding])) {
        case (acc, (label, value)) => acc.flatMap {
            case some @ Some(_) => Future.successful(some)
            case None =>
              val url = point.placeInto(target, value)
              fetch(url).flatMap {
                case Some((ms1, _))
                    if SqlInjectionCheck.confirmsTiming(baselineMs, ms1) =>
                  // Re-test to rule out a one-off slow response.
                  fetch(url).map {
                    case Some((ms2, _))
                        if SqlInjectionCheck.confirmsTiming(baselineMs, ms2) =>
                      Some(SqlInjectionCheck.timeFinding(point, label))
                    case _ => None
                  }
                case _ => Future.successful(None)
              }
          }
      }

  /** GET `url` (no redirect following), returning (elapsed ms, body). None on
    * any failure, so a failed request never fabricates a timing or error hit.
    */
  private def fetch(url: String)(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[(Long, String)]] =
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = url,
      headers = List(headers.RawHeader("User-Agent", UserAgent)),
    )
    val start = System.nanoTime()
    Http()(system).singleRequest(request).flatMap { response =>
      Unmarshal(response.entity).to[String]
        .map(body => Some(((System.nanoTime() - start) / 1000000L, body)))
    }.recover { case t =>
      log.warn("SQLi probe error for {}: {}", url, t.getMessage)
      None
    }

  private def paramValue(url: String, name: String): String = Try {
    Option(new java.net.URI(url).getRawQuery).getOrElse("").split("&").iterator
      .map(_.split("=", 2)).collectFirst {
        case Array(k, v) if dec(k) == name => dec(v)
      }.getOrElse("")
  }.getOrElse("")

  private def dec(s: String): String =
    Try(URLDecoder.decode(s, StandardCharsets.UTF_8)).getOrElse(s)
