package dast

import scala.util.Try

/** Pure logic for the SSRF probe (CLAUDE.md sections 0.4 and 4).
  *
  * SSRF is confirmed out-of-band, which is the only honest signal: inject a URL
  * pointing at a callback listener we control, carrying a unique token. If the
  * target's server fetches it, the listener records the token and the finding
  * is confirmed by that server-side interaction, never guessed from the
  * response body. No callback, no finding.
  *
  * The listener ([[OastListener]]) and the request/poll ([[SsrfProbe]]) are
  * live-only; the URL building and confirm decision here are pure and tested.
  */
object SsrfCheck:

  /** Query-parameter names of a URL: the surfaces probed. Pure. */
  def paramNames(url: String): Seq[String] = Try(new java.net.URI(url).getRawQuery)
    .toOption.flatMap(Option(_))
    .map(_.split("&").toSeq.filter(_.nonEmpty).map(_.split("=", 2)(0)).distinct)
    .getOrElse(Seq.empty)

  /** The callback URL injected for `token`, under the listener's base URL. */
  def callbackUrl(oastBaseUrl: String, token: String): String =
    s"${oastBaseUrl.stripSuffix("/")}/$token"

  /** True when the listener recorded this probe's token (server-side fetch). */
  def confirms(token: String, received: Set[String]): Boolean =
    received.contains(token)

  def toFinding(point: InjectionPoint, token: String): Finding = Finding(
    kind = FindingKind.Ssrf,
    severity = Severity.High,
    evidence =
      s"${point.describe} caused a server-side request to an out-of-band host (token $token)",
    reproducible = true,
    replay = s"ssrf ${point.describe} token=$token",
  )
