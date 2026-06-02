package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AccessControlCheckSpec extends AnyWordSpec with Matchers {

  "AccessControlCheck.confirms" should {
    "hit on a 2xx body containing the discriminator" in {
      AccessControlCheck
        .confirms(200, """{"email":"bob@x"}""", "bob@x") shouldBe true
    }
    "miss when the discriminator is absent" in {
      AccessControlCheck.confirms(200, "denied", "bob@x") shouldBe false
    }
    "miss on 401/403/3xx even if the body somehow matches" in {
      AccessControlCheck.confirms(403, "bob@x", "bob@x") shouldBe false
      AccessControlCheck.confirms(302, "bob@x", "bob@x") shouldBe false
    }
  }

  "AccessControlCheck.parseSpec" should {
    "parse identities and cases, defaulting absent identity to unauthenticated" in {
      val json =
        """{
          |  "identities": {
          |    "alice": { "cookie": "session=alice", "headers": {"X-Tok": "t1"} }
          |  },
          |  "cases": [
          |    { "name": "idor", "url": "http://h/a?id=2", "identity": "alice", "mustContain": "user2" },
          |    { "name": "forced", "url": "http://h/admin", "identity": null, "mustContain": "admin" }
          |  ]
          |}""".stripMargin
      val spec = AccessControlCheck.parseSpec(json).toOption.get
      spec.identities("alice").cookie shouldBe Some("session=alice")
      spec.identities("alice").headers shouldBe Map("X-Tok" -> "t1")
      spec.cases.map(_.name) shouldBe Seq("idor", "forced")
      spec.cases(0).identity shouldBe Some("alice")
      spec.cases(1).identity shouldBe None
    }

    "parse an identity that logs in instead of carrying a cookie" in {
      val json =
        """{
          |  "identities": {
          |    "alice": { "login": {
          |      "loginUrl": "http://h/login", "username": "alice", "password": "pw"
          |    } }
          |  },
          |  "cases": [
          |    { "name": "c", "url": "http://h/a?id=2", "identity": "alice", "mustContain": "x" }
          |  ]
          |}""".stripMargin
      val spec = AccessControlCheck.parseSpec(json).toOption.get
      val alice = spec.identities("alice")
      alice.cookie shouldBe None
      alice.login.map(_.loginUrl) shouldBe Some("http://h/login")
      alice.login.map(_.username) shouldBe Some("alice")
    }

    "reject a case referencing an unknown identity" in {
      val json =
        """{"identities": {}, "cases": [
          |  {"name":"x","url":"http://h/","identity":"ghost","mustContain":"y"}
          |]}""".stripMargin
      AccessControlCheck.parseSpec(json).isLeft shouldBe true
    }

    "return Left on malformed JSON" in {
      AccessControlCheck.parseSpec("not json").isLeft shouldBe true
    }
  }

  "AccessControlCheck.toFinding" should {
    "be a reproducible High naming the case, identity, and url" in {
      val c = AccessControlCheck
        .AccessCase("idor", "http://h/a?id=2", Some("alice"), "user2")
      val f = AccessControlCheck.toFinding(c)
      f.kind shouldBe FindingKind.BrokenAccessControl
      f.severity shouldBe Severity.High
      f.replay should include("case='idor'")
      f.replay should include("as=alice")
    }
  }
}
