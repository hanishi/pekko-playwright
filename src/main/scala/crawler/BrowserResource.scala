package crawler

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

import com.microsoft.playwright.*
import com.microsoft.playwright.options.{LoadState, Proxy as PlaywrightProxy, WaitUntilState}
import org.slf4j.LoggerFactory

import crawler.PlaywrightCrawler.PageScrapedResult

/** Thread-affine resource wrapping a Playwright driver + headless
  * Chromium browser, hosted on one pinned actor thread by
  * [[crawler.pool.ResourcePool]]. Playwright Java's driver is
  * single-threaded — every API call must originate from the thread
  * that called `Playwright.create()`. Building this in
  * `ResourceSession`'s setup (on the pinned thread) and only ever
  * invoking [[scrape]] via `pool.submit` keeps that invariant.
  *
  * One `BrowserResource` == one Chromium process. The pool keeps a
  * fixed `size` of them; crawl concurrency is an in-flight cap
  * against the shared pool rather than a browser count.
  *
  * Mutable fields (`context`, `successCount`) are safe without
  * synchronization because they are only touched on the pinned
  * thread.
  */
final class BrowserResource(
    id: Int,
    proxy: Option[ProxyProviderConf],
    settings: BrowserResource.Settings = BrowserResource.Settings(),
) extends AutoCloseable {

  import BrowserResource.*

  private val log = LoggerFactory.getLogger(s"crawler.BrowserResource.$id")

  log.info("launching playwright + chromium (proxy={})", proxy.map(_.provider).getOrElse("none"))
  private val playwright: Playwright = Playwright.create()
  private val browser: Browser       = playwright.chromium().launch(launchOptions(proxy))
  private val stealthScript: Path    = resolveResource("/stealth.js", "stealth-")
  private val initScript: Path       = resolveResource("/crawler.js", "crawler-init-")

  private var context: BrowserContext = newContext()
  private var successCount: Int       = 0

  /** Scrape one URL on the pinned thread. Returns a [[PageScrapedResult]]
    * with status 1 on success and status 0 for terminal non-HTML
    * responses. Retryable failures are thrown so the caller (the
    * crawler) can reschedule — the future returned by `pool.submit`
    * fails with the same exception. */
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
        new Page.NavigateOptions()
          .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
          .setTimeout(settings.navigationTimeoutMs),
      )
      val resp = Option(response)
        .getOrElse(throw new RuntimeException(s"No response received for $url"))
      if (resp.status() >= 400)
        throw new RuntimeException(
          s"Bad response status ${resp.status()} for $url: ${resp.statusText()}",
        )

      val contentType = Option(resp.headerValue("content-type")).getOrElse("")
      if (!contentType.contains("text/html") && !contentType.contains("application/xhtml"))
        throw new NonHtmlContentException(s"Non-HTML content-type for $url: $contentType")

      page.waitForLoadState(LoadState.DOMCONTENTLOADED)
      clickSelector.foreach { selector =>
        Option(page.querySelector(selector)).foreach(el => if (el.isVisible) el.click())
      }
      val results = targetElements.map(target => extractTextAndLinks(page, linkRegex, target))
      val texts: Seq[String]            = results.map(_._1).toSeq
      val links: Seq[(String, String)] = results.flatMap(_._2).toSeq
      rotateContextIfNeeded()
      PageScrapedResult(url, texts, links, depth, 1)
    } catch {
      case _: NonHtmlContentException =>
        PageScrapedResult(url, Seq.empty, Seq.empty, depth, 0)
    } finally {
      try page.close() catch { case _: Exception => () }
    }
  }

  private def newContext(): BrowserContext = {
    val opts = new Browser.NewContextOptions()
      .setUserAgent(
        // Match a current real Chrome — bot managers flag stale majors.
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
          "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
      )
      .setViewportSize(1280, 800)
      .setLocale("en-US")
      .setTimezoneId("America/New_York")
    val c = browser.newContext(opts)
    // Stealth must run before crawler init: addInitScript fires in
    // registration order on every navigation.
    c.addInitScript(stealthScript)
    c.addInitScript(initScript)
    c
  }

  private def rotateContextIfNeeded(): Unit = {
    successCount += 1
    if (successCount >= settings.contextRotationEvery) {
      log.info("Rotating BrowserContext after {} successful scrapes", successCount)
      try context.close() catch { case _: Exception => () }
      context = newContext()
      successCount = 0
    }
  }

  override def close(): Unit = {
    log.info("BrowserResource {} closing Chromium", id)
    if (context != null)    try context.close()    catch { case _: Exception => () }
    if (browser != null)    try browser.close()    catch { case _: Exception => () }
    if (playwright != null) try playwright.close() catch { case _: Exception => () }
  }
}

object BrowserResource {

  final case class Settings(
      contextRotationEvery: Int = 5,
      navigationTimeoutMs: Int  = 15000,
  )

  /** Non-HTML response (PDF, image, etc.) — terminal, no retry. */
  private final class NonHtmlContentException(message: String) extends RuntimeException(message)

  /** Hardened launch profile shared across all sessions. Strips the
    * automation signals bot managers read before deeper fingerprinting;
    * `--headless=new` uses the full Chrome binary. Set
    * `CHROMIUM_NO_SANDBOX=true` in containers (Chromium can't use its
    * namespace sandbox as root, and /dev/shm is tiny). */
  private def launchOptions(proxy: Option[ProxyProviderConf]): BrowserType.LaunchOptions = {
    val args = {
      val core = new java.util.ArrayList[String](java.util.List.of(
        "--disable-blink-features=AutomationControlled",
        "--disable-features=IsolateOrigins,site-per-process",
        "--headless=new",
      ))
      if (sys.env.get("CHROMIUM_NO_SANDBOX").contains("true")) {
        core.add("--no-sandbox")
        core.add("--disable-dev-shm-usage")
      }
      core
    }
    val opts = new BrowserType.LaunchOptions()
      .setHeadless(true)
      .setArgs(args)
      .setIgnoreDefaultArgs(java.util.List.of("--enable-automation"))
    proxy.foreach { p =>
      opts.setProxy(
        new PlaywrightProxy(p.server).setUsername(p.username).setPassword(p.password),
      )
    }
    opts
  }

  private def resolveResource(resourcePath: String, prefix: String): Path = {
    val stream = Option(getClass.getResourceAsStream(resourcePath))
      .getOrElse(throw new IllegalArgumentException(s"Resource not found: $resourcePath"))
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
      }
      .toSeq
    (text, links)
  }
}
