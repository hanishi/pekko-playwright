package adtech.taxonomy

import scala.concurrent.ExecutionContext

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import adtech.taxonomy.IABTaxonomyAssistant.Response
import adtech.taxonomy.IABTaxonomyAssistant.responseFormat
import spray.json.*

class OpenAIClientSpec extends AnyFlatSpec with Matchers {
  given ec: ExecutionContext = system.executionContext

  given system: ActorSystem[_] = testKit.system

  given testKit: ActorTestKit = ActorTestKit()

  "OpenAIClient" should "return a structured output" in {
    val apiKey = "your-openai-api-key-here"

    val categoryCandidates = Set(
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
    )
    val url = "https://www.sponichi.co.jp/"
    val text =
      """FLPA Executive Director Lloyd Howell Jr. during an NFLPA news conference at the Super Bowl LIX media center in New Orleans on February 5, 2025. Kirby Lee/USA Today Sports/Imagn Images/Reuters/File CNN — NFL Players Association (NFLPA) Executive Director Lloyd Howell Jr. has stepped down from his role amid various controversies, including reports on Friday that he expensed the union for trips to strip clubs. “It’s clear that my leadership has become a distraction to the important work the NFLPA advances every day,” Howell said in a statement late on Thursday night. “For this reason, I have informed the NFLPA Executive Committee that I am stepping down as Executive Director of the NFLPA and Chairman of the Board of NFL Players effective immediately. I hope this will allow the NFLPA to maintain its focus on its player members ahead of the upcoming season.” Ad Feedback Ad Feedback When contacted by CNN, the NFLPA declined to comment. CNN has also reached out to Howell Jr., through the union. According to Reuters, which cited ESPN, receipts from a trip taken by Howell in Novemember 2023 showed that the NFLPA was billed for a car service and other costs by Tootsie’s Cabaret in Miami, which claims to be the largest strip club in the world. Citing ESPN, Reuters also reported that a second strip club bill was also reviewed by the NFLPA’s lawyers, relating to a reported trip in February during the NFLPA summit and itemizing $2,426 in charges, which included cash withdrawals from a club ATM ranging between $200 and $525. Ad Feedback Howell reportedly earned between $3.5 million and $4 million in his role with the NFLPA. RELATED ARTICLE Ashtrays on planes, bad odors and ‘top-down problems’: Takeaways from the NFL Players Association’s annual report card That was not the only controversy which Howell has become embroiled in during the last two weeks. Citing ESPN, Reuters reported that alongside his commitments to the NFLPA, Howell also held a part-time consultancy role with The Carlyle Group, a private equity firm that the NFL reportedly approved to pursue minority ownership in NFL franchises. This has been viewed by many as a serious conflict of interest. On top of that, Reuters – citing ESPN – also reported that Howell had decided to keep NFL players in the dark over an arbitration ruling on suspected collusion between team owners. Amid concerns that owners were coming together to reduce the growth of quarterback contracts, arbitrator Christopher Droney ruled there was not sufficient evidence to support the claims, per Reuters. However, he added that “by a clear preponderance of the evidence,” the NFL’s general counsel, along with commissioner Roger Goodell, did encourage owners to restrict guaranteed money in player contracts, the agency reported. According to reports, Howell and the NFLPA had a confidentiality agreement with the NFL designed to stop the full report from leaking. Although Howell did brief players on the matter, he did not give them copies of the report, according to Reuters, which cited ESPN. “I am proud of what we have been able to accomplish at the NFLPA over the past two years,” Howell added in his statement. “I will be rooting for the players from the sidelines as loud as ever, and I know the NFLPA will continue to ensure that players remain firmly at the center of football’s future."""
        .stripMargin

    val client = new IABTaxonomyAssistant(apiKey, categoryCandidates)
    client.analyzeTaxonomy(url, text).map(response => println(response))
    Thread.sleep(100000)
  }

  "response from openai" should "be unmarshalled into Response" in {
    val jsonString =
      """{
      "id": "chatcmpl-B19rmcJPRrhPiaN1XxnxgGcr8ueWg",
      "object": "chat.completion",
      "created": 1739615954,
      "model": "gpt-4o-mini-2024-07-18",
      "choices": [
        {
          "index": 0,
          "message": {
            "role": "assistant",
            "content": "{\"selected_taxonomy_ids\":[{\"id\":\"93\",\"confidence\":0.9},{\"id\":\"106\",\"confidence\":0.8},{\"id\":\"58\",\"confidence\":0.7},{\"id\":\"95\",\"confidence\":0.6},{\"id\":\"100\",\"confidence\":0.6},{\"id\":\"68\",\"confidence\":0.5}]}",
            "refusal": null
          },
          "logprobs": null,
          "finish_reason": "stop"
        }
      ],
      "usage": {
        "prompt_tokens": 1312,
        "completion_tokens": 69,
        "total_tokens": 1381,
        "prompt_tokens_details": {
          "cached_tokens": 1152,
          "audio_tokens": 0
        },
        "completion_tokens_details": {
          "reasoning_tokens": 0,
          "audio_tokens": 0,
          "accepted_prediction_tokens": 0,
          "rejected_prediction_tokens": 0
        }
      },
      "service_tier": "default",
      "system_fingerprint": "fp_00428b782a"
    }""".stripMargin

    val response = jsonString.parseJson.convertTo[Response].choices.headOption
      .map(_.message).getOrElse(List.empty)
    println(response)
  }

}
