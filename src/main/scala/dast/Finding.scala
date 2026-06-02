package dast

/** Severity of a finding, low to high. */
enum Severity:
  case Info, Low, Medium, High, Critical

/** The category of a finding. Extended as checks are added. */
enum FindingKind:
  case InsecureCookie, SecretInStorage, Xss, MissingSecurityHeader, OpenRedirect,
    SqlInjection, Ssrf

/** A reported issue.
  *
  * `reproducible` is true when a deterministic check produced this (CLAUDE.md
  * section 6): these are the findings buyers trust. `replay` is an exact,
  * model-free handle that re-locates the evidence (a cookie name + domain, or a
  * storage query). `evidence` describes what was seen and deliberately does not
  * embed raw secret values.
  */
final case class Finding(
    kind: FindingKind,
    severity: Severity,
    evidence: String,
    reproducible: Boolean,
    replay: String,
)
