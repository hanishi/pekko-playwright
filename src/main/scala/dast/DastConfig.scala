package dast

import scala.util.Try

/** Settings lookup for the scanner, so a run needs no `export`/`set -a` dance.
  *
  * Resolution order for each key: real environment variable (so an inline
  * `VAR=value sbt ...` still wins), then a dotenv-style file (default
  * `.env.local`, override with `DAST_ENV_FILE`), then JVM system properties.
  * Put `ANTHROPIC_API_KEY`, `DAST_AUTHORIZED_HOSTS`, `DAST_MAX_PAGES`, etc. in
  * `.env.local` (gitignored) and just run.
  */
object DastConfig:

  /** Parse dotenv content: `KEY=value` per line, `#` comments and blanks
    * ignored, optional leading `export `, surrounding quotes stripped. Pure.
    */
  private[dast] def parse(content: String): Map[String, String] = content
    .linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#"))
    .flatMap { raw =>
      val line = if raw.startsWith("export ") then raw.drop(7).trim else raw
      line.split("=", 2) match
        case Array(k, v) if k.trim.nonEmpty => Some(k.trim -> unquote(v.trim))
        case _ => None
    }.toMap

  private def unquote(s: String): String =
    if s.length >= 2 &&
      ((s.startsWith("\"") && s.endsWith("\"")) ||
        (s.startsWith("'") && s.endsWith("'")))
    then s.substring(1, s.length - 1)
    else s

  private def envFilePath: String = sys.env.get("DAST_ENV_FILE").map(_.trim)
    .filter(_.nonEmpty).getOrElse(".env.local")

  private lazy val fromFile: Map[String, String] =
    val f = new java.io.File(envFilePath)
    if !f.isFile then Map.empty
    else
      Try {
        val src = scala.io.Source.fromFile(f, "UTF-8")
        try parse(src.mkString)
        finally src.close()
      }.getOrElse(Map.empty)

  /** First non-empty of: env var, dotenv file, system property. */
  def get(name: String): Option[String] = sys.env.get(name)
    .orElse(fromFile.get(name)).orElse(sys.props.get(name)).map(_.trim)
    .filter(_.nonEmpty)

  def getInt(name: String, default: Int): Int = get(name).flatMap(_.toIntOption)
    .getOrElse(default)
