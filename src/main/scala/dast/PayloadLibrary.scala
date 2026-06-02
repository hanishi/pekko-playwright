package dast

/** A single audited probe. The only dynamic part of a payload is the unique
  * `marker` substituted at render time; everything else is fixed text written
  * and reviewed here. The model never supplies template text, only the
  * `payloadId` that selects one of these.
  *
  * Each template embeds the marker inside a call to a `confirm` hook
  * (`window.__dastConfirm`) that a later confirm op installs on the page. A
  * vulnerability is only reported when that hook fires with the marker, never
  * because a payload was injected.
  */
final case class Payload(id: String, description: String, template: String):

  /** Render this payload with a system-generated `marker`. The marker is
    * escaped into a JS string literal even though it is system data, not model
    * input: escaping here is the invariant that dynamic text can never break
    * out of the template into executable position. */
  def render(marker: String): String =
    template.replace(PayloadLibrary.MarkerPlaceholder, PayloadLibrary.escapeJsString(marker))

/** The fixed, audited set of probes. Adding a probe is a deliberate,
  * reviewed act; ids referenced by an [[LlmDecision.Probe]] are validated
  * against [[ids]] by [[DecisionParser]] before anything is rendered.
  */
object PayloadLibrary:

  /** Placeholder replaced by the escaped marker in every template. */
  val MarkerPlaceholder = "__MARKER__"

  private val payloads: Map[String, Payload] =
    Seq(
      Payload(
        "img-onerror",
        "Reflected/stored XSS via an <img> error handler.",
        s"""<img src=x onerror="window.__dastConfirm&&window.__dastConfirm('$MarkerPlaceholder')">""",
      ),
      Payload(
        "svg-onload",
        "Reflected/stored XSS via an <svg> load handler.",
        s"""<svg onload="window.__dastConfirm&&window.__dastConfirm('$MarkerPlaceholder')">""",
      ),
      Payload(
        "script-tag",
        "Reflected/stored XSS via an injected <script> element.",
        s"""<script>window.__dastConfirm&&window.__dastConfirm('$MarkerPlaceholder')</script>""",
      ),
      Payload(
        "js-string-breakout",
        "Breakout from a single-quoted JS string sink into a confirm call.",
        s"""';window.__dastConfirm&&window.__dastConfirm('$MarkerPlaceholder');//""",
      ),
    ).map(p => p.id -> p).toMap

  def get(id: String): Option[Payload] = payloads.get(id)

  def ids: Set[String] = payloads.keySet

  /** Escape a string for safe inclusion inside a JS string literal. Beyond the
    * usual control/quote escapes, `<` and `>` are emitted as unicode escapes so
    * a value containing `</script>` (or any tag) cannot terminate a surrounding
    * script element or open a new tag when the payload is parsed as HTML. The
    * result is still the same string value at runtime. */
  def escapeJsString(s: String): String =
    val sb = new StringBuilder(s.length + 8)
    s.foreach {
      case '\\' => sb ++= "\\\\"
      case '"'  => sb ++= "\\\""
      case '\'' => sb ++= "\\'"
      case '\n' => sb ++= "\\n"
      case '\r' => sb ++= "\\r"
      case '\t' => sb ++= "\\t"
      case '<'  => sb ++= "\\u003C"
      case '>'  => sb ++= "\\u003E"
      case '/'  => sb ++= "\\/"
      case c    => sb += c
    }
    sb.toString
