package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AuthCrawlSpec extends AnyWordSpec with Matchers {

  "AuthCrawl.links" should {
    "resolve relative hrefs against the base URL" in {
      val html =
        """<a href="/account?id=1001">acct</a> <a href="orders">o</a>"""
      AuthCrawl.links("http://h/dashboard", html) shouldBe
        Seq("http://h/account?id=1001", "http://h/orders")
    }

    "keep absolute links and dedupe" in {
      val html =
        """<a href="http://h/a">a</a><a href="http://h/a">a again</a>"""
      AuthCrawl.links("http://h/", html) shouldBe Seq("http://h/a")
    }

    "drop fragments, javascript:, and mailto: links" in {
      val html =
        """<a href="#top">t</a><a href="javascript:void(0)">j</a><a href="mailto:x@y">m</a>"""
      AuthCrawl.links("http://h/", html) shouldBe empty
    }

    "be empty for markup with no links" in {
      AuthCrawl.links("http://h/", "<p>nothing here</p>") shouldBe empty
    }
  }
}
