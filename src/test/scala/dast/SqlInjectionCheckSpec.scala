package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SqlInjectionCheckSpec extends AnyWordSpec with Matchers {

  private val point = InjectionPoint.QueryParam("id")

  "SqlInjectionCheck.detectError" should {
    "name the DB from a known error signature, case-insensitively" in {
      SqlInjectionCheck.detectError(
        "Warning: You have an error in your SQL syntax near ''",
      ) shouldBe Some("MySQL")
      SqlInjectionCheck
        .detectError("Unclosed quotation mark after the character string") shouldBe
        Some("MSSQL")
      SqlInjectionCheck.detectError("ORA-00933: command not properly ended")
        .map(_ => "ora") shouldBe Some("ora")
    }
    "return None for an ordinary page" in {
      SqlInjectionCheck.detectError("<html>Item: 42</html>") shouldBe None
    }
  }

  "SqlInjectionCheck.errorPayload" should {
    "append a single quote to break quoting" in {
      SqlInjectionCheck.errorPayload("42") shouldBe "42'"
    }
  }

  "SqlInjectionCheck.timePayloads" should {
    "embed the original value and the configured delay, covering engines" in {
      val ps = SqlInjectionCheck.timePayloads("42")
      ps.map(_._1) should contain allOf
        ("mysql-sleep", "postgres-pg_sleep", "mssql-waitfor")
      ps.map(_._2).foreach { v =>
        v should startWith("42")
        v should include(SqlInjectionCheck.delaySeconds.toString)
      }
    }
  }

  "SqlInjectionCheck.confirmsTiming" should {
    "confirm only when the injected request beats baseline by the threshold" in {
      SqlInjectionCheck.confirmsTiming(100, 100 + SqlInjectionCheck.delayThresholdMs) shouldBe
        true
      SqlInjectionCheck.confirmsTiming(100, 100 + 500) shouldBe false
      // A faster injected response never confirms.
      SqlInjectionCheck.confirmsTiming(6000, 200) shouldBe false
    }
  }

  "SqlInjectionCheck findings" should {
    "be reproducible High with technique in the replay handle" in {
      val e = SqlInjectionCheck.errorFinding(point, "MySQL")
      e.kind shouldBe FindingKind.SqlInjection
      e.severity shouldBe Severity.High
      e.replay should include("technique=error")

      val t = SqlInjectionCheck.timeFinding(point, "mysql-sleep")
      t.replay should include("technique=time")
      t.replay should include("payload=mysql-sleep")
    }
  }
}
