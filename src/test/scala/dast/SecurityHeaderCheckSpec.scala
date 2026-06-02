package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SecurityHeaderCheckSpec extends AnyWordSpec with Matchers {

  private def snap(
      url: String,
      headers: Map[String, String],
      status: Int = 200,
  ): ClientStateSnapshot =
    ClientStateSnapshot(url = url, responseHeaders = headers, status = status)

  private def kinds(fs: Seq[Finding]): Set[FindingKind] = fs.map(_.kind).toSet
  private def replays(fs: Seq[Finding]): Set[String] = fs.map(_.replay).toSet

  // A response with every checked header present and constraining.
  private val allPresent = Map(
    "content-security-policy" -> "default-src 'self'; frame-ancestors 'none'",
    "x-content-type-options" -> "nosniff",
    "referrer-policy" -> "no-referrer",
    "strict-transport-security" -> "max-age=63072000",
    "x-frame-options" -> "DENY",
  )

  "SecurityHeaderCheck" should {

    "report nothing when no response was captured (status 0)" in {
      SecurityHeaderCheck.check(
        snap("https://x.test/", Map.empty, status = 0),
      ) shouldBe empty
    }

    "report nothing when all security headers are present" in {
      SecurityHeaderCheck.check(snap("https://x.test/", allPresent)) shouldBe
        empty
    }

    "flag every missing header on a bare HTTPS response" in {
      val fs = SecurityHeaderCheck.check(snap("https://x.test/", Map.empty))
      kinds(fs) shouldBe Set(FindingKind.MissingSecurityHeader)
      replays(fs) shouldBe Set(
        "header:content-security-policy@https://x.test/",
        "header:x-content-type-options@https://x.test/",
        "header:referrer-policy@https://x.test/",
        "header:strict-transport-security@https://x.test/",
        "header:x-frame-options@https://x.test/",
      )
    }

    "not expect HSTS over plain HTTP" in {
      val fs = SecurityHeaderCheck.check(snap("http://x.test/", Map.empty))
      replays(fs) should not contain
        "header:strict-transport-security@http://x.test/"
    }

    "accept CSP frame-ancestors in lieu of X-Frame-Options" in {
      val headers = Map(
        "content-security-policy" -> "frame-ancestors 'self'",
        "x-content-type-options" -> "nosniff",
        "referrer-policy" -> "no-referrer",
        "strict-transport-security" -> "max-age=1",
      )
      val fs = SecurityHeaderCheck.check(snap("https://x.test/", headers))
      replays(fs) should not contain "header:x-frame-options@https://x.test/"
    }

    "rate a missing CSP Medium and a missing Referrer-Policy Low" in {
      val fs = SecurityHeaderCheck.check(snap("https://x.test/", Map.empty))
      fs.find(_.replay.contains("content-security-policy"))
        .map(_.severity) shouldBe Some(Severity.Medium)
      fs.find(_.replay.contains("referrer-policy"))
        .map(_.severity) shouldBe Some(Severity.Low)
    }
  }
}
