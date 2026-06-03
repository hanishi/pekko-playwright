package dast

/** A minimal cookie jar for carrying session state across navigation hops.
  *
  * Multi-hop flows accumulate server state: a step's `Set-Cookie` must be
  * replayed on the next request (a login/search may rotate or add cookies).
  * Pure and immutable: `merge` folds in `Set-Cookie` values, `header` renders
  * the `Cookie` request header. Only the name=value pair of each cookie is
  * kept (attributes like Path/HttpOnly are irrelevant to replay here).
  */
final case class CookieJar(cookies: Map[String, String]):

  /** Fold in `Set-Cookie` header values from a response (later wins). */
  def merge(setCookies: Seq[String]): CookieJar =
    CookieJar(cookies ++ setCookies.flatMap(CookieJar.parseSetCookie))

  /** The `Cookie` request header value, or None when empty. */
  def header: Option[String] =
    if cookies.isEmpty then None
    else Some(cookies.map((k, v) => s"$k=$v").mkString("; "))

object CookieJar:

  val empty: CookieJar = CookieJar(Map.empty)

  /** Seed a jar from an existing `Cookie` header value (e.g. from login). */
  def fromHeader(header: Option[String]): CookieJar =
    CookieJar(header.map(parsePairs).getOrElse(Map.empty))

  private def parsePairs(s: String): Map[String, String] = s.split(";").toSeq
    .flatMap(pair =>
      pair.split("=", 2) match
        case Array(k, v) => Some(k.trim -> v.trim)
        case _ => None,
    ).toMap

  /** The name=value pair from a `Set-Cookie` value (before the first `;`). */
  private[dast] def parseSetCookie(setCookie: String): Option[(String, String)] =
    setCookie.split(";", 2).headOption.flatMap(_.split("=", 2) match
      case Array(k, v) => Some(k.trim -> v.trim)
      case _ => None,
    )
