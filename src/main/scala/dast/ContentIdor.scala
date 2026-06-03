package dast

/** Pure logic for content-harvested IDOR (instruction.md §0.2, §0.4).
  *
  * The model is the navigator/harvester: shown the authenticated pages the
  * browser visited, it finds the real object-reference IDs present in the
  * content (campaign/order/user ids, including non-enumerable ULIDs/UUIDs) and
  * proposes tests as a request template with a `{id}` placeholder -- covering a
  * query param, a path segment, or a POST body field -- plus the caller's own
  * id (baseline) and candidate ids it actually observed (never invented).
  *
  * Determinism confirms: requesting a candidate id returns a 2xx whose
  * discriminator field differs from the caller's own baseline (cross-user
  * data). Building/confirming here is pure; the HTTP lives in
  * [[ContentIdorProbe]].
  */
object ContentIdor:

  /** A model-proposed IDOR test. `urlTemplate` (and optional `bodyTemplate` for
    * a POST) contain `{id}`, substituted with `ownValue` (baseline) then each
    * candidate. The model supplies only these parameters, never code.
    */
  final case class Proposal(
      method: String,
      urlTemplate: String,
      bodyTemplate: Option[String],
      ownValue: String,
      candidates: Seq[String],
      discriminatorField: String,
  ):
    def isPost: Boolean = method.equalsIgnoreCase("POST")

  /** Substitute the `{id}` placeholder. */
  def fill(template: String, id: String): String = template.replace("{id}", id)

  def toFinding(p: Proposal, candidate: String, leaked: String): Finding =
    Finding(
      kind = FindingKind.BrokenAccessControl,
      severity = Severity.High,
      evidence =
        s"${p.method} ${fill(p.urlTemplate, candidate)} returned another caller's '${p.discriminatorField}' ($leaked) (IDOR)",
      reproducible = true,
      replay =
        s"idor ${p.method} ${p.urlTemplate} id=$candidate field=${p.discriminatorField}",
    )

  /** Parse the tool's `proposals` array, dropping malformed/empty entries.
    * Requires a `{id}` placeholder somewhere in the url or body template.
    */
  def parseProposals(proposals: ujson.Value): Seq[Proposal] = proposals.arrOpt
    .getOrElse(Nil).flatMap { p =>
      for
        urlT <- p.obj.get("urlTemplate").flatMap(_.strOpt).filter(_.nonEmpty)
        own <- p.obj.get("ownValue").flatMap(strOrNum)
        field <- p.obj.get("discriminatorField").flatMap(_.strOpt)
          .filter(_.nonEmpty)
        method = p.obj.get("method").flatMap(_.strOpt).getOrElse("GET")
        body = p.obj.get("bodyTemplate").flatMap(_.strOpt).filter(_.nonEmpty)
        cands = p.obj.get("candidates").flatMap(_.arrOpt).getOrElse(Nil)
          .flatMap(strOrNum).filter(_.nonEmpty).distinct
        if cands.nonEmpty && (urlT.contains("{id}") ||
          body.exists(_.contains("{id}")))
      yield Proposal(method, urlT, body, own, cands.toSeq, field)
    }.toSeq

  private def strOrNum(v: ujson.Value): Option[String] = v.strOpt.orElse(
    v.numOpt.map(n => if n.isWhole then n.toLong.toString else n.toString),
  )
