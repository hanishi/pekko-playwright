package adtech.taxonomy

import scala.concurrent.Future

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import adtech.taxonomy.PublisherSite.Settings
import crawler.CrawlerConfig

class PublisherSiteSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "PublisherSite actor" should {
    def fetchConfig(id: String): Future[Settings] = Future.successful(Settings(
      CrawlerConfig(
        domain = "edition.cnn.com",
        concurrency = 5,
        maxDepth = 3,
        seedUrl = "https://edition.cnn.com/sport",
        hostRegex = "\\/2025\\/07\\/\\d+\\/[^ ]*",
        targetElements = Array(
          "#maincontent",
          "body > div.layout-article-elevate__content-wrapper.layout__content-wrapper > section.layout__wrapper.layout-article-elevate__wrapper > section.layout__main.layout-article-elevate__main > section.layout__center.layout-article-elevate__center > article > section > main > div.article__content-container"
        ),
        cronSchedule = "0 46 8 * * ?", // every day at 8:10 AM
      ),
      OpenAIConfig(
        apiKey =
          "your-openai-api-key-here",
        taxonomyIds = Set(
          "53",
          "123",
          "223",
          "239",
          "1020",
          "1013",
          "1021",
          "1019",
          "1216",
          "1217",
          "483",
        ),
      ),
    ))

    "Initialize successfully" in {
      spawn(PublisherSite("publisher-site", fetchConfig))

      Thread.sleep(Integer.MAX_VALUE)
    }
  }
}
