package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CookieFlagCheckSpec extends AnyWordSpec with Matchers {

  private def cookie(
      name: String,
      httpOnly: Boolean,
      secure: Boolean,
      sameSite: Option[String],
  ): Cookie = Cookie(name, "v", "example.com", "/", httpOnly, secure, sameSite)

  private def snap(c: Cookie): ClientStateSnapshot =
    ClientStateSnapshot(url = "https://example.com", cookies = Seq(c))

  "CookieFlagCheck" should {

    "report nothing for a fully-secured cookie" in {
      CookieFlagCheck.check(snap(cookie(
        "sessionid",
        httpOnly = true,
        secure = true,
        sameSite = Some("Lax"),
      ))) shouldBe empty
    }

    "flag all three missing attributes on a session cookie" in {
      val findings = CookieFlagCheck.check(snap(
        cookie("sessionid", httpOnly = false, secure = false, sameSite = None),
      ))
      findings.map(_.kind).distinct shouldBe Seq(FindingKind.InsecureCookie)
      findings.map(_.evidence).count(_.contains("HttpOnly")) shouldBe 1
      findings.map(_.evidence).count(_.contains("Secure")) shouldBe 1
      findings.map(_.evidence).count(_.contains("SameSite")) shouldBe 1
      findings.foreach(_.reproducible shouldBe true)
      findings.foreach(_.replay shouldBe "cookie:sessionid@example.com")
    }

    "rate a missing HttpOnly higher on a session cookie than on a plain one" in {
      def httpOnlySeverity(name: String): Severity = CookieFlagCheck.check(snap(
        cookie(name, httpOnly = false, secure = true, sameSite = Some("Lax")),
      )).find(_.evidence.contains("HttpOnly")).get.severity

      httpOnlySeverity("auth_token") shouldBe Severity.High
      httpOnlySeverity("theme") shouldBe Severity.Low
    }
  }
}
