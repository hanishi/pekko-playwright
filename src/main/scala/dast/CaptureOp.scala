package dast

import scala.jdk.CollectionConverters.*

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.Cookie as PwCookie

import crawler.BrowserResource

/** Passive capture: read a page's client-side state into an immutable
  * [[ClientStateSnapshot]]. This is the first DAST `PageOp`. It only reads
  * (storage, cookies), never mutates, so it is observe-only and needs no active
  * authorization.
  *
  * Run it on the pinned thread:
  * {{{ pool.submit(r => CaptureOp.capture(r, url)) }}}
  *
  * The JS-result-to-Scala conversion ([[parseStorage]]) is pure and unit
  * tested; the browser-driving [[capture]] is exercised only against a live
  * page (stated, not unit tested).
  */
object CaptureOp:

  /** Reads both web-storage areas into plain string maps. A pre-written
    * template (CLAUDE.md section 3), no dynamic parameters, nothing the model
    * supplied. */
  val CaptureJs: String =
    """() => ({
      |  localStorage: Object.fromEntries(Object.entries(window.localStorage)),
      |  sessionStorage: Object.fromEntries(Object.entries(window.sessionStorage))
      |})""".stripMargin

  /** Pure: turn the `CaptureJs` evaluate result into (localStorage,
    * sessionStorage) string maps. Missing or wrong-typed sections become empty
    * maps rather than throwing, so a hostile or unusual page cannot break
    * capture. */
  def parseStorage(raw: Any): (Map[String, String], Map[String, String]) =
    val top = asAnyMap(raw)
    (asStringMap(top.get("localStorage")), asStringMap(top.get("sessionStorage")))

  /** Browser-side capture on the pinned thread. Not unit tested (needs a live
    * page); the parsing it delegates to is. */
  def capture(resource: BrowserResource, url: String): ClientStateSnapshot =
    resource.withPage(url) { page =>
      val (local, session) = parseStorage(page.evaluate(CaptureJs))
      val cookies          = page.context().cookies().asScala.map(toCookie).toSeq
      ClientStateSnapshot(url = url, localStorage = local, sessionStorage = session, cookies = cookies)
    }

  private def asAnyMap(raw: Any): Map[String, Any] =
    raw match
      case m: java.util.Map[?, ?] =>
        m.asScala.iterator.map((k, v) => k.toString -> (v: Any)).toMap
      case _ => Map.empty

  private def asStringMap(section: Option[Any]): Map[String, String] =
    section match
      case Some(m: java.util.Map[?, ?]) =>
        m.asScala.iterator.collect {
          case (k, v) if k != null && v != null => k.toString -> v.toString
        }.toMap
      case _ => Map.empty

  private def toCookie(c: PwCookie): Cookie =
    Cookie(
      name = c.name,
      value = c.value,
      domain = Option(c.domain).getOrElse(""),
      path = Option(c.path).getOrElse("/"),
      httpOnly = Option(c.httpOnly).exists(_.booleanValue()),
      secure = Option(c.secure).exists(_.booleanValue()),
      sameSite = Option(c.sameSite).map(_.name),
    )
