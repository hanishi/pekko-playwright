package dast.analyzer

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory

import dast.DastConfig
import dast.FormParse.FormInfo
import dast.NavPlan
import dast.NavPlan.NavChoice

/** LLM-driven navigation planner: given the forms on an authenticated page,
  * propose which to submit and with what values to reach object-listing pages.
  *
  * The model is the navigator (instruction.md §5 navigation-action carve-out):
  * it picks forms and values and flags whether a submission is non-state-
  * changing. That flag is advisory; [[dast.ActionGuard]] is the deterministic
  * floor. The model authors no code. Fails closed to no choices.
  *
  * [[buildRequestBody]] / [[responseToChoices]] are pure and unit tested;
  * [[plan]] (the HTTP call) is exercised only live.
  */
object NavPlanner:

  private val log = LoggerFactory.getLogger("dast.analyzer.NavPlanner")

  val ToolName = "propose_form_submissions"

  def model: String = DastConfig.get("ANTHROPIC_MODEL")
    .getOrElse("claude-opus-4-8")

  private val SystemPrompt =
    "You are the navigation step of a consented scan. Given an authenticated " +
      "page's forms, choose which to submit (and the values to fill) to reach " +
      "pages that LIST objects, e.g. search or filter results, so an IDOR check " +
      "can then run. Set safe=true ONLY for a non-state-changing submission (a " +
      "search/filter/lookup); never for anything that creates, updates, deletes, " +
      "pays, logs out, or emails. Propose an empty list if no form would reveal " +
      "an object listing. You never write code; you only fill the tool's fields."

  private def tool: ujson.Value = ujson.Obj(
    "name" -> ToolName,
    "description" ->
      "Choose form submissions that reveal object listings (or an empty list).",
    "input_schema" -> ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj("proposals" -> ujson.Obj(
        "type" -> "array",
        "items" -> ujson.Obj(
          "type" -> "object",
          "properties" -> ujson.Obj(
            "formIndex" -> ujson.Obj("type" -> "integer"),
            "values" -> ujson.Obj("type" -> "object"),
            "safe" -> ujson.Obj("type" -> "boolean"),
          ),
          "required" -> ujson.Arr("formIndex", "safe"),
        ),
      )),
      "required" -> ujson.Arr("proposals"),
    ),
  )

  def buildRequestBody(url: String, forms: Seq[FormInfo]): ujson.Value = ujson.Obj(
    "model" -> model,
    "max_tokens" -> 1024,
    "system" -> SystemPrompt,
    "tools" -> ujson.Arr(tool),
    "tool_choice" -> ujson.Obj("type" -> "tool", "name" -> ToolName),
    "messages" -> ujson.Arr(
      ujson.Obj("role" -> "user", "content" -> NavPlan.renderForms(url, forms)),
    ),
  )

  def responseToChoices(body: ujson.Value): Seq[NavChoice] = body.objOpt
    .flatMap(_.get("content")).flatMap(_.arrOpt).flatMap { blocks =>
      blocks.find { b =>
        b.objOpt.exists(o =>
          o.get("type").flatMap(_.strOpt).contains("tool_use") && o.get("name")
            .flatMap(_.strOpt).contains(ToolName),
        )
      }
    }.flatMap(_.objOpt.flatMap(_.get("input")))
    .flatMap(_.objOpt.flatMap(_.get("proposals"))).map(NavPlan.parseChoices)
    .getOrElse(Seq.empty)

  def plan(url: String, forms: Seq[FormInfo])(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Seq[NavChoice]] = DastConfig.get("ANTHROPIC_API_KEY") match
    case None =>
      log.warn("ANTHROPIC_API_KEY not set; navigator returning no choices")
      Future.successful(Seq.empty)
    case Some(apiKey) =>
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = "https://api.anthropic.com/v1/messages",
        headers = List(
          headers.RawHeader("x-api-key", apiKey),
          headers.RawHeader("anthropic-version", "2023-06-01"),
        ),
        entity = HttpEntity(
          ContentTypes.`application/json`,
          ujson.write(buildRequestBody(url, forms)),
        ),
      )
      Http()(system).singleRequest(request).flatMap { response =>
        if response.status.isSuccess() then Unmarshal(response.entity).to[String]
          .map { raw =>
            val cs = Try(ujson.read(raw)).toOption.map(responseToChoices)
              .getOrElse(Seq.empty)
            log.info("Navigator proposed {} submission(s) for {}", cs.size, url)
            cs
          }
        else
          response.entity.discardBytes()
          log.warn("Navigator request failed: {}", response.status)
          Future.successful(Seq.empty)
      }.recover { case t =>
        log.warn("Navigator error: {}", t.getMessage)
        Seq.empty
      }
