package dast.analyzer

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory

import dast.DecisionParser
import dast.LlmDecision

/** Turns an [[AnalyzerContext]] into one validated [[LlmDecision]] by calling
  * Claude with forced tool use (CLAUDE.md section 4).
  *
  * The model fills in the closed `decide` tool; [[DecisionParser]] is the
  * authoritative boundary that rejects anything off-menu. The call fails closed:
  * a missing key, HTTP failure, non-200, missing tool_use block, or parse
  * failure all yield [[LlmDecision.Done]] (a no-op), never a guess.
  *
  * Decision logic ([[buildRequestBody]], [[responseToDecision]]) is pure and
  * unit tested. [[analyze]] (the HTTP call) is exercised only against the live
  * Claude API (stated, not unit tested). Run it from an ordinary actor via
  * `ctx.pipeToSelf`, never on a pinned browser thread.
  */
object ClaudeAnalyzer:

  private val log = LoggerFactory.getLogger("dast.analyzer.ClaudeAnalyzer")

  val Endpoint         = "https://api.anthropic.com/v1/messages"
  val AnthropicVersion = "2023-06-01"
  val MaxTokens        = 1024

  /** Default per the claude-api skill; override with ANTHROPIC_MODEL. */
  def model: String = sys.env.getOrElse("ANTHROPIC_MODEL", "claude-opus-4-8")

  private val SystemPrompt =
    "You are the decision step of a consented DAST (dynamic application security " +
      "testing) scanner. Given the observed state of a page, select exactly one " +
      "next action by calling the `decide` tool. You never write or return code; " +
      "you only choose from the tool's menu. Prefer passive actions (navigate, " +
      "classify) and choose an active probe only when an injection point is " +
      "plausibly exploitable. When nothing useful remains, choose kind=done."

  /** Assemble the Messages API request body. Static prefix (system + tool) is
    * marked cache-friendly; `decide` is forced; no sampling/thinking params. */
  def buildRequestBody(context: AnalyzerContext): ujson.Value =
    val systemBlock = ujson.Obj(
      "type"          -> "text",
      "text"          -> SystemPrompt,
      "cache_control" -> ujson.Obj("type" -> "ephemeral"),
    )
    val tool = DecisionTool.tool
    tool("cache_control") = ujson.Obj("type" -> "ephemeral")

    ujson.Obj(
      "model"       -> model,
      "max_tokens"  -> MaxTokens,
      "system"      -> ujson.Arr(systemBlock),
      "tools"       -> ujson.Arr(tool),
      "tool_choice" -> ujson.Obj("type" -> "tool", "name" -> DecisionTool.Name),
      "messages" -> ujson.Arr(
        ujson.Obj("role" -> "user", "content" -> context.render),
      ),
    )

  /** Map a Messages API response body to a decision, failing closed to `Done`.
    * Finds the forced `decide` tool_use block and runs its input through
    * [[DecisionParser]]. */
  def responseToDecision(body: ujson.Value): LlmDecision =
    decideToolInput(body)
      .map(input => DecisionParser.parse(ujson.write(input)))
      .flatMap(_.toOption)
      .getOrElse(LlmDecision.Done)

  private def decideToolInput(body: ujson.Value): Option[ujson.Value] =
    body.objOpt
      .flatMap(_.get("content"))
      .flatMap(_.arrOpt)
      .flatMap { blocks =>
        blocks.find { b =>
          b.objOpt.exists { o =>
            o.get("type").flatMap(_.strOpt).contains("tool_use") &&
            o.get("name").flatMap(_.strOpt).contains(DecisionTool.Name)
          }
        }
      }
      .flatMap(_.objOpt.flatMap(_.get("input")))

  /** Call Claude and return the decision. Never throws and never blocks the
    * caller; any failure resolves to `Done`. Not unit tested (needs a key and a
    * live endpoint). */
  def analyze(
      context: AnalyzerContext,
  )(using system: ActorSystem[?], ec: ExecutionContext): Future[LlmDecision] =
    sys.env.get("ANTHROPIC_API_KEY").filter(_.nonEmpty) match
      case None =>
        log.warn("ANTHROPIC_API_KEY not set; analyzer failing closed to Done")
        Future.successful(LlmDecision.Done)
      case Some(apiKey) =>
        val request = HttpRequest(
          method = HttpMethods.POST,
          uri = Endpoint,
          headers = List(
            headers.RawHeader("x-api-key", apiKey),
            headers.RawHeader("anthropic-version", AnthropicVersion),
          ),
          entity = HttpEntity(ContentTypes.`application/json`, ujson.write(buildRequestBody(context))),
        )
        Http()(system).singleRequest(request).flatMap { response =>
          if response.status.isSuccess() then
            Unmarshal(response.entity).to[String].map { raw =>
              Try(ujson.read(raw)).toOption.fold(LlmDecision.Done)(responseToDecision)
            }
          else
            response.entity.discardBytes()
            log.warn("Analyzer request failed: {}", response.status)
            Future.successful(LlmDecision.Done)
        }.recover { case t =>
          log.warn("Analyzer request error: {}", t.getMessage)
          LlmDecision.Done
        }
