package dast

/** A cookie as observed on the page, with the security-relevant attributes. */
final case class Cookie(
    name: String,
    value: String,
    domain: String,
    path: String,
    httpOnly: Boolean,
    secure: Boolean,
    sameSite: Option[String],
)

/** An immutable record of a page's client-side state at capture time.
  *
  * This is plain data (CLAUDE.md section 3): it is produced by a passive
  * capture `PageOp` (a later slice) and consumed by the deterministic Tier 1
  * checks and, on ambiguity, by the Tier 2 LLM classifier. It never carries
  * behaviour. DOM and IndexedDB are intentionally omitted for now; add them as
  * fields when the checks that need them land.
  */
final case class ClientStateSnapshot(
    url: String,
    localStorage: Map[String, String] = Map.empty,
    sessionStorage: Map[String, String] = Map.empty,
    cookies: Seq[Cookie] = Seq.empty,
    // Whether a login / auth exchange was observed during capture. Used only to
    // raise the severity of a secret finding, never to create one.
    observedAuthFlow: Boolean = false,
)
