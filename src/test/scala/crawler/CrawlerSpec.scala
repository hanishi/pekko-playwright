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
      spawn(Crawler(
        "edition.cnn.com",
        3,
        5,
        "https://edition.cnn.com/sport",
        "\\/2025\\/07\\/\\d+\\/[^ ]*",
        "body > div.layout__content-wrapper.layout-with-rail__content-wrapper > section.layout__wrapper.layout-with-rail__wrapper > section.layout__main-wrapper.layout-with-rail__main-wrapper > section.layout__main.layout-with-rail__main > article > section > main",
        testProbe.ref,
      ))
      while (true) {
        val content = testProbe.receiveMessage(Duration(5, "minutes"))
        println(s"${content.url}, ${content.text}")
      }
    }
  }
}
