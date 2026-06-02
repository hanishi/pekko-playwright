package dast.analyzer

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import dast.LlmDecision
import dast.LlmDecision.*
import dast.PayloadLibrary

class ClaudeAnalyzerSpec extends AnyWordSpec with Matchers {

  private val context = AnalyzerContext(
    url = "https://example.com/page",
    storageKeys = Seq("id_token"),
    injectionPointIds = Seq("q"),
    linkIds = Seq("l1"),
  )

  "ClaudeAnalyzer.buildRequestBody" should {

    val body = ClaudeAnalyzer.buildRequestBody(context)

    "force the decide tool and send no sampling or thinking params" in {
      body("tool_choice")("type").str shouldBe "tool"
      body("tool_choice")("name").str shouldBe "decide"
      body.obj.contains("temperature") shouldBe false
      body.obj.contains("thinking") shouldBe false
      body("model").str should not be empty
    }

    "constrain payloadId to the audited library" in {
      val enumValues =
        body("tools")(0)("input_schema")("properties")("payloadId")("enum").arr
          .map(_.str).toSet
      enumValues shouldBe PayloadLibrary.ids
    }

    "mark the static prefix (system + tool) cache-friendly" in {
      body("system")(0).obj.contains("cache_control") shouldBe true
      body("tools")(0).obj.contains("cache_control") shouldBe true
    }
  }

  "ClaudeAnalyzer.responseToDecision" should {

    def toolUse(input: ujson.Value): ujson.Value = ujson
      .Obj("type" -> "tool_use", "name" -> "decide", "input" -> input)

    def response(blocks: ujson.Value*): ujson.Value = ujson
      .Obj("content" -> ujson.Arr(blocks*))

    "map a valid decide tool_use to the decision" in {
      val body = response(toolUse(ujson.Obj(
        "kind" -> "probe",
        "injectionPointId" -> "q",
        "payloadId" -> "img-onerror",
      )))
      ClaudeAnalyzer.responseToDecision(body) shouldBe Probe("q", "img-onerror")
    }

    "fail closed to Done when there is no tool_use block" in {
      val body =
        response(ujson.Obj("type" -> "text", "text" -> "here is my analysis"))
      ClaudeAnalyzer.responseToDecision(body) shouldBe Done
    }

    "fail closed to Done on an off-menu payloadId" in {
      val body = response(toolUse(ujson.Obj(
        "kind" -> "probe",
        "injectionPointId" -> "q",
        "payloadId" -> "rm -rf",
      )))
      ClaudeAnalyzer.responseToDecision(body) shouldBe Done
    }

    "fail closed to Done on a malformed body" in {
      ClaudeAnalyzer.responseToDecision(ujson.Obj()) shouldBe Done
    }
  }
}
