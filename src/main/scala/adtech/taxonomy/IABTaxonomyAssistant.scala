package adtech.taxonomy

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller

import adtech.taxonomy.IABTaxonomyAssistant.Request
import adtech.taxonomy.IABTaxonomyAssistant.Response
import adtech.taxonomy.IABTaxonomyAssistant.Selection
import adtech.taxonomy.IABTaxonomyAssistant.apiUrl
import adtech.taxonomy.IABTaxonomyAssistant.content
import adtech.taxonomy.IABTaxonomyAssistant.instruction
import adtech.taxonomy.IABTaxonomyAssistant.jsonSchema
import adtech.taxonomy.IABTaxonomyAssistant.tieredCategory
import spray.json.*

class IABTaxonomyAssistant(apiKey: String, baseTaxonomyIds: Set[String]):

  def analyzeTaxonomy(url: String, text: String)(using
      ActorSystem[_],
      ExecutionContext,
  ): Future[List[Selection]] = getStructuredOutput(url, text)
    .map(res =>
      res.choices.headOption.map(_.message.content.selected_taxonomy_ids)
        .getOrElse(List.empty),
    )

  private def getStructuredOutput(
      url: String,
      text: String,
  )(using system: ActorSystem[_], ec: ExecutionContext): Future[Response] =
    val candidates = taxonomy(baseTaxonomyIds)
    val payload = Request(
      "gpt-4o-mini-2024-07-18",
      List(
        Map(
          "role" -> "system",
          "content" -> instruction(
            url,
            candidates.map(entry => s"- ${entry._2}").mkString("\n"),
          ),
        ),
        Map("role" -> "user", "content" -> content(text)),
      ),
      jsonSchema(candidates.keys),
    ).toJson.compactPrint

    val requestEntity = HttpEntity(ContentTypes.`application/json`, payload)
    val httpRequest = HttpRequest(
      method = HttpMethods.POST,
      uri = apiUrl,
      headers = List(headers.RawHeader("Authorization", s"Bearer $apiKey")),
      entity = requestEntity,
    )
    for {
      httpResponse <- Http().singleRequest(httpRequest)
      response <-
        if httpResponse.status.isSuccess() then {
          val x = Unmarshal(httpResponse.entity).to[Response]
          x
        } else
          Unmarshal(httpResponse.entity).to[String].map { errorBody =>
            throw new RuntimeException(s"Error from server: ${httpResponse
                .status}")
          }
    } yield response

  private def taxonomy(categoryCandidates: Set[String]): Map[String, String] =
    (for {
      id <- categoryCandidates
      category <- tieredCategory(id)
    } yield category.id -> category.toString).toMap

object IABTaxonomyAssistant extends DefaultJsonProtocol:
  val apiUrl = "https://api.openai.com/v1/chat/completions"

  given RootJsonFormat[Request] = jsonFormat3(Request.apply)

  given taxonomyIdFormat: RootJsonFormat[Selection] =
    jsonFormat2(Selection.apply)

  given contentFormat: RootJsonFormat[Content] = jsonFormat1(Content.apply)

  given choiceFormat: RootJsonFormat[Choice] = jsonFormat4(Choice.apply)

  given responseFormat: RootJsonFormat[Response] = jsonFormat1(Response.apply)

  given responseUnmarshaller: Unmarshaller[ResponseEntity, Response] =
    Unmarshaller.stringUnmarshaller
      .map(jsonString => jsonString.parseJson.convertTo[Response])

  private def tieredCategory(id: String) = TieredCategory.getAllDescendants(id)

  private def instruction(url: String, categories: String) =
    s"""
       |You are an assistant that classifies the provided text found at the $url into the most relevant categories from the following taxonomy.
       |
       |### Taxonomy Overview:
       |The taxonomy is structured hierarchically as:
       |- `name(id) -> Tier 1 name(id) -> ... -> Tier N name(id)`
       |
       |Below is the list of available categories in the taxonomy for classification:
       |$categories
       |
       |### Instructions:
       |1. Match the content to the most relevant categories using the hierarchical format provided.
       |2. If the content fits a bottom-most category, select that category's ID. Otherwise, select the appropriate parent category's ID.
       |3. Return each selected category as an object with:
       |   - `id`: the category ID as a string
       |   - `confidence`: a number between 0 and 1
       |""".stripMargin

  private def content(text: String) =
    s"""
       |Here is the content to be categorized:
       |$text
       |""".stripMargin

  private def jsonSchema(ids: Iterable[String]) = JsObject(
    "type" -> JsString("json_schema"),
    "json_schema" -> JsObject(
      "name" -> JsString("iab_taxonomy_selection"),
      "schema" -> JsObject(
        "type" -> JsString("object"),
        "properties" -> JsObject(
          "selected_taxonomy_ids" -> JsObject(
            "type" -> JsString("array"),
            "items" -> JsObject(
              "type" -> JsString("object"),
              "properties" -> JsObject(
                "id" -> JsObject(
                  "type" -> JsString("string"),
                  "enum" -> JsArray(ids.map(JsString(_)).toVector),
                ),
                "confidence" -> JsObject(
                  "type" -> JsString("number"),
                  "minimum" -> JsNumber(0),
                  "maximum" -> JsNumber(1),
                ),
              ),
              "required" -> JsArray(JsString("id"), JsString("confidence")),
            ),
          ),
        ),
        "required" -> JsArray(JsString("selected_taxonomy_ids")),
      ),
    ),
  )

  case class Selection(id: String, confidence: Double)

  case class ResponseMessage(
      content: Content,
      refusal: Option[String],
      role: String,
  )

  case class Content(selected_taxonomy_ids: List[Selection])

  case class Choice(
      finish_reason: String,
      index: Int,
      logprobs: Option[String],
      message: ResponseMessage,
  )

  case class Response(choices: List[Choice])

  case class Request(
      model: String,
      messages: List[Map[String, String]],
      response_format: JsObject,
  )

  given RootJsonFormat[ResponseMessage] with {
    def write(responseMessage: ResponseMessage): JsValue = JsObject(
      "content" -> JsString(responseMessage.content.toJson.compactPrint),
      "refusal" -> responseMessage.refusal.toJson,
      "role" -> JsString(responseMessage.role),
    )

    def read(value: JsValue): ResponseMessage = {
      value.asJsObject.getFields("content", "refusal", "role") match {
        case Seq(JsString(contentStr), refusal, JsString(role)) =>
          val content = contentStr.parseJson.convertTo[Content]
          ResponseMessage(content, refusal.convertTo[Option[String]], role)
        case _ => throw DeserializationException("ResponseMessage expected")
      }
    }
  }
