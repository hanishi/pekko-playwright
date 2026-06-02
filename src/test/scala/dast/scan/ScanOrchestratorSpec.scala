package dast.scan

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import dast.*
import dast.LlmDecision.*
import dast.scan.ScanOrchestrator.*

class ScanOrchestratorSpec
    extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  // A snapshot whose insecure session cookie yields deterministic Tier 1 findings.
  private val snapshot = ClientStateSnapshot(
    url = "https://example.com/p",
    cookies = Seq(Cookie(
      "sessionid",
      "v",
      "example.com",
      "/",
      httpOnly = false,
      secure = false,
      sameSite = None,
    )),
  )
  private val tier1 = Tier1.run(snapshot).toVector

  private val target = "https://example.com/p?q=1"
  private val probeFinding = Finding(
    FindingKind.Xss,
    Severity.High,
    "executed at q",
    reproducible = true,
    "probe q",
  )

  private def effects(
      analyze: AnalyzerCtxF,
      probe: (String, InjectionPoint, String, String) => Future[Option[Finding]],
  ): Effects = Effects(
    capture = _ => Future.successful(snapshot),
    analyze = analyze,
    probe = probe,
  )

  private type AnalyzerCtxF =
    dast.analyzer.AnalyzerContext => Future[LlmDecision]

  "ScanOrchestrator" should {

    "report only Tier 1 findings and never probe under observe-only auth" in {
      val probeCalls = new AtomicInteger(0)
      val orch = spawn(ScanOrchestrator(
        Authorization.ObserveOnly,
        effects(
          analyze = _ => Future.successful(Probe("q", "img-onerror")), // always wants to probe
          probe = (_, _, _, _) => {
            probeCalls.incrementAndGet(); Future.successful(Some(probeFinding))
          },
        ),
        maxSteps = 3,
      ))
      val reply = createTestProbe[ScanComplete]()

      orch ! Start(target, reply.ref)
      val done = reply.expectMessageType[ScanComplete]
      done.findings shouldBe tier1
      probeCalls.get() shouldBe 0
    }

    "run a gated probe and collect its finding under active auth" in {
      val analyzeCalls = new AtomicInteger(0)
      val orch = spawn(ScanOrchestrator(
        Authorization.active("example.com"),
        effects(
          analyze = _ =>
            Future.successful(
              if analyzeCalls.getAndIncrement() == 0 then
                Probe("q", "img-onerror")
              else Done,
            ),
          probe = (_, _, _, _) => Future.successful(Some(probeFinding)),
        ),
      ))
      val reply = createTestProbe[ScanComplete]()

      orch ! Start(target, reply.ref)
      val done = reply.expectMessageType[ScanComplete]
      done.findings shouldBe (tier1 :+ probeFinding)
    }

    "stop at the step budget when the analyzer never says Done" in {
      val probeCalls = new AtomicInteger(0)
      val orch = spawn(ScanOrchestrator(
        Authorization.active("example.com"),
        effects(
          analyze = _ => Future.successful(Probe("q", "img-onerror")),
          probe = (_, _, _, _) => {
            probeCalls.incrementAndGet(); Future.successful(None)
          },
        ),
        maxSteps = 2,
      ))
      val reply = createTestProbe[ScanComplete]()

      orch ! Start(target, reply.ref)
      reply.expectMessageType[ScanComplete].findings shouldBe tier1
      probeCalls.get() shouldBe 2
    }

    "finish with no findings when capture fails" in {
      val orch = spawn(ScanOrchestrator(
        Authorization.ObserveOnly,
        Effects(
          capture = _ => Future.failed(new RuntimeException("boom")),
          analyze = _ => Future.successful(Done),
          probe = (_, _, _, _) => Future.successful(None),
        ),
      ))
      val reply = createTestProbe[ScanComplete]()

      orch ! Start(target, reply.ref)
      reply.expectMessageType[ScanComplete].findings shouldBe empty
    }
  }
}
