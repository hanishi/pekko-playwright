package dast

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory

import dast.AccessControlCheck.AccessCase
import dast.AccessControlCheck.AccessSpec

/** Browser-free, spec-driven access-control / IDOR probe.
  *
  * For each case, send one request to the case's URL under its identity (no
  * redirect following, so a login redirect reads as denied), then confirm via
  * [[AccessControlCheck.confirms]]. Every case is gated by [[ConsentGate]]
  * against `DAST_AUTHORIZED_HOSTS`; a case whose host is not authorized is
  * skipped, never sent. Identifies itself with the scanner User-Agent.
  *
  * The HTTP machinery is live-only; the confirm decision it composes is unit
  * tested in [[AccessControlCheck]].
  */
object AccessControlProbe:

  private val log = LoggerFactory.getLogger("dast.AccessControlProbe")

  private val UserAgent =
    "pekko-dast-scanner/0.1 (+authorized security testing)"

  def scan(spec: AccessSpec, auth: Authorization)(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Vector[Finding]] = Future
    .sequence(spec.cases.map(c => probeCase(spec, c, auth)))
    .map(_.flatten.toVector)

  private def probeCase(spec: AccessSpec, c: AccessCase, auth: Authorization)(
      using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[Finding]] =
    ConsentGate.decide(auth, ActionClass.Active, c.url) match
      case GateDecision.Deny(reason) =>
        log.info("Access case '{}' skipped: {}", c.name, reason)
        Future.successful(None)
      case GateDecision.Permit =>
        val request = HttpRequest(
          method = HttpMethods.GET,
          uri = c.url,
          headers = buildHeaders(spec, c),
        )
        fetch(request).map {
          case Some((status, body))
              if AccessControlCheck.confirms(status, body, c.mustContain) =>
            Some(AccessControlCheck.toFinding(c))
          case _ => None
        }

  private def buildHeaders(spec: AccessSpec, c: AccessCase): List[HttpHeader] =
    val ua = headers.RawHeader("User-Agent", UserAgent)
    val identity = c.identity.flatMap(spec.identities.get)
    val cookie = identity.flatMap(_.cookie)
      .map(ck => headers.RawHeader("Cookie", ck)).toList
    val extra = identity.map(_.headers).getOrElse(Map.empty)
      .map((k, v) => headers.RawHeader(k, v)).toList
    ua :: cookie ++ extra

  /** GET (no redirect following), returning (status, body). None on failure. */
  private def fetch(request: HttpRequest)(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[(Int, String)]] =
    HttpThrottle(Http()(system).singleRequest(request)).flatMap { response =>
      Unmarshal(response.entity).to[String]
        .map(body => Some((response.status.intValue(), body)))
    }.recover { case t =>
      log.warn("Access probe error for {}: {}", request.uri, t.getMessage)
      None
    }
