package crawler

import java.io.BufferedWriter
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Try

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.Terminated
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import crawler.Crawler.PageContent

/** Runnable one-shot crawler.
  *
  * Reads the crawl settings from the `crawler` block of `application.conf` (see
  * [[CrawlerConfig.fromConfig]]), optionally overridden by command-line args,
  * spawns a [[Crawler]], writes each scraped page to a two-column `url,text`
  * CSV, and exits when the crawl finishes. `Crawler` stops itself once nothing
  * is in flight; the guardian watches it and stops on `Terminated`, which
  * terminates the ActorSystem (and the forked JVM) — closing the browser pool
  * and the CSV writer on the way out.
  *
  * Usage: sbt run # config defaults sbt "run https://edition.cnn.com/business"
  * # override seed URL sbt "run https://edition.cnn.com/business 2" # override
  * seed URL + max depth sbt "run https://edition.cnn.com/business 2 out.csv" #
  * + output path
  *
  * Config sources, lowest to highest precedence: bundled `application.conf`
  * defaults < `CRAWLER_*` env vars < an external HOCON file pointed at by
  * `CRAWLER_CONFIG` < CLI args (seed URL, max depth, output path). Output path
  * resolution: arg 2, else `crawler.output-csv`, else `crawler-output.csv`.
  */
object Main:

  private val log = LoggerFactory.getLogger("crawler.Main")

  def main(args: Array[String]): Unit =
    val rootConfig = loadConfig()
    val argv = args.toList
    val config = applyArgs(CrawlerConfig.fromConfig(rootConfig), argv)
    val outputPath = resolveOutput(rootConfig, argv)
    ActorSystem(guardian(config, outputPath), "crawler")

  /** Layered config: an optional external HOCON file (`CRAWLER_CONFIG`) is
    * overlaid on the bundled `application.conf`, which carries the built-in
    * defaults and `CRAWLER_*` env overrides. A partial external file is fine —
    * any keys it omits fall back to the bundled defaults.
    */
  private def loadConfig(): Config =
    val bundled = ConfigFactory.load()
    sys.env.get("CRAWLER_CONFIG").map(_.trim).filter(_.nonEmpty) match
      case None => bundled
      case Some(path) =>
        val f = new File(path)
        if !f.isFile then
          log.warn(
            "CRAWLER_CONFIG={} is not a readable file; using bundled defaults",
            path,
          )
          bundled
        else
          log.info("Loading config overrides from {}", f.getAbsolutePath)
          ConfigFactory.parseFile(f).withFallback(bundled).resolve()

  /** Override seed URL (arg 0) and max depth (arg 1) from the command line.
    * When the seed URL changes we re-derive `domain` from its host so link
    * filtering still matches the new site.
    */
  private def applyArgs(
      base: CrawlerConfig,
      args: List[String],
  ): CrawlerConfig =
    val withSeed = args.headOption.filter(_.nonEmpty).fold(base) { seed =>
      val domain = Try(new java.net.URI(seed).getHost).toOption.flatMap(Option(_))
        .getOrElse(base.domain)
      base.copy(seedUrl = seed, domain = domain)
    }
    args.lift(1).flatMap(_.toIntOption)
      .fold(withSeed)(d => withSeed.copy(maxDepth = d))

  private def resolveOutput(config: Config, args: List[String]): Path =
    val fromArg = args.lift(2).filter(_.nonEmpty)
    val fromCfg = Option
      .when(config.hasPath("crawler.output-csv"))(config.getString(
        "crawler.output-csv",
      ))
    Paths.get(fromArg.orElse(fromCfg).getOrElse("crawler-output.csv"))

  /** Quote a field per RFC 4180: wrap in double quotes and double any embedded
    * double quotes. Always quoting is safe since page text routinely contains
    * commas, quotes, and newlines.
    */
  private def csv(field: String): String = "\"" + field.replace("\"", "\"\"") +
    "\""

  private def guardian(
      config: CrawlerConfig,
      outputPath: Path,
  ): Behavior[PageContent] = Behaviors.setup { ctx =>
    ctx.log.info(
      "Starting crawl: seed={}, domain={}, maxDepth={}, concurrency={}, hostRegex={}, out={}",
      config.seedUrl,
      config.domain,
      config.maxDepth,
      config.concurrency,
      config.hostRegex,
      outputPath.toAbsolutePath,
    )

    val writer: BufferedWriter = Files
      .newBufferedWriter(outputPath, StandardCharsets.UTF_8)
    writer.write("url,text")
    writer.newLine()
    var rows = 0

    val crawler = ctx.spawn(Crawler(config, ctx.self), "crawler")
    ctx.watch(crawler)

    Behaviors.receiveMessage[PageContent] { case PageContent(url, text) =>
      writer.write(csv(url) + "," + csv(text))
      writer.newLine()
      writer.flush()
      rows += 1
      ctx.log.info("Scraped {} ({} chars)", url, text.length)
      Behaviors.same
    }.receiveSignal { case (sctx, Terminated(_)) =>
      writer.close()
      sctx.log.info(
        "Crawl complete — wrote {} rows to {}",
        rows,
        outputPath.toAbsolutePath,
      )
      Behaviors.stopped
    }
  }
