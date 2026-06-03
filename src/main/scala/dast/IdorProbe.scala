package dast

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory

import dast.IdorPlan.Observation
import dast.IdorPlan.Proposal

/** Browser-free IDOR probe: observe an authenticated page, let a planner
  * propose tests, then confirm each deterministically.
  *
  * The planner is injected (so the loop is testable without the model). The
  * confirmation is pure [[IdorPlan]] logic: baseline the caller's own value,
  * then for each neighbour value check whether the discriminator field comes
  * back present and different (cross-user data). Gated by [[ConsentGate]] on
  * the page host. Identifies itself with the scanner User-Agent. HTTP is
  * live-only.
  */
object IdorProbe:

  private val log = LoggerFactory.getLogger("dast.IdorProbe")

  private val UserAgent =
    "pekko-dast-scanner/0.1 (+authorized security testing)"

  /** Observe `url` as the authenticated caller, plan, and confirm. */
  def scan(
      url: String,
      cookie: Option[String],
      auth: Authorization,
      planner: Observation => Future[Seq[Proposal]],
  )(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Vector[Finding]] =
    ConsentGate.decide(auth, ActionClass.Active, url) match
      case GateDecision.Deny(reason) =>
        log.info("IDOR scan of {} skipped: {}", url, reason)
        Future.successful(Vector.empty)
      case GateDecision.Permit => fetch(url, cookie).flatMap {
          case None => Future.successful(Vector.empty)
          case Some((_, body)) =>
            val obs = Observation(
              url,
              IdorPlan.queryParams(url),
              IdorPlan.jsonFieldNames(body),
            )
            planner(obs).flatMap { proposals =>
              Future.sequence(proposals.map(p => confirm(url, cookie, p)))
                .map(_.flatten.toVector)
            }
        }

  /** Baseline the caller's own value, then return the first neighbour whose
    * discriminator field differs (confirmed cross-user access).
    */
  private def confirm(url: String, cookie: Option[String], p: Proposal)(using
      ActorSystem[?],
      ExecutionContext,
  ): Future[Option[Finding]] =
    val point = InjectionPoint.QueryParam(p.param)
    fetch(point.placeInto(url, p.ownValue), cookie).flatMap {
      case Some((s, body)) if s >= 200 && s <= 299 =>
        IdorPlan.extractField(body, p.discriminatorField) match
          case None => Future.successful(None)
          case Some(ownValue) => firstHit(url, cookie, point, p, ownValue)
      case _ => Future.successful(None)
    }

  private def firstHit(
      url: String,
      cookie: Option[String],
      point: InjectionPoint,
      p: Proposal,
      ownValue: String,
  )(using ActorSystem[?], ExecutionContext): Future[Option[Finding]] = p
    .candidates
    .foldLeft(Future.successful(Option.empty[Finding])) { (acc, candidate) =>
      acc.flatMap {
        case some @ Some(_) => Future.successful(some)
        case None => fetch(point.placeInto(url, candidate), cookie).map {
            case Some((cs, cbody))
                if IdorPlan
                  .confirms(ownValue, cs, cbody, p.discriminatorField) =>
              val leaked = IdorPlan.extractField(cbody, p.discriminatorField)
                .getOrElse("")
              Some(IdorPlan.toFinding(
                url,
                p.param,
                candidate,
                p.discriminatorField,
                leaked,
              ))
            case _ => None
          }
      }
    }

  private def fetch(url: String, cookie: Option[String])(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[(Int, String)]] =
    val hs = headers.RawHeader("User-Agent", UserAgent) ::
      cookie.map(c => headers.RawHeader("Cookie", c)).toList
    val request = HttpRequest(method = HttpMethods.GET, uri = url, headers = hs)
    HttpThrottle(Http()(system).singleRequest(request)).flatMap { response =>
      Unmarshal(response.entity).to[String]
        .map(body => Some((response.status.intValue(), body)))
    }.recover { case t =>
      log.warn("IDOR probe error for {}: {}", url, t.getMessage)
      None
    }
