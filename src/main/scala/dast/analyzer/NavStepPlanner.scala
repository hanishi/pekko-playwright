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
import dast.NavStep

/** The per-hop navigation planner: given the current page (indexed forms +
  * links) and the history, the model chooses ONE next [[NavStep]] (follow a
  * link, submit a form, or done) via forced tool use.
  *
  * The model drives the trajectory (instruction.md §5 navigation-action
  * carve-out) but picks only indexed elements -- it never authors a URL or code
  * (§0.2). Fails closed to [[NavStep.Done]] (which ends the loop) on a missing
  * key, HTTP error, or unparseable response.
  *
  * [[buildRequestBody]] / [[responseToStep]] are pure and unit tested; [[plan]]
  * (the HTTP call) is exercised only live.
  */
object NavStepPlanner:

  private val log = LoggerFactory.getLogger("dast.analyzer.NavStepPlanner")

  val ToolName = "choose_navigation_step"

  def model: String = DastConfig.get("ANTHROPIC_MODEL")
    .getOrElse("claude-opus-4-8")

  private val SystemPrompt =
    "You are the navigation step of a consented scan. From the current " +
      "authenticated page, choose ONE next action to reach pages that LIST " +
      "objects (search/filter/lookup results) so an IDOR check can run: follow " +
      "a link, submit a form, or finish. For a form, set safe=true only when " +
      "the submission is non-state-changing (a search/filter/lookup); never for " +
      "create/update/delete/pay/logout/email. Pick only the indexed elements " +
      "shown. Choose done when nothing new remains. You never write code."

  private def tool: ujson.Value = ujson.Obj(
    "name" -> ToolName,
    "description" -> "Choose the next navigation step.",
    "input_schema" -> ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "action" -> ujson.Obj(
          "type" -> "string",
          "enum" -> ujson.Arr("follow", "submit", "done"),
        ),
        "linkIndex" -> ujson.Obj("type" -> "integer"),
        "formIndex" -> ujson.Obj("type" -> "integer"),
        "values" -> ujson.Obj("type" -> "object"),
        "safe" -> ujson.Obj("type" -> "boolean"),
      ),
      "required" -> ujson.Arr("action"),
    ),
  )

  def buildRequestBody(
      url: String,
      forms: Seq[FormInfo],
      links: Seq[String],
      history: Seq[String],
  ): ujson.Value = ujson.Obj(
    "model" -> model,
    "max_tokens" -> 512,
    "system" -> SystemPrompt,
    "tools" -> ujson.Arr(tool),
    "tool_choice" -> ujson.Obj("type" -> "tool", "name" -> ToolName),
    "messages" -> ujson.Arr(ujson.Obj(
      "role" -> "user",
      "content" -> NavStep.render(url, forms, links, history),
    )),
  )

  def responseToStep(body: ujson.Value): NavStep = body.objOpt
    .flatMap(_.get("content")).flatMap(_.arrOpt).flatMap { blocks =>
      blocks.find { b =>
        b.objOpt.exists(o =>
          o.get("type").flatMap(_.strOpt).contains("tool_use") && o.get("name")
            .flatMap(_.strOpt).contains(ToolName),
        )
      }
    }.flatMap(_.objOpt.flatMap(_.get("input"))).map(NavStep.parse)
    .getOrElse(NavStep.Done)

  def plan(
      url: String,
      forms: Seq[FormInfo],
      links: Seq[String],
      history: Seq[String],
  )(using system: ActorSystem[?], ec: ExecutionContext): Future[NavStep] =
    DastConfig.get("ANTHROPIC_API_KEY") match
      case None =>
        log.warn("ANTHROPIC_API_KEY not set; navigator ending (done)")
        Future.successful(NavStep.Done)
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
            ujson.write(buildRequestBody(url, forms, links, history)),
          ),
        )
        Http()(system).singleRequest(request).flatMap { response =>
          if response.status.isSuccess() then
            Unmarshal(response.entity).to[String].map { raw =>
              val step = Try(ujson.read(raw)).toOption.map(responseToStep)
                .getOrElse(NavStep.Done)
              log.info("Navigator step for {}: {}", url, step)
              step
            }
          else
            response.entity.discardBytes()
            log.warn("Navigator request failed: {}", response.status)
            Future.successful(NavStep.Done)
        }.recover { case t =>
          log.warn("Navigator error: {}", t.getMessage)
          NavStep.Done
        }
