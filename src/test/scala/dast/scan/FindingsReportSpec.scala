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

  "FindingsReport.toJsonSite" should {

    "group findings per URL and total the count" in {
      val f =
        Finding(FindingKind.Xss, Severity.High, "x", reproducible = true, "r")
      val json = FindingsReport.toJsonSite(
        "https://seed",
        Seq("https://seed/a" -> Seq(f), "https://seed/b" -> Seq.empty),
      )
      json("seed").str shouldBe "https://seed"
      json("findingCount").num shouldBe 1.0
      json("pages").arr should have size 2
      json("pages")(0)("url").str shouldBe "https://seed/a"
      json("pages")(0)("findings").arr should have size 1
      json("pages")(1)("findings").arr shouldBe empty
    }
  }
}
