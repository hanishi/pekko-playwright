package crawler

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class UrlNormalizerSpec extends AnyWordSpec with Matchers {

  "UrlNormalizer.normalize" should {

    "lowercase scheme and host but preserve path case" in {
      UrlNormalizer.normalize("HTTPS://Example.COM/Path/To/Page") shouldBe
        "https://example.com/Path/To/Page"
    }

    "strip default ports" in {
      UrlNormalizer.normalize("http://example.com:80/a") shouldBe
        "http://example.com/a"
      UrlNormalizer.normalize("https://example.com:443/a") shouldBe
        "https://example.com/a"
    }

    "keep non-default ports" in {
      UrlNormalizer.normalize("https://example.com:8443/a") shouldBe
        "https://example.com:8443/a"
    }

    "drop a single trailing slash but keep the root slash" in {
      UrlNormalizer.normalize("https://example.com/a/") shouldBe
        "https://example.com/a"
      UrlNormalizer.normalize("https://example.com/") shouldBe
        "https://example.com/"
      UrlNormalizer.normalize("https://example.com") shouldBe
        "https://example.com/"
    }

    "strip tracking params, sort the rest, and drop the fragment" in {
      UrlNormalizer.normalize(
        "https://example.com/a?b=2&utm_source=news&a=1&fbclid=xyz#section",
      ) shouldBe "https://example.com/a?a=1&b=2"
    }

    "keep valueless query keys and sort them in" in {
      UrlNormalizer.normalize("https://example.com/p?flag&a=1") shouldBe
        "https://example.com/p?a=1&flag"
    }

    "return the original string when there is no host" in {
      UrlNormalizer.normalize("not a url") shouldBe "not a url"
      UrlNormalizer.normalize("example.com/path") shouldBe "example.com/path"
    }
  }
}
