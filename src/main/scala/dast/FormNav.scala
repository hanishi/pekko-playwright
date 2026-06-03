package dast

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory

import dast.FormParse.FormInfo
import dast.NavPlan.NavChoice

/** Browser-free, LLM-driven form navigation: on an authenticated page, parse
  * its forms, let the planner choose submissions, gate each through
  * [[ActionGuard]], submit the allowed ones, and return the result pages' URLs
  * (final URL + same-host links found there) for the IDOR planner to scan.
  *
  * GET submissions are read-semantic; POSTs pass only with the model's safe
  * flag AND the deny-list. Identifies itself with the scanner User-Agent and is
  * gated by [[ConsentGate]] on the page host. HTTP is live-only; the parse /
  * guard / choice logic it composes is unit tested.
  */
object FormNav:

  private val log = LoggerFactory.getLogger("dast.FormNav")

  private val UserAgent =
    "pekko-dast-scanner/0.1 (+authorized security testing)"

  /** Discover object-listing URLs reachable by submitting forms on `pageUrl`. */
  def explore(
      pageUrl: String,
      cookie: Option[String],
      auth: Authorization,
      planner: (String, Seq[FormInfo]) => Future[Seq[NavChoice]],
  )(using system: ActorSystem[?], ec: ExecutionContext): Future[Seq[String]] =
    ConsentGate.decide(auth, ActionClass.Active, pageUrl) match
      case GateDecision.Deny(_) => Future.successful(Seq.empty)
      case GateDecision.Permit => get(pageUrl, cookie).flatMap {
          case None => Future.successful(Seq.empty)
          case Some((_, html)) =>
            val forms = FormParse.parse(html, pageUrl)
            if forms.isEmpty then Future.successful(Seq.empty)
            else planner(pageUrl, forms).flatMap { choices =>
              Future.sequence(choices.flatMap { ch =>
                forms.lift(ch.formIndex).map(form => submit(form, ch, cookie))
              }).map(_.flatten.distinct)
            }
        }

  private def submit(form: FormInfo, choice: NavChoice, cookie: Option[String])(
      using ActorSystem[?], ExecutionContext,
  ): Future[Seq[String]] = ActionGuard.allow(form, choice.safe) match
    case Left(reason) =>
      log.info("Form submission to {} refused: {}", form.action, reason)
      Future.successful(Seq.empty)
    case Right(_) =>
      log.info("Submitting {} form to {}", form.method, form.action)
      execute(form, choice.values, cookie).map {
        case None => Seq.empty
        case Some((finalUrl, body)) =>
          finalUrl +: AuthCrawl.links(finalUrl, body)
      }

  /** Issue the (guarded) submission and follow at most one redirect; return the
    * final URL and body.
    */
  private def execute(
      form: FormInfo,
      values: Map[String, String],
      cookie: Option[String],
  )(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[(String, String)]] =
    val encoded = values.map((k, v) => s"${enc(k)}=${enc(v)}").mkString("&")
    val request =
      if form.method == "post" then HttpRequest(
        method = HttpMethods.POST,
        uri = form.action,
        headers = hdrs(cookie),
        entity =
          HttpEntity(ContentTypes.`application/x-www-form-urlencoded`, encoded),
      )
      else
        val sep = if form.action.contains("?") then "&" else "?"
        val uri = if encoded.isEmpty then form.action else form.action + sep + encoded
        HttpRequest(HttpMethods.GET, uri, hdrs(cookie))
    send(request).flatMap {
      case Some((status, loc, body)) if status >= 300 && status < 400 =>
        loc.flatMap(l => resolve(form.action, l)) match
          case Some(next) => send(HttpRequest(HttpMethods.GET, next, hdrs(cookie)))
              .map(_.map((_, _, b) => (next, b)))
          case None => Future.successful(Some((form.action, body)))
      case Some((_, _, body)) =>
        Future.successful(Some((requestUri(request), body)))
      case None => Future.successful(None)
    }

  private def send(request: HttpRequest)(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[(Int, Option[String], String)]] = Http()(system)
    .singleRequest(request).flatMap { response =>
      val loc = response.header[headers.Location].map(_.uri.toString)
      Unmarshal(response.entity).to[String]
        .map(body => Some((response.status.intValue(), loc, body)))
    }.recover { case t =>
      log.warn("Form submit error for {}: {}", request.uri, t.getMessage)
      None
    }

  private def get(url: String, cookie: Option[String])(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[(Int, String)]] =
    send(HttpRequest(HttpMethods.GET, url, hdrs(cookie)))
      .map(_.map((s, _, b) => (s, b)))

  private def hdrs(cookie: Option[String]): List[HttpHeader] =
    headers.RawHeader("User-Agent", UserAgent) ::
      cookie.map(c => headers.RawHeader("Cookie", c)).toList

  private def requestUri(r: HttpRequest): String = r.uri.toString

  private def resolve(base: String, href: String): Option[String] =
    scala.util.Try(new java.net.URI(base).resolve(href).toString).toOption

  private def enc(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8)
