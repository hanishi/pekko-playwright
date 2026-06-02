package crawler

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.math.max
import scala.util.{Failure, Random, Success, Try}

import com.typesafe.config.Config
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.StashBuffer

import crawler.PlaywrightCrawler.*
import crawler.pool.ResourcePool
import crawler.pool.ResourcePool.{Pool, asPool, submit}

private class PlaywrightCrawler private (
    context: ActorContext[CommandOrResponse],
    buffer: StashBuffer[CommandOrResponse],
    depths: mutable.Map[Int, Int],
    crawlerConfig: CrawlerConfig,
    pool: Pool[BrowserResource],
):

  private val maxRetries = 5

  // Full link-acceptance regex: optional same-domain prefix + host path
  // pattern. Passed to crawler.js `extractContent` to filter links.
  private val linkRegex =
    s"(?:https?:\\/\\/(?:.+\\.)?${crawlerConfig.domain.replace(".", "\\.")})?" +
      crawlerConfig.hostRegex

  private def targetsFor: Array[String] =
    if depths.isEmpty then Array("body") else crawlerConfig.targetElements

  /** Submit one URL to the pool. Its pinned-thread scrape result (or
    * failure) comes back as a [[TaskResult]] via `pipeToSelf`. */
  private def dispatch(
      replyTo: ActorRef[PageScrapedResult],
      url: String,
      targetElements: Array[String],
      depth: Int,
      attempt: Int,
  ): Unit =
    context.pipeToSelf(
      pool.submit(_.scrape(url, targetElements, depth, linkRegex, crawlerConfig.clickSelector)),
    )(outcome => TaskResult(replyTo, url, targetElements, depth, attempt, outcome))

  private def idle: Behavior[CommandOrResponse] = Behaviors.receiveMessage {
    case StartScrape(replyTo, urls, depth) =>
      context.log.info(
        s"Starting scrape of ${urls.size} URLs with concurrency=${crawlerConfig.concurrency}.",
      )
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
    context.log.info(s"${urls.size} URLs remaining, $inFlight in flight at $depth")
    if (urls.isEmpty && inFlight == 0) buffer.unstashAll(idle)
    else {
      val (urlsToProcess, remaining) =
        urls.splitAt(max(0, crawlerConfig.concurrency - inFlight))

      val targets = targetsFor
      urlsToProcess.foreach { url =>
        // Stagger dispatch slightly to avoid overwhelming the target.
        val delay = (Random.nextInt(5) + 1).seconds
        context.log.debug(s"Dispatching scrape task for: $url after $delay")
        context.scheduleOnce(
          delay,
          context.self,
          Dispatch(replyTo, url, targets, depth, attempt = 0),
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
    case Dispatch(rt, url, targetElements, d, attempt) =>
      dispatch(rt, url, targetElements, d, attempt)
      Behaviors.same

    case TaskResult(_, _, _, d, _, Success(result)) =>
      // Terminal (success or non-HTML). Forward and free the slot.
      context.log.info(s"Scraped ${result.url}, ${result.links.size} links found")
      replyTo ! result
      depths(d) = depths(d) - 1
      runScrape(urls, depth, replyTo, max(0, inFlight - 1))

    case TaskResult(rt, url, targetElements, d, attempt, Failure(e)) =>
      if (attempt >= maxRetries) {
        context.log.warn(s"Max retries reached for $url. Giving up: ${e.getMessage}")
        rt ! PageScrapedResult(url, Seq.empty, Seq.empty, d, 0)
        depths(d) = depths(d) - 1
        runScrape(urls, depth, replyTo, max(0, inFlight - 1))
      } else {
        context.log.warn(s"Error processing $url (attempt $attempt): ${e.getMessage}")
        val delay = (Random.nextInt(5) + 1).seconds
        // Stays in flight: reschedule the same URL without freeing the slot.
        context.scheduleOnce(
          delay,
          context.self,
          Dispatch(rt, url, targetElements, d, attempt + 1),
        )
        Behaviors.same
      }

    case other =>
      buffer.stash(other)
      Behaviors.same
  }

object PlaywrightCrawler:

  private type CommandOrResponse = Command | Internal

  def apply(
      depths: mutable.Map[Int, Int],
      crawlerConfig: CrawlerConfig,
  ): Behavior[Command] = Behaviors.setup[CommandOrResponse] { context =>
    val cfg     = context.system.settings.config
    val size    = poolSize(cfg)
    val proxies = proxiesFromConfig(cfg)
    val poolRef = context.spawn(
      ResourcePool[BrowserResource](
        size = size,
        make = i => new BrowserResource(i, if (proxies.isEmpty) None else Some(proxies(i % proxies.size))),
      ),
      "browser-pool",
    )
    Behaviors.withStash(1000)(buffer =>
      new PlaywrightCrawler(context, buffer, depths, crawlerConfig, poolRef.asPool[BrowserResource]).idle,
    )
  }.narrow

  private def poolSize(config: Config): Int =
    if (config.hasPath("crawler.browser-pool.size")) config.getInt("crawler.browser-pool.size")
    else 4

  private def proxiesFromConfig(config: Config): Seq[ProxyProviderConf] = {
    if (!config.hasPath("crawler")) return Seq.empty
    val c = config.getConfig("crawler")
    val enabled = c.hasPath("useProxy") && c.getBoolean("useProxy")
    if (!enabled || !c.hasPath("proxyProviders")) Seq.empty
    else
      c.getConfigList("proxyProviders").asScala
        .map(p =>
          ProxyProviderConf(
            p.getString("provider"),
            p.getString("server"),
            p.getString("username"),
            p.getString("password"),
          ),
        )
        .toSeq
  }

  sealed trait Command
  case class StartScrape(
      replyTo: ActorRef[PageScrapedResult],
      urls: Set[String],
      depth: Int,
  ) extends Command

  case class PageScrapedResult(
      url: String,
      contents: Seq[String],
      links: Seq[(String, String)],
      depth: Int,
      status: Int,
  )

  // -- Internal self-messages (pool dispatch + result plumbing) --

  private sealed trait Internal

  private final case class Dispatch(
      replyTo: ActorRef[PageScrapedResult],
      url: String,
      targetElements: Array[String],
      depth: Int,
      attempt: Int,
  ) extends Internal

  private final case class TaskResult(
      replyTo: ActorRef[PageScrapedResult],
      url: String,
      targetElements: Array[String],
      depth: Int,
      attempt: Int,
      outcome: Try[PageScrapedResult],
  ) extends Internal
