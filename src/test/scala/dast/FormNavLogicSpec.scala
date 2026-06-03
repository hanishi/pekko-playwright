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
      val forms = FormParse.parse("""<form><input name="x"></form>""", "http://h/p")
      forms.head.method shouldBe "get"
      forms.head.action shouldBe "http://h/p"
    }
  }

  "ActionGuard.allow" should {
    val search = FormInfo("post", "http://h/search", Seq("q" -> "text"), "Search")

    "allow a GET form regardless of the safe flag" in {
      ActionGuard.allow(search.copy(method = "get"), modelSaysSafe = false) shouldBe
        Right(())
    }
    "allow a POST only when the model classifies it safe" in {
      ActionGuard.allow(search, modelSaysSafe = true) shouldBe Right(())
      ActionGuard.allow(search, modelSaysSafe = false).isLeft shouldBe true
    }
    "refuse a destructive POST even when the model says safe" in {
      val del = FormInfo("post", "http://h/account/delete", Seq("id" -> "text"), "Delete")
      ActionGuard.allow(del, modelSaysSafe = true).isLeft shouldBe true
      val pay = FormInfo("post", "http://h/checkout", Seq("amount" -> "text"), "Pay")
      ActionGuard.allow(pay, modelSaysSafe = true).isLeft shouldBe true
    }
    "refuse file uploads and non-GET/POST methods" in {
      ActionGuard.allow(search.copy(fields = Seq("f" -> "file")), true).isLeft shouldBe
        true
      ActionGuard.allow(search.copy(method = "delete"), true).isLeft shouldBe true
    }
  }

  "NavPlan.parseChoices" should {
    "parse formIndex, values and safe, defaulting safe to false" in {
      val arr = ujson.read("""[
        {"formIndex":0,"values":{"q":"a","n":2},"safe":true},
        {"formIndex":1}
      ]""")
      val cs = NavPlan.parseChoices(arr)
      cs should have size 2
      cs(0).formIndex shouldBe 0
      cs(0).values shouldBe Map("q" -> "a", "n" -> "2")
      cs(0).safe shouldBe true
      cs(1).safe shouldBe false
    }
  }
}
