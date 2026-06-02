package dast

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import scala.util.Try

/** Pure logic for LLM-planned IDOR (instruction.md §0.2, §0.4).
  *
  * The model is the *navigator*: given an authenticated page it proposes which
  * object-reference parameter to tamper, the caller's own value, neighbour
  * values to try, and the per-user response field that would reveal another
  * user's record. It supplies only those parameters; it authors no code.
  *
  * Determinism is the *confirmer*: a finding requires that requesting a
  * neighbour value as the authenticated caller returns a 2xx whose discriminator
  * field is present and DIFFERENT from the caller's own baseline value. A
  * properly-authorized app returns 403 / a redirect / the caller's own data, so
  * the field does not differ and nothing is reported. The model can propose, but
  * it cannot fabricate a finding.
  */
object IdorPlan:

  /** One model-proposed IDOR test (parameters only, validated here). */
  final case class Proposal(
      param: String,
      ownValue: String,
      candidates: Seq[String],
      discriminatorField: String,
  )

  /** What the planner sees: the page, its query params with values, and the
    * response's JSON field *names* (names only, not values).
    */
  final case class Observation(
      url: String,
      params: Seq[(String, String)],
      fields: Seq[String],
  ):
    def render: String =
      val ps = params.map((k, v) => s"$k=$v").mkString(", ")
      s"""Authenticated page: $url
         |Query parameters: $ps
         |Response JSON fields: ${fields.mkString(", ")}
         |
         |Propose IDOR tests by calling the tool, or an empty list if there is
         |no object-reference parameter to tamper.""".stripMargin

  /** Query-param (name, decoded value) pairs, first occurrence per name. */
  def queryParams(url: String): Seq[(String, String)] = Try(
    new java.net.URI(url).getRawQuery,
  ).toOption.flatMap(Option(_)).map { q =>
    q.split("&").toSeq.filter(_.nonEmpty).map(_.split("=", 2)).collect {
      case Array(k, v) => dec(k) -> dec(v)
      case Array(k) => dec(k) -> ""
    }.distinctBy(_._1)
  }.getOrElse(Seq.empty)

  /** Top-level JSON field names (object keys, or the first array element's). */
  def jsonFieldNames(body: String): Seq[String] = Try(ujson.read(body)).toOption
    .map {
      case o: ujson.Obj => o.value.keys.toSeq
      case a: ujson.Arr => a.value.headOption.collect { case o: ujson.Obj =>
          o.value.keys.toSeq
        }.getOrElse(Seq.empty)
      case _ => Seq.empty
    }.getOrElse(Seq.empty)

  /** First scalar value for `field`, searched recursively. */
  def extractField(body: String, field: String): Option[String] = Try(
    ujson.read(body),
  ).toOption.flatMap(findField(_, field))

  private def findField(v: ujson.Value, field: String): Option[String] = v match
    case o: ujson.Obj => o.value.get(field).flatMap(scalar)
        .orElse(o.value.valuesIterator.flatMap(findField(_, field)).nextOption())
    case a: ujson.Arr => a.value.iterator.flatMap(findField(_, field)).nextOption()
    case _ => None

  private def scalar(v: ujson.Value): Option[String] = v match
    case ujson.Str(s) => Some(s)
    case ujson.Num(n) => Some(if n.isWhole then n.toLong.toString else n.toString)
    case ujson.Bool(b) => Some(b.toString)
    case _ => None

  /** Confirmed IDOR: a 2xx whose discriminator field is present and differs from
    * the caller's own baseline value (so cross-user data came back).
    */
  def confirms(
      ownFieldValue: String,
      status: Int,
      candidateBody: String,
      field: String,
  ): Boolean = status >= 200 && status <= 299 &&
    extractField(candidateBody, field).exists(v => v.nonEmpty && v != ownFieldValue)

  /** Parse the tool's `proposals` array into validated proposals (drops any
    * missing a required field or with no candidates).
    */
  def parseProposals(proposals: ujson.Value): Seq[Proposal] = proposals.arrOpt
    .getOrElse(Nil).flatMap { p =>
      for
        param <- p.obj.get("param").flatMap(_.strOpt).filter(_.nonEmpty)
        own <- p.obj.get("ownValue").flatMap(strOrNum)
        field <- p.obj.get("discriminatorField").flatMap(_.strOpt)
          .filter(_.nonEmpty)
        cands = p.obj.get("candidates").flatMap(_.arrOpt).getOrElse(Nil)
          .flatMap(strOrNum).filter(_.nonEmpty)
        if cands.nonEmpty
      yield Proposal(param, own, cands.toSeq, field)
    }.toSeq

  private def strOrNum(v: ujson.Value): Option[String] = v.strOpt
    .orElse(v.numOpt.map(n => if n.isWhole then n.toLong.toString else n.toString))

  def toFinding(
      url: String,
      param: String,
      candidate: String,
      field: String,
      leaked: String,
  ): Finding = Finding(
    kind = FindingKind.BrokenAccessControl,
    severity = Severity.High,
    evidence =
      s"$param=$candidate returned another caller's '$field' ($leaked) to the authenticated user (IDOR)",
    reproducible = true,
    replay = s"idor url=$url param=$param value=$candidate field=$field",
  )

  private def dec(s: String): String =
    Try(URLDecoder.decode(s, StandardCharsets.UTF_8)).getOrElse(s)
