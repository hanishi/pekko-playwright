package dast

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ConsentGateSpec extends AnyWordSpec with Matchers {

  private val authed = Authorization.active("example.com")

  "ConsentGate.decide" should {

    "always permit passive actions, even observe-only and off-scope" in {
      ConsentGate.decide(
        Authorization.ObserveOnly,
        ActionClass.Passive,
        "https://anything.example.org/x",
      ) shouldBe GateDecision.Permit
    }

    "deny active actions under the observe-only default" in {
      ConsentGate.decide(
        Authorization.ObserveOnly,
        ActionClass.Active,
        "https://example.com/x",
      ) shouldBe GateDecision.Deny("active testing is disabled (observe-only)")
    }

    "permit an active action against an authorized host" in {
      ConsentGate.decide(authed, ActionClass.Active, "https://example.com/login") shouldBe
        GateDecision.Permit
    }

    "match the authorized host case-insensitively" in {
      ConsentGate.decide(authed, ActionClass.Active, "HTTPS://Example.COM/login") shouldBe
        GateDecision.Permit
    }

    "deny an active action against a host not in scope" in {
      ConsentGate.decide(authed, ActionClass.Active, "https://evil.test/x") shouldBe
        GateDecision.Deny("host 'evil.test' is not in the authorized scope")
    }

    "not auto-authorize subdomains of an authorized host" in {
      ConsentGate.decide(authed, ActionClass.Active, "https://api.example.com/x") shouldBe
        GateDecision.Deny("host 'api.example.com' is not in the authorized scope")
    }

    "deny when the host cannot be determined" in {
      ConsentGate.decide(authed, ActionClass.Active, "not a url") shouldBe
        GateDecision.Deny("cannot determine host of 'not a url'")
    }
  }

  "ConsentGate.classOf" should {

    "classify only Probe as active" in {
      ConsentGate.classOf(LlmDecision.Probe("p", "img-onerror")) shouldBe ActionClass.Active
      ConsentGate.classOf(LlmDecision.Done) shouldBe ActionClass.Passive
      ConsentGate.classOf(LlmDecision.Navigate(NavIntent.Reload)) shouldBe ActionClass.Passive
      ConsentGate.classOf(
        LlmDecision.Classify("k", Sensitivity.Unknown),
      ) shouldBe ActionClass.Passive
    }
  }
}
