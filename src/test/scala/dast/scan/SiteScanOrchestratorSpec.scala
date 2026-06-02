package dast.scan

import scala.concurrent.Future

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import dast.Finding
import dast.FindingKind
import dast.Severity
import dast.scan.SiteScanOrchestrator.*

class SiteScanOrchestratorSpec
    extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  private val seed = "https://example.com"

  private def findingFor(url: String): Finding =
    Finding(FindingKind.Xss, Severity.Low, s"f:$url", reproducible = true, s"r:$url")

  private def effects(
      discover: String => Future[Seq[String]],
      scanOne: String => Future[Vector[Finding]],
  ): Effects = Effects(discover, scanOne)

  "SiteScanOrchestrator" should {

    "scan the seed plus each discovered in-scope URL, grouped" in {
      val orch = spawn(SiteScanOrchestrator(effects(
        discover = _ => Future.successful(Seq(
          "https://example.com/a",
          "https://evil.test/x", // off-host, dropped by Scope
          "https://example.com/b",
        )),
        scanOne = url => Future.successful(Vector(findingFor(url))),
      )))
      val reply = createTestProbe[SiteScanComplete]()

      orch ! Start(seed, reply.ref)
      val done = reply.expectMessageType[SiteScanComplete]
      done.results.map(_._1) shouldBe Seq(
        "https://example.com/",
        "https://example.com/a",
        "https://example.com/b",
      )
      done.results.foreach { case (url, fs) => fs shouldBe Vector(findingFor(url)) }
    }

    "fail soft: a failed scanOne yields an empty result, not an abort" in {
      val orch = spawn(SiteScanOrchestrator(effects(
        discover = _ => Future.successful(Seq("https://example.com/a", "https://example.com/b")),
        scanOne = url =>
          if url.endsWith("/b") then Future.failed(new RuntimeException("boom"))
          else Future.successful(Vector(findingFor(url))),
      )))
      val reply = createTestProbe[SiteScanComplete]()

      orch ! Start(seed, reply.ref)
      val byUrl = reply.expectMessageType[SiteScanComplete].results.toMap
      byUrl("https://example.com/a") shouldBe Vector(findingFor("https://example.com/a"))
      byUrl("https://example.com/b") shouldBe empty
    }

    "scan only the seed when discovery is empty" in {
      val orch = spawn(SiteScanOrchestrator(effects(
        discover = _ => Future.successful(Seq.empty),
        scanOne = url => Future.successful(Vector(findingFor(url))),
      )))
      val reply = createTestProbe[SiteScanComplete]()

      orch ! Start(seed, reply.ref)
      reply.expectMessageType[SiteScanComplete].results.map(_._1) shouldBe
        Seq("https://example.com/")
    }

    "respect the maxPages cap" in {
      val orch = spawn(SiteScanOrchestrator(
        effects(
          discover = _ => Future.successful(
            (1 to 50).map(i => s"https://example.com/p$i"),
          ),
          scanOne = url => Future.successful(Vector(findingFor(url))),
        ),
        maxPages = 3,
      ))
      val reply = createTestProbe[SiteScanComplete]()

      orch ! Start(seed, reply.ref)
      reply.expectMessageType[SiteScanComplete].results should have size 3
    }
  }
}
