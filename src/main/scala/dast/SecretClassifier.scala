package dast

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.Try

/** Structured classification of whether a single stored value looks like a
  * secret. Pure and conservative: it recognises shape (JWT structure, known
  * credential prefixes) or genuine randomness (high Shannon entropy over a
  * token-like charset), rather than flagging every base64 blob. This keeps
  * deterministic findings low in false positives (CLAUDE.md section 3).
  */
object SecretClassifier:

  enum Kind:
    case Jwt, KnownCredential, HighEntropyToken

  final case class Hit(kind: Kind, detail: String)

  /** Vendor token prefixes that are unambiguous on their own. */
  private val knownPrefixes: Seq[String] =
    Seq("sk-", "ghp_", "gho_", "github_pat_", "xoxb-", "xoxp-", "AKIA", "AIza", "ya29.")

  private val tokenCharset = "^[A-Za-z0-9_\\-+/=]+$".r

  def classify(raw: String): Option[Hit] =
    val v = raw.trim
    if looksLikeJwt(v) then Some(Hit(Kind.Jwt, "JWT structure"))
    else
      knownPrefixes.find(v.startsWith) match
        case Some(p) => Some(Hit(Kind.KnownCredential, s"known credential prefix '$p'"))
        case None =>
          val e = shannonEntropy(v)
          if isHighEntropyToken(v, e) then
            Some(Hit(Kind.HighEntropyToken, f"high-entropy token ($e%.1f bits/char)"))
          else None

  /** Three non-empty base64url segments whose header decodes to JSON naming an
    * algorithm. Avoids matching arbitrary dotted strings. */
  def looksLikeJwt(s: String): Boolean =
    val parts = s.split("\\.", -1)
    parts.length == 3 &&
    parts.forall(p => p.nonEmpty && p.matches("[A-Za-z0-9_-]+")) &&
    decodeBase64Url(parts(0)).exists(_.contains("\"alg\""))

  /** Genuine-looking randomness: long enough, token-shaped (no whitespace or
    * prose punctuation), mixing letters and digits, with high entropy. The
    * mixed-class requirement rejects long dictionary words and pure numeric ids. */
  def isHighEntropyToken(v: String, entropy: Double): Boolean =
    v.length >= 20 &&
    tokenCharset.matches(v) &&
    v.exists(_.isLetter) &&
    v.exists(_.isDigit) &&
    entropy >= 3.5

  /** Shannon entropy in bits per character. */
  def shannonEntropy(s: String): Double =
    if s.isEmpty then 0.0
    else
      val n = s.length.toDouble
      s.groupBy(identity).values.foldLeft(0.0) { (acc, occurrences) =>
        val p = occurrences.length / n
        acc - p * (math.log(p) / math.log(2))
      }

  private def decodeBase64Url(seg: String): Option[String] =
    val padded = seg.length % 4 match
      case 0 => seg
      case r => seg + ("=" * (4 - r))
    Try(new String(Base64.getUrlDecoder.decode(padded), StandardCharsets.UTF_8)).toOption
