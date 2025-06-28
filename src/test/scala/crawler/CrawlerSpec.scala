package crawler

import Crawler.PageContent
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.Duration

class CrawlerSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "A Crawler" should {

    "crawl the url specified 3" in {
      val testProbe = TestProbe[PageContent]()
      spawn(Crawler(
        "edition.cnn.com",
        3,
        5,
        "https://edition.cnn.com/",
        "\\/2025\\/06\\/27\\/[^ ]*",
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
