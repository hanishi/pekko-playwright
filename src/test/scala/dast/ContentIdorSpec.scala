package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ContentIdorSpec extends AnyWordSpec with Matchers {

  "ContentIdor.fill" should {
    "substitute the {id} placeholder" in {
      ContentIdor.fill("https://h/c?campaignId={id}", "01KT") shouldBe
        "https://h/c?campaignId=01KT"
      ContentIdor.fill("campaignId={id}&x=1", "9") shouldBe "campaignId=9&x=1"
    }
  }

  "ContentIdor.parseProposals" should {
    "parse a query-param test" in {
      val arr = ujson.read("""[{
        "method":"GET",
        "urlTemplate":"https://h/creatives?campaignId={id}",
        "ownValue":"01OWN","candidates":["01OTHER"],"discriminatorField":"name"
      }]""")
      val ps = ContentIdor.parseProposals(arr)
      ps should have size 1
      ps.head.method shouldBe "GET"
      ps.head.bodyTemplate shouldBe None
      ps.head.candidates shouldBe Seq("01OTHER")
      ps.head.isPost shouldBe false
    }
    "parse a POST body test" in {
      val arr = ujson.read("""[{
        "method":"POST","urlTemplate":"https://h/creatives",
        "bodyTemplate":"campaignId={id}","ownValue":"1","candidates":["2","3"],
        "discriminatorField":"owner"
      }]""")
      val p = ContentIdor.parseProposals(arr).head
      p.isPost shouldBe true
      p.bodyTemplate shouldBe Some("campaignId={id}")
    }
    "drop proposals with no {id} placeholder, no candidates, or missing field" in {
      ContentIdor.parseProposals(ujson.read("""[
        {"urlTemplate":"https://h/x","ownValue":"1","candidates":["2"],"discriminatorField":"f"},
        {"urlTemplate":"https://h/x?id={id}","ownValue":"1","candidates":[],"discriminatorField":"f"},
        {"urlTemplate":"https://h/x?id={id}","ownValue":"1","candidates":["2"]}
      ]""")) shouldBe empty
    }
  }

  "ContentIdor.toFinding" should {
    "be a reproducible High naming method, candidate, and field" in {
      val p = ContentIdor
        .Proposal("GET", "https://h/c?id={id}", None, "1", Seq("2"), "email")
      val f = ContentIdor.toFinding(p, "2", "bob@x")
      f.kind shouldBe FindingKind.BrokenAccessControl
      f.severity shouldBe Severity.High
      f.replay should include("id=2")
      f.replay should include("field=email")
    }
  }
}
