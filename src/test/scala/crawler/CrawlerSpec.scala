package crawler

import scala.concurrent.duration.Duration

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.scalatest.wordspec.AnyWordSpecLike

import crawler.Crawler.PageContent

class CrawlerSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "A Crawler" should {

    "crawl the url specified" in {
      val testProbe = TestProbe[PageContent]()
      val crawlerConfig = CrawlerConfig(
        domain = "edition.cnn.com",
        concurrency = 5,
        maxDepth = 3,
        seedUrl = "https://edition.cnn.com/sport",
        hostRegex = "\\/2025\\/08\\/\\d+\\/[^ ]*",
        targetElements = Array(
          "#maincontent",
          "body > div.layout-article-elevate__content-wrapper.layout__content-wrapper > section.layout__wrapper.layout-article-elevate__wrapper > section.layout__main.layout-article-elevate__main > section.layout__center.layout-article-elevate__center > article > section > main > div.article__content-container",
        ),
        cronSchedule = "",
      )
      spawn(Crawler(crawlerConfig, testProbe.ref))
      while (true) {
        val content = testProbe.receiveMessage(Duration(5, "minutes"))
        println(s"${content.url}, ${content.text}")
      }
    }
  }
}
