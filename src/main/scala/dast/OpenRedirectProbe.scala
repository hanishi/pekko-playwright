package dast

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.slf4j.LoggerFactory

/** Browser-free, HTTP-level open-redirect probe.
  *
  * For each query parameter it injects [[OpenRedirectCheck]]'s sentinel and
  * sends a single GET that does NOT follow redirects (pekko-http's
  * `singleRequest` never auto-follows), then reads the `Location` header. A
  * finding is produced only when [[OpenRedirectCheck.confirms]] says the
  * redirect targets the sentinel host, so it is confirmed, not guessed, and the
  * request never actually leaves the target (the sentinel never resolves).
  *
  * This is the first of the HTTP-level probers; it runs only under an
  * authorized active scope (the orchestrator gates it) and identifies itself
  * with the scanner User-Agent (CLAUDE.md section 5). The HTTP call is exercised
  * only live; the build / confirm logic it composes is unit tested in
  * [[OpenRedirectCheck]].
  */
object OpenRedirectProbe:

  private val log = LoggerFactory.getLogger("dast.OpenRedirectProbe")

  private val UserAgent =
    "pekko-dast-scanner/0.1 (+authorized security testing)"

  /** Probe every query parameter; at most one finding per parameter (first
    * confirming payload wins). Per-request failures are swallowed (no finding).
    */
  def scan(target: String)(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Vector[Finding]] =
    val params = OpenRedirectCheck.paramNames(target)
    Future.sequence(params.map(p => probeParam(target, p)))
      .map(_.flatten.toVector)

  private def probeParam(target: String, name: String)(using
      ActorSystem[?],
      ExecutionContext,
  ): Future[Option[Finding]] =
    val point = InjectionPoint.QueryParam(name)
    // Try payloads in order; stop at the first that confirms.
    OpenRedirectCheck.payloads.foldLeft(Future.successful(Option.empty[Finding])) {
      (acc, payload) =>
        acc.flatMap {
          case some @ Some(_) => Future.successful(some)
          case None => locationOf(point.placeInto(target, payload)).map {
              case Some(loc) if OpenRedirectCheck.confirms(loc) =>
                Some(OpenRedirectCheck.toFinding(point, payload))
              case _ => None
            }
        }
    }

  /** The `Location` of a redirect response, if any; None for non-3xx, no
    * Location, or any request failure.
    */
  private def locationOf(url: String)(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[String]] =
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = url,
      headers = List(headers.RawHeader("User-Agent", UserAgent)),
    )
    Http()(system).singleRequest(request).map { response =>
      response.entity.discardBytes()
      val loc =
        if response.status.isRedirection() then
          response.header[headers.Location].map(_.uri.toString)
        else None
      loc
    }.recover { case t =>
      log.warn("Open-redirect probe error for {}: {}", url, t.getMessage)
      None
    }
