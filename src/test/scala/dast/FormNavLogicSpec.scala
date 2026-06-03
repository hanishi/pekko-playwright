package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import dast.FormParse.FormInfo

class FormNavLogicSpec extends AnyWordSpec with Matchers {

  "FormParse.parse" should {
    "extract method, resolved action, fields and submit text" in {
      val html =
        """<form method="POST" action="/search">
          |<input type="text" name="q" placeholder="x">
          |<button type=submit>Search</button></form>""".stripMargin
      val forms = FormParse.parse(html, "http://h/dashboard")
      forms should have size 1
      forms.head.method shouldBe "post"
      forms.head.action shouldBe "http://h/search"
      forms.head.fields shouldBe Seq("q" -> "text")
      forms.head.submitText should include("Search")
    }
    "default method to get and action to the page url" in {
      val forms = FormParse
        .parse("""<form><input name="x"></form>""", "http://h/p")
      forms.head.method shouldBe "get"
      forms.head.action shouldBe "http://h/p"
    }
  }

  "ActionGuard.allow" should {
    val search =
      FormInfo("post", "http://h/search", Seq("q" -> "text"), "Search")

    "allow a GET form regardless of the safe flag" in {
      ActionGuard
        .allow(search.copy(method = "get"), modelSaysSafe = false) shouldBe
        Right(())
    }
    "allow a POST only when the model classifies it safe" in {
      ActionGuard.allow(search, modelSaysSafe = true) shouldBe Right(())
      ActionGuard.allow(search, modelSaysSafe = false).isLeft shouldBe true
    }
    "refuse a destructive POST even when the model says safe" in {
      val del = FormInfo(
        "post",
        "http://h/account/delete",
        Seq("id" -> "text"),
        "Delete",
      )
      ActionGuard.allow(del, modelSaysSafe = true).isLeft shouldBe true
      val pay =
        FormInfo("post", "http://h/checkout", Seq("amount" -> "text"), "Pay")
      ActionGuard.allow(pay, modelSaysSafe = true).isLeft shouldBe true
    }
    "refuse file uploads and non-GET/POST methods" in {
      ActionGuard.allow(search.copy(fields = Seq("f" -> "file")), true)
        .isLeft shouldBe true
      ActionGuard.allow(search.copy(method = "delete"), true).isLeft shouldBe
        true
    }
  }

  "NavStep.parse" should {
    "parse a follow step" in {
      NavStep
        .parse(ujson.read("""{"action":"follow","linkIndex":2}""")) shouldBe
        NavStep.Follow(2)
    }
    "parse a submit step with values and safe" in {
      val s = NavStep.parse(
        ujson.read("""{"action":"submit","formIndex":0,"values":{"q":"a","n":3},"safe":true}"""),
      )
      s shouldBe NavStep.Submit(0, Map("q" -> "a", "n" -> "3"), true)
    }
    "fail closed to Done on off-menu or malformed input" in {
      NavStep.parse(ujson.read("""{"action":"runjs","code":"x"}""")) shouldBe
        NavStep.Done
      NavStep.parse(ujson.read("""{"action":"submit"}""")) shouldBe NavStep.Done
      NavStep.parse(ujson.read("""{}""")) shouldBe NavStep.Done
    }
  }

  "CookieJar" should {
    "render a Cookie header and merge Set-Cookie values (later wins)" in {
      val jar = CookieJar.fromHeader(Some("session=alice"))
      jar.header shouldBe Some("session=alice")
      val merged = jar.merge(Seq("csrf=tok; Path=/; HttpOnly", "session=bob"))
      merged.cookies("csrf") shouldBe "tok"
      merged.cookies("session") shouldBe "bob"
    }
    "be empty (no header) when seeded with nothing" in {
      CookieJar.fromHeader(None).header shouldBe None
    }
  }
}
