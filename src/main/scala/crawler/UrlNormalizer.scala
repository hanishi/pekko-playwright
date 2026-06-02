package crawler

import java.net.URI
import scala.util.Try

object UrlNormalizer:

  private val TrackingParams: Set[String] = Set(
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "fbclid", "gclid", "msclkid", "mc_cid", "mc_eid",
  )

  def normalize(url: String): String =
    Try {
      val uri = new URI(url.trim)
      val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("https")
      Option(uri.getHost).map(_.toLowerCase) match {
        case None => url
        case Some(host) =>
          val port = uri.getPort match {
            case -1 => ""
            case 80 if scheme == "http" => ""
            case 443 if scheme == "https" => ""
            case p => s":$p"
          }
          val path = Option(uri.getRawPath) match {
            case None | Some("") => "/"
            case Some(p) =>
              if (p.length > 1 && p.endsWith("/")) p.dropRight(1) else p
          }
          // Sort query params and strip tracking params
          val query = Option(uri.getRawQuery).map { q =>
            q.split("&")
              .filter(_.nonEmpty)
              .map { param =>
                val parts = param.split("=", 2)
                (parts(0), if (parts.length > 1) parts(1) else "")
              }
              .filterNot { case (key, _) => TrackingParams.contains(key.toLowerCase) }
              .sortBy(_._1)
              .map { case (k, v) => if (v.isEmpty) k else s"$k=$v" }
              .mkString("&")
          }.filter(_.nonEmpty).map("?" + _).getOrElse("")

          // Drop fragment
          s"$scheme://$host$port$path$query"
      }
    }.getOrElse(url)
