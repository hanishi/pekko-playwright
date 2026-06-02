package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import dast.InjectionPoint.QueryParam

class ProbeOpSpec extends AnyWordSpec with Matchers {

  private val authed = Authorization.active("example.com")
  private val point = QueryParam("q")

  "ProbeOp.precheck" should {

    "deny under the observe-only default (no browser touched)" in {
      ProbeOp.precheck(
        Authorization.ObserveOnly,
        "https://example.com",
        "img-onerror",
      ) shouldBe Left("active testing is disabled (observe-only)")
    }

    "deny an off-scope host" in {
      ProbeOp.precheck(authed, "https://evil.test", "img-onerror") shouldBe
        Left("host 'evil.test' is not in the authorized scope")
    }

    "deny an unknown payload id even when authorized" in {
      ProbeOp.precheck(authed, "https://example.com", "rm -rf") shouldBe
        Left("unknown payloadId 'rm -rf'")
    }

    "permit an authorized host with a known payload id" in {
      ProbeOp.precheck(authed, "https://example.com", "img-onerror")
        .map(_.id) shouldBe Right("img-onerror")
    }
  }

  "ProbeOp.firedMarkers" should {

    "collect markers from a Java list and tolerate non-list input" in {
      val list = new java.util.ArrayList[Object]()
      list.add("dastABC")
      list.add("dastXYZ")
      ProbeOp.firedMarkers(list) shouldBe Set("dastABC", "dastXYZ")
      ProbeOp.firedMarkers(null) shouldBe Set.empty
      ProbeOp.firedMarkers("nope") shouldBe Set.empty
    }
  }

  "ProbeOp.toFinding" should {

    "emit a High reproducible XSS finding when the marker fired" in {
      val f = ProbeOp.toFinding("img-onerror", point, "dastABC", Set("dastABC"))
        .get
      f.kind shouldBe FindingKind.Xss
      f.severity shouldBe Severity.High
      f.reproducible shouldBe true
      f.replay shouldBe "probe query param 'q' payload=img-onerror"
    }

    "emit nothing when the marker did not fire" in {
      ProbeOp
        .toFinding("img-onerror", point, "dastABC", Set("dastOTHER")) shouldBe
        None
      ProbeOp.toFinding("img-onerror", point, "dastABC", Set.empty) shouldBe
        None
    }
  }
}
