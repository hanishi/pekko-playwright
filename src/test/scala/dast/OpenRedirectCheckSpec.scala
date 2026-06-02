package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class OpenRedirectCheckSpec extends AnyWordSpec with Matchers {

  private val host = OpenRedirectCheck.SentinelHost

  "OpenRedirectCheck.paramNames" should {
    "list distinct query parameter names" in {
      OpenRedirectCheck
        .paramNames("https://x.test/go?next=1&u=2&next=3") shouldBe
        Seq("next", "u")
    }
    "be empty when there is no query" in {
      OpenRedirectCheck.paramNames("https://x.test/go") shouldBe empty
    }
  }

  "OpenRedirectCheck.confirms" should {
    "accept an absolute URL whose host is the sentinel" in {
      OpenRedirectCheck.confirms(s"https://$host/landing") shouldBe true
    }
    "accept a scheme-relative redirect to the sentinel" in {
      OpenRedirectCheck.confirms(s"//$host/") shouldBe true
    }
    "reject a same-site relative redirect" in {
      OpenRedirectCheck.confirms("/dashboard") shouldBe false
    }
    "reject a redirect to an unrelated host" in {
      OpenRedirectCheck.confirms("https://legit.test/next") shouldBe false
    }
    "not be fooled by the sentinel appearing in a path or query" in {
      OpenRedirectCheck.confirms(s"https://legit.test/?u=$host") shouldBe false
    }
  }

  "OpenRedirectCheck.toFinding" should {
    "be a reproducible Medium with a replay handle naming the point" in {
      val f = OpenRedirectCheck
        .toFinding(InjectionPoint.QueryParam("next"), s"https://$host/")
      f.kind shouldBe FindingKind.OpenRedirect
      f.severity shouldBe Severity.Medium
      f.reproducible shouldBe true
      f.replay should include("query param 'next'")
      f.replay should include(s"https://$host/")
    }
  }
}
