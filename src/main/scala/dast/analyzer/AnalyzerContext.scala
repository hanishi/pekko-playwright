package dast.analyzer

import dast.ClientStateSnapshot

/** Immutable input the analyzer hands to the model. Carries only what the model
  * needs to choose a next action: the page, the storage *keys* present (never
  * the values, so secrets are not sent to the model), and the candidate
  * injection-point and link ids it may reference. The payload menu comes from
  * `PayloadLibrary` and is enforced in the tool schema, not here.
  */
final case class AnalyzerContext(
    url: String,
    storageKeys: Seq[String] = Seq.empty,
    injectionPointIds: Seq[String] = Seq.empty,
    linkIds: Seq[String] = Seq.empty,
):

  /** The user-message text. Pure and deterministic (sorted) so the request is
    * cache-friendly. */
  def render: String =
    s"""Target page: $url
       |Storage keys present: ${storageKeys.sorted.mkString(", ")}
       |Candidate injection points: ${injectionPointIds.sorted.mkString(", ")}
       |Candidate links: ${linkIds.sorted.mkString(", ")}
       |
       |Choose the single best next action by calling the `decide` tool.""".stripMargin

object AnalyzerContext:

  /** Build from a captured snapshot. Storage keys only (no values). */
  def fromSnapshot(
      snapshot: ClientStateSnapshot,
      injectionPointIds: Seq[String] = Seq.empty,
      linkIds: Seq[String] = Seq.empty,
  ): AnalyzerContext =
    AnalyzerContext(
      url = snapshot.url,
      storageKeys = (snapshot.localStorage.keys ++ snapshot.sessionStorage.keys).toSeq.sorted,
      injectionPointIds = injectionPointIds,
      linkIds = linkIds,
    )
