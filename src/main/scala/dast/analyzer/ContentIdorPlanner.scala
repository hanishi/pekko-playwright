package dast.analyzer

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.slf4j.LoggerFactory

import dast.ContentIdor
import dast.ContentIdor.Proposal
import dast.DastConfig

/** LLM planner that harvests real object IDs from the authenticated pages the
  * browser visited and proposes IDOR tests (instruction.md §0.2). The model
  * finds the ids present in the content (incl. non-enumerable ULIDs/UUIDs),
  * decides which are object references, and where to inject them (query / path
  * / POST body) via a `{id}` template. It uses only ids it actually observed
  * and supplies only parameters; deterministic code confirms.
  *
  * [[buildRequestBody]] / [[responseToProposals]] are pure and unit tested;
  * [[plan]] (the HTTP call) is exercised only live.
  */
object ContentIdorPlanner:

  private val log = LoggerFactory.getLogger("dast.analyzer.ContentIdorPlanner")

  val ToolName = "propose_idor_tests"
  private val MaxPages = 8
  private val MaxCharsPerPage = 4000

  def model: String = DastConfig.get("ANTHROPIC_MODEL")
    .getOrElse("claude-opus-4-8")

  private val SystemPrompt = "You are the IDOR planning step of a consented scan. You are shown the " +
    "authenticated pages a browser visited and the requests it made. Find the " +
    "object-reference IDs actually present in that content (campaign, order, " +
    "user, account ids, etc., including random ULIDs/UUIDs). Propose IDOR " +
    "tests by calling the tool: a request template with a {id} placeholder " +
    "where the reference goes (a query parameter, a path segment, or a POST " +
    "body field), the caller's OWN id (baseline), candidate ids to try (other " +
    "real ids you saw that may belong to someone else), and the response " +
    "field that is per-user and would reveal another caller's record if it " +
    "differed. Use ONLY ids present in the content; never invent one. Propose " +
    "an empty list if there is no object reference to test. You never write " +
    "code; you only fill the tool's fields."

  private def tool: ujson.Value = ujson.Obj(
    "name" -> ToolName,
    "description" -> "Propose IDOR tests from observed ids (or an empty list).",
    "input_schema" -> ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "proposals" -> ujson.Obj(
          "type" -> "array",
          "items" -> ujson.Obj(
            "type" -> "object",
            "properties" -> ujson.Obj(
              "method" ->
                ujson.Obj("type" -> "string", "enum" -> ujson.Arr("GET", "POST")),
              "urlTemplate" -> ujson.Obj("type" -> "string"),
              "bodyTemplate" -> ujson.Obj("type" -> "string"),
              "ownValue" -> ujson.Obj("type" -> "string"),
              "candidates" -> ujson.Obj(
                "type" -> "array",
                "items" -> ujson.Obj("type" -> "string"),
              ),
              "discriminatorField" -> ujson.Obj("type" -> "string"),
            ),
            "required" -> ujson.Arr(
              "urlTemplate",
              "ownValue",
              "candidates",
              "discriminatorField",
            ),
          ),
        ),
      ),
      "required" -> ujson.Arr("proposals"),
    ),
  )

  /** Render the visited pages (capped) + observed request URLs for the model.
    */
  def renderContext(
      pages: Seq[(String, String)],
      requests: Seq[String],
  ): String =
    val pageBlocks = pages.take(MaxPages)
      .map((url, html) => s"PAGE $url\n${html.take(MaxCharsPerPage)}")
      .mkString("\n\n")
    s"""Observed requests:
       |${requests.distinct.take(40).mkString("\n")}
       |
       |Authenticated pages:
       |$pageBlocks""".stripMargin

  def buildRequestBody(
      pages: Seq[(String, String)],
      requests: Seq[String],
  ): ujson.Value = ujson.Obj(
    "model" -> model,
    "max_tokens" -> 1500,
    "system" -> SystemPrompt,
    "tools" -> ujson.Arr(tool),
    "tool_choice" -> ujson.Obj("type" -> "tool", "name" -> ToolName),
    "messages" -> ujson.Arr(
      ujson.Obj("role" -> "user", "content" -> renderContext(pages, requests)),
    ),
  )

  def responseToProposals(body: ujson.Value): Seq[Proposal] = body.objOpt
    .flatMap(_.get("content")).flatMap(_.arrOpt).flatMap { blocks =>
      blocks.find(b =>
        b.objOpt.exists(o =>
          o.get("type").flatMap(_.strOpt).contains("tool_use") && o.get("name")
            .flatMap(_.strOpt).contains(ToolName),
        ),
      )
    }.flatMap(_.objOpt.flatMap(_.get("input")))
    .flatMap(_.objOpt.flatMap(_.get("proposals")))
    .map(ContentIdor.parseProposals).getOrElse(Seq.empty)

  private val CrossPrompt = "You are the IDOR planning step of a consented two-account test. You are " +
    "shown YOUR pages (authenticated as the attacker) and ANOTHER account's " +
    "pages (a different user). Propose IDOR tests by calling the tool: a " +
    "request template with a {id} placeholder for the object reference (query " +
    "param, path segment, or POST body field), `ownValue` = an object id from " +
    "YOUR pages (baseline), and `candidates` = object ids from the OTHER " +
    "account's pages (objects that belong to them, not you). The candidates " +
    "MUST be real ids seen in the other account's pages; never invent. " +
    "`discriminatorField` = the per-user response field that would reveal the " +
    "other account's record. We request the candidates AS YOU; if their " +
    "object comes back, that is IDOR. Empty list if there is no object " +
    "reference to test. You never write code."

  /** Render two labelled content sets: the attacker's own pages and the other
    * account's pages (whose ids are the candidates).
    */
  def renderCross(
      ownPages: Seq[(String, String)],
      otherPages: Seq[(String, String)],
      requests: Seq[String],
  ): String =
    def block(ps: Seq[(String, String)]) = ps.take(MaxPages)
      .map((url, html) => s"PAGE $url\n${html.take(MaxCharsPerPage)}")
      .mkString("\n\n")
    s"""Observed requests:
       |${requests.distinct.take(40).mkString("\n")}
       |
       |=== YOUR pages (attacker) ===
       |${block(ownPages)}
       |
       |=== OTHER account's pages (use THEIR ids as candidates) ===
       |${block(otherPages)}""".stripMargin

  def buildCrossRequestBody(
      ownPages: Seq[(String, String)],
      otherPages: Seq[(String, String)],
      requests: Seq[String],
  ): ujson.Value = ujson.Obj(
    "model" -> model,
    "max_tokens" -> 1500,
    "system" -> CrossPrompt,
    "tools" -> ujson.Arr(tool),
    "tool_choice" -> ujson.Obj("type" -> "tool", "name" -> ToolName),
    "messages" -> ujson.Arr(ujson.Obj(
      "role" -> "user",
      "content" -> renderCross(ownPages, otherPages, requests),
    )),
  )

  /** Two-identity plan: candidates come from the other account's pages. */
  def planCross(
      ownPages: Seq[(String, String)],
      otherPages: Seq[(String, String)],
      requests: Seq[String],
  )(using system: ActorSystem[?], ec: ExecutionContext): Future[Seq[Proposal]] =
    callPlanner(buildCrossRequestBody(ownPages, otherPages, requests))

  def plan(pages: Seq[(String, String)], requests: Seq[String])(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Future[Seq[Proposal]] = callPlanner(buildRequestBody(pages, requests))

  private def callPlanner(
      body: ujson.Value,
  )(using system: ActorSystem[?], ec: ExecutionContext): Future[Seq[Proposal]] =
    DastConfig.get("ANTHROPIC_API_KEY") match
      case None =>
        log.warn(
          "ANTHROPIC_API_KEY not set; content-IDOR planner returns nothing",
        )
        Future.successful(Seq.empty)
      case Some(apiKey) =>
        val request = HttpRequest(
          method = HttpMethods.POST,
          uri = "https://api.anthropic.com/v1/messages",
          headers = List(
            headers.RawHeader("x-api-key", apiKey),
            headers.RawHeader("anthropic-version", "2023-06-01"),
          ),
          entity = HttpEntity(ContentTypes.`application/json`, ujson.write(body)),
        )
        Http()(system).singleRequest(request).flatMap { response =>
          if response.status.isSuccess() then
            Unmarshal(response.entity).to[String].map { raw =>
              val ps = Try(ujson.read(raw)).toOption.map(responseToProposals)
                .getOrElse(Seq.empty)
              log.info("Content-IDOR planner proposed {} test(s)", ps.size)
              ps
            }
          else
            response.entity.discardBytes()
            log.warn("Content-IDOR planner failed: {}", response.status)
            Future.successful(Seq.empty)
        }.recover { case t =>
          log.warn("Content-IDOR planner error: {}", t.getMessage)
          Seq.empty
        }
