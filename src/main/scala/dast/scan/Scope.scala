package dast.scan

import scala.util.Try

import crawler.UrlNormalizer

/** Pure frontier helpers for a site scan. Discovery is kept on the same host as
  * the seed so the crawl never wanders off the authorized target; per-URL
  * active probing is still gated separately by `ConsentGate`.
  */
object Scope:

  /** Lowercased host of a URL, if parseable. */
  def hostOf(url: String): Option[String] =
    Try(new java.net.URI(url).getHost).toOption.flatMap(Option(_)).map(_.toLowerCase)

  /** True when `url` is on the same host as `seedHost`. */
  def inScope(seedHost: String, url: String): Boolean =
    hostOf(url).contains(seedHost.toLowerCase)

  /** Normalize (via [[UrlNormalizer]]) and de-duplicate, preserving first-seen
    * order. */
  def normalizeAndDedupe(urls: Seq[String]): Seq[String] =
    val seen = scala.collection.mutable.LinkedHashSet.empty[String]
    urls.foreach(u => seen += UrlNormalizer.normalize(u))
    seen.toSeq

  /** Keep at most `maxPages` urls. */
  def cap(urls: Seq[String], maxPages: Int): Seq[String] =
    if maxPages <= 0 then Seq.empty else urls.take(maxPages)

  /** Full frontier: same-host, normalized, deduped, capped — with the seed
    * always first. */
  def frontier(seed: String, discovered: Seq[String], maxPages: Int): Seq[String] =
    hostOf(seed) match
      case None => Seq.empty
      case Some(host) =>
        val all = normalizeAndDedupe(seed +: discovered).filter(inScope(host, _))
        cap(all, maxPages)
