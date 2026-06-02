package dast

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Where a payload is placed in a request. Sealed so the set of injection
  * surfaces stays closed and auditable. All cases are GET-delivered and
  * non-destructive: form fields / POST bodies are deliberately excluded because
  * CLAUDE.md section 5 forbids auto-submitting forms.
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
      rebuild(uri, query = Some(query), fragment = Option(uri.getRawFragment))

  /** Injection via the URL fragment (`#...`). Not sent to the server; the
    * surface for client-side / DOM XSS that reads `location.hash`.
    */
  case object Fragment extends InjectionPoint:

    def describe: String = "URL fragment"

    def placeInto(baseUrl: String, value: String): String =
      val uri = new java.net.URI(baseUrl)
      rebuild(uri, query = Option(uri.getRawQuery), fragment = Some(enc(value)))

  /** Injection into the path segment at `index` (0-based over the `/`-split
    * path; segment 0 is the empty string before the leading slash, so real
    * segments start at 1). Out-of-range appends a new segment.
    */
  final case class PathSegment(index: Int) extends InjectionPoint:

    def describe: String = s"path segment $index"

    def placeInto(baseUrl: String, value: String): String =
      val uri = new java.net.URI(baseUrl)
      val segs = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
        .split("/", -1).toIndexedSeq
      val valEnc = enc(value)
      val newSegs =
        if index >= 1 && index < segs.size then segs.updated(index, valEnc)
        else segs :+ valEnc
      val path = newSegs.mkString("/")
      rebuild(
        uri,
        pathOverride = Some(if path.startsWith("/") then path else "/" + path),
        query = Option(uri.getRawQuery),
        fragment = Option(uri.getRawFragment),
      )

  private def rebuild(
      uri: java.net.URI,
      query: Option[String],
      fragment: Option[String],
      pathOverride: Option[String] = None,
  ): String =
    val sb = new StringBuilder
    Option(uri.getScheme).foreach(s => sb.append(s).append("://"))
    Option(uri.getRawAuthority).foreach(sb.append)
    sb.append(pathOverride.orElse(Option(uri.getRawPath)).getOrElse(""))
    query.filter(_.nonEmpty).foreach(q => sb.append("?").append(q))
    fragment.foreach(f => sb.append("#").append(f))
    sb.toString

  private def enc(s: String): String = URLEncoder
    .encode(s, StandardCharsets.UTF_8)
