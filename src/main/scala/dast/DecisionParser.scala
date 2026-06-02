package dast

import scala.collection.Map as ScalaMap
import scala.util.Try

import dast.LlmDecision.*

/** Turns model output (a JSON string, or equivalently the `input` of an
  * Anthropic `decide` tool call) into a validated [[LlmDecision]].
  *
  * This is the security boundary described in CLAUDE.md section 4. Every field
  * is checked and only the on-menu fields are read; anything malformed,
  * off-menu, or referencing an unknown id returns `Left` and is never turned
  * into an executable decision. Extra fields a model might emit (a stray
  * `code` or `script`) are simply ignored, so they cannot reach execution.
  * Callers fail closed on `Left` (default to a no-op, never to a guess).
  */
object DecisionParser:

  def parse(json: String): Either[String, LlmDecision] =
    Try(ujson.read(json)).toEither.left
      .map(e => s"malformed JSON: ${e.getMessage}")
      .flatMap(fromJson)

  private def fromJson(v: ujson.Value): Either[String, LlmDecision] =
    for
      obj      <- v.objOpt.toRight("decision must be a JSON object")
      kind     <- strField(obj, "kind")
      decision <- kind match
        case "probe"    => parseProbe(obj)
        case "navigate" => parseNavigate(obj)
        case "classify" => parseClassify(obj)
        case "done"     => Right(Done)
        case other      => Left(s"unknown kind '$other'")
    yield decision

  private def parseProbe(obj: ScalaMap[String, ujson.Value]): Either[String, LlmDecision] =
    for
      injectionPointId <- strField(obj, "injectionPointId")
      payloadId        <- strField(obj, "payloadId")
      _ <-
        if PayloadLibrary.ids.contains(payloadId) then Right(())
        else Left(s"unknown payloadId '$payloadId'")
    yield Probe(injectionPointId, payloadId)

  private def parseNavigate(obj: ScalaMap[String, ujson.Value]): Either[String, LlmDecision] =
    for
      actionV   <- obj.get("action").toRight("missing field 'action'")
      actionObj <- actionV.objOpt.toRight("field 'action' must be a JSON object")
      tpe       <- strField(actionObj, "type")
      intent <- tpe match
        case "reload"     => Right(NavIntent.Reload)
        case "back"       => Right(NavIntent.Back)
        case "forward"    => Right(NavIntent.Forward)
        case "followLink" => strField(actionObj, "linkId").map(NavIntent.FollowLink(_))
        case other        => Left(s"unknown navigate action type '$other'")
    yield Navigate(intent)

  private def parseClassify(obj: ScalaMap[String, ujson.Value]): Either[String, LlmDecision] =
    for
      storageKey <- strField(obj, "storageKey")
      verdictStr <- strField(obj, "verdict")
      verdict <- verdictStr match
        case "sensitive"    => Right(Sensitivity.Sensitive)
        case "notSensitive" => Right(Sensitivity.NotSensitive)
        case "unknown"      => Right(Sensitivity.Unknown)
        case other          => Left(s"unknown verdict '$other'")
    yield Classify(storageKey, verdict)

  /** Require a present, string-typed, non-blank field. */
  private def strField(obj: ScalaMap[String, ujson.Value], key: String): Either[String, String] =
    obj.get(key).toRight(s"missing field '$key'").flatMap { v =>
      v.strOpt.toRight(s"field '$key' must be a string").flatMap { s =>
        if s.trim.nonEmpty then Right(s) else Left(s"field '$key' must not be blank")
      }
    }
