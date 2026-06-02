package dast

import java.util.UUID

/** Unique, JS-safe confirmation markers. A marker is injected (escaped) into a
  * payload; if the payload executes it calls the confirm hook with the marker,
  * which is how a probe is confirmed. Generation is the only impure part of the
  * probe path; callers pass a fixed marker in tests.
  */
object Markers:

  /** A fresh alphanumeric marker, e.g. `dast` + 32 hex chars. */
  def fresh(): String = "dast" + UUID.randomUUID().toString.replace("-", "")
