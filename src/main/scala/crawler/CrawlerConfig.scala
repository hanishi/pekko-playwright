package crawler

case class CrawlerConfig(
    domain: String,
    maxDepth: Int,
    concurrency: Int,
    seedUrl: String,
    hostRegex: String,
    targetElements: Array[String],
    clickSelector: Option[String] = None,
    cronSchedule: String = "0 0 * * *" // Default to run every day at midnight,
)
