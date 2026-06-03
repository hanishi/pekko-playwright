package dast

import dast.FormParse.FormInfo

/** One model-chosen navigation move (closed ADT, per instruction.md §0.2). The
  * model selects an indexed element on the current page; it never invents a
  * URL. Parsing is the boundary and fails closed to [[Done]].
  */
enum NavStep:
  case Follow(linkIndex: Int)
  case Submit(formIndex: Int, values: Map[String, String], safe: Boolean)
  case Done

object NavStep:

  /** Parse the planner's tool input into one step; anything off-menu -> Done.
    */
  def parse(input: ujson.Value): NavStep = input.objOpt.flatMap(_.get("action"))
    .flatMap(_.strOpt).map(_.toLowerCase) match
    case Some("follow") => intField(input, "linkIndex").map(NavStep.Follow(_))
        .getOrElse(NavStep.Done)
    case Some("submit") => intField(input, "formIndex").map { fi =>
        val values = input.obj.get("values").flatMap(_.objOpt)
          .map(_.flatMap((k, v) => strOrNum(v).map(k -> _)).toMap)
          .getOrElse(Map.empty)
        val safe = input.obj.get("safe").flatMap(_.boolOpt).getOrElse(false)
        NavStep.Submit(fi, values, safe)
      }.getOrElse(NavStep.Done)
    case _ => NavStep.Done

  /** Render the current page (indexed forms + links) and history for the
    * planner. History lists URLs already reached so the model explores onward.
    */
  def render(
      url: String,
      forms: Seq[FormInfo],
      links: Seq[String],
      history: Seq[String],
  ): String =
    val fs = forms.zipWithIndex.map { (f, i) =>
      val fields = f.fields.map((n, t) => s"$n:$t").mkString(", ")
      s"  form[$i] method=${f.method} action=${f
          .action} fields=[$fields] submit='${f.submitText}'"
    }.mkString("\n")
    val ls = links.zipWithIndex.map((l, i) => s"  link[$i] $l").mkString("\n")
    s"""Current page: $url
       |Forms:
       |${
        if forms.isEmpty then "  (none)" else fs
      }
       |Links:
       |${
        if links.isEmpty then "  (none)" else ls
      }
       |Already reached: ${
        if history.isEmpty then "(nothing yet)" else history.mkString(", ")
      }
       |
       |Choose ONE next step by calling the tool: follow a link, submit a form
       |(set safe=true only for a non-state-changing search/filter/lookup), or
       |done. Aim to reach pages that LIST objects so an IDOR check can run.
       |Choose done when nothing new remains.""".stripMargin

  private def intField(v: ujson.Value, name: String): Option[Int] = v.objOpt
    .flatMap(_.get(name))
    .flatMap(x => x.numOpt.map(_.toInt).orElse(x.strOpt.flatMap(_.toIntOption)))

  private def strOrNum(v: ujson.Value): Option[String] = v.strOpt.orElse(
    v.numOpt.map(n => if n.isWhole then n.toLong.toString else n.toString),
  )
