package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SinkScanOpSpec extends AnyWordSpec with Matchers {

  "SinkScanOp.parseSinkHits" should {

    "collect sink names from a Java list and tolerate non-list input" in {
      val list = new java.util.ArrayList[Object]()
      list.add("innerHTML")
      list.add("eval")
      SinkScanOp.parseSinkHits(list) shouldBe Set("innerHTML", "eval")
      SinkScanOp.parseSinkHits(null) shouldBe Set.empty
      SinkScanOp.parseSinkHits("nope") shouldBe Set.empty
    }
  }

  "SinkScanOp.toFindings" should {

    "emit one reproducible DOM-XSS finding per sink reached" in {
      val findings = SinkScanOp
        .toFindings(InjectionPoint.Fragment, Set("innerHTML", "eval"))
      findings should have size 2
      findings.foreach { f =>
        f.kind shouldBe FindingKind.Xss
        f.severity shouldBe Severity.High
        f.reproducible shouldBe true
      }
      (findings.map(_.replay) should contain).allOf(
        "domxss URL fragment sink=eval",
        "domxss URL fragment sink=innerHTML",
      )
      findings.head.evidence should include("URL fragment")
    }

    "emit nothing when no sink was reached" in {
      SinkScanOp.toFindings(InjectionPoint.Fragment, Set.empty) shouldBe empty
    }
  }

  "SinkScanOp.sinkScanJs" should {

    "embed the marker and wrap the dangerous sinks" in {
      val js = SinkScanOp.sinkScanJs("dastMARK1")
      js should include("dastMARK1")
      js should include("__dastSinks")
      js should include("innerHTML")
      js should include("document.write")
      js should include("eval")
    }
  }
}
