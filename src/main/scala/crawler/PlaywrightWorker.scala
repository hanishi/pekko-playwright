package crawler

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright}
import com.microsoft.playwright.options.LoadState
import PlaywrightWorker.{PageScrapedResult, ScrapePage, extractTextAndLinks}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Random, Success, Try}

private class PlaywrightWorker(
    browser: Browser,
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

  def apply(
      domain: String,
      hostRegex: String,
      clickSelector: Option[String],
  ): Behavior[ScrapePage] = Behaviors.setup[ScrapePage]: context =>
    Behaviors.withTimers: timers =>
      new PlaywrightWorker(
        Playwright.create().chromium()
          .launch(new BrowserType.LaunchOptions().setHeadless(true)),
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
    val result = page.evaluate(
      """([regexString, target]) => {
      const currentUrl = new URL(window.location.href);
      const baseUrl = `${currentUrl.protocol}//${currentUrl.host}`;
      const linkRegex = new RegExp(regexString);
      const targetElement = document.querySelector(target);

      function traverse(node, insideTarget) {
        let text = "";
        let links = [];

        if (node.nodeType === Node.TEXT_NODE) {
          // Do not trim here so we can do the whitespace collapsing later.
          const rawText = node.textContent;
          return { text: insideTarget ? rawText : "", links: [] };
        }

        if (node.nodeType !== Node.ELEMENT_NODE) {
          return { text: "", links: [] };
        }

        if (["SCRIPT", "STYLE", "NOSCRIPT"].includes(node.tagName)) {
          return { text: "", links: [] };
        }

        const newInsideTarget = insideTarget || (node === targetElement);

        if (node.tagName === "A") {
          const aHref = node.getAttribute("href");
          const aText = (node.innerText || "").replace(/[\u00A0\s]+/g, ' ').trim();
          let aLinks = [];
          if (aHref && !/^\/\//.test(aHref) && linkRegex.test(aHref)) {
            const fullHref = aHref.startsWith("/") ? `${baseUrl}${aHref}` : aHref;
          aLinks.push({ href: fullHref, text: aText });
        }
        return { text: newInsideTarget ? aText : "", links: aLinks };
      }

      let childText = "";
      for (let i = 0; i < node.childNodes.length; i++) {
        const result = traverse(node.childNodes[i], newInsideTarget);
        childText += result.text;
        links = links.concat(result.links);
      }
      return { text: childText, links: links };
    }

    const result = traverse(document.body, false);
    result.text = result.text.replace(/[\u00A0\s]+/g, ' ').trim();
    return result;
  }""",
      Array(regexString, target),
    )
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
      url: String,
  )(using system: ActorSystem[_], ec: ExecutionContext): Future[Boolean] = {
    val robotsUrl =
      s"${new java.net.URL(url).getProtocol}://${new java.net.URL(url)
          .getHost}/robots.txt"
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
