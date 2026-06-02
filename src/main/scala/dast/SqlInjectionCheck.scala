package dast

import scala.util.Try

/** Pure logic for the SQL-injection probe (CLAUDE.md sections 0.4 and 4).
  *
  * Two deterministic confirmation techniques, no body-content guessing beyond
  * known DB error signatures:
  *
  *   - Error-based: appending a single quote makes a vulnerable backend emit a
  *     DB error. Confirmed only when an error signature appears that was NOT in
  *     the baseline response (so a page that always shows the text is not a
  *     false positive).
  *   - Time-based: a DB-specific `SLEEP`/`pg_sleep`/`WAITFOR` makes the
  *     response measurably slower. Confirmed only when the injected request is
  *     slower than the baseline by [[delayThresholdMs]] (execution proof, not
  *     text).
  *
  * The browser-free HTTP requests live in [[SqlInjectionProbe]]; the decisions
  * here (signature match, timing verdict, payload list) are pure and tested.
  */
object SqlInjectionCheck:

  /** Seconds the time-based payloads ask the DB to sleep. */
  val delaySeconds = 5

  /** A time-based hit must be at least this much slower than baseline. Set
    * comfortably below `delaySeconds * 1000` to tolerate jitter, well above
    * normal variance to avoid false positives.
    */
  val delayThresholdMs = 3500L

  /** Query-parameter names of a URL: the surfaces probed. Pure. */
  def paramNames(url: String): Seq[String] = Try(
    new java.net.URI(url).getRawQuery,
  ).toOption.flatMap(Option(_))
    .map(_.split("&").toSeq.filter(_.nonEmpty).map(_.split("=", 2)(0)).distinct)
    .getOrElse(Seq.empty)

  /** Substrings (lowercased) that only appear in a DB error, mapped to the
    * engine they implicate. Matched case-insensitively against the body.
    */
  private val errorSignatures: Seq[(String, String)] = Seq(
    "you have an error in your sql syntax" -> "MySQL",
    "warning: mysql" -> "MySQL",
    "mysql_fetch" -> "MySQL",
    "unclosed quotation mark after the character string" -> "MSSQL",
    "incorrect syntax near" -> "MSSQL",
    "microsoft sql server" -> "MSSQL",
    "syntax error at or near" -> "PostgreSQL",
    "pg_query" -> "PostgreSQL",
    "unterminated quoted string" -> "PostgreSQL",
    "quoted string not properly terminated" -> "Oracle",
    "ora-0" -> "Oracle",
    "sqlite3::" -> "SQLite",
    "sqlite error" -> "SQLite",
    "unrecognized token" -> "SQLite",
  )

  /** The DB implicated by an error signature in `body`, if any. */
  def detectError(body: String): Option[String] =
    val b = body.toLowerCase
    errorSignatures.collectFirst { case (sig, db) if b.contains(sig) => db }

  /** Error-based payload: break the surrounding quoting with a single quote. */
  def errorPayload(original: String): String = original + "'"

  /** Time-based payloads, DB-agnostic enough to hit the common engines. Each is
    * a (label, value) where the value goes into the parameter.
    */
  def timePayloads(original: String): Seq[(String, String)] =
    val s = delaySeconds
    Seq(
      "mysql-sleep" -> s"$original' AND SLEEP($s)-- -",
      "mysql-sleep-numeric" -> s"$original AND SLEEP($s)",
      "postgres-pg_sleep" -> s"$original'; SELECT pg_sleep($s)-- -",
      "mssql-waitfor" -> s"$original'; WAITFOR DELAY '0:0:$s'-- -",
    )

  /** True when the injected request was slower than baseline by the threshold.
    */
  def confirmsTiming(baselineMs: Long, injectedMs: Long): Boolean =
    injectedMs - baselineMs >= delayThresholdMs

  def errorFinding(point: InjectionPoint, db: String): Finding = Finding(
    kind = FindingKind.SqlInjection,
    severity = Severity.High,
    evidence = s"${point
        .describe} triggers a $db error when a quote is injected (error-based)",
    reproducible = true,
    replay = s"sqli ${point.describe} technique=error",
  )

  def timeFinding(point: InjectionPoint, label: String): Finding = Finding(
    kind = FindingKind.SqlInjection,
    severity = Severity.High,
    evidence = s"${point
        .describe} delays the response under a time-based payload ($label)",
    reproducible = true,
    replay = s"sqli ${point.describe} technique=time payload=$label",
  )
