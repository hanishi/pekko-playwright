package dast

/** The closed vocabulary the model is allowed to choose from.
  *
  * This is the heart of the LLM boundary (CLAUDE.md sections 0.2 and 4): the
  * model never authors executed code, it selects one of these cases and fills
  * in parameters drawn from audited, validated sets. There is deliberately no
  * `RunJs(code)` case and one must never be added. The mapping from model
  * output to this ADT lives in [[DecisionParser]], and that parsing is the
  * security boundary: anything off this menu is rejected, never executed.
  */
sealed trait LlmDecision

object LlmDecision:

  /** Inject the payload identified by `payloadId` (an id in [[PayloadLibrary]],
    * validated by the parser) at the named injection point. The model supplies
    * only ids, never payload text.
    */
  final case class Probe(injectionPointId: String, payloadId: String)
      extends LlmDecision

  /** Move within the application using a non-state-changing navigation. */
  final case class Navigate(action: NavIntent) extends LlmDecision

  /** Record the model's verdict on whether a captured storage value is
    * sensitive. A classification is advisory input to a finding, not a finding
    * on its own (findings require a deterministic confirm step).
    */
  final case class Classify(storageKey: String, verdict: Sensitivity)
      extends LlmDecision

  /** Nothing further to do on this page. */
  case object Done extends LlmDecision

/** Navigations the model may request. Restricted to actions that do not change
  * server or application state: no form submission, no destructive clicks.
  * `FollowLink` references a link id discovered during capture, not a raw URL
  * the model invented.
  */
enum NavIntent:
  case Reload
  case Back
  case Forward
  case FollowLink(linkId: String)

/** The model's sensitivity verdict for a captured value. */
enum Sensitivity:
  case Sensitive
  case NotSensitive
  case Unknown
