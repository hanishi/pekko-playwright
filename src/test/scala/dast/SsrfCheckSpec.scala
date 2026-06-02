package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SsrfCheckSpec extends AnyWordSpec with Matchers {

  private val point = InjectionPoint.QueryParam("url")

  "SsrfCheck.paramNames" should {
    "list distinct query parameter names" in {
      SsrfCheck.paramNames("https://x.test/f?url=a&n=b&url=c") shouldBe
        Seq("url", "n")
    }
  }

  "SsrfCheck.callbackUrl" should {
    "join base and token with a single slash" in {
      SsrfCheck.callbackUrl("http://oast.test:9000", "tok1") shouldBe
        "http://oast.test:9000/tok1"
      SsrfCheck.callbackUrl("http://oast.test:9000/", "tok1") shouldBe
        "http://oast.test:9000/tok1"
    }
  }

  "SsrfCheck.confirms" should {
    "be true only when the token was recorded by the listener" in {
      SsrfCheck.confirms("tok1", Set("tok1", "tok2")) shouldBe true
      SsrfCheck.confirms("tok1", Set("other")) shouldBe false
      SsrfCheck.confirms("tok1", Set.empty) shouldBe false
    }
  }

  "SsrfCheck.toFinding" should {
    "be a reproducible High naming the point and token" in {
      val f = SsrfCheck.toFinding(point, "tok1")
      f.kind shouldBe FindingKind.Ssrf
      f.severity shouldBe Severity.High
      f.reproducible shouldBe true
      f.replay should include("query param 'url'")
      f.replay should include("token=tok1")
    }
  }
}
