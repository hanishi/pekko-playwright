package crawler

import scala.util.Try

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.Terminated
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import crawler.Crawler.PageContent

/** Runnable one-shot crawler.
  *
  * Reads the crawl settings from the `crawler` block of `application.conf`
  * (see [[CrawlerConfig.fromConfig]]), optionally overridden by command-line
  * args, spawns a [[Crawler]], prints each scraped page, and exits when the
  * crawl finishes. `Crawler` stops itself once nothing is in flight; the
  * guardian watches it and stops on `Terminated`, which terminates the
  * ActorSystem (and the forked JVM) — closing the browser pool on the way out.
  *
  * Usage:
  *   sbt run                                   # config defaults
  *   sbt "run https://edition.cnn.com/business"   # override seed URL
  *   sbt "run https://edition.cnn.com/business 2" # override seed URL + max depth
  */
object Main:

  def main(args: Array[String]): Unit =
    val config = applyArgs(CrawlerConfig.fromConfig(ConfigFactory.load()), args.toList)
    ActorSystem(guardian(config), "crawler")

  /** Override seed URL (arg 0) and max depth (arg 1) from the command line.
    * When the seed URL changes we re-derive `domain` from its host so link
    * filtering still matches the new site. */
  private def applyArgs(base: CrawlerConfig, args: List[String]): CrawlerConfig =
    val withSeed = args.headOption.filter(_.nonEmpty).fold(base) { seed =>
      val domain = Try(new java.net.URI(seed).getHost).toOption.flatMap(Option(_)).getOrElse(base.domain)
      base.copy(seedUrl = seed, domain = domain)
    }
    args.lift(1).flatMap(_.toIntOption).fold(withSeed)(d => withSeed.copy(maxDepth = d))

  private def guardian(config: CrawlerConfig): Behavior[PageContent] =
    Behaviors.setup { ctx =>
      ctx.log.info(
        "Starting crawl: seed={}, domain={}, maxDepth={}, concurrency={}",
        config.seedUrl,
        config.domain,
        config.maxDepth,
        config.concurrency,
      )
      val crawler = ctx.spawn(Crawler(config, ctx.self), "crawler")
      ctx.watch(crawler)

      Behaviors
        .receiveMessage[PageContent] { case PageContent(url, text) =>
          ctx.log.info("Scraped {} ({} chars)", url, text.length)
          println(s"===== $url =====\n$text\n")
          Behaviors.same
        }
        .receiveSignal { case (sctx, Terminated(_)) =>
          sctx.log.info("Crawl complete — shutting down.")
          Behaviors.stopped
        }
    }
