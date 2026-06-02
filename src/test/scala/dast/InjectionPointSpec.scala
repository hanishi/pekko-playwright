package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import dast.InjectionPoint.QueryParam

class InjectionPointSpec extends AnyWordSpec with Matchers {

  "QueryParam.placeInto" should {

    "add the URL-encoded payload as the named param" in {
      QueryParam("q")
        .placeInto("https://example.com/search", "<img src=x>") shouldBe
        "https://example.com/search?q=%3Cimg+src%3Dx%3E"
    }

    "replace an existing value of the same param and preserve others" in {
      val out = QueryParam("q")
        .placeInto("https://example.com/s?q=old&page=2", "new")
      out should include("q=new")
      out should include("page=2")
      (out should not).include("q=old")
    }

    "preserve the fragment" in {
      QueryParam("q").placeInto("https://example.com/s#top", "v") shouldBe
        "https://example.com/s?q=v#top"
    }

    "describe itself for evidence and replay" in {
      QueryParam("token").describe shouldBe "query param 'token'"
    }
  }
}
