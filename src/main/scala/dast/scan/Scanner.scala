package dast.scan

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.util.Timeout

import crawler.BrowserResource
import crawler.UrlNormalizer
import crawler.pool.ResourcePool
import crawler.pool.ResourcePool.Pool
import crawler.pool.ResourcePool.asPool
import crawler.pool.ResourcePool.submit
import dast.AccessControlCheck.AccessSpec
import dast.AccessControlCheck.Identity
import dast.AccessControlProbe
import dast.ActionClass
import dast.Authorization
import dast.CaptureOp
import dast.ConsentGate
import dast.DastConfig
import dast.Finding
import dast.GateDecision
import dast.IdorProbe
import dast.LoginOp
import dast.Oast
import dast.OastListener
import dast.OpenRedirectProbe
import dast.ProbeOp
import dast.SinkScanOp
import dast.SqlInjectionProbe
import dast.SsrfProbe
import dast.analyzer.ClaudeAnalyzer
import dast.analyzer.IdorPlanner

/** Assembles the real, pool- and Claude-backed effects and spawns an
  * orchestrator. This is wiring, not logic: it runs only against a live target
  * (and a key for the probe path), so it is not unit tested — the orchestrators
  * are tested with stubbed effects instead.
  *
  * The browser pool is a [[crawler.pool.ResourcePool]] of [[BrowserResource]]
  * on the `session-pinned-dispatcher`; all browser work goes through
  * `pool.submit` to stay on the pinned thread (CLAUDE.md section 0.1). The pool
  * is built with `stealth = false` so the scanner is identifiable, not evasive
  * (section 5).
  */
object Scanner:

  /** Spawn a browser pool and a single-URL scan orchestrator wired to it. */
  def spawn(
      ctx: ActorContext[?],
      auth: Authorization = Authorization.ObserveOnly,
      poolSize: Int = 2,
      navTimeoutMs: Int = 30000,
  )(using
      ActorSystem[?],
      ExecutionContext,
  ): ActorRef[ScanOrchestrator.Command] =
    val pool = buildPool(ctx, poolSize, navTimeoutMs)
    val oast = buildOast()
    ctx.spawn(
      ScanOrchestrator(auth, scanEffects(pool, auth, navTimeoutMs, oast)),
      "dast-scan-orchestrator",
    )

  /** Spawn a site-scan orchestrator: discover in-scope URLs from the seed
    * (read-only crawl) and run a full scan on each.
    */
  def spawnSite(
      ctx: ActorContext[?],
      auth: Authorization = Authorization.ObserveOnly,
      poolSize: Int = 2,
      navTimeoutMs: Int = 30000,
      maxDepth: Int = 2,
      maxPages: Int = 20,
      perScanTimeout: FiniteDuration = 5.minutes,
  )(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): ActorRef[SiteScanOrchestrator.Command] =
    val pool = buildPool(ctx, poolSize, navTimeoutMs)
    val oast = buildOast()
    val counter = new AtomicInteger(0)
    given Timeout = Timeout(perScanTimeout)

    val effects = SiteScanOrchestrator.Effects(
      discover = seed => discover(pool, seed, maxDepth, maxPages),
      scanOne = url =>
        val ref = system.systemActorOf(
          ScanOrchestrator(auth, scanEffects(pool, auth, navTimeoutMs, oast)),
          s"dast-scan-${counter.incrementAndGet()}",
        )
        ref.ask[ScanOrchestrator.ScanComplete](ScanOrchestrator.Start(url, _))
          .map(_.findings),
    )
    ctx.spawn(SiteScanOrchestrator(effects, maxPages), "dast-site-orchestrator")

  /** Run a spec-driven access-control / IDOR scan. Identities configured with a
    * `login` get a browser-minted session first (gated, §5 carve-out); the
    * browser pool is only built when some identity needs to log in -- a
    * cookie-only spec stays browser-free.
    */
  def runAccess(
      ctx: ActorContext[?],
      spec: AccessSpec,
      auth: Authorization,
      navTimeoutMs: Int = 30000,
  )(using ActorSystem[?], ExecutionContext): Future[Vector[Finding]] =
    val needsLogin = spec.identities.values.exists(_.login.isDefined)
    val resolved =
      if !needsLogin then Future.successful(spec)
      else resolveLogins(buildPool(ctx, 1, navTimeoutMs), spec, auth)
    resolved.flatMap(s => AccessControlProbe.scan(s, auth))

  /** Run an LLM-planned IDOR scan of one authenticated URL: resolve the
    * identity's session (logging in if configured), observe the page, let the
    * planner propose tests, and confirm each deterministically.
    */
  def runIdor(
      ctx: ActorContext[?],
      url: String,
      identity: Identity,
      auth: Authorization,
      navTimeoutMs: Int = 30000,
  )(using ActorSystem[?], ExecutionContext): Future[Vector[Finding]] =
    resolveCookie(ctx, identity, auth, navTimeoutMs).flatMap { cookie =>
      IdorProbe.scan(url, cookie, auth, obs => IdorPlanner.plan(obs))
    }

  /** A single identity's session cookie: minted by login if configured (gated,
    * on the pool), else the static cookie. Login failure falls back to static.
    */
  private def resolveCookie(
      ctx: ActorContext[?],
      identity: Identity,
      auth: Authorization,
      navTimeoutMs: Int,
  )(using ActorSystem[?], ExecutionContext): Future[Option[String]] =
    identity.login match
      case None => Future.successful(identity.cookie)
      case Some(login) =>
        ConsentGate.decide(auth, ActionClass.Active, login.loginUrl) match
          case GateDecision.Deny(reason) =>
            ctx_log_skip("login", reason)
            Future.successful(identity.cookie)
          case GateDecision.Permit => buildPool(ctx, 1, navTimeoutMs)
              .submit(r =>
                LoginOp.login(r, login.loginUrl, login.username, login.password),
              ).map {
                case Right(c) => Some(c)
                case Left(_) => identity.cookie
              }

  /** Replace each login-configured identity's cookie with one minted by
    * actually logging in (on the pinned thread, gated by host). A failed or
    * denied login leaves the identity unchanged (its cases simply will not
    * confirm).
    */
  private def resolveLogins(
      pool: Pool[BrowserResource],
      spec: AccessSpec,
      auth: Authorization,
  )(using ExecutionContext): Future[AccessSpec] =
    val resolved = spec.identities.toSeq.map { (name, identity) =>
      identity.login match
        case None => Future.successful(name -> identity)
        case Some(login) =>
          ConsentGate.decide(auth, ActionClass.Active, login.loginUrl) match
            case GateDecision.Deny(reason) =>
              ctx_log_skip(name, reason)
              Future.successful(name -> identity)
            case GateDecision.Permit => pool.submit(r =>
                LoginOp.login(r, login.loginUrl, login.username, login.password),
              ).map {
                case Right(cookie) => name -> identity.copy(cookie = Some(cookie))
                case Left(_) => name -> identity
              }
    }
    Future.sequence(resolved).map(pairs => spec.copy(identities = pairs.toMap))

  private def ctx_log_skip(name: String, reason: String): Unit = org.slf4j
    .LoggerFactory.getLogger("dast.scan.Scanner")
    .info("Login for identity '{}' skipped: {}", name, reason)

  private def buildPool(
      ctx: ActorContext[?],
      poolSize: Int,
      navTimeoutMs: Int,
  ): Pool[BrowserResource] =
    val poolRef = ctx.spawn(
      ResourcePool[BrowserResource](
        size = poolSize,
        make = i =>
          new BrowserResource(
            i,
            None,
            BrowserResource.Settings(
              navigationTimeoutMs = navTimeoutMs,
              stealth = false,
              userAgent =
                Some("pekko-dast-scanner/0.1 (+authorized security testing)"),
            ),
          ),
      ),
      "dast-browser-pool",
    )
    poolRef.asPool[BrowserResource]

  private def scanEffects(
      pool: Pool[BrowserResource],
      auth: Authorization,
      navTimeoutMs: Int,
      oast: Option[Oast],
  )(using ActorSystem[?], ExecutionContext): ScanOrchestrator.Effects =
    ScanOrchestrator.Effects(
      capture = url => pool.submit(r => CaptureOp.capture(r, url)),
      analyze = context => ClaudeAnalyzer.analyze(context),
      probe = (baseUrl, point, payloadId, marker) =>
        pool.submit(r =>
          ProbeOp.probe(r, auth, baseUrl, point, payloadId, marker, navTimeoutMs),
        ).map(_.toOption.flatten),
      sinkScan = (baseUrl, source, marker) =>
        pool
          .submit(r => SinkScanOp.scan(r, baseUrl, source, marker, navTimeoutMs)),
      // HTTP-level, off the browser pool entirely (CLAUDE.md: the browser is
      // only for execution-confirmed XSS; redirects/SQLi are HTTP concerns).
      redirectScan = baseUrl => OpenRedirectProbe.scan(baseUrl),
      sqlScan = baseUrl => SqlInjectionProbe.scan(baseUrl),
      // SSRF needs an out-of-band listener; skipped (and so never guessed) when
      // DAST_OAST_BASE_URL is unset.
      ssrfScan = oast match
        case Some(o) => baseUrl => SsrfProbe.scan(baseUrl, o)
        case None => _ => scala.concurrent.Future.successful(Vector.empty),
    )

  /** Build and bind an OAST listener if DAST_OAST_BASE_URL is configured. The
    * base URL must be reachable by the target (a tunnel / public address for a
    * real target; loopback for local testing). Returns None when unset, which
    * disables SSRF probing (no honest confirmation is possible without it).
    */
  private def buildOast()(using
      system: ActorSystem[?],
      ec: ExecutionContext,
  ): Option[Oast] = DastConfig.get("DAST_OAST_BASE_URL").flatMap { base =>
    val uri = new java.net.URI(base)
    val host = Option(uri.getHost).getOrElse("127.0.0.1")
    val port = if uri.getPort > 0 then uri.getPort else 80
    val listener = new OastListener(host, port)
    listener.start() // fire-and-forget; binds well before the first probe
    Some(listener)
  }

  /** Read-only, same-host BFS over the pool collecting anchor hrefs to
    * `maxDepth` / `maxPages`. Failures on a page yield no links (fail soft).
    */
  private def discover(
      pool: Pool[BrowserResource],
      seed: String,
      maxDepth: Int,
      maxPages: Int,
  )(using ExecutionContext): Future[Seq[String]] =
    val seedHost = Scope.hostOf(seed).getOrElse("")

    def linksOf(url: String): Future[Seq[String]] = pool
      .submit(r => r.withPage(url)((page, _) => hrefs(page)))
      .recover { case _ => Seq.empty }

    def loop(
        frontier: List[String],
        depth: Int,
        seen: Set[String],
        acc: Vector[String],
    ): Future[Seq[String]] =
      if depth > maxDepth || frontier.isEmpty || acc.size >= maxPages then
        Future.successful(acc)
      else
        Future.sequence(frontier.map(linksOf)).flatMap { results =>
          val next = results.flatten.map(UrlNormalizer.normalize)
            .filter(u => Scope.inScope(seedHost, u) && !seen.contains(u))
            .distinct
          loop(next.toList, depth + 1, seen ++ next, (acc ++ next).take(maxPages))
        }

    loop(List(seed), 0, Set(UrlNormalizer.normalize(seed)), Vector.empty)

  private def hrefs(page: com.microsoft.playwright.Page): Seq[String] = page
    .evaluate(
      "() => Array.from(document.querySelectorAll('a[href]')).map(a => a.href)",
    ).asInstanceOf[java.util.List[?]].asScala.iterator
    .collect { case s if s != null => s.toString }.toSeq
