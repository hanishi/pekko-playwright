package dast

import scala.jdk.CollectionConverters.*

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState

import crawler.BrowserResource

/** Active probe: inject an audited payload at an injection point and confirm
  * execution deterministically (CLAUDE.md sections 0.4 and 4).
  *
  * Active testing, so it is gated by [[ConsentGate]] and fails closed: the gate
  * and payload-id checks run in [[precheck]] before any browser work, so a
  * denied or invalid probe never injects. A `Finding` is produced only when the
  * confirm hook reports the probe's marker; the model is never consulted here.
  *
  * The decision logic ([[precheck]], [[firedMarkers]], [[toFinding]]) is pure
  * and unit tested. The browser-driving [[probe]] is exercised only against a
  * live, consenting target (stated, not unit tested).
  */
object ProbeOp:

  /** Installed on the page before navigation. Payload templates call
    * `window.__dastConfirm(marker)`; executed payloads push their marker here.
    */
  val ConfirmHookJs: String = "window.__dastFired=[];" +
    "window.__dastConfirm=function(m){window.__dastFired.push(String(m))};"

  /** Authorize and validate before any browser work. `Left` means do not
    * proceed (denied by the gate, or unknown payload id).
    */
  def precheck(
      auth: Authorization,
      baseUrl: String,
      payloadId: String,
  ): Either[String, Payload] =
    ConsentGate.decide(auth, ActionClass.Active, baseUrl) match
      case GateDecision.Deny(reason) => Left(reason)
      case GateDecision.Permit => PayloadLibrary.get(payloadId)
          .toRight(s"unknown payloadId '$payloadId'")

  /** Parse the `__dastFired` readback into a set of fired markers. Tolerates a
    * null or non-list value (returns empty).
    */
  def firedMarkers(raw: Any): Set[String] = raw match
    case l: java.util.List[?] => l.asScala.iterator
        .collect { case m if m != null => m.toString }.toSet
    case _ => Set.empty

  /** A confirmed XSS finding iff this probe's marker fired. Reproducible, with
    * a model-free replay handle (payload id + injection point).
    */
  def toFinding(
      payloadId: String,
      point: InjectionPoint,
      marker: String,
      fired: Set[String],
  ): Option[Finding] = Option.when(fired.contains(marker))(Finding(
    kind = FindingKind.Xss,
    severity = Severity.High,
    evidence = s"payload '$payloadId' executed at ${point.describe}",
    reproducible = true,
    replay = s"probe ${point.describe} payload=$payloadId",
  ))

  /** Browser-side probe on the pinned thread. Composes [[precheck]] (fail
    * closed) with injection + confirmation. Not unit tested (needs a live
    * page). `Left` is a refusal reason; `Right(None)` means injected but not
    * confirmed.
    */
  def probe(
      resource: BrowserResource,
      auth: Authorization,
      baseUrl: String,
      point: InjectionPoint,
      payloadId: String,
      marker: String,
      navTimeoutMs: Int = 15000,
  ): Either[String, Option[Finding]] = precheck(auth, baseUrl, payloadId)
    .map { payload =>
      val url = point.placeInto(baseUrl, payload.render(marker))
      val fired = resource.withFreshPage { page =>
        page.addInitScript(ConfirmHookJs)
        page.navigate(
          url,
          new Page.NavigateOptions()
            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
            .setTimeout(navTimeoutMs),
        )
        page.waitForLoadState(LoadState.DOMCONTENTLOADED)
        firedMarkers(page.evaluate("() => window.__dastFired || []"))
      }
      toFinding(payloadId, point, marker, fired)
    }
