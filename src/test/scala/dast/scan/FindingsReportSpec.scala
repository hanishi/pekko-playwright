package dast.scan

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import dast.Finding
import dast.FindingKind
import dast.Severity

class FindingsReportSpec extends AnyWordSpec with Matchers {

  "FindingsReport.toJson" should {

    "serialize a finding's fields" in {
      val f = Finding(
        FindingKind.Xss,
        Severity.High,
        "executed at q",
        reproducible = true,
        "probe q",
      )
      val json = FindingsReport.toJson("https://target", Seq(f))

      json("target").str shouldBe "https://target"
      json("findingCount").num shouldBe 1.0
      val first = json("findings")(0)
      first("kind").str shouldBe "Xss"
      first("severity").str shouldBe "High"
      first("evidence").str shouldBe "executed at q"
      first("reproducible").bool shouldBe true
      first("replay").str shouldBe "probe q"
    }

    "produce an empty findings array for no findings" in {
      val json = FindingsReport.toJson("https://target", Seq.empty)
      json("findingCount").num shouldBe 0.0
      json("findings").arr shouldBe empty
    }
  }
}
