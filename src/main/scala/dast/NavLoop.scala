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
import dast.scan.Scope

/** Browser-free, LLM-driven multi-hop navigation (instruction.md §5
  * navigation-action carve-out). From a seed it observes the page, asks the
  * planner for ONE next step (follow a link / submit a form / done), executes
  * it through the [[ActionGuard]] floor while threading a [[CookieJar]] across
  * hops, and harvests same-host URLs reached along the way for the IDOR planner.
  *
  * The loop is a plain recursive Future (not an actor): it is sequential
  * HTTP+LLM with no browser, so the pinned-thread invariant does not apply.
  * Termination is guaranteed three ways: a hop budget, a cycle guard (an action
  * already attempted ends the loop), and a dry counter (hops that surface
  * nothing new). A POST budget bounds state-touching submissions. Every action
  * is logged for audit. HTTP is live-only; the parse/guard/jar/step logic it
  * composes is unit tested.
  */
object NavLoop:

  private val log = LoggerFactory.getLogger("dast.NavLoop")

  private val UserAgent =
    "pekko-dast-scanner/0.1 (+authorized security testing)"

  private val DryLimit = 2

  /** A planner: current (url, forms, links, history) -> one step. */
  type Planner =
    (String, Seq[FormInfo], Seq[String], Seq[String]) => Future[NavStep]

  /** Explore from `seed`, returning the same-host URLs reached. */
  def explore(
      seed: String,
      jar0: CookieJar,
      auth: Authorization,
      planner: Planner,
      maxHops: Int,
      postBudget: Int,
  )(using system: ActorSystem[?], ec: ExecutionContext): Future[Seq[String]] =
    val seedHost = Scope.hostOf(seed).getOrElse("")

    def sameHostLinks(url: String, body: String): Seq[String] = AuthCrawl
      .links(url, body).filter(u => Scope.inScope(seedHost, u))

    def hop(
        url: String,
        body: String,
        jar: CookieJar,
        visited: Set[String],
        found: Vector[String],
        hops: Int,
        dry: Int,
        postsLeft: Int,
    ): Future[Seq[String]] =
      val forms = FormParse.parse(body, url)
      val links = sameHostLinks(url, body)
      if hops <= 0 || dry >= DryLimit then Future.successful(found)
      else planner(url, forms, links, found).flatMap {
        case NavStep.Done => Future.successful(found)
        case step =>
          val sig = signature(step, forms, links)
          if visited.contains(sig) then Future.successful(found) // converged
          else act(step, forms, links, jar, postsLeft).flatMap {
            case None => // denied / invalid: count as dry, keep going
              hop(url, body, jar, visited + sig, found, hops - 1, dry + 1, postsLeft)
            case Some((nextUrl, nextBody, setCookies, postUsed)) =>
              val harvested = (nextUrl +: sameHostLinks(nextUrl, nextBody))
                .filterNot(found.contains)
              hop(
                nextUrl,
                nextBody,
                jar.merge(setCookies),
                visited + sig,
                (found ++ harvested).distinct,
                hops - 1,
                if harvested.isEmpty then dry + 1 else 0,
                postsLeft - (if postUsed then 1 else 0),
              )
          }
      }

    ConsentGate.decide(auth, ActionClass.Active, seed) match
      case GateDecision.Deny(_) => Future.successful(Seq.empty)
      case GateDecision.Permit => get(seed, jar0).flatMap {
          case None => Future.successful(Seq.empty)
          case Some((_, body, set)) =>
            hop(
              seed,
              body,
              jar0.merge(set),
              Set.empty,
              Vector.empty,
              maxHops,
              0,
              postBudget,
            )
        }

  /** Execute a step. None = not performed (denied / invalid / out of budget). */
  private def act(
      step: NavStep,
      forms: Seq[FormInfo],
      links: Seq[String],
      jar: CookieJar,
      postsLeft: Int,
  )(using
      ActorSystem[?],
      ExecutionContext,
  ): Future[Option[(String, String, Seq[String], Boolean)]] = step match
    case NavStep.Done => Future.successful(None)
    case NavStep.Follow(i) => links.lift(i) match
        case None => Future.successful(None)
        case Some(url) =>
          get(url, jar).map(_.map((_, b, set) => (url, b, set, false)))
    case NavStep.Submit(fi, values, safe) => forms.lift(fi) match
        case None => Future.successful(None)
        case Some(form) => ActionGuard.allow(form, safe) match
            case Left(reason) =>
              log.info("Submission to {} refused: {}", form.action, reason)
              Future.successful(None)
            case Right(_) if form.method == "post" && postsLeft <= 0 =>
              log.info("POST budget exhausted; skipping {}", form.action)
              Future.successful(None)
            case Right(_) =>
              log.info("Submitting {} form to {}", form.method, form.action)
              submit(form, values, jar)
                .map(_.map((u, b, set) => (u, b, set, form.method == "post")))

  /** Issue a (guarded) form submission, following at most one redirect. */
  private def submit(
      form: FormInfo,
      values: Map[String, String],
      jar: CookieJar,
  )(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[(String, String, Seq[String])]] =
    val encoded = values.map((k, v) => s"${enc(k)}=${enc(v)}").mkString("&")
    val request =
      if form.method == "post" then HttpRequest(
        method = HttpMethods.POST,
        uri = form.action,
        headers = hdrs(jar),
        entity =
          HttpEntity(ContentTypes.`application/x-www-form-urlencoded`, encoded),
      )
      else
        val sep = if form.action.contains("?") then "&" else "?"
        val uri =
          if encoded.isEmpty then form.action else form.action + sep + encoded
        HttpRequest(HttpMethods.GET, uri, hdrs(jar))
    send(request).flatMap {
      case Some((status, loc, set, body)) if status >= 300 && status < 400 =>
        loc.flatMap(resolve(form.action, _)) match
          case Some(next) => get(next, jar.merge(set))
              .map(_.map((_, b, s2) => (next, b, set ++ s2)))
          case None => Future.successful(Some((form.action, body, set)))
      case Some((_, _, set, body)) =>
        Future.successful(Some((request.uri.toString, body, set)))
      case None => Future.successful(None)
    }

  private def get(url: String, jar: CookieJar)(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[(Int, String, Seq[String])]] =
    send(HttpRequest(HttpMethods.GET, url, hdrs(jar)))
      .map(_.map((s, _, set, b) => (s, b, set)))

  private def send(request: HttpRequest)(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[(Int, Option[String], Seq[String], String)]] = Http()(system)
    .singleRequest(request).flatMap { response =>
      val loc = response.header[headers.Location].map(_.uri.toString)
      val set = response.headers.collect {
        case c: headers.`Set-Cookie` => c.cookie.pair.toString
      }
      Unmarshal(response.entity).to[String]
        .map(body => Some((response.status.intValue(), loc, set, body)))
    }.recover { case t =>
      log.warn("Nav request error for {}: {}", request.uri, t.getMessage)
      None
    }

  private def signature(
      step: NavStep,
      forms: Seq[FormInfo],
      links: Seq[String],
  ): String = step match
    case NavStep.Follow(i) => s"follow:${links.lift(i).getOrElse(i.toString)}"
    case NavStep.Submit(fi, values, _) =>
      s"submit:${forms.lift(fi).map(_.action).getOrElse(fi.toString)}:${values.toSeq.sorted}"
    case NavStep.Done => "done"

  private def hdrs(jar: CookieJar): List[HttpHeader] =
    headers.RawHeader("User-Agent", UserAgent) ::
      jar.header.map(c => headers.RawHeader("Cookie", c)).toList

  private def resolve(base: String, href: String): Option[String] =
    scala.util.Try(new java.net.URI(base).resolve(href).toString).toOption

  private def enc(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8)
