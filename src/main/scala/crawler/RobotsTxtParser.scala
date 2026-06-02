package crawler

import java.net.URI
import scala.util.Try

/** Simple robots.txt parser that respects User-agent: * rules. */
class RobotsTxtParser(val disallowedPaths: Seq[String], val allowedPaths: Seq[String]):

  /** Check if a URL is allowed by robots.txt rules.
    * Allow rules take precedence over disallow when the allow path is more specific.
    */
  def isAllowed(url: String): Boolean =
    val path = Try(new URI(url).getRawPath).getOrElse("/")
    val matchingDisallow = disallowedPaths.find(rule => matchesRule(path, rule))
    val matchingAllow = allowedPaths.find(rule => matchesRule(path, rule))

    (matchingDisallow, matchingAllow) match {
      case (None, _) => true // No disallow rule matches
      case (Some(_), None) => false // Disallow matches, no allow override
      case (Some(d), Some(a)) => a.length >= d.length // More specific rule wins
    }

  private def matchesRule(path: String, rule: String): Boolean =
    if (rule.endsWith("*"))
      path.startsWith(rule.dropRight(1))
    else if (rule.endsWith("$"))
      path == rule.dropRight(1)
    else
      path.startsWith(rule)

object RobotsTxtParser:

  def parse(content: String): RobotsTxtParser =
    if (content.isBlank) return new RobotsTxtParser(Seq.empty, Seq.empty)

    val lines = content.linesIterator.map(_.trim).toSeq
    var inWildcardBlock = false
    val disallowed = Seq.newBuilder[String]
    val allowed = Seq.newBuilder[String]

    for (line <- lines) {
      val stripped = line.split("#").head.trim // Remove comments
      if (stripped.isEmpty) {
        // Empty line ends current block
        // (but we keep inWildcardBlock until we see another User-agent)
      } else if (stripped.toLowerCase.startsWith("user-agent:")) {
        val agent = stripped.drop("user-agent:".length).trim
        inWildcardBlock = agent == "*"
      } else if (inWildcardBlock) {
        if (stripped.toLowerCase.startsWith("disallow:")) {
          val path = stripped.drop("disallow:".length).trim
          if (path.nonEmpty) disallowed += path
        } else if (stripped.toLowerCase.startsWith("allow:")) {
          val path = stripped.drop("allow:".length).trim
          if (path.nonEmpty) allowed += path
        }
      }
    }

    new RobotsTxtParser(disallowed.result(), allowed.result())
