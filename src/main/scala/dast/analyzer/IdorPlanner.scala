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
import dast.IdorPlan
import dast.IdorPlan.Observation
import dast.IdorPlan.Proposal

/** The LLM planning step for IDOR: turn an [[Observation]] of an authenticated
  * page into proposed access-control tests via forced tool use.
  *
  * This is where the model earns its place (instruction.md §0.2): it reasons
  * about which parameter is an object reference, which neighbours are worth
  * trying, and which response field is per-user. It supplies only those
  * parameters; [[IdorPlan.parseProposals]] validates them and deterministic
  * code ([[dast.IdorProbe]]) confirms. Fails closed to an empty plan: a missing
  * key, HTTP error, or unparseable response yields no proposals, never a guess.
  *
  * [[buildRequestBody]] / [[responseToProposals]] are pure and unit tested;
  * [[plan]] (the HTTP call) is exercised only live.
  */
object IdorPlanner:

  private val log = LoggerFactory.getLogger("dast.analyzer.IdorPlanner")

  val ToolName = "propose_idor_tests"
  val MaxTokens = 1024

  def model: String = DastConfig.get("ANTHROPIC_MODEL")
    .getOrElse("claude-opus-4-8")

  private val SystemPrompt = "You are the planning step of a consented IDOR (insecure direct object " +
    "reference) check. Given an authenticated page, its query parameters with " +
    "current values, and the response's JSON field names, propose access-" +
    "control tests by calling the tool. For each parameter that looks like an " +
    "object reference (an id, account, order, user, etc.), give: the caller's " +
    "own current value, a few neighbour values to try (e.g. nearby ids), and " +
    "the response field that is per-user and would reveal another user's " +
    "record if it changed. Propose an empty list when no parameter is an " +
    "object reference. You never write code; you only fill the tool's fields."

  private def tool: ujson.Value = ujson.Obj(
    "name" -> ToolName,
    "description" ->
      "Propose IDOR tests for an authenticated page (or an empty list).",
    "input_schema" -> ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "proposals" -> ujson.Obj(
          "type" -> "array",
          "items" -> ujson.Obj(
            "type" -> "object",
            "properties" -> ujson.Obj(
              "param" -> ujson.Obj("type" -> "string"),
              "ownValue" -> ujson.Obj("type" -> "string"),
              "candidates" -> ujson.Obj(
                "type" -> "array",
                "items" -> ujson.Obj("type" -> "string"),
              ),
              "discriminatorField" -> ujson.Obj("type" -> "string"),
            ),
            "required" ->
              ujson.Arr("param", "ownValue", "candidates", "discriminatorField"),
          ),
        ),
      ),
      "required" -> ujson.Arr("proposals"),
    ),
  )

  def buildRequestBody(obs: Observation): ujson.Value = ujson.Obj(
    "model" -> model,
    "max_tokens" -> MaxTokens,
    "system" -> SystemPrompt,
    "tools" -> ujson.Arr(tool),
    "tool_choice" -> ujson.Obj("type" -> "tool", "name" -> ToolName),
    "messages" -> ujson.Arr(ujson.Obj("role" -> "user", "content" -> obs.render)),
  )

  /** Pull the forced tool input's `proposals` and validate, failing closed. */
  def responseToProposals(body: ujson.Value): Seq[Proposal] = body.objOpt
    .flatMap(_.get("content")).flatMap(_.arrOpt).flatMap { blocks =>
      blocks.find { b =>
        b.objOpt.exists(o =>
          o.get("type").flatMap(_.strOpt).contains("tool_use") && o.get("name")
            .flatMap(_.strOpt).contains(ToolName),
        )
      }
    }.flatMap(_.objOpt.flatMap(_.get("input")))
    .flatMap(_.objOpt.flatMap(_.get("proposals"))).map(IdorPlan.parseProposals)
    .getOrElse(Seq.empty)

  /** Call Claude for a plan. Never throws; any failure resolves to no plan. */
  def plan(
      obs: Observation,
  )(using system: ActorSystem[?], ec: ExecutionContext): Future[Seq[Proposal]] =
    DastConfig.get("ANTHROPIC_API_KEY") match
      case None =>
        log.warn("ANTHROPIC_API_KEY not set; IDOR planner returning no plan")
        Future.successful(Seq.empty)
      case Some(apiKey) =>
        log.info("Planning IDOR for {}", obs.url)
        val request = HttpRequest(
          method = HttpMethods.POST,
          uri = "https://api.anthropic.com/v1/messages",
          headers = List(
            headers.RawHeader("x-api-key", apiKey),
            headers.RawHeader("anthropic-version", "2023-06-01"),
          ),
          entity = HttpEntity(
            ContentTypes.`application/json`,
            ujson.write(buildRequestBody(obs)),
          ),
        )
        Http()(system).singleRequest(request).flatMap { response =>
          if response.status.isSuccess() then
            Unmarshal(response.entity).to[String].map { raw =>
              val ps = Try(ujson.read(raw)).toOption.map(responseToProposals)
                .getOrElse(Seq.empty)
              log.info(
                "IDOR planner proposed {} test(s) for {}",
                ps.size,
                obs.url,
              )
              ps
            }
          else
            response.entity.discardBytes()
            log.warn("IDOR planner request failed: {}", response.status)
            Future.successful(Seq.empty)
        }.recover { case t =>
          log.warn("IDOR planner error: {}", t.getMessage)
          Seq.empty
        }
