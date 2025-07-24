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
          "body > div.layout__content-wrapper.layout-with-rail__content-wrapper > section.layout__top.layout-with-rail__top > div.headline.headline--has-lowertext",
          "body > div.layout__content-wrapper.layout-with-rail__content-wrapper > " +
            "section.layout__wrapper.layout-with-rail__wrapper > section.layout__main-wrapper.layout-with-rail__main-wrapper > " +
            "section.layout__main.layout-with-rail__main > article > section > main",
        ),
        cronSchedule = "0 28 1 * * ?",
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
