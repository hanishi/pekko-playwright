package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PayloadLibrarySpec extends AnyWordSpec with Matchers {

  "PayloadLibrary" should {

    "return a payload for a known id and None for an unknown id" in {
      PayloadLibrary.get("img-onerror") should not be empty
      PayloadLibrary.get("nope") shouldBe None
    }

    "expose every payload's id via ids" in {
      (PayloadLibrary.ids should contain)
        .allOf("img-onerror", "svg-onload", "script-tag")
      PayloadLibrary.ids
        .foreach(id => PayloadLibrary.get(id).map(_.id) shouldBe Some(id))
    }

    "substitute the marker into the template" in {
      val rendered = PayloadLibrary.get("img-onerror").get.render("PVMARK123")
      rendered should include("PVMARK123")
      (rendered should not).include(PayloadLibrary.MarkerPlaceholder)
    }

    "cover multiple injection contexts and group ids by context" in {
      (PayloadLibrary.idsFor(InjectionContext.HtmlAttr) should contain)
        .allOf("attr-breakout", "attr-onfocus")
      PayloadLibrary.idsFor(InjectionContext.UrlOrSrc) should
        contain("url-javascript")
      PayloadLibrary.idsFor(InjectionContext.JsString) should
        contain("js-string-breakout")
      // Every payload is tagged with the context idsFor reports it under.
      PayloadLibrary.ids.foreach { id =>
        val p = PayloadLibrary.get(id).get
        PayloadLibrary.idsFor(p.context) should contain(id)
      }
    }

    "render the new context payloads with the marker substituted" in
      Seq("attr-breakout", "attr-onfocus", "url-javascript").foreach { id =>
        val rendered = PayloadLibrary.get(id).get.render("PVMARK123")
        withClue(s"$id: ") {
          rendered should include("PVMARK123")
          (rendered should not).include(PayloadLibrary.MarkerPlaceholder)
        }
      }
  }

  "escapeJsString" should {

    "neutralise quotes, backslashes, and newlines" in {
      val out = PayloadLibrary.escapeJsString("a'b\"c\\d\ne")
      (out should not).include("\n")
      out should include("\\'")
      out should include("\\\"")
      out should include("\\\\")
      out should include("\\n")
    }

    "prevent a </script> breakout" in {
      val out = PayloadLibrary
        .escapeJsString("</script><img src=x onerror=alert(1)>")
      // No raw '<' or '>' survive, so the value cannot close a script element
      // or open a tag when the surrounding payload is parsed as HTML.
      (out should not).include("<")
      (out should not).include(">")
      (out should not).include("</script>")
    }

    "render a malicious marker without breaking out of the template" in {
      val rendered = PayloadLibrary.get("script-tag").get.render("</script><b>")
      (rendered should not).include("</script><b>")
      // The injected confirm script element remains the only real <script>.
      rendered.sliding("</script>".length).count(_ == "</script>") shouldBe 1
    }
  }
}
