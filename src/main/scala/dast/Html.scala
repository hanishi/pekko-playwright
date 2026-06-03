package dast

/** Minimal HTML-entity decoding for attribute values (hrefs, form actions).
  *
  * Real markup escapes `&` as `&amp;` in attributes, so a raw regex extraction
  * mangles multi-parameter URLs (`?a=1&amp;b=2` -> `?a=1&amp;b=2` kept
  * literally). This decodes the handful of entities that actually occur in
  * URLs. Pure.
  */
object Html:

  def unescape(s: String): String =
    if s.indexOf('&') < 0 then s
    else
      s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'")
        .replace("&amp;", "&") // last, so "&amp;lt;" -> "&lt;" not "<"
