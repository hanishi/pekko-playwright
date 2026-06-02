package dast.analyzer

import dast.PayloadLibrary

/** Builds the single `decide` tool the model is forced to call. Its input
  * schema is the `LlmDecision` discriminated union, with `payloadId` and the
  * other choice fields constrained by `enum` so the model is steered toward
  * valid values at the schema level. This is defense in depth: `DecisionParser`
  * still validates the returned input and is the authoritative boundary.
  */
object DecisionTool:

  val Name = "decide"

  /** The tool object `{name, description, input_schema}` as a ujson value. */
  def tool: ujson.Value =
    ujson.Obj(
      "name" -> Name,
      "description" ->
        ("Choose exactly one next action for the DAST scanner. " +
          "kind=probe injects an audited payload at an injection point (active); " +
          "kind=navigate moves within the app; kind=classify records whether a " +
          "stored value is sensitive; kind=done stops. Supply only the fields " +
          "relevant to the chosen kind."),
      "input_schema" -> inputSchema,
    )

  private def inputSchema: ujson.Value =
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "kind" -> enumSchema(Seq("probe", "navigate", "classify", "done")),
        "injectionPointId" -> ujson.Obj("type" -> "string"),
        "payloadId" -> enumSchema(PayloadLibrary.ids.toSeq.sorted),
        "action" -> ujson.Obj(
          "type" -> "object",
          "properties" -> ujson.Obj(
            "type" -> enumSchema(Seq("reload", "back", "forward", "followLink")),
            "linkId" -> ujson.Obj("type" -> "string"),
          ),
        ),
        "storageKey" -> ujson.Obj("type" -> "string"),
        "verdict" -> enumSchema(Seq("sensitive", "notSensitive", "unknown")),
      ),
      "required" -> ujson.Arr("kind"),
    )

  private def enumSchema(values: Seq[String]): ujson.Value =
    ujson.Obj("type" -> "string", "enum" -> ujson.Arr.from(values.map(ujson.Str(_))))
