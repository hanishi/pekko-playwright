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
        hostRegex = "\\/2025\\/07\\/\\d+\\/[^ ]*",
        targetElements = Array(
          "body > div.layout__content-wrapper.layout-with-rail__content-wrapper > section.layout__top.layout-with-rail__top > div.headline.headline--has-lowertext",
          "body > div.layout__content-wrapper.layout-with-rail__content-wrapper > " +
            "section.layout__wrapper.layout-with-rail__wrapper > section.layout__main-wrapper.layout-with-rail__main-wrapper > " +
            "section.layout__main.layout-with-rail__main > article > section > main",
        ),
        cronSchedule = "",
      )
      spawn(Crawler(
        crawlerConfig,
        testProbe.ref,
      ))
      while (true) {
        val content = testProbe.receiveMessage(Duration(5, "minutes"))
        println(s"${content.url}, ${content.text}")
      }
    }
  }
}
