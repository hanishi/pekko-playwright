package adtech.taxonomy

import java.util.Date
import java.util.TimeZone

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.apache.pekko.`extension`.quartz.QuartzSchedulerTypedExtension
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.Terminated
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import adtech.taxonomy.IABTaxonomyAssistant.Selection
import adtech.taxonomy.PublisherSite.*
import crawler.Crawler
import crawler.Crawler.PageContent
import crawler.CrawlerConfig

private class PublisherSite(
    id: String,
    context: ActorContext[PageContent | Message | Command],
) {
  private val quartzScheduler = QuartzSchedulerTypedExtension(context.system)

  private val idle: Behavior[PageContent | Message | Command] = Behaviors
    .receiveMessagePartial {
      case IABTaxonomyAnalysis(publisherSiteSettings) => scheduleWith(
          publisherSiteSettings.crawlerConfig,
          publisherSiteSettings.crawlerConfig.cronSchedule,
          "daily-crawl",
        ).fold {
          context.log.error("Failed to schedule daily crawl")
          Behaviors.same
        } { nextRun =>
          context.log.info(s"Next run: $nextRun")
          val OpenAIConfig(apiKey, baseTaxonomyIds) =
            publisherSiteSettings.openAIConfig
          active(new IABTaxonomyAssistant(apiKey, baseTaxonomyIds))
        }
      case InitializationFailed(throwable) =>
        context.log.error("Failed to initialize: {}", throwable.getMessage)
        Behaviors.stopped
    }

  private def scheduleWith(
      crawlerConfig: CrawlerConfig,
      cronExpression: String,
      scheduleName: String,
  ): Option[Date] = Try(quartzScheduler.updateTypedJobSchedule(
    scheduleName,
    context.self,
    StartCrawling(crawlerConfig),
    cronExpression = cronExpression,
    timezone = TimeZone.getDefault,
  )).toOption

  private def active(
      assistant: IABTaxonomyAssistant,
      results: Map[String, (String, List[Selection])] = Map.empty,
  ): Behavior[PageContent | Message | Command] = Behaviors
    .receive[PageContent | Message | Command] { (ctx, msg) =>
      msg match {
        case StartCrawling(cfg) =>
          val crawler = ctx.spawn(Crawler(cfg, ctx.self), id)
          ctx.watch(crawler)
          Behaviors.same
        case PageContent(url, text) =>
          ctx.pipeToSelf(assistant.analyzeTaxonomy(url, text)) {
            case Success(selections) => ContentAnalyzed(url, text, selections)
            case Failure(ex) => FailedToAnalyzeContent(ex)
          }
          Behaviors.same

        case ContentAnalyzed(url, text, selections) =>
          ctx.log
            .info(s"Content analyzed for $url: \n$text\nSelections: ${selections
                .mkString(", ")}")
          active(assistant, results + (url -> (text, selections)))
        case FailedToAnalyzeContent(ex) =>
          ctx.log.error(ex.getMessage)
          Behaviors.same

        case _ =>
          ctx.log.warn(s"Received unexpected message: $msg")
          Behaviors.same
      }
    }.receiveSignal { case (ctx, Terminated(crawler)) =>
      ctx.log.info(s"Crawler ${crawler.path.name} terminated")
      Behaviors.same
    }

  given ExecutionContext = context.executionContext

  given ActorSystem[_] = context.system
}

object PublisherSite {

  case class Settings(crawlerConfig: CrawlerConfig, openAIConfig: OpenAIConfig)

  def apply(
      id: String,
      fetchConfig: String => Future[Settings],
  ): Behavior[Command] = Behaviors
    .setup[PageContent | Message | Command] { context =>
      context.log.info(s"Initializing PublisherSite with ID: $id")
      context.pipeToSelf(fetchConfig(id)) {
        case Success(publisherSiteSettings) =>
          IABTaxonomyAnalysis(publisherSiteSettings)
        case Failure(exception) => InitializationFailed(exception)
      }
      new PublisherSite(id, context).idle
    }.narrow

  sealed trait Message

  sealed trait Command

  private case class IABTaxonomyAnalysis(publisherSiteSettings: Settings)
      extends Command

  case class StartCrawling(crawlerConfig: CrawlerConfig) extends Message

  private case class InitializationFailed(exception: Throwable) extends Message

  private case class ContentAnalyzed(
      url: String,
      text: String,
      selections: List[Selection],
  ) extends Message
  private case class FailedToAnalyzeContent(exception: Throwable)
      extends Message
}
