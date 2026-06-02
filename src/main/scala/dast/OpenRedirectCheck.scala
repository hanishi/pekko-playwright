package dast

import scala.util.Try

/** Pure logic for the open-redirect probe (CLAUDE.md sections 0.4 and 4).
  *
  * An open redirect is confirmed deterministically: inject an off-origin
  * sentinel host into a redirect-ish parameter and check whether the server's
  * `Location` header points at that sentinel. No model, no pattern-guessing on
  * the body, no following the redirect into the wild. The sentinel uses the
  * reserved `.example` TLD so it never resolves and the probe never actually
  * leaves the target.
  *
  * The browser-free HTTP request lives in [[OpenRedirectProbe]]; the build /
  * confirm logic here is pure and unit tested.
  */
object OpenRedirectCheck:

  /** Reserved, non-resolving host used as the redirect sentinel. */
  val SentinelHost = "dast-redirect-probe.example"

  /** Payloads tried per parameter: a fully-qualified URL and the scheme-relative
    * bypass that defeats naive `startsWith("/")` allow-listing.
    */
  val payloads: Seq[String] =
    Seq(s"https://$SentinelHost/", s"//$SentinelHost/")

  /** Query-parameter names of a URL (the redirect surfaces we probe). Pure. */
  def paramNames(url: String): Seq[String] = Try(new java.net.URI(url).getRawQuery)
    .toOption.flatMap(Option(_))
    .map(_.split("&").toSeq.filter(_.nonEmpty).map(_.split("=", 2)(0)).distinct)
    .getOrElse(Seq.empty)

  /** True when a redirect `Location` points at the sentinel host: an absolute
    * URL whose host is the sentinel, or a scheme-relative `//sentinel...`.
    */
  def confirms(location: String): Boolean =
    val loc = location.trim
    val lower = loc.toLowerCase
    val schemeRelative = lower.startsWith(s"//$SentinelHost")
    val absolute = Try(new java.net.URI(loc).getHost).toOption.flatMap(Option(_))
      .map(_.toLowerCase).contains(SentinelHost)
    schemeRelative || absolute

  def toFinding(point: InjectionPoint, payload: String): Finding = Finding(
    kind = FindingKind.OpenRedirect,
    severity = Severity.Medium,
    evidence =
      s"${point.describe} controls a redirect to an off-origin host ($payload)",
    reproducible = true,
    replay = s"redirect ${point.describe} payload=$payload",
  )
