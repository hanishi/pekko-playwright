package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import dast.LlmDecision.*

class DecisionParserSpec extends AnyWordSpec with Matchers {

  "DecisionParser" should {

    "parse a valid probe" in {
      DecisionParser.parse(
        """{"kind":"probe","injectionPointId":"q","payloadId":"img-onerror"}""",
      ) shouldBe Right(Probe("q", "img-onerror"))
    }

    "parse the navigate variants" in {
      DecisionParser.parse("""{"kind":"navigate","action":{"type":"reload"}}""") shouldBe
        Right(Navigate(NavIntent.Reload))
      DecisionParser.parse("""{"kind":"navigate","action":{"type":"back"}}""") shouldBe
        Right(Navigate(NavIntent.Back))
      DecisionParser.parse(
        """{"kind":"navigate","action":{"type":"followLink","linkId":"l7"}}""",
      ) shouldBe Right(Navigate(NavIntent.FollowLink("l7")))
    }

    "parse classify with each verdict" in {
      DecisionParser.parse(
        """{"kind":"classify","storageKey":"token","verdict":"sensitive"}""",
      ) shouldBe Right(Classify("token", Sensitivity.Sensitive))
      DecisionParser.parse(
        """{"kind":"classify","storageKey":"theme","verdict":"notSensitive"}""",
      ) shouldBe Right(Classify("theme", Sensitivity.NotSensitive))
    }

    "parse done" in {
      DecisionParser.parse("""{"kind":"done"}""") shouldBe Right(Done)
    }

    "reject an unknown kind" in {
      DecisionParser.parse("""{"kind":"exfiltrate"}""").left.map(_.toLowerCase) shouldBe
        Left("unknown kind 'exfiltrate'")
    }

    "reject a probe with a payloadId not in the library" in {
      DecisionParser.parse(
        """{"kind":"probe","injectionPointId":"q","payloadId":"rm -rf"}""",
      ) shouldBe Left("unknown payloadId 'rm -rf'")
    }

    "reject an unknown verdict and an unknown navigate type" in {
      DecisionParser.parse(
        """{"kind":"classify","storageKey":"k","verdict":"maybe"}""",
      ) shouldBe Left("unknown verdict 'maybe'")
      DecisionParser.parse(
        """{"kind":"navigate","action":{"type":"submitForm"}}""",
      ) shouldBe Left("unknown navigate action type 'submitForm'")
    }

    "reject missing, blank, or wrong-typed fields" in {
      DecisionParser.parse("""{"kind":"probe","payloadId":"img-onerror"}""") shouldBe
        Left("missing field 'injectionPointId'")
      DecisionParser.parse(
        """{"kind":"probe","injectionPointId":"  ","payloadId":"img-onerror"}""",
      ) shouldBe Left("field 'injectionPointId' must not be blank")
      DecisionParser.parse("""{"kind":123}""").isLeft shouldBe true
    }

    "reject malformed JSON and non-object input" in {
      DecisionParser.parse("not json").isLeft shouldBe true
      DecisionParser.parse("""["probe"]""") shouldBe Left("decision must be a JSON object")
    }

    "ignore off-menu fields and never produce anything executable" in {
      // A model that smuggles a `code`/`script` field gets parsed only for its
      // on-menu fields; the extra fields are dropped, not executed.
      val result = DecisionParser.parse(
        """{"kind":"done","code":"fetch('//evil')","script":"<script>x</script>"}""",
      )
      result shouldBe Right(Done)
    }
  }
}
