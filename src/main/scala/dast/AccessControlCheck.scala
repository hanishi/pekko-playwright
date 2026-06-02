package dast

import scala.util.Try

/** Pure logic for the spec-driven access-control / IDOR probe (CLAUDE.md
  * sections 0.4 and 5).
  *
  * Unlike the automatic checks, this is operator-driven: access-control bugs
  * need identity context (who is asking) and an oracle (what counts as seeing
  * data that should be restricted). The operator supplies both in a spec --
  * captured sessions (we never auto-log-in, per section 5) and, per case, a
  * `mustContain` discriminator proving the response holds restricted data.
  *
  * A finding is confirmed only when a request made under the case's identity
  * returns a 2xx whose body contains that discriminator. This covers IDOR
  * (attacker identity + victim's object), missing function-level auth
  * (unauthenticated identity + protected URL), and privilege escalation
  * (low-priv identity + high-priv URL).
  *
  * The HTTP requests live in [[AccessControlProbe]]; parsing and the confirm
  * decision here are pure and tested.
  */
object AccessControlCheck:

  /** An operator-configured login: the scanner submits this one form (per the
    * §5 authenticated-scan carve-out) to mint a session. Credentials are
    * operator-supplied; fields are detected deterministically.
    */
  final case class Login(loginUrl: String, username: String, password: String)

  /** A caller identity. Either a pre-captured `cookie` (raw Cookie header
    * value) / `headers` (bearer tokens etc.), or a `login` the scanner performs
    * to obtain the cookie. Absent identity = unauthenticated.
    */
  final case class Identity(
      cookie: Option[String],
      headers: Map[String, String],
      login: Option[Login] = None,
  )

  /** One access-control assertion: requesting `url` as `identity` (None =
    * unauthenticated) must NOT return `mustContain` -- if it does, access that
    * should be restricted was granted.
    */
  final case class AccessCase(
      name: String,
      url: String,
      identity: Option[String],
      mustContain: String,
  )

  final case class AccessSpec(
      identities: Map[String, Identity],
      cases: Seq[AccessCase],
  )

  /** Confirmed when the response succeeded (2xx) and leaked the discriminator.
    * A 3xx (e.g. redirect to login) or 401/403 is correctly NOT a hit.
    */
  def confirms(status: Int, body: String, mustContain: String): Boolean =
    status >= 200 && status <= 299 && body.contains(mustContain)

  def toFinding(c: AccessCase): Finding =
    val who = c.identity.getOrElse("unauthenticated")
    Finding(
      kind = FindingKind.BrokenAccessControl,
      severity = Severity.High,
      evidence = s"'${c.name}': request to ${c
          .url} as $who returned data that should be restricted",
      reproducible = true,
      replay = s"access case='${c.name}' as=$who url=${c.url}",
    )

  /** Parse a JSON spec. `Left` carries a human message on any malformed input
    * or a case referencing an unknown identity.
    */
  def parseSpec(jsonStr: String): Either[String, AccessSpec] = Try {
    val j = ujson.read(jsonStr)
    val identities = j("identities").obj.map { (name, v) =>
      name -> Identity(
        cookie = v.obj.get("cookie").map(_.str),
        headers = v.obj.get("headers")
          .map(_.obj.map((k, hv) => k -> hv.str).toMap).getOrElse(Map.empty),
        login = v.obj.get("login").map { l =>
          Login(l("loginUrl").str, l("username").str, l("password").str)
        },
      )
    }.toMap
    val cases = j("cases").arr.map { c =>
      AccessCase(
        name = c("name").str,
        url = c("url").str,
        identity = c.obj.get("identity")
          .flatMap(v => if v.isNull then None else Some(v.str)),
        mustContain = c("mustContain").str,
      )
    }.toSeq
    cases.flatMap(_.identity).foreach { ref =>
      require(
        identities.contains(ref),
        s"case references unknown identity '$ref'",
      )
    }
    AccessSpec(identities, cases)
  }.toEither.left.map(e => Option(e.getMessage).getOrElse(e.toString))
