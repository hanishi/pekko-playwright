package crawler

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RobotsTxtParserSpec extends AnyWordSpec with Matchers {

  "RobotsTxtParser" should {

    "allow everything for empty/blank content" in {
      val p = RobotsTxtParser.parse("")
      p.isAllowed("https://x.com/anything") shouldBe true
    }

    "only honor rules inside the User-agent: * block" in {
      val p = RobotsTxtParser.parse(
        """User-agent: *
          |Disallow: /private
          |
          |User-agent: Googlebot
          |Disallow: /
          |""".stripMargin,
      )
      // Googlebot's Disallow: / must be ignored for the wildcard agent.
      p.isAllowed("https://x.com/open") shouldBe true
      p.isAllowed("https://x.com/private/data") shouldBe false
    }

    "let a more specific Allow override a Disallow" in {
      val p = RobotsTxtParser.parse(
        """User-agent: *
          |Disallow: /private
          |Allow: /private/public
          |""".stripMargin,
      )
      p.isAllowed("https://x.com/private/secret") shouldBe false
      p.isAllowed("https://x.com/private/public/page") shouldBe true
    }

    "support prefix '*' and exact '$' rules" in {
      val p = RobotsTxtParser.parse(
        """User-agent: *
          |Disallow: /tmp*
          |Disallow: /exact$
          |""".stripMargin,
      )
      p.isAllowed("https://x.com/tmpfile") shouldBe false
      p.isAllowed("https://x.com/exact") shouldBe false
      p.isAllowed("https://x.com/exact/more") shouldBe true
    }

    "ignore inline comments" in {
      val p = RobotsTxtParser.parse(
        """User-agent: *
          |Disallow: /x   # no crawling here
          |""".stripMargin,
      )
      p.isAllowed("https://x.com/x") shouldBe false
      p.isAllowed("https://x.com/y") shouldBe true
    }
  }
}
