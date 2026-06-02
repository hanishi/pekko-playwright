package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DastConfigSpec extends AnyWordSpec with Matchers {

  "DastConfig.parse" should {

    "parse KEY=value lines, skipping comments and blanks" in {
      val content =
        """# a comment
          |
          |ANTHROPIC_API_KEY=sk-ant-123
          |DAST_AUTHORIZED_HOSTS=example.com,api.example.com
          |""".stripMargin
      DastConfig.parse(content) shouldBe Map(
        "ANTHROPIC_API_KEY" -> "sk-ant-123",
        "DAST_AUTHORIZED_HOSTS" -> "example.com,api.example.com",
      )
    }

    "strip surrounding quotes and a leading export" in {
      val content =
        """export ANTHROPIC_API_KEY="sk-ant-xyz"
          |NOTE='hello world'
          |""".stripMargin
      DastConfig.parse(content) shouldBe Map(
        "ANTHROPIC_API_KEY" -> "sk-ant-xyz",
        "NOTE" -> "hello world",
      )
    }

    "keep '=' that appears in the value" in {
      DastConfig.parse("TOKEN=a=b=c") shouldBe Map("TOKEN" -> "a=b=c")
    }
  }
}
