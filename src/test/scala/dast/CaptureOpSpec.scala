package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CaptureOpSpec extends AnyWordSpec with Matchers {

  private def jmap(pairs: (String, String)*): java.util.Map[String, Object] =
    val m = new java.util.LinkedHashMap[String, Object]()
    pairs.foreach((k, v) => m.put(k, v))
    m

  "CaptureOp.parseStorage" should {

    "extract both storage areas as string maps" in {
      val raw = new java.util.LinkedHashMap[String, Object]()
      raw.put("localStorage", jmap("id_token" -> "abc", "theme" -> "dark"))
      raw.put("sessionStorage", jmap("csrf" -> "xyz"))

      CaptureOp.parseStorage(raw) shouldBe
        (Map("id_token" -> "abc", "theme" -> "dark"), Map("csrf" -> "xyz"))
    }

    "default missing sections to empty maps" in {
      CaptureOp.parseStorage(new java.util.LinkedHashMap[String, Object]()) shouldBe
        (Map.empty, Map.empty)
    }

    "default wrong-typed input or sections to empty maps without throwing" in {
      CaptureOp.parseStorage("not a map") shouldBe (Map.empty, Map.empty)

      val weird = new java.util.LinkedHashMap[String, Object]()
      weird.put("localStorage", "oops")
      weird.put("sessionStorage", jmap("k" -> "v"))
      CaptureOp.parseStorage(weird) shouldBe (Map.empty, Map("k" -> "v"))
    }
  }
}
