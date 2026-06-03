package dast

import dast.FormParse.FormInfo

/** Pure logic for LLM-driven form navigation: render the forms on a page for
  * the planner, and parse the planner's chosen submissions. The model selects
  * which form and what to type; [[ActionGuard]] is the deterministic floor on
  * whether a choice may actually be submitted.
  */
object NavPlan:

  /** A model-chosen submission: which form, the field values to fill, and the
    * model's (advisory) verdict that it is non-state-changing.
    */
  final case class NavChoice(
      formIndex: Int,
      values: Map[String, String],
      safe: Boolean,
  )

  def renderForms(url: String, forms: Seq[FormInfo]): String =
    val list = forms.zipWithIndex.map { (f, i) =>
      val fs = f.fields.map((n, t) => s"$n:$t").mkString(", ")
      s"[$i] method=${f.method} action=${f.action} fields=[$fs] submit='${f.submitText}'"
    }.mkString("\n")
    s"""Authenticated page: $url
       |Forms on this page:
       |$list
       |
       |To reach pages that list objects (search / filter / lookup results),
       |choose forms to submit and the values to fill. Set safe=true only for a
       |non-state-changing submission (a search/filter/lookup, never something
       |that creates, deletes, pays, or emails). Propose an empty list if no form
       |would reveal object listings.""".stripMargin

  def parseChoices(proposals: ujson.Value): Seq[NavChoice] = proposals.arrOpt
    .getOrElse(Nil).flatMap { c =>
      c.obj.get("formIndex").flatMap(toInt).map { idx =>
        val values = c.obj.get("values").flatMap(_.objOpt)
          .map(_.flatMap((k, v) => strOrNum(v).map(k -> _)).toMap)
          .getOrElse(Map.empty)
        val safe = c.obj.get("safe").flatMap(_.boolOpt).getOrElse(false)
        NavChoice(idx, values, safe)
      }
    }.toSeq

  private def toInt(v: ujson.Value): Option[Int] = v.numOpt.map(_.toInt)
    .orElse(v.strOpt.flatMap(_.toIntOption))

  private def strOrNum(v: ujson.Value): Option[String] = v.strOpt.orElse(
    v.numOpt.map(n => if n.isWhole then n.toLong.toString else n.toString),
  )
