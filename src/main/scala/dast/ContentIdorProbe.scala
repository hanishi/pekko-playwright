package dast

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory

import dast.ContentIdor.Proposal

/** Browser-free probe that runs [[ContentIdor]] proposals as the authenticated
  * caller and confirms IDOR by cross-user diff: baseline the caller's own id,
  * then for each candidate id check whether the response is a 2xx whose
  * discriminator field is present and DIFFERENT from the caller's own (so a
  * record that isn't theirs came back). Reuses [[IdorPlan.extractField]] /
  * [[IdorPlan.confirms]]. Gated by [[ConsentGate]]; throttled; uses the
  * post-login session cookie. HTTP is live-only.
  */
object ContentIdorProbe:

  private val log = LoggerFactory.getLogger("dast.ContentIdorProbe")

  private val UserAgent =
    "pekko-dast-scanner/0.1 (+authorized security testing)"

  def run(proposals: Seq[Proposal], cookie: Option[String], auth: Authorization)(
      using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Vector[Finding]] = Future
    .sequence(proposals.map(p => probe(p, cookie, auth))).map(_.flatten.toVector)

  private def probe(p: Proposal, cookie: Option[String], auth: Authorization)(
      using
      ActorSystem[?],
      ExecutionContext,
  ): Future[Option[Finding]] =
    val baselineUrl = ContentIdor.fill(p.urlTemplate, p.ownValue)
    ConsentGate.decide(auth, ActionClass.Active, baselineUrl) match
      case GateDecision.Deny(reason) =>
        log.info("IDOR test skipped ({}): {}", baselineUrl, reason)
        Future.successful(None)
      case GateDecision.Permit => fetch(p, p.ownValue, cookie).flatMap {
          case Some((s, body)) if s >= 200 && s <= 299 =>
            IdorPlan.extractField(body, p.discriminatorField) match
              case None => Future.successful(None)
              case Some(own) => firstHit(p, own, cookie)
          case _ => Future.successful(None)
        }

  private def firstHit(p: Proposal, ownValue: String, cookie: Option[String])(
      using
      ActorSystem[?],
      ExecutionContext,
  ): Future[Option[Finding]] = p.candidates
    .foldLeft(Future.successful(Option.empty[Finding])) { (acc, candidate) =>
      acc.flatMap {
        case some @ Some(_) => Future.successful(some)
        case None => fetch(p, candidate, cookie).map {
            case Some((s, body))
                if IdorPlan.confirms(ownValue, s, body, p.discriminatorField) =>
              val leaked = IdorPlan.extractField(body, p.discriminatorField)
                .getOrElse("")
              Some(ContentIdor.toFinding(p, candidate, leaked))
            case _ => None
          }
      }
    }

  private def fetch(p: Proposal, id: String, cookie: Option[String])(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[(Int, String)]] =
    val hs = headers.RawHeader("User-Agent", UserAgent) ::
      cookie.map(c => headers.RawHeader("Cookie", c)).toList
    val request =
      if p.isPost then
        HttpRequest(
          method = HttpMethods.POST,
          uri = ContentIdor.fill(p.urlTemplate, id),
          headers = hs,
          entity = HttpEntity(
            ContentTypes.`application/x-www-form-urlencoded`,
            p.bodyTemplate.map(b => ContentIdor.fill(b, id)).getOrElse(""),
          ),
        )
      else HttpRequest(HttpMethods.GET, ContentIdor.fill(p.urlTemplate, id), hs)
    HttpThrottle(Http()(system).singleRequest(request)).flatMap { response =>
      Unmarshal(response.entity).to[String]
        .map(body => Some((response.status.intValue(), body)))
    }.recover { case t =>
      log.warn("IDOR probe error: {}", t.getMessage)
      None
    }
