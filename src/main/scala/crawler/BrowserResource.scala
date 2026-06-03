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
import com.microsoft.playwright.options.Cookie as PwCookie
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
    * real visit leaves it. `op` receives the live `Page` and the main
    * navigation `Response` (for response-header / status checks); it must not
    * retain either beyond the call.
    */
  def withPage[A](url: String)(op: (Page, Response) => A): A = {
    val page = context.newPage()
    try {
      val response = page.navigate(
        url,
        new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD)
          .setTimeout(settings.navigationTimeoutMs),
      )
      val resp = Option(response)
        .getOrElse(throw new RuntimeException(s"No response received for $url"))
      // Wait for full `load` (not just domcontentloaded) so cookies/storage set
      // during the page load are visible to a passive capture.
      page.waitForLoadState(LoadState.LOAD)
      op(page, resp)
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

  // --- Persistent navigation session ---------------------------------------
  // For LLM-driven multi-hop browser navigation: one page kept alive across
  // many `pool`/`session` submits, so the model can click/submit through a
  // (possibly JS-driven) app while we accumulate the requests it makes. All of
  // these run on the pinned thread (submit-only); the mutable slots are safe
  // because only the pinned thread touches them.
  private var navCtx: BrowserContext = null
  private var navPage: Page = null
  private val navReqs = java.util.Collections
    .synchronizedList(new java.util.ArrayList[String]())

  /** Open a fresh nav context+page (optionally seeded with cookies) and start
    * recording every request it makes (incl. JS XHR/fetch). Does NOT navigate
    * yet, so a login can run in this same context first (so the app's JS sets
    * up localStorage/session that subsequent navigation needs).
    */
  def navOpen(cookies: Seq[(String, String)], baseUrl: String): Unit = {
    navCtx = newContext()
    if (cookies.nonEmpty) navCtx.addCookies(
      cookies.map((n, v) => new PwCookie(n, v).setUrl(baseUrl)).asJava,
    )
    navPage = navCtx.newPage()
    navReqs.clear()
    navPage.onRequest { (req: Request) =>
      navReqs.add(req.url()); ()
    }
  }

  /** Navigate the open nav page to `url`. */
  def navGoto(url: String, navTimeoutMs: Int): Unit =
    if (navPage != null) navGo(url, navTimeoutMs)

  /** Log in within the nav context by driving the real login form, so the app's
    * JavaScript establishes the client session (cookies AND localStorage) that
    * the SPA's route guard checks. Field detection is deterministic (password
    * input + nearest text input + submit control); the model is not consulted
    * (instruction.md §5 authenticated-scan carve-out, §0.2).
    */
  def navLogin(
      loginUrl: String,
      username: String,
      password: String,
      navTimeoutMs: Int,
  ): Unit = if (navPage != null) {
    navGo(loginUrl, navTimeoutMs)
    Option(navPage.querySelector("input[type=password]")) match {
      case None => log.warn("navLogin: no password field at {}", loginUrl)
      case Some(pwd) =>
        val user = navUsernameField()
        log.info(
          "navLogin: form at {} (username field found={})",
          loginUrl,
          user.isDefined,
        )
        user.foreach(_.fill(username))
        pwd.fill(password)
        navSubmitButton() match {
          case Some(btn) =>
            try btn.click()
            catch { case _: Exception => () }
          case None =>
            try pwd.press("Enter")
            catch { case _: Exception => () }
        }
        try navPage.waitForLoadState(LoadState.LOAD)
        catch { case _: Exception => () }
        try navPage.waitForLoadState(
            LoadState.NETWORKIDLE,
            new Page.WaitForLoadStateOptions().setTimeout(navTimeoutMs.toDouble),
          )
        catch { case _: Exception => () }
        log.info(
          "navLogin: after submit at {} ({} cookie(s))",
          navPage.url(),
          navCtx.cookies().size,
        )
    }
  }

  /** The login submit control: a real submit if present, else the button whose
    * text looks like sign-in/login (htmx buttons are often not type=submit and
    * may sit outside a <form>), else the first button.
    */
  private def navSubmitButton(): Option[ElementHandle] =
    Option(navPage.querySelector("button[type=submit], input[type=submit]"))
      .orElse {
        val buttons = navPage.querySelectorAll("button").asScala.toSeq
        val keywords =
          Seq("sign in", "signin", "sign-in", "log in", "login", "submit")
        buttons.find { b =>
          val t = Option(b.innerText()).getOrElse("").toLowerCase
          keywords.exists(t.contains)
        }.orElse(buttons.headOption)
      }

  private def navUsernameField() = Option(
    navPage.querySelector("input[type=email]"),
  ).orElse(Option(navPage.querySelector("input[type=text]"))).orElse(Option(
    navPage.querySelector("input[name='username'], input[name='user'], input[name='email'], input[name='login']"),
  )).orElse(Option(
    navPage.querySelector("input:not([type='password']):not([type='hidden']):not([type='submit']):not([type='checkbox']):not([type='radio'])"),
  ))

  /** The nav context's current cookies as (name, value) pairs, e.g. to replay
    * the post-login session in the (HTTP) IDOR confirm step.
    */
  def navCookies(): Seq[(String, String)] =
    if (navCtx != null) navCtx.cookies().asScala.map(c => c.name -> c.value)
      .toSeq
    else Seq.empty

  private def navGo(url: String, navTimeoutMs: Int): Unit = {
    // A slow/hanging navigation must not abort the whole scan; proceed with
    // whatever loaded (navUrl/navHtml reflect the current page either way).
    try navPage.navigate(
        url,
        new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD)
          .setTimeout(navTimeoutMs),
      )
    catch { case _: Exception => () }
    try navPage.waitForLoadState(
        LoadState.NETWORKIDLE,
        new Page.WaitForLoadStateOptions().setTimeout(navTimeoutMs.toDouble),
      )
    catch { case _: Exception => () }
  }

  /** The live nav page's current URL (post client-side routing). */
  def navUrl(): String = if (navPage != null) navPage.url() else ""

  /** The live nav page's rendered HTML (post-JS), for form/link extraction. */
  def navHtml(): String =
    if (navPage != null)
      try navPage.content()
      catch { case _: Exception => "" }
    else ""

  /** Navigate the nav page to `url` (a model-chosen link). */
  def navFollow(url: String, navTimeoutMs: Int): Unit =
    if (navPage != null) navGo(url, navTimeoutMs)

  /** Fill and submit the `formIndex`-th form on the live nav page (document
    * order, matching the HTML extraction), triggering its real JS handler.
    */
  def navSubmit(
      formIndex: Int,
      values: Seq[(String, String)],
      navTimeoutMs: Int,
  ): Unit = if (navPage != null) {
    val forms = navPage.querySelectorAll("form")
    if (formIndex >= 0 && formIndex < forms.size) {
      val form = forms.get(formIndex)
      values.foreach { (n, v) =>
        // Fields may be hidden / selects / non-fillable; never let one throw.
        Option(form.querySelector(s"[name='$n']")).foreach(el =>
          try el.fill(v)
          catch { case _: Exception => () },
        )
      }
      Option(
        form.querySelector("button[type=submit], input[type=submit], button"),
      ) match {
        case Some(btn) =>
          try btn.click()
          catch { case _: Exception => () }
        case None => Option(form.querySelector("input")).foreach(i =>
            try i.press("Enter")
            catch { case _: Exception => () },
          )
      }
      try navPage.waitForLoadState(LoadState.LOAD)
      catch { case _: Exception => () }
      try navPage.waitForLoadState(
          LoadState.NETWORKIDLE,
          new Page.WaitForLoadStateOptions().setTimeout(navTimeoutMs.toDouble),
        )
      catch { case _: Exception => () }
    }
  }

  /** All requests the nav page has made so far (deduped). */
  def navRequests(): Seq[String] = navReqs.asScala.toList.distinct

  /** Close the nav page and its context. */
  def navStop(): Unit = {
    try if (navPage != null) navPage.close()
    catch { case _: Exception => () }
    try if (navCtx != null) navCtx.close()
    catch { case _: Exception => () }
    navPage = null
    navCtx = null
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
