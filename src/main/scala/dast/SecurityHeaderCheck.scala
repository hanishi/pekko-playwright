package dast

/** Deterministic Tier 1 check: missing HTTP response security headers.
  *
  * Pure over a [[ClientStateSnapshot]]; no browser or network. It reads only
  * the response headers captured during the page visit, so it needs no active
  * authorization (a normal visit already saw them). Every finding is
  * reproducible and replays from the header name + url.
  *
  * Scope is deliberately narrow to keep false positives near zero: it flags a
  * header only when it is entirely absent (a present-but-weak policy, e.g. a
  * CSP with `unsafe-inline`, is a deeper analysis left to a later slice).
  * `Strict-Transport-Security` is only expected on HTTPS responses.
  */
object SecurityHeaderCheck:

  /** Checked headers: lowercased name, severity when absent, and why it
    * matters. Order here is the order findings are emitted.
    */
  private val checks: Seq[(String, Severity, String)] = Seq(
    (
      "content-security-policy",
      Severity.Medium,
      "no declarative defence-in-depth against script injection",
    ),
    ("x-content-type-options", Severity.Low, "responses may be MIME-sniffed"),
    ("referrer-policy", Severity.Low, "the full URL may leak in the Referer"),
  )

  def check(snapshot: ClientStateSnapshot): Seq[Finding] =
    // status == 0 means no response was captured; do not invent findings.
    if snapshot.status <= 0 then Seq.empty
    else
      val h = snapshot.responseHeaders
      def missing(name: String): Boolean = !h.contains(name)
      val out = Seq.newBuilder[Finding]

      checks.foreach { case (name, severity, why) =>
        if missing(name) then out += finding(snapshot.url, name, severity, why)
      }

      // HSTS is only meaningful over HTTPS.
      if isHttps(snapshot.url) && missing("strict-transport-security") then
        out += finding(
          snapshot.url,
          "strict-transport-security",
          Severity.Medium,
          "connections are not pinned to HTTPS",
        )

      // Clickjacking: X-Frame-Options OR a CSP frame-ancestors directive must
      // constrain framing. Only flag when neither is present.
      val cspFrameAncestors = h.get("content-security-policy")
        .exists(_.toLowerCase.contains("frame-ancestors"))
      if missing("x-frame-options") && !cspFrameAncestors then
        out += finding(
          snapshot.url,
          "x-frame-options",
          Severity.Low,
          "the page can be framed (clickjacking)",
        )

      out.result()

  private def isHttps(url: String): Boolean = url.toLowerCase
    .startsWith("https://")

  private def finding(
      url: String,
      header: String,
      severity: Severity,
      why: String,
  ): Finding = Finding(
    kind = FindingKind.MissingSecurityHeader,
    severity = severity,
    evidence = s"response is missing the '$header' header ($why)",
    reproducible = true,
    replay = s"header:$header@$url",
  )
