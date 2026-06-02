package dast

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Where a payload is placed in a request. Sealed so the set of injection
  * surfaces stays closed and auditable. This slice ships the reflected
  * query-parameter case; form fields, headers, and DOM points are follow-ups.
  */
sealed trait InjectionPoint:
  /** A short, stable description for evidence and replay handles. */
  def describe: String

  /** Build the request URL that carries `value` at this injection point. */
  def placeInto(baseUrl: String, value: String): String

object InjectionPoint:

  /** Reflected injection via a URL query parameter `name`. */
  final case class QueryParam(name: String) extends InjectionPoint:

    def describe: String = s"query param '$name'"

    def placeInto(baseUrl: String, value: String): String =
      val uri = new java.net.URI(baseUrl)
      val nameEnc = enc(name)
      val valEnc = enc(value)
      val kept = Option(uri.getRawQuery)
        .map(_.split("&").toIndexedSeq.filter(_.nonEmpty)).getOrElse(Seq.empty)
        .filterNot(p =>
          p.split("=", 2)(0) == nameEnc || p.split("=", 2)(0) == name,
        )
      val query = (kept :+ s"$nameEnc=$valEnc").mkString("&")

      val sb = new StringBuilder
      Option(uri.getScheme).foreach(s => sb.append(s).append("://"))
      Option(uri.getRawAuthority).foreach(sb.append)
      Option(uri.getRawPath).foreach(sb.append)
      sb.append("?").append(query)
      Option(uri.getRawFragment).foreach(f => sb.append("#").append(f))
      sb.toString

  private def enc(s: String): String = URLEncoder
    .encode(s, StandardCharsets.UTF_8)
