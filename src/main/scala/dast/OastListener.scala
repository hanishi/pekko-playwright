package dast

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.Materializer.matFromSystem
import org.slf4j.LoggerFactory

/** What an SSRF probe needs from an out-of-band callback sink: a base URL to
  * inject and a way to ask whether a token was hit. Abstracted so the probe
  * does not depend on the concrete server.
  */
trait Oast:
  /** Base URL the target must be able to reach (a path is appended per token).
    */
  def baseUrl: String

  /** True once a request carrying `token` has reached the sink. */
  def saw(token: String): Boolean

/** A minimal out-of-band callback server. Any HTTP request whose first path
  * segment is a token records that token; the SSRF probe later asks [[saw]].
  *
  * This is the honest SSRF oracle (CLAUDE.md section 0.4): a finding requires
  * an actual server-side request from the target to this listener, not a guess.
  * For a real target the `baseUrl` must be externally reachable (a tunnel /
  * public address); for local testing it binds on loopback. Live-only.
  */
final class OastListener(host: String, port: Int) extends Oast:

  private val log = LoggerFactory.getLogger("dast.OastListener")
  private val tokens = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  def baseUrl: String = s"http://$host:$port"

  def saw(token: String): Boolean = tokens.contains(token)

  /** Bind the listener. Resolves when it is accepting connections. */
  def start()(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Unit] = Http()(system).newServerAt(host, port).bindSync { request =>
    request.uri.path.toString.split("/").iterator.map(_.trim).find(_.nonEmpty)
      .foreach { token =>
        tokens.add(token)
        log.info("OAST callback received for token {}", token)
      }
    request.discardEntityBytes()
    HttpResponse(StatusCodes.OK, entity = "ok")
  }.map { binding =>
    log.info("OAST listener bound at {}", baseUrl)
    ()
  }
