package dast

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory

import dast.scan.Scope

/** Authenticated, browser-free, same-host link crawl: BFS from a seed carrying
  * the caller's session cookie, to discover object-bearing endpoints for the
  * IDOR planner to reason about.
  *
  * This is the deterministic "navigation": link-following is exhaustive and
  * cheap, so a crawler does it rather than the model. Discovery is read-only
  * (GETs), stays on the seed's host, and is bounded by depth / page count. Link
  * extraction is pure and tested; the HTTP BFS is live-only.
  */
object AuthCrawl:

  private val log = LoggerFactory.getLogger("dast.AuthCrawl")

  private val UserAgent =
    "pekko-dast-scanner/0.1 (+authorized security testing)"

  private val hrefRe = """(?i)href\s*=\s*["']([^"'>]+)["']""".r

  /** Absolute, same-scheme http(s) links in `html`, resolved against `baseUrl`.
    * Pure. Pure fragments and javascript: / mailto: are dropped.
    */
  def links(baseUrl: String, html: String): Seq[String] =
    val base = Try(new java.net.URI(baseUrl)).toOption
    hrefRe.findAllMatchIn(html).map(_.group(1).trim)
      .filter(h => h.nonEmpty && !h.startsWith("#")).flatMap { href =>
        base.flatMap(b => Try(b.resolve(href).toString).toOption)
      }.filter(u => u.startsWith("http://") || u.startsWith("https://"))
      .distinct.toSeq

  /** Discover same-host URLs reachable from `seed` (excluding the seed),
    * normalized and deduped, to `maxDepth` / `maxPages`. Fail-soft per page.
    */
  def discover(seed: String, cookie: Option[String], maxDepth: Int, maxPages: Int)(
      using system: ActorSystem[?], ec: ExecutionContext,
  ): Future[Seq[String]] =
    val seedHost = Scope.hostOf(seed).getOrElse("")

    def linksOf(url: String): Future[Seq[String]] = get(url, cookie).map {
      case Some(body) => links(url, body).map(crawler.UrlNormalizer.normalize)
          .filter(u => Scope.inScope(seedHost, u))
      case None => Seq.empty
    }

    def loop(
        frontier: List[String],
        depth: Int,
        seen: Set[String],
        acc: Vector[String],
    ): Future[Seq[String]] =
      if depth > maxDepth || frontier.isEmpty || acc.size >= maxPages then
        Future.successful(acc)
      else Future.sequence(frontier.map(linksOf)).flatMap { results =>
        val next = results.flatten.filterNot(seen.contains).distinct
        loop(next.toList, depth + 1, seen ++ next, (acc ++ next).take(maxPages))
      }

    loop(List(seed), 0, Set(crawler.UrlNormalizer.normalize(seed)), Vector.empty)

  private def get(url: String, cookie: Option[String])(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Option[String]] =
    val hs = headers.RawHeader("User-Agent", UserAgent) ::
      cookie.map(c => headers.RawHeader("Cookie", c)).toList
    Http()(system).singleRequest(HttpRequest(uri = url, headers = hs)).flatMap {
      response =>
        if response.status.isSuccess() then
          Unmarshal(response.entity).to[String].map(Some(_))
        else
          response.entity.discardBytes()
          Future.successful(None)
    }.recover { case t =>
      log.warn("Auth crawl error for {}: {}", url, t.getMessage)
      None
    }
