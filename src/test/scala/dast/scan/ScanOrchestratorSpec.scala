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
      sinkScan: (String, InjectionPoint, String) => Future[Set[String]] =
        (_, _, _) => Future.successful(Set.empty),
      redirectScan: String => Future[Vector[Finding]] =
        _ => Future.successful(Vector.empty),
      sqlScan: String => Future[Vector[Finding]] =
        _ => Future.successful(Vector.empty),
      ssrfScan: String => Future[Vector[Finding]] =
        _ => Future.successful(Vector.empty),
  ): Effects = Effects(
    capture = _ => Future.successful(snapshot),
    analyze = analyze,
    probe = probe,
    sinkScan = sinkScan,
    redirectScan = redirectScan,
    sqlScan = sqlScan,
    ssrfScan = ssrfScan,
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

    "stop at the step budget when the analyzer keeps choosing new probes" in {
      val probeCalls = new AtomicInteger(0)
      val orch = spawn(ScanOrchestrator(
        Authorization.active("example.com"),
        effects(
          // A distinct payload each step so no probe is ever a repeat; the loop
          // is then bounded only by the step budget.
          analyze =
            _ => Future.successful(Probe("q", s"payload-${probeCalls.get()}")),
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

    "not re-probe a repeated decision: finish early and record it once" in {
      val probeCalls = new AtomicInteger(0)
      val orch = spawn(ScanOrchestrator(
        Authorization.active("example.com"),
        effects(
          // Always the same probe; once attempted, the orchestrator finishes
          // instead of re-confirming, so the finding is recorded exactly once.
          analyze = _ => Future.successful(Probe("q", "img-onerror")),
          probe = (_, _, _, _) => {
            probeCalls.incrementAndGet(); Future.successful(Some(probeFinding))
          },
        ),
        maxSteps = 5,
      ))
      val reply = createTestProbe[ScanComplete]()

      orch ! Start(target, reply.ref)
      reply.expectMessageType[ScanComplete].findings shouldBe
        (tier1 :+ probeFinding)
      probeCalls.get() shouldBe 1
    }

    "merge open-redirect findings under active auth" in {
      val redirectFinding = Finding(
        FindingKind.OpenRedirect,
        Severity.Medium,
        "query param 'next' controls a redirect",
        reproducible = true,
        "redirect query param 'next'",
      )
      val orch = spawn(ScanOrchestrator(
        Authorization.active("example.com"),
        effects(
          analyze = _ => Future.successful(Done), // no reflected probing
          probe = (_, _, _, _) => Future.successful(None),
          redirectScan = _ => Future.successful(Vector(redirectFinding)),
        ),
      ))
      val reply = createTestProbe[ScanComplete]()

      orch ! Start(target, reply.ref)
      reply.expectMessageType[ScanComplete].findings shouldBe
        (tier1 :+ redirectFinding)
    }

    "merge SQL-injection findings under active auth" in {
      val sqliFinding = Finding(
        FindingKind.SqlInjection,
        Severity.High,
        "query param 'id' triggers a MySQL error",
        reproducible = true,
        "sqli query param 'id' technique=error",
      )
      val orch = spawn(ScanOrchestrator(
        Authorization.active("example.com"),
        effects(
          analyze = _ => Future.successful(Done),
          probe = (_, _, _, _) => Future.successful(None),
          sqlScan = _ => Future.successful(Vector(sqliFinding)),
        ),
      ))
      val reply = createTestProbe[ScanComplete]()

      orch ! Start(target, reply.ref)
      reply.expectMessageType[ScanComplete].findings shouldBe
        (tier1 :+ sqliFinding)
    }

    "merge SSRF findings under active auth" in {
      val ssrfFinding = Finding(
        FindingKind.Ssrf,
        Severity.High,
        "query param 'url' caused a server-side request",
        reproducible = true,
        "ssrf query param 'url' token=t1",
      )
      val orch = spawn(ScanOrchestrator(
        Authorization.active("example.com"),
        effects(
          analyze = _ => Future.successful(Done),
          probe = (_, _, _, _) => Future.successful(None),
          ssrfScan = _ => Future.successful(Vector(ssrfFinding)),
        ),
      ))
      val reply = createTestProbe[ScanComplete]()

      orch ! Start(target, reply.ref)
      reply.expectMessageType[ScanComplete].findings shouldBe
        (tier1 :+ ssrfFinding)
    }

    "not run the open-redirect probe under observe-only auth" in {
      val redirectCalls = new AtomicInteger(0)
      val orch = spawn(ScanOrchestrator(
        Authorization.ObserveOnly,
        effects(
          analyze = _ => Future.successful(Done),
          probe = (_, _, _, _) => Future.successful(None),
          redirectScan = _ => {
            redirectCalls.incrementAndGet(); Future.successful(Vector.empty)
          },
        ),
      ))
      val reply = createTestProbe[ScanComplete]()

      orch ! Start(target, reply.ref)
      reply.expectMessageType[ScanComplete].findings shouldBe tier1
      redirectCalls.get() shouldBe 0
    }

    "run a DOM sink-scan under active auth and report reached sinks" in {
      val orch = spawn(ScanOrchestrator(
        Authorization.active("example.com"),
        effects(
          analyze = _ => Future.successful(Done), // no reflected probing this run
          probe = (_, _, _, _) => Future.successful(None),
          sinkScan = (_, _, _) => Future.successful(Set("innerHTML")),
        ),
      ))
      val reply = createTestProbe[ScanComplete]()

      orch ! Start(target, reply.ref)
      val expected = tier1 ++
        SinkScanOp.toFindings(InjectionPoint.Fragment, Set("innerHTML"))
      reply.expectMessageType[ScanComplete].findings shouldBe expected
    }

    "finish with no findings when capture fails" in {
      val orch = spawn(ScanOrchestrator(
        Authorization.ObserveOnly,
        Effects(
          capture = _ => Future.failed(new RuntimeException("boom")),
          analyze = _ => Future.successful(Done),
          probe = (_, _, _, _) => Future.successful(None),
          sinkScan = (_, _, _) => Future.successful(Set.empty),
        ),
      ))
      val reply = createTestProbe[ScanComplete]()

      orch ! Start(target, reply.ref)
      reply.expectMessageType[ScanComplete].findings shouldBe empty
    }
  }
}
