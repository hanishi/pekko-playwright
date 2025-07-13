package crawler

import java.net.URL

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.*

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal

import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.Proxy as PlaywrightProxy
import com.typesafe.config.ConfigFactory

import crawler.PlaywrightWorker.PageScrapedResult
import crawler.PlaywrightWorker.ScrapePage
import crawler.PlaywrightWorker.extractTextAndLinks

private class PlaywrightWorker(
    browser: BrowserContext,
    domain: String,
    hostRegex: String,
    clickSelector: Option[String],
    context: ActorContext[ScrapePage],
    timers: TimerScheduler[ScrapePage],
):

  private val maxRetries = 5
  private val domainRegex =
    s"(?:https?:\\/\\/(?:.+\\.)?${domain.replace(".", "\\.")})?"

  private def processing: Behavior[ScrapePage] = Behaviors.receiveMessage {
    case command @ ScrapePage(replyTo, url, targetElement, depth, attempt) =>

      val page = browser.newPage()
      Try {
        page.route("**/*.{png,jpg,jpeg}", route => route.abort())
        page.route(
          "**",
          route => {
            val requestUrl = route.request().url()
            if (!requestUrl.matches("^" + domainRegex + "\\/.*")) route.abort()
            else route.resume()
          },
        )
        page.navigate(url)
        page.waitForLoadState(LoadState.DOMCONTENTLOADED)
        clickSelector.foreach { selector =>
          val clickElement = page.querySelector(selector)
          if (clickElement != null && clickElement.isVisible) {
            clickElement.click()
            context.log.info(s"Clicked on $selector")
          }
        }
        extractTextAndLinks(page, domainRegex + hostRegex, targetElement)
      } match {
        case Success((text, links)) =>
          page.close()
          replyTo ! PageScrapedResult(url, text, links, depth, 1)
          processing
        case Failure(e) =>
          page.close()
          context.log.warn(s"Error processing $url: ${e.getMessage}")
          if (attempt >= maxRetries) {
            context.log
              .warn(s"Max retries reached for ${command.url}. Skipping.")
            replyTo !
              PageScrapedResult(command.url, "", Seq.empty, command.depth, 0)
            processing
          } else {
            val delay = (Random.nextInt(5) + 1).seconds
            timers.startSingleTimer(command.copy(attempt = attempt + 1), delay)
            processing
          }
      }
  }

object PlaywrightWorker:
  private val javascript = Using
    .resource(getClass.getResourceAsStream("/crawler.js")) { jsStream =>
      scala.io.Source.fromInputStream(jsStream).mkString
    }
  private val config = ConfigFactory.load().getConfig("crawler")
  private val useProxy = config.getBoolean("useProxy")
  private val proxyConfigs =
    if useProxy then
      Option(config.getConfigList("proxyProviders").asScala.map { conf =>
        ProxyProviderConf(
          conf.getString("provider"),
          conf.getString("server"),
          conf.getString("username"),
          conf.getString("password"),
        )
      }.toSeq).filter(_.nonEmpty).map(new ProxyRoundRobin(_))
    else None

  private class ProxyRoundRobin(proxies: Seq[ProxyProviderConf]) {
    private val it = Iterator.continually(proxies).flatten

    def next(): ProxyProviderConf = it.next()
  }

  def apply(
      domain: String,
      hostRegex: String,
      clickSelector: Option[String],
  ): Behavior[ScrapePage] = Behaviors.setup[ScrapePage]: context =>
    Behaviors.withTimers: timers =>

      val launchOptions = proxyConfigs
        .fold(BrowserType.LaunchOptions().setHeadless(true)) {
          proxyRoundRobin =>
            val nextProxy = proxyRoundRobin.next()
            context.log
              .info(s"Using proxy from ${nextProxy.provider}: ${nextProxy
                  .server}")
            BrowserType.LaunchOptions().setHeadless(true).setProxy(
              PlaywrightProxy(nextProxy.server).setUsername(nextProxy.username)
                .setPassword(nextProxy.password),
            )
        }
      val browserContext = Playwright.create().chromium().launch(launchOptions)
        .newContext()
      browserContext.addInitScript(javascript)
      new PlaywrightWorker(
        browserContext,
        domain,
        hostRegex,
        clickSelector,
        context,
        timers,
      ).processing

  def extractTextAndLinks(
      page: Page,
      regexString: String,
      target: String,
  ): (String, Seq[(String, String)]) =
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

  def robotsTxt(
      url: URL,
  )(using system: ActorSystem[_], ec: ExecutionContext): Future[Boolean] = {

    val robotsUrl = s"${url.getProtocol}://${url.getHost}/robots.txt"
    val responseFuture = Http().singleRequest(HttpRequest(uri = robotsUrl))
    responseFuture.flatMap { response =>
      Unmarshal(response.entity).to[String].map { content =>
        // TODO: Implement a proper parser for robots.txt
        true
      }
    }.recover { case _ => true }
  }
  case class ScrapePage(
      replyTo: ActorRef[PageScrapedResult],
      url: String,
      targetElement: String,
      depth: Int,
      attempt: Int = 0,
  )

  case class PageScrapedResult(
      url: String,
      content: String,
      links: Seq[(String, String)],
      depth: Int,
      status: Int,
  )
