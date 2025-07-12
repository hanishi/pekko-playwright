package crawler

import scala.collection.mutable
import scala.math.max

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.PoolRouter
import org.apache.pekko.actor.typed.scaladsl.Routers
import org.apache.pekko.actor.typed.scaladsl.StashBuffer

import crawler.PlaywrightCrawler.CommandOrResponse
import crawler.PlaywrightCrawler.StartScrape
import crawler.PlaywrightWorker.*

private class PlaywrightCrawler(
    context: ActorContext[CommandOrResponse],
    buffer: StashBuffer[CommandOrResponse],
    concurrency: Int,
    depths: mutable.Map[Int, Int],
    domain: String,
    hostRegex: String,
    targetElement: String,
    clickSelector: Option[String],
):

  private val workerPool: PoolRouter[ScrapePage] = Routers.pool(concurrency) {

    Behaviors.supervise(PlaywrightWorker(domain, hostRegex, clickSelector))
      .onFailure(SupervisorStrategy.restart)
  }
  private val workerRouter: ActorRef[ScrapePage] = context
    .spawn(workerPool, "worker-pool")

  private def idle: Behavior[CommandOrResponse] = Behaviors.receiveMessage {
    case StartScrape(replyTo, urls, depth) =>
      context.log.info(s"Starting scrape of ${urls
          .size} URLs with concurrency=$concurrency.")
      runScrape(urls, depth, replyTo)
    case other =>
      buffer.stash(other)
      Behaviors.same
  }

  private def runScrape(
      urls: Set[String],
      depth: Int,
      replyTo: ActorRef[PageScrapedResult],
      inFlight: Int = 0,
  ): Behavior[CommandOrResponse] = {
    context.log
      .info(s"${urls.size} URLs remaining, $inFlight in flight at $depth")
    if (urls.isEmpty && inFlight == 0) buffer.unstashAll(idle)
    else {
      val (urlsToProcess, remaining) = urls
        .splitAt(max(0, concurrency - inFlight))

      urlsToProcess.foreach { url =>
        context.log.debug(s"Dispatching scrape task for: $url")
        workerRouter ! ScrapePage(
          context.self,
          url,
          if depths.isEmpty then "body" else targetElement,
          depth,
        )
        depths(depth) = depths.getOrElseUpdate(depth, 0) + 1
      }

      activeScrape(remaining, depth, replyTo, inFlight + urlsToProcess.size)
    }
  }

  private def activeScrape(
      urls: Set[String],
      depth: Int,
      replyTo: ActorRef[PageScrapedResult],
      inFlight: Int,
  ): Behavior[CommandOrResponse] = Behaviors.receiveMessage[CommandOrResponse] {
    case page: PageScrapedResult =>
      context.log.info(s"Scraped ${page.url}, ${page.links.size} links found")
      replyTo ! page
      depths(depth) = depths(depth) - 1
      runScrape(urls, depth, replyTo, max(0, inFlight - 1))
    case other =>
      buffer.stash(other)
      Behaviors.same
  }

object PlaywrightCrawler:

  private type CommandOrResponse = Command | PageScrapedResult
  def apply(
      concurrency: Int,
      depths: mutable.Map[Int, Int],
      domain: String,
      hostRegex: String,
      targetElement: String,
      clickSelector: Option[String],
  ): Behavior[Command] = Behaviors.setup[CommandOrResponse] { context =>
    Behaviors.withStash(1000)(buffer =>
      new PlaywrightCrawler(
        context,
        buffer,
        concurrency,
        depths,
        domain,
        hostRegex,
        targetElement,
        clickSelector,
      ).idle,
    )
  }.narrow

  sealed trait Command
  case class StartScrape(
      replyTo: ActorRef[PageScrapedResult],
      urls: Set[String],
      depth: Int,
  ) extends Command
