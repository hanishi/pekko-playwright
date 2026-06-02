package dast

/** Where a reflected value lands in the response, which decides what shape a
  * payload must take to execute there.
  */
enum InjectionContext:
  case HtmlBody, HtmlAttr, JsString, UrlOrSrc

/** A single audited probe. The only dynamic part of a payload is the unique
  * `marker` substituted at render time; everything else is fixed text written
  * and reviewed here. The model never supplies template text, only the
  * `payloadId` that selects one of these. `context` records the reflection
  * context the template is built for.
  *
  * Each template embeds the marker inside a call to a `confirm` hook
  * (`window.__dastConfirm`) that a later confirm op installs on the page. A
  * vulnerability is only reported when that hook fires with the marker, never
  * because a payload was injected.
  */
final case class Payload(
    id: String,
    context: InjectionContext,
    description: String,
    template: String,
):

  /** Render this payload with a system-generated `marker`. The marker is
    * escaped into a JS string literal even though it is system data, not model
    * input: escaping here is the invariant that dynamic text can never break
    * out of the template into executable position.
    */
  def render(marker: String): String = template.replace(
    PayloadLibrary.MarkerPlaceholder,
    PayloadLibrary.escapeJsString(marker),
  )

/** The fixed, audited set of probes. Adding a probe is a deliberate, reviewed
  * act; ids referenced by an [[LlmDecision.Probe]] are validated against
  * [[ids]] by [[DecisionParser]] before anything is rendered.
  */
object PayloadLibrary:

  import InjectionContext.*

  /** Placeholder replaced by the escaped marker in every template. */
  val MarkerPlaceholder = "__MARKER__"

  private def confirm =
    s"window.__dastConfirm&&window.__dastConfirm('$MarkerPlaceholder')"

  private val payloads: Map[String, Payload] = Seq(
    Payload(
      "img-onerror",
      HtmlBody,
      "HTML-body XSS via an <img> error handler.",
      s"""<img src=x onerror="$confirm">""",
    ),
    Payload(
      "svg-onload",
      HtmlBody,
      "HTML-body XSS via an <svg> load handler.",
      s"""<svg onload="$confirm">""",
    ),
    Payload(
      "script-tag",
      HtmlBody,
      "HTML-body XSS via an injected <script> element.",
      s"""<script>$confirm</script>""",
    ),
    Payload(
      "attr-breakout",
      HtmlAttr,
      "Break out of a double-quoted attribute into a new <img> tag.",
      s"""\"><img src=x onerror="$confirm">""",
    ),
    Payload(
      "attr-onfocus",
      HtmlAttr,
      "Stay in the tag, add an autofocus onfocus handler.",
      s"""\" autofocus onfocus="$confirm" x=\"""",
    ),
    Payload(
      "js-string-breakout",
      JsString,
      "Break out of a single-quoted JS string sink into a confirm call.",
      s"""';$confirm;//""",
    ),
    Payload(
      "url-javascript",
      UrlOrSrc,
      "javascript: URL for a value reflected into href/src.",
      s"""javascript:$confirm""",
    ),
  ).map(p => p.id -> p).toMap

  def get(id: String): Option[Payload] = payloads.get(id)

  def ids: Set[String] = payloads.keySet

  /** Ids whose template targets the given reflection context. */
  def idsFor(context: InjectionContext): Set[String] = payloads.values
    .filter(_.context == context).map(_.id).toSet

  /** Escape a string for safe inclusion inside a JS string literal. Beyond the
    * usual control/quote escapes, `<` and `>` are emitted as unicode escapes so
    * a value containing `</script>` (or any tag) cannot terminate a surrounding
    * script element or open a new tag when the payload is parsed as HTML. The
    * result is still the same string value at runtime.
    */
  def escapeJsString(s: String): String =
    val sb = new StringBuilder(s.length + 8)
    s.foreach {
      case '\\' => sb ++= "\\\\"
      case '"' => sb ++= "\\\""
      case '\'' => sb ++= "\\'"
      case '\n' => sb ++= "\\n"
      case '\r' => sb ++= "\\r"
      case '\t' => sb ++= "\\t"
      case '<' => sb ++= "\\u003C"
      case '>' => sb ++= "\\u003E"
      case '/' => sb ++= "\\/"
      case c => sb += c
    }
    sb.toString
