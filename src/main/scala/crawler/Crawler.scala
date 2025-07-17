package crawler

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Random
import scala.util.Success

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import crawler.Crawler.CheckIfDone
import crawler.Crawler.Command
import crawler.Crawler.PageContent
import crawler.Crawler.StartCrawling
import crawler.PlaywrightCrawler.StartScrape
import crawler.PlaywrightWorker.PageScrapedResult

object Crawler:

  def apply(
      domain: String,
      maxDepth: Int,
      concurrency: Int,
      seedUrl: String,
      hostRegex: String,
      targetElements: Array[String],
      clickSelector: String,
      receiver: ActorRef[PageContent],
  ): Behavior[Command] = apply(
    domain,
    maxDepth,
    concurrency,
    seedUrl,
    hostRegex,
    targetElements,
    Some(clickSelector),
    receiver: ActorRef[PageContent],
  )

  def apply(
      domain: String,
      maxDepth: Int,
      concurrency: Int,
      seedUrl: String,
      hostRegex: String,
      targetElements: Array[String],
      receiver: ActorRef[PageContent],
  ): Behavior[Command] = apply(
    domain,
    maxDepth,
    concurrency,
    seedUrl,
    hostRegex,
    targetElements,
    None,
    receiver: ActorRef[PageContent],
  )

  def apply(
      domain: String,
      maxDepth: Int,
      concurrency: Int,
      seedUrl: String,
      hostRegex: String,
      targetElements: Array[String],
      clickSelector: Option[String] = None,
      receiver: ActorRef[PageContent],
  ): Behavior[Command] = Behaviors
    .setup[Command | PageScrapedResult] { context =>
      val depths = mutable.Map[Int, Int]()
      Behaviors.withTimers { timers =>
        val crawler = context.spawnAnonymous(PlaywrightCrawler(
          concurrency,
          depths,
          domain,
          hostRegex,
          targetElements,
          clickSelector,
        ))

        given ExecutionContext = context.executionContext
        given ActorSystem[_] = context.system
        context.pipeToSelf(PlaywrightWorker.robotsTxt(java.net.URL(seedUrl))) {
          case Success(_) => StartCrawling(Set(seedUrl), maxDepth)
          case Failure(exception) =>
            context.log.error(s"Failed to fetch robots.txt: $exception")
            StartCrawling(Set(seedUrl), maxDepth)
        }
        new Crawler(context, timers, crawler, maxDepth, receiver, depths)
          .running()
      }
    }.narrow

  sealed trait Command

  case class StartCrawling(urls: Set[String], depth: Int) extends Command

  case class PageContent(url: String, text: String)

  case object CheckIfDone extends Command

private class Crawler(
    context: ActorContext[Command | PageScrapedResult],
    timers: TimerScheduler[Command | PageScrapedResult],
    browserWorker: ActorRef[PlaywrightCrawler.Command],
    maxDepth: Int,
    receiver: ActorRef[PageContent],
    depths: mutable.Map[Int, Int],
):
  private val visitedUrls: mutable.Set[String] = mutable.Set.empty

  private def printDepthsTable(): Unit = {
    println(f"${"Depth"}%-10s ${"Count"}%-10s")
    println("-" * 20)
    depths.foreach { case (depth, count) =>
      println(f"$depth%-10d $count%-10d")
    }
  }

  private def running(
      inProgress: Int = 0,
  ): Behavior[Command | PageScrapedResult] = Behaviors.receiveMessage {
    case StartCrawling(urls, depth) =>
      if (depth <= 0 || urls.isEmpty)
        context.self ! CheckIfDone
        Behaviors.same
      else {
        val filteredUrls = urls.diff(visitedUrls)
        if (filteredUrls.nonEmpty) browserWorker !
          StartScrape(context.self, filteredUrls, depth)
        else context.log
          .info("All URLs were blocked by robots.txt or already visited.")
        visitedUrls ++= filteredUrls
        running(inProgress + filteredUrls.size)
      }
    case PageScrapedResult(url, contents, links, depth, status) =>
      val newLinks = links
        .collect { case (href, _) if !visitedUrls.contains(href) => href }
      context.log.info(s"Scraped $url at ${maxDepth - depth}. Found ${newLinks
          .size} new links. Status: $status")
      if (newLinks.nonEmpty && depth > 0) schedule(newLinks.toSet, depth - 1)
      else context.self ! CheckIfDone
      receiver ! PageContent(url, contents.mkString("\n"))
      running(inProgress - 1)
    case CheckIfDone =>
      if (inProgress == 0) {
        context.log.info("Crawling is complete. Shutting down.")
        context.log.info(s"Visited ${visitedUrls.size} URLs.")
        printDepthsTable()
        Behaviors.stopped
      } else Behaviors.same
  }

  private def schedule(urls: Set[String], depth: Int): Unit = timers
    .startSingleTimer(
      StartCrawling(urls, depth),
      (Random.nextInt(5) + 1).seconds,
    )
