package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class IdorPlanSpec extends AnyWordSpec with Matchers {

  "IdorPlan.queryParams" should {
    "decode name/value pairs, first occurrence per name" in {
      IdorPlan.queryParams("http://h/a?id=1001&u=x&id=2") shouldBe
        Seq("id" -> "1001", "u" -> "x")
    }
  }

  "IdorPlan.jsonFieldNames" should {
    "list top-level object keys" in {
      IdorPlan
        .jsonFieldNames("""{"id":"1","email":"a@x","balance":9}""") shouldBe
        Seq("id", "email", "balance")
    }
    "use the first element's keys for an array" in {
      IdorPlan.jsonFieldNames("""[{"id":1,"name":"a"}]""") shouldBe
        Seq("id", "name")
    }
    "be empty for non-JSON" in {
      IdorPlan.jsonFieldNames("<html>not json</html>") shouldBe empty
    }
  }

  "IdorPlan.extractField" should {
    "find a scalar field, including nested, as a string" in {
      IdorPlan.extractField("""{"email":"a@x"}""", "email") shouldBe Some("a@x")
      IdorPlan.extractField("""{"user":{"email":"b@x"}}""", "email") shouldBe
        Some("b@x")
      IdorPlan.extractField("""{"id":42}""", "id") shouldBe Some("42")
    }
    "return None when absent" in {
      IdorPlan.extractField("""{"id":1}""", "email") shouldBe None
    }
  }

  "IdorPlan.confirms" should {
    "confirm a 2xx whose field differs from the caller's own value" in {
      IdorPlan
        .confirms("alice@x", 200, """{"email":"bob@x"}""", "email") shouldBe
        true
    }
    "not confirm when the field matches the caller's own value" in {
      IdorPlan
        .confirms("alice@x", 200, """{"email":"alice@x"}""", "email") shouldBe
        false
    }
    "not confirm on 403 even if the field differs" in {
      IdorPlan
        .confirms("alice@x", 403, """{"email":"bob@x"}""", "email") shouldBe
        false
    }
    "not confirm when the field is absent" in {
      IdorPlan.confirms("alice@x", 200, """{"id":2}""", "email") shouldBe false
    }
  }

  "IdorPlan.parseProposals" should {
    "validate proposals and drop incomplete ones" in {
      val arr = ujson.read(
        """[
        {"param":"id","ownValue":"1001","candidates":["1002","1003"],"discriminatorField":"email"},
        {"param":"id","ownValue":"1","candidates":[],"discriminatorField":"email"},
        {"param":"id","ownValue":"1","candidates":["2"]}
      ]""",
      )
      val ps = IdorPlan.parseProposals(arr)
      ps should have size 1
      ps.head.param shouldBe "id"
      ps.head.candidates shouldBe Seq("1002", "1003")
      ps.head.discriminatorField shouldBe "email"
    }
    "coerce numeric ids to strings" in {
      val arr = ujson.read(
        """[{"param":"id","ownValue":1001,"candidates":[1002],"discriminatorField":"email"}]""",
      )
      val ps = IdorPlan.parseProposals(arr)
      ps.head.ownValue shouldBe "1001"
      ps.head.candidates shouldBe Seq("1002")
    }
  }
}
