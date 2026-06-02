package dast.scan

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ScopeSpec extends AnyWordSpec with Matchers {

  "Scope.inScope" should {
    "accept same-host and reject other hosts / unparseable" in {
      Scope.inScope("example.com", "https://example.com/a") shouldBe true
      Scope.inScope("example.com", "https://EXAMPLE.com/a?b=1") shouldBe true
      Scope.inScope("example.com", "https://evil.test/a") shouldBe false
      Scope.inScope("example.com", "not a url") shouldBe false
    }
  }

  "Scope.normalizeAndDedupe" should {
    "collapse normalize-equal urls, preserving order" in {
      Scope.normalizeAndDedupe(Seq(
        "https://example.com/a/",
        "https://example.com/a",
        "https://example.com/b?utm_source=x",
        "https://example.com/b",
      )) shouldBe Seq("https://example.com/a", "https://example.com/b")
    }
  }

  "Scope.cap" should {
    "bound the list" in {
      Scope.cap(Seq("a", "b", "c"), 2) shouldBe Seq("a", "b")
      Scope.cap(Seq("a"), 0) shouldBe empty
    }
  }

  "Scope.frontier" should {
    "include the seed, keep same-host, dedupe, and cap" in {
      val out = Scope.frontier(
        seed = "https://example.com/",
        discovered = Seq(
          "https://example.com/a",
          "https://evil.test/x", // off-host, dropped
          "https://example.com/a", // dupe
          "https://example.com/b",
        ),
        maxPages = 2,
      )
      out shouldBe Seq("https://example.com/", "https://example.com/a")
    }

    "be empty when the seed has no host" in {
      Scope.frontier("not a url", Seq("https://example.com/a"), 10) shouldBe empty
    }
  }
}
