package dast

import scala.util.Try
import scala.util.matching.Regex

/** Pure HTML form extraction for the navigation step. Regex-based (no DOM
  * dependency): good enough to enumerate the forms a search/filter page exposes
  * so the navigator can decide which to submit. Browser-free and unit tested.
  */
object FormParse:

  /** A form the navigator may consider submitting. `method` is lowercased
    * (default "get"); `action` is resolved to an absolute URL; `fields` are
    * (name, type) for named inputs; `submitText` aggregates button / submit
    * labels (used by the deny-list).
    */
  final case class FormInfo(
      method: String,
      action: String,
      fields: Seq[(String, String)],
      submitText: String,
  )

  private val formRe: Regex = "(?is)<form\\b([^>]*)>(.*?)</form>".r
  private val inputRe: Regex = "(?is)<(input|select|textarea)\\b([^>]*)>".r
  private val buttonRe: Regex = "(?is)<button\\b[^>]*>(.*?)</button>".r

  def parse(html: String, baseUrl: String): Seq[FormInfo] = formRe
    .findAllMatchIn(html).map { m =>
      val attrs = m.group(1)
      val body = m.group(2)
      val method = attr(attrs, "method").getOrElse("get").toLowerCase
      val action = attr(attrs, "action").flatMap(resolve(baseUrl, _))
        .getOrElse(baseUrl)
      val fields = inputRe.findAllMatchIn(body).flatMap { im =>
        val a = im.group(2)
        attr(a, "name").filter(_.nonEmpty)
          .map(n => n -> attr(a, "type").getOrElse("text").toLowerCase)
      }.toSeq.distinctBy(_._1)
      val submitText =
        (buttonRe.findAllMatchIn(body).map(_.group(1)) ++
          inputRe.findAllMatchIn(body).filter { im =>
            attr(im.group(2), "type").map(_.toLowerCase).contains("submit")
          }.flatMap(im => attr(im.group(2), "value"))).mkString(" ")
          .replaceAll("(?is)<[^>]+>", " ").trim
      FormInfo(method, action, fields, submitText)
    }.toSeq

  private def attr(tag: String, name: String): Option[String] =
    s"""(?is)\\b${Regex.quote(name)}\\s*=\\s*["']([^"']*)["']""".r
      .findFirstMatchIn(tag).map(_.group(1))

  private def resolve(base: String, href: String): Option[String] =
    Try(new java.net.URI(base).resolve(href).toString).toOption
