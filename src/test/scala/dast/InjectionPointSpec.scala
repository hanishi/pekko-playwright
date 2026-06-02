package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import dast.InjectionPoint.Fragment
import dast.InjectionPoint.PathSegment
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

  "Fragment.placeInto" should {

    "set the encoded payload as the fragment, preserving query" in {
      Fragment.placeInto("https://example.com/s?q=1", "<img src=x>") shouldBe
        "https://example.com/s?q=1#%3Cimg+src%3Dx%3E"
    }

    "replace an existing fragment" in {
      Fragment.placeInto("https://example.com/p#old", "v") shouldBe
        "https://example.com/p#v"
    }

    "describe itself" in { Fragment.describe shouldBe "URL fragment" }
  }

  "PathSegment.placeInto" should {

    "replace the segment at the index and preserve query/fragment" in {
      PathSegment(1)
        .placeInto("https://example.com/login?q=1#f", "INJ") shouldBe
        "https://example.com/INJ?q=1#f"
    }

    "append when the index is out of range" in {
      PathSegment(5).placeInto("https://example.com/a", "INJ") shouldBe
        "https://example.com/a/INJ"
    }

    "describe itself" in { PathSegment(2).describe shouldBe "path segment 2" }
  }
}
