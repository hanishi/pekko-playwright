package dast

/** Deterministic Tier 1 check: cookies missing security attributes.
  *
  * Pure over a [[ClientStateSnapshot]]; no browser or network. Every finding is
  * reproducible and replays from the cookie name + domain. Severity is raised
  * for cookies whose name suggests they carry a session or auth token, since a
  * missing flag matters most there.
  */
object CookieFlagCheck:

  private val sessionHints: Set[String] =
    Set("session", "sess", "sid", "auth", "token", "jwt")

  private def looksSessiony(name: String): Boolean =
    val n = name.toLowerCase
    sessionHints.exists(n.contains)

  def check(snapshot: ClientStateSnapshot): Seq[Finding] = snapshot.cookies
    .flatMap { c =>
      val sessiony = looksSessiony(c.name)
      val out = Seq.newBuilder[Finding]
      if !c.httpOnly then
        out += finding(
          c,
          "HttpOnly",
          if sessiony then Severity.High else Severity.Low,
        )
      if !c.secure then
        out += finding(
          c,
          "Secure",
          if sessiony then Severity.Medium else Severity.Low,
        )
      if c.sameSite.isEmpty then out += finding(c, "SameSite", Severity.Low)
      out.result()
    }

  private def finding(c: Cookie, flag: String, severity: Severity): Finding =
    Finding(
      kind = FindingKind.InsecureCookie,
      severity = severity,
      evidence = s"cookie '${c.name}' is missing the $flag attribute",
      reproducible = true,
      replay = s"cookie:${c.name}@${c.domain}",
    )
