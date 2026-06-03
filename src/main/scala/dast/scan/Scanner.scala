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
import dast.ActionGuard
import dast.AuthCrawl
import dast.Authorization
import dast.CaptureOp
import dast.ConsentGate
import dast.ContentIdorProbe
import dast.CookieJar
import dast.DastConfig
import dast.Finding
import dast.FormParse
import dast.GateDecision
import dast.IdorPlan
import dast.IdorProbe
import dast.LoginOp
import dast.NavLoop
import dast.NavStep
import dast.Oast
import dast.OastListener
import dast.OpenRedirectProbe
import dast.ProbeOp
import dast.SinkScanOp
import dast.SqlInjectionProbe
import dast.SsrfProbe
import dast.analyzer.ClaudeAnalyzer
import dast.analyzer.ContentIdorPlanner
import dast.analyzer.IdorPlanner
import dast.analyzer.NavStepPlanner

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

  /** Run an LLM-planned IDOR scan from a seed: resolve the identity's session
    * (logging in if configured), crawl same-host pages authenticated, and on
    * each param-bearing page let the planner propose tests that deterministic
    * code confirms. `maxPages`/`maxDepth` bound the crawl; param-less pages are
    * crawled for links but not planned (no object reference, no LLM call).
    */
  def runIdor(
      ctx: ActorContext[?],
      seed: String,
      identity: Identity,
      auth: Authorization,
      navTimeoutMs: Int = 30000,
      maxDepth: Int = 2,
      maxPages: Int = 20,
      maxHops: Int = 4,
      postBudget: Int = 3,
  )(using ActorSystem[?], ExecutionContext): Future[Vector[Finding]] =
    resolveCookie(ctx, identity, auth, navTimeoutMs).flatMap { cookie =>
      AuthCrawl.discover(seed, cookie, maxDepth, maxPages).flatMap {
        discovered =>
          val pages = (seed +: discovered).distinct
          // LLM-driven multi-hop navigation: from each page the model submits
          // forms / follows links (gated by ActionGuard, cookie jar threaded)
          // to reach object listings a link crawl cannot. Reached URLs join the
          // IDOR target set.
          val jar = CookieJar.fromHeader(cookie)
          Future.sequence(pages.map(p =>
            NavLoop
              .explore(p, jar, auth, NavStepPlanner.plan, maxHops, postBudget),
          )).flatMap { navResults =>
            val navUrls = navResults.flatten
            val targets = (pages ++ navUrls).distinct
              .filter(u => IdorPlan.queryParams(u).nonEmpty)
            org.slf4j.LoggerFactory.getLogger("dast.scan.Scanner").info(
              "IDOR from {}: {} crawled + {} via navigation, {} to plan",
              seed,
              discovered.size,
              navUrls.size,
              targets.size,
            )
            Future.sequence(
              targets.map(u => IdorProbe.scan(u, cookie, auth, IdorPlanner.plan)),
            ).map(_.flatten.toVector)
          }
      }
    }

  /** Run an IDOR scan of an SPA target by driving a real browser: the model
    * navigates (clicks links / submits forms) through the app while we record
    * every request it makes, then we IDOR-plan the captured (param-bearing,
    * same-host) API endpoints. This needs no pool -- one dedicated, thread-
    * affine [[ResourceSession]] holds a single page alive across the LLM-paced
    * hops (the LLM call runs off the pinned thread, between submits).
    */
  def runSpaIdor(
      ctx: ActorContext[?],
      url: String,
      attacker: Identity,
      victim: Option[Identity],
      auth: Authorization,
      navTimeoutMs: Int = 30000,
      maxHops: Int = 6,
      postBudget: Int = 3,
  )(using ActorSystem[?], ExecutionContext): Future[Vector[Finding]] =
    ConsentGate.decide(auth, ActionClass.Active, url) match
      case GateDecision.Deny(_) => Future.successful(Vector.empty)
      case GateDecision.Permit =>
        val seedHost = Scope.hostOf(url).getOrElse("")
        val log = org.slf4j.LoggerFactory.getLogger("dast.scan.Scanner")
        // Spawn both sessions NOW, on the actor thread -- ctx.spawn must not run
        // from a Future callback. Then: harvest the victim's owned object ids
        // first, then run as the attacker and try to read them (the candidates).
        val attackerSession = spawnNavSession(ctx, navTimeoutMs)
        val victimSession = victim.map(_ => spawnNavSession(ctx, navTimeoutMs))
        def sameHost(reqs: Seq[String]) = reqs.map(UrlNormalizer.normalize)
          .filter(u => Scope.inScope(seedHost, u)).distinct
        val victimDataF = (victim, victimSession) match
          case (Some(v), Some(vs)) => navigateAndCollect(
              vs,
              url,
              v,
              auth,
              navTimeoutMs,
              maxHops,
              postBudget,
            ).map((pages, _, rqs) => (pages, sameHost(rqs)))
          case _ => Future
              .successful((Vector.empty[(String, String)], Seq.empty[String]))
        victimDataF.flatMap { (victimPages, victimRequests) =>
          navigateAndCollect(
            attackerSession,
            url,
            attacker,
            auth,
            navTimeoutMs,
            maxHops,
            postBudget,
          ).flatMap { (pages, cookie, requested) =>
            val reqs = sameHost(requested)
            log.info(
              "SPA IDOR: attacker {} page(s)/{} req(s), victim {} page(s)/{} req(s)",
              pages.size,
              reqs.size,
              victimPages.size,
              victimRequests.size,
            )
            val proposalsF =
              if victimPages.nonEmpty || victimRequests.nonEmpty then
                ContentIdorPlanner
                  .planCross(pages, reqs, victimPages, victimRequests)
              else ContentIdorPlanner.plan(pages, reqs)
            proposalsF.flatMap { proposals =>
              log.info("Content-IDOR: {} proposal(s)", proposals.size)
              proposals.foreach(p =>
                log.info(
                  "  test: {} {} ({} candidate id(s), field={})",
                  p.method,
                  p.urlTemplate,
                  p.candidates.size,
                  p.discriminatorField,
                ),
              )
              ContentIdorProbe.run(proposals, cookie, auth)
            }
          }
        }

  /** Spawn one dedicated thread-affine nav session. Must be called on the actor
    * thread (ctx.spawn), never from a Future callback.
    */
  private def spawnNavSession(
      ctx: ActorContext[?],
      navTimeoutMs: Int,
  ): ActorRef[crawler.pool.ResourceSession.Command] = ctx.spawn(
    crawler.pool.ResourceSession[BrowserResource](0, makeBrowser(navTimeoutMs)),
    s"dast-nav-session-${poolCounter.incrementAndGet()}",
  )

  /** Log in (in-context, gated) as `identity` on the given session, navigate
    * the authenticated app, and return the visited pages, the session cookie,
    * and observed requests. Stops the session when done.
    */
  private def navigateAndCollect(
      session: ActorRef[crawler.pool.ResourceSession.Command],
      url: String,
      identity: Identity,
      auth: Authorization,
      navTimeoutMs: Int,
      maxHops: Int,
      postBudget: Int,
  )(using
      ActorSystem[?],
      ExecutionContext,
  ): Future[(Vector[(String, String)], Option[String], Seq[String])] =
    val seedHost = Scope.hostOf(url).getOrElse("")
    val log = org.slf4j.LoggerFactory.getLogger("dast.scan.Scanner")
    val loginAllowed = identity.login.exists(l =>
      ConsentGate.decide(auth, ActionClass.Active, l.loginUrl) ==
        GateDecision.Permit,
    )
    val result = sessionSubmit(session) { r =>
      r.navOpen(parseCookies(identity.cookie), url)
      if loginAllowed then
        identity.login.foreach(l =>
          r.navLogin(l.loginUrl, l.username, l.password, navTimeoutMs),
        )
      val landedAfterLogin = r.navUrl()
      r.navGoto(url, navTimeoutMs)
      if r.navUrl().contains("/login") && !landedAfterLogin.contains("/login")
      then r.navGoto(landedAfterLogin, navTimeoutMs)
      (r.navUrl(), r.navHtml(), r.navCookies())
    }.flatMap { (u0, h0, cookiePairs) =>
      val cookie =
        if cookiePairs.isEmpty then identity.cookie
        else Some(cookiePairs.map((n, v) => s"$n=$v").mkString("; "))
      log.info("SPA nav: landed on {} ({} cookie(s))", u0, cookiePairs.size)
      navHop(
        session,
        seedHost,
        u0,
        h0,
        Set.empty,
        maxHops,
        postBudget,
        auth,
        navTimeoutMs,
        Vector.empty,
      ).flatMap { pages =>
        sessionSubmit(session) { r =>
          val reqs = r.navRequests(); r.navStop(); reqs
        }.map(reqs => (pages, cookie, reqs))
      }
    }
    result.onComplete(_ => session ! crawler.pool.ResourceSession.Stop)
    result

  /** One navigation hop in the browser session: observe the live page, ask the
    * model for a step, gate it, perform it on the persistent page, recurse.
    * Terminates on the hop budget, a repeated action, or the model's `Done`.
    */
  private def navHop(
      session: ActorRef[crawler.pool.ResourceSession.Command],
      seedHost: String,
      url: String,
      html: String,
      visited: Set[String],
      hops: Int,
      postsLeft: Int,
      auth: Authorization,
      navTimeoutMs: Int,
      pages: Vector[(String, String)],
  )(using ActorSystem[?], ExecutionContext): Future[Vector[(String, String)]] =
    val acc = if pages.exists(_._1 == url) then pages else pages :+ (url -> html)
    if hops <= 0 then Future.successful(acc)
    else
      val forms = FormParse.parse(html, url)
      val links = AuthCrawl.links(url, html)
        .filter(u => Scope.inScope(seedHost, u))
      NavStepPlanner.plan(url, forms, links, visited.toSeq).flatMap { step =>
        val sig = navSig(step, forms, links)
        if step == NavStep.Done || visited.contains(sig) then
          Future.successful(acc)
        else
          performStep(
            session,
            step,
            forms,
            links,
            postsLeft,
            navTimeoutMs,
          ) match
            case None => navHop(
                session,
                seedHost,
                url,
                html,
                visited + sig,
                hops - 1,
                postsLeft,
                auth,
                navTimeoutMs,
                acc,
              )
            case Some((obsF, posts)) => obsF.flatMap { (u, h) =>
                navHop(
                  session,
                  seedHost,
                  u,
                  h,
                  visited + sig,
                  hops - 1,
                  posts,
                  auth,
                  navTimeoutMs,
                  acc,
                )
              }
      }

  /** Perform one nav step on the session. None = not performed (invalid index,
    * gate refusal, or POST budget exhausted); Some is the future page state and
    * the remaining POST budget.
    */
  private def performStep(
      session: ActorRef[crawler.pool.ResourceSession.Command],
      step: NavStep,
      forms: Seq[FormParse.FormInfo],
      links: Seq[String],
      postsLeft: Int,
      navTimeoutMs: Int,
  ): Option[(Future[(String, String)], Int)] = step match
    case NavStep.Done => None
    case NavStep.Follow(i) => links.lift(i).map { link =>
        val f = sessionSubmit(session) { r =>
          r.navFollow(link, navTimeoutMs); (r.navUrl(), r.navHtml())
        }
        (f, postsLeft)
      }
    case NavStep.Submit(fi, values, safe) => forms.lift(fi).flatMap { form =>
        ActionGuard.allow(form, safe) match
          case Left(reason) =>
            org.slf4j.LoggerFactory.getLogger("dast.scan.Scanner")
              .info("Nav submit to {} refused: {}", form.action, reason)
            None
          case Right(_) if form.method == "post" && postsLeft <= 0 => None
          case Right(_) =>
            val posts =
              if form.method == "post" then postsLeft - 1 else postsLeft
            val f = sessionSubmit(session) { r =>
              r.navSubmit(fi, values.toSeq, navTimeoutMs)
              (r.navUrl(), r.navHtml())
            }
            Some((f, posts))
      }

  private def navSig(
      step: NavStep,
      forms: Seq[FormParse.FormInfo],
      links: Seq[String],
  ): String = step match
    case NavStep.Follow(i) => s"follow:${links.lift(i).getOrElse(i.toString)}"
    case NavStep.Submit(fi, values, _) => s"submit:${forms.lift(fi)
          .map(_.action).getOrElse(fi.toString)}:${values.toSeq.sorted}"
    case NavStep.Done => "done"

  private def makeBrowser(navTimeoutMs: Int): Int => BrowserResource = i =>
    new BrowserResource(
      i,
      None,
      BrowserResource.Settings(
        navigationTimeoutMs = navTimeoutMs,
        stealth = false,
        userAgent = Some("pekko-dast-scanner/0.1 (+authorized security testing)"),
      ),
    )

  /** Submit one unit of work to a dedicated session and get its result. */
  private def sessionSubmit[T](
      session: ActorRef[crawler.pool.ResourceSession.Command],
  )(work: BrowserResource => T): Future[T] =
    val p = scala.concurrent.Promise[Any]()
    session !
      crawler.pool.ResourceSession.Submit(work.asInstanceOf[Any => Any], p)
    p.future.asInstanceOf[Future[T]]

  /** Parse a Cookie header value into (name, value) pairs. */
  private def parseCookies(header: Option[String]): Seq[(String, String)] =
    header.map(_.split(";").toSeq.flatMap(p =>
      p.split("=", 2) match
        case Array(k, v) => Some(k.trim -> v.trim)
        case _ => None,
    )).getOrElse(Seq.empty)

  /** A single identity's session cookie: minted by login if configured (gated,
    * on the pool), else the static cookie. Login failure falls back to static.
    * Builds the pool lazily (only when a login is needed) on the actor thread.
    */
  private def resolveCookie(
      ctx: ActorContext[?],
      identity: Identity,
      auth: Authorization,
      navTimeoutMs: Int,
  )(using ActorSystem[?], ExecutionContext): Future[Option[String]] =
    identity.login match
      case None => Future.successful(identity.cookie)
      case Some(_) =>
        resolveCookieOn(buildPool(ctx, 1, navTimeoutMs), identity, auth)

  /** As [[resolveCookie]] but on a pre-built pool (so the caller controls when
    * `ctx.spawn` runs -- it must be on the actor thread, not a Future
    * callback).
    */
  private def resolveCookieOn(
      pool: Pool[BrowserResource],
      identity: Identity,
      auth: Authorization,
  )(using ExecutionContext): Future[Option[String]] = identity.login match
    case None => Future.successful(identity.cookie)
    case Some(login) =>
      ConsentGate.decide(auth, ActionClass.Active, login.loginUrl) match
        case GateDecision.Deny(reason) =>
          ctx_log_skip("login", reason)
          Future.successful(identity.cookie)
        case GateDecision.Permit => pool.submit(r =>
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
      s"dast-browser-pool-${poolCounter.incrementAndGet()}",
    )
    poolRef.asPool[BrowserResource]

  // Unique pool actor names: a run may build more than one pool (e.g. login
  // then capture), and actor names must not collide.
  private val poolCounter = new AtomicInteger(0)

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
