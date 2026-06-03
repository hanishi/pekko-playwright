package dast

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.Materializer.matFromSystem
import org.slf4j.LoggerFactory

/** Browser-free, out-of-band SSRF probe over a URL's query parameters.
  *
  * Per parameter: mint a unique token, inject `oast.baseUrl/token` into the
  * parameter, fire the request at the target, then poll [[Oast.saw]] for a
  * bounded window. A finding is produced only if the target's server actually
  * fetched the callback (the token was recorded), so it is confirmed
  * out-of-band, never guessed. Runs only under an authorized active scope (the
  * orchestrator gates it) and identifies itself with the scanner User-Agent.
  *
  * The HTTP/poll machinery is live-only; the URL build and confirm decision it
  * composes are unit tested in [[SsrfCheck]].
  */
object SsrfProbe:

  private val log = LoggerFactory.getLogger("dast.SsrfProbe")

  private val UserAgent =
    "pekko-dast-scanner/0.1 (+authorized security testing)"

  private val PollInterval = 500.millis
  private val PollAttempts = 16 // ~8s for a slow server-side fetch

  def scan(target: String, oast: Oast)(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Vector[Finding]] =
    val params = SsrfCheck.paramNames(target)
    Future.sequence(params.map(p => probeParam(target, p, oast)))
      .map(_.flatten.toVector)

  private def probeParam(target: String, name: String, oast: Oast)(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[Finding]] =
    val point = InjectionPoint.QueryParam(name)
    val token = Markers.fresh()
    val injected = point
      .placeInto(target, SsrfCheck.callbackUrl(oast.baseUrl, token))
    fire(injected).flatMap(_ => waitForToken(oast, token, PollAttempts))
      .map(seen => Option.when(seen)(SsrfCheck.toFinding(point, token)))

  /** Send the injection request; its own response is irrelevant. Never fails.
    */
  private def fire(
      url: String,
  )(using system: ActorSystem[?], ec: ExecutionContext): Future[Unit] =
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = url,
      headers = List(headers.RawHeader("User-Agent", UserAgent)),
    )
    HttpThrottle(Http()(system).singleRequest(request)).map { response =>
      response.entity.discardBytes()
      ()
    }.recover { case t =>
      log.warn("SSRF probe error for {}: {}", url, t.getMessage)
      ()
    }

  /** Poll the listener up to `attempts` times, spaced by [[PollInterval]]. */
  private def waitForToken(oast: Oast, token: String, attempts: Int)(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Boolean] =
    if oast.saw(token) then Future.successful(true)
    else if attempts <= 0 then Future.successful(false)
    else
      after(PollInterval, system.toClassic.scheduler)(
        waitForToken(oast, token, attempts - 1),
      )
