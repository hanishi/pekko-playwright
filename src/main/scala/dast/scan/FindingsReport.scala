package dast.scan

import dast.Finding

/** Structured, reproducible findings output (CLAUDE.md section 6) — the
  * low-false-positive document the product leads with, not scraped rows. Pure;
  * serializes via ujson.
  */
object FindingsReport:

  def toJson(target: String, findings: Seq[Finding]): ujson.Value =
    ujson.Obj(
      "target" -> target,
      "findingCount" -> findings.size,
      "findings" -> ujson.Arr.from(findings.map(findingJson)),
    )

  /** Pretty-printed JSON string. */
  def render(target: String, findings: Seq[Finding]): String =
    ujson.write(toJson(target, findings), indent = 2)

  private def findingJson(f: Finding): ujson.Value =
    ujson.Obj(
      "kind" -> f.kind.toString,
      "severity" -> f.severity.toString,
      "evidence" -> f.evidence,
      "reproducible" -> f.reproducible,
      "replay" -> f.replay,
    )
