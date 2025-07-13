package crawler

import java.nio.file.Paths

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright

class BrowserContextFactory(
    launchOptions: BrowserType.LaunchOptions,
    initScriptPath: String,
) {
  val playwright: Playwright = Playwright.create()
  private val browser = playwright.chromium().launch(launchOptions)
  def create(): BrowserContext = {

    val contextOptions = new Browser.NewContextOptions().setUserAgent(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
          "(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
      ).setViewportSize(1280, 800).setLocale("en-US")
      .setTimezoneId("America/New_York")
    
    println("Creating new browser context")
    val context = browser.newContext(contextOptions)
    context.addInitScript(Paths.get(getClass.getResource(initScriptPath).toURI))
    context
  }
}
