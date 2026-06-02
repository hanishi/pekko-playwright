package dast

import scala.util.Try

/** How invasive an action is. Passive actions only read; active actions inject
  * payloads or otherwise touch target state. */
enum ActionClass:
  case Passive, Active

/** The gate's ruling. `Deny` always carries a reason for the audit log. */
sealed trait GateDecision
object GateDecision:
  case object Permit                     extends GateDecision
  final case class Deny(reason: String)  extends GateDecision

/** The single chokepoint every active operation must pass before touching a
  * target (CLAUDE.md section 5). Deny-by-default for active work: passive
  * capture is always allowed (it is reading, like crawling), but an active
  * action runs only when active testing is enabled and the target host is in
  * the authorized scope. Pure; no browser, network, or model.
  */
object ConsentGate:

  /** Classify a model decision: only [[LlmDecision.Probe]] is active. */
  def classOf(decision: LlmDecision): ActionClass = decision match
    case _: LlmDecision.Probe => ActionClass.Active
    case _                    => ActionClass.Passive

  def decide(auth: Authorization, action: ActionClass, url: String): GateDecision =
    action match
      case ActionClass.Passive => GateDecision.Permit
      case ActionClass.Active =>
        if !auth.allowActive then
          GateDecision.Deny("active testing is disabled (observe-only)")
        else
          hostOf(url) match
            case None => GateDecision.Deny(s"cannot determine host of '$url'")
            case Some(h) if auth.authorizedHosts.contains(h) => GateDecision.Permit
            case Some(h) =>
              GateDecision.Deny(s"host '$h' is not in the authorized scope")

  private def hostOf(url: String): Option[String] =
    Try(new java.net.URI(url).getHost).toOption.flatMap(Option(_)).map(_.toLowerCase)
