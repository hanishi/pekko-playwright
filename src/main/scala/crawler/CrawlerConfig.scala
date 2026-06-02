package crawler

import scala.jdk.CollectionConverters.*

import com.typesafe.config.Config

case class CrawlerConfig(
    domain: String,
    maxDepth: Int,
    concurrency: Int,
    seedUrl: String,
    hostRegex: String,
    targetElements: Array[String],
    clickSelector: Option[String] = None,
)

object CrawlerConfig:

  /** Build a [[CrawlerConfig]] from the `crawler` block of `application.conf`.
    * `target-elements` is a string list; `click-selector` is optional (omit or
    * set to null). Proxy / browser-pool settings live in the same `crawler.*`
    * namespace and are read separately by [[PlaywrightCrawler]]. */
  def fromConfig(config: Config): CrawlerConfig =
    val c = config.getConfig("crawler")
    CrawlerConfig(
      domain = c.getString("domain"),
      maxDepth = c.getInt("max-depth"),
      concurrency = c.getInt("concurrency"),
      seedUrl = c.getString("seed-url"),
      hostRegex = c.getString("host-regex"),
      targetElements = c.getStringList("target-elements").asScala.toArray,
      clickSelector =
        if c.hasPath("click-selector") then Some(c.getString("click-selector")) else None,
    )
