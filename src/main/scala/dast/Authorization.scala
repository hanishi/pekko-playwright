package dast

/** An operator's authorization to actively test a target (CLAUDE.md section 5).
  *
  * Active probing mutates target state, so it is gated on an explicit record:
  * `allowActive` is the master opt-in (default off, observe-only), and
  * `authorizedHosts` is the exact set of hosts in scope. Host match is exact on
  * purpose: authorizing `example.com` does not authorize `api.example.com`. To
  * include a subdomain, list it. `note` records the human scope reference
  * (ticket, signed-scope id) for the audit trail.
  */
final case class Authorization(
    authorizedHosts: Set[String],
    allowActive: Boolean,
    note: String = "",
)

object Authorization:

  /** The default: passive capture only, no active probing anywhere. */
  val ObserveOnly: Authorization = Authorization(Set.empty, allowActive = false)

  /** Authorize active testing for the given exact hosts. */
  def active(hosts: String*): Authorization =
    Authorization(hosts.iterator.map(_.trim.toLowerCase).filter(_.nonEmpty).toSet, allowActive = true)
