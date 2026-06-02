package crawler

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.slf4j.LoggerFactory

import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.Proxy as PlaywrightProxy
import com.microsoft.playwright.options.WaitUntilState

import crawler.PlaywrightCrawler.PageScrapedResult

/** Thread-affine resource wrapping a Playwright driver + headless Chromium
  * browser, hosted on one pinned actor thread by [[crawler.pool.ResourcePool]].
  * Playwright Java's driver is single-threaded — every API call must originate
  * from the thread that called `Playwright.create()`. Building this in
  * `ResourceSession`'s setup (on the pinned thread) and only ever invoking
  * [[scrape]] via `pool.submit` keeps that invariant.
  *
  * One `BrowserResource` == one Chromium process. The pool keeps a fixed `size`
  * of them; crawl concurrency is an in-flight cap against the shared pool
  * rather than a browser count.
  *
  * Mutable fields (`context`, `successCount`) are safe without synchronization
  * because they are only touched on the pinned thread.
  */
final class BrowserResource(
    id: Int,
    proxy: Option[ProxyProviderConf],
    settings: BrowserResource.Settings = BrowserResource.Settings(),
) extends AutoCloseable {

  import BrowserResource.*

  private val log = LoggerFactory.getLogger(s"crawler.BrowserResource.$id")

  log.info(
    "launching playwright + chromium (proxy={})",
    proxy.map(_.provider).getOrElse("none"),
  )
  private val playwright: Playwright = Playwright.create()
  private val browser: Browser = playwright.chromium()
    .launch(launchOptions(proxy, settings.stealth))
  private val stealthScript: Path = resolveResource("/stealth.js", "stealth-")
  private val initScript: Path = resolveResource("/crawler.js", "crawler-init-")

  private var context: BrowserContext = newContext()
  private var successCount: Int = 0

  /** Scrape one URL on the pinned thread. Returns a [[PageScrapedResult]] with
    * status 1 on success and status 0 for terminal non-HTML responses.
    * Retryable failures are thrown so the caller (the crawler) can reschedule —
    * the future returned by `pool.submit` fails with the same exception.
    */
  def scrape(
      url: String,
      targetElements: Array[String],
      depth: Int,
      linkRegex: String,
      clickSelector: Option[String],
  ): PageScrapedResult = {
    context.clearCookies()
    val page = context.newPage()
    try {
      val blockedTypes: Set[String] = {
        val base = Set("image", "font", "media")
        if (clickSelector.isDefined) base else base + "stylesheet"
      }
      page.route(
        "**",
        route => {
          val resourceType = route.request().resourceType()
          if (blockedTypes.contains(resourceType)) route.abort()
          else route.resume()
        },
      )

      val response = page.navigate(
        url,
        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
          .setTimeout(settings.navigationTimeoutMs),
      )
      val resp = Option(response)
        .getOrElse(throw new RuntimeException(s"No response received for $url"))
      if (resp.status() >= 400) throw new RuntimeException(
        s"Bad response status ${resp.status()} for $url: ${resp.statusText()}",
      )

      val contentType = Option(resp.headerValue("content-type")).getOrElse("")
      if (
        !contentType.contains("text/html") &&
        !contentType.contains("application/xhtml")
      ) throw new NonHtmlContentException(
        s"Non-HTML content-type for $url: $contentType",
      )

      page.waitForLoadState(LoadState.DOMCONTENTLOADED)
      clickSelector.foreach { selector =>
        Option(page.querySelector(selector))
          .foreach(el => if (el.isVisible) el.click())
      }
      val results = targetElements
        .map(target => extractTextAndLinks(page, linkRegex, target))
      val texts: Seq[String] = results.map(_._1).toSeq
      val links: Seq[(String, String)] = results.flatMap(_._2).toSeq
      rotateContextIfNeeded()
      PageScrapedResult(url, texts, links, depth, 1)
    } catch {
      case _: NonHtmlContentException =>
        PageScrapedResult(url, Seq.empty, Seq.empty, depth, 0)
    } finally
      try page.close()
      catch { case _: Exception => () }
  }

  /** Run a passive page operation on the pinned thread: open a page, navigate
    * to `url`, hand the live `Page` to `op`, and close the page afterwards.
    *
    * This is the generic entry point for DAST page operations (capture today,
    * probe/confirm later). Like [[scrape]] it must only be invoked via
    * `pool.submit` so it stays on the browser's pinned thread. It does no
    * request blocking and does not clear cookies, so `op` sees the page as a
    * real visit leaves it. `op` must not retain the `Page` beyond the call.
    */
  def withPage[A](url: String)(op: Page => A): A = {
    val page = context.newPage()
    try {
      val response = page.navigate(
        url,
        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
          .setTimeout(settings.navigationTimeoutMs),
      )
      Option(response)
        .getOrElse(throw new RuntimeException(s"No response received for $url"))
      page.waitForLoadState(LoadState.DOMCONTENTLOADED)
      op(page)
    } finally
      try page.close()
      catch { case _: Exception => () }
  }

  /** Run an op on a fresh page on the pinned thread WITHOUT navigating first.
    * Unlike [[withPage]], the op owns the full page lifecycle (e.g. install an
    * init script, then navigate), which DAST probe ops need so a confirm hook
    * is in place before the target loads. Same pinned-thread, pool-submit-only
    * contract; the page is closed on the way out. `op` must not retain it.
    */
  def withFreshPage[A](op: Page => A): A = {
    val page = context.newPage()
    try op(page)
    finally
      try page.close()
      catch { case _: Exception => () }
  }

  private def newContext(): BrowserContext = {
    // Match a current real Chrome for scraping (bot managers flag stale majors);
    // the scanner overrides this with an identifiable UA.
    val chromeUA =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    val opts = new Browser.NewContextOptions()
      .setUserAgent(settings.userAgent.getOrElse(chromeUA))
      .setViewportSize(1280, 800).setLocale("en-US")
      .setTimezoneId("America/New_York")
    val c = browser.newContext(opts)
    if (settings.stealth)
      // Stealth must run before crawler init: addInitScript fires in
      // registration order on every navigation.
      c.addInitScript(stealthScript)
    else
      // Be identifiable, not evasive (CLAUDE.md section 5).
      c.setExtraHTTPHeaders(java.util.Map.of("X-Scanner", ScannerHeader))
    c.addInitScript(initScript)
    c
  }

  private def rotateContextIfNeeded(): Unit = {
    successCount += 1
    if (successCount >= settings.contextRotationEvery) {
      log.info(
        "Rotating BrowserContext after {} successful scrapes",
        successCount,
      )
      try context.close()
      catch { case _: Exception => () }
      context = newContext()
      successCount = 0
    }
  }

  override def close(): Unit = {
    log.info("BrowserResource {} closing Chromium", id)
    if (context != null)
      try context.close()
      catch { case _: Exception => () }
    if (browser != null)
      try browser.close()
      catch { case _: Exception => () }
    if (playwright != null)
      try playwright.close()
      catch { case _: Exception => () }
  }
}

object BrowserResource {

  final case class Settings(
      contextRotationEvery: Int = 5,
      navigationTimeoutMs: Int = 15000,
      // Scraping default. For sanctioned scanning set stealth = false: the DAST
      // path must be identifiable, not evasive (CLAUDE.md sections 2 and 5).
      stealth: Boolean = true,
      // Overrides the user agent when set (the scanner announces itself).
      userAgent: Option[String] = None,
  )

  /** Sent as the X-Scanner header on non-stealth (DAST) contexts so a
    * consenting target can see who is testing it.
    */
  private val ScannerHeader =
    "pekko-dast-scanner/0.1 (+authorized security testing)"

  /** Non-HTML response (PDF, image, etc.) — terminal, no retry. */
  private final class NonHtmlContentException(message: String)
      extends RuntimeException(message)

  /** Hardened launch profile shared across all sessions. Strips the automation
    * signals bot managers read before deeper fingerprinting; `--headless=new`
    * uses the full Chrome binary. Set `CHROMIUM_NO_SANDBOX=true` in containers
    * (Chromium can't use its namespace sandbox as root, and /dev/shm is tiny).
    */
  private def launchOptions(
      proxy: Option[ProxyProviderConf],
      stealth: Boolean,
  ): BrowserType.LaunchOptions = {
    val args = {
      val core = new java.util.ArrayList[String](java.util.List.of(
        "--disable-features=IsolateOrigins,site-per-process",
        "--headless=new",
      ))
      // Evasion only on the scraping path; the scanner stays identifiable.
      if (stealth) core.add("--disable-blink-features=AutomationControlled")
      if (sys.env.get("CHROMIUM_NO_SANDBOX").contains("true")) {
        core.add("--no-sandbox")
        core.add("--disable-dev-shm-usage")
      }
      core
    }
    val opts = new BrowserType.LaunchOptions().setHeadless(true).setArgs(args)
    // Strip the CDP automation banner only when evading; the scanner leaves it.
    if (stealth) opts
      .setIgnoreDefaultArgs(java.util.List.of("--enable-automation"))
    proxy.foreach { p =>
      opts.setProxy(
        new PlaywrightProxy(p.server).setUsername(p.username)
          .setPassword(p.password),
      )
    }
    opts
  }

  private def resolveResource(resourcePath: String, prefix: String): Path = {
    val stream = Option(getClass.getResourceAsStream(resourcePath)).getOrElse(
      throw new IllegalArgumentException(s"Resource not found: $resourcePath"),
    )
    try {
      val content = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      val tmp = Files.createTempFile(prefix, ".js")
      tmp.toFile.deleteOnExit()
      Files.writeString(tmp, content)
      tmp
    } finally stream.close()
  }

  private def extractTextAndLinks(
      page: Page,
      regexString: String,
      target: String,
  ): (String, Seq[(String, String)]) = {
    val result = page.evaluate("extractContent", Array(regexString, target))
    val resultMap = result.asInstanceOf[java.util.Map[String, Any]].asScala
    val text = resultMap("text").toString
    val links = resultMap("links")
      .asInstanceOf[java.util.List[java.util.Map[String, Any]]].asScala
      .map { linkObj =>
        val m = linkObj.asScala
        (m("href").toString, m("text").toString)
      }.toSeq
    (text, links)
  }
}
