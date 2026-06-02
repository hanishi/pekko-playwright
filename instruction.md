# CLAUDE.md

Guidance for Claude Code working in this repository.

This repo began as an Apache Pekko + Playwright web crawler and is being
extended into a browser-driven **DAST** (Dynamic Application Security Testing)
engine: an actor-supervised system that crawls a consenting target, identifies
injection points and client-side state, and confirms vulnerabilities through
real in-browser execution rather than pattern matching.

Read the existing source before changing it. This file encodes the things the
code cannot tell you on its own: hard invariants, the LLM-safety boundary, the
consent rules for active testing, and house conventions. Where this file and the
code disagree about a *signature*, trust the code and ask. Where they disagree
about a *rule below*, the rule wins.

---

## 0. Non-negotiable rules (read first)

These are not style preferences. Violating any one is a defect, not a tradeoff.

1. **Thread affinity.** Playwright Java's driver is single-threaded: every
   Playwright call must run on the thread that created the browser. All browser
   work goes through the thread-affine pool (`ResourcePool` / `ResourceSession`,
   `PinnedDispatcher`). Never call a Playwright API from an arbitrary actor's
   `receiveMessage`, a `Future` callback, or a stream stage that isn't routed
   through the pool. If you need browser work done, you submit it to the pool.

2. **The LLM never authors executed code.** The model selects from a closed,
   audited vocabulary of operations and supplies *parameters* (a payload id, a
   selector, a storage key). It must never return JavaScript, Scala, or shell
   that any part of the system then runs. There is no `RunJs(code)` path and you
   must not add one. See §4.

3. **Active testing requires consent and guards.** This system injects payloads
   and can mutate target state. It only runs against targets the operator is
   authorized to test, defaults to observe-only, and never auto-fires
   destructive actions. See §5.

4. **Findings are confirmed, not guessed.** A vulnerability is only reported
   when a deterministic confirmation step fires (a marker executed, a dialog
   opened). LLM output alone is never a finding. Every confirmed finding logs
   the exact parameters needed to replay it without the model. See §4 and §6.

5. **No em dashes in prose** you write (docs, comments, commit messages,
   PR text). Use commas, parentheses, or separate sentences.

---

## 1. Build, run, test

- **Toolchain:** Scala 3.3.4, Pekko 1.1.5, Playwright 1.53.0, JDK 21+, sbt 1.10+
  (managed by the build; confirm against `build.sbt`).
- **Fast tests (no browser/network), run these by default after changes:**
  ```bash
  sbt "testOnly crawler.UrlNormalizerSpec crawler.RobotsTxtParserSpec crawler.pool.ResourcePoolSpec"
  ```
  `ResourcePoolSpec` asserts the core invariant (work runs on the resource's
  construction thread). If you touch the pool, this spec must stay green and you
  should extend it, not weaken it.
- **Full pipeline against a real site:** `sbt run` (see README for CLI args and
  `CRAWLER_*` / `CRAWLER_CONFIG` overrides). Prefer Docker (`--ipc=host`) for
  anything browser-heavy.
- **Formatting:** scalafmt is configured (`.scalafmt.conf`). Run it; do not
  hand-format around it.

Before proposing a change as done: relevant unit specs pass, scalafmt is clean,
and you have stated what you did *not* test (anything needing a live browser or
a real target counts as untested unless you actually ran it).

---

## 2. Architecture you are working within

Read these before editing; they are the load-bearing pieces.

- **`crawler/pool/` (`ResourcePool[R]`, `ResourceSession[R]`)** — generic
  node-local pool. Each session owns one `AutoCloseable` resource on its own
  pinned thread and runs all submitted work there. Callers get `Future[T]` from
  `pool.submit(work)`. This is the substrate everything browser-related sits on.
- **`crawler/BrowserResource.scala`** — one Playwright + Chromium pinned to one
  thread; launch flags, init scripts (`stealth.js`, `crawler.js`), proxy,
  request blocking, `BrowserContext` rotation. **Note:** `stealth.js` is a
  scraping-era artifact and is *wrong* for sanctioned scanning (see §5); treat
  it as opt-in, not default, for the DAST path.
- **`crawler/PlaywrightCrawler.scala`** — routes work through the pool, keeps an
  in-flight cap, retries with backoff. The DAST page operations are submitted
  here.
- **`crawler/Crawler.scala`** — BFS orchestration; robots.txt once, URL
  normalization before dedupe, self-terminates on empty frontier.
- **`crawler/Main.scala`** — boot, watch, shut down cleanly.

**Key property to preserve:** total Chromium processes == `browser-pool.size`
regardless of `concurrency`. Concurrency is an in-flight *page* cap, not a
browser count. Do not spawn browsers outside the pool.

---

## 3. The DAST extension: how new work attaches

The pivot reframes the unit of work: instead of "scrape text + links," a browser
operation is "run one audited probe/capture/confirm op and return a value." It
attaches to the existing system without changing the substrate.

The shape of a page operation (align names with the actual code as it lands):

- **`PageOp`** — a parameterized unit of browser work submitted via
  `pool.submit`. Each op is a *pre-written* JS template plus escaped parameters.
  Categories:
  - `capture` — dump `localStorage` / `sessionStorage` / IndexedDB / cookies
    (+ flags) / DOM into an immutable `ClientStateSnapshot`. Passive.
  - `probe` — inject a marked payload at a named injection point. Active.
  - `sink-scan` — walk the DOM for dangerous sinks (`innerHTML`, `eval`,
    `document.write`, `location` assignment, framework sinks) and report whether
    a known marker reached one.
  - `confirm` — the dialog/console/marker hook that proves execution. This, not
    the LLM, decides whether something is real.
- **`ClientStateSnapshot`** — a plain immutable record (storage maps, IndexedDB
  records, cookie flags, DOM, whether an auth flow was observed). It is *data*,
  consumed by both check tiers and logged for replay. Never put behavior here.

**Two-tier checks consume the snapshot:**

- **Tier 1, deterministic (always runs, no model):** cookie-flag findings
  (`HttpOnly`/`Secure`/`SameSite`), secrets-in-storage via *structured*
  classification (JWT structure, entropy, known token shapes, correlation with
  an observed auth flow), reachable-sink detection. These are reproducible and
  are the findings buyers trust. Keep false positives low: classify, do not
  flag every base64 blob.
- **Tier 2, LLM-reasoned (only on ambiguity worth the spend):** is this stored
  value actually sensitive in context, does this storage/DOM value plausibly
  reach a sink. Gated behind a cheap static prefilter so the model is not on the
  hot path. See §4.

---

## 4. The LLM boundary (the part most likely to go wrong)

The model is a decision-maker over a closed menu, not a code generator.

- **Placement.** The LLM call runs in an ordinary actor or stream stage, *never*
  on a pinned browser thread. Use `ctx.pipeToSelf` so the actor does not block
  on the `Future`, and so the call **fails closed** (on error, default to a
  no-op / `Done`, never to a guess).
- **Contract.** The model returns JSON conforming to a schema you control. You
  parse it into a sealed ADT (`LlmDecision`). Parsing is the security boundary:
  anything off-menu is rejected and not executed. Prefer provider
  structured-output / tool-schema modes so the JSON is guaranteed parseable.
- **The ADT is closed and contains no executable payload:**
  ```scala
  sealed trait LlmDecision
  object LlmDecision {
    final case class Probe(injectionPointId: String, payloadId: String) extends LlmDecision
    final case class Navigate(action: NavIntent)                        extends LlmDecision
    final case class Classify(storageKey: String, verdict: Sensitivity) extends LlmDecision
    case object Done extends LlmDecision
  }
  // There is deliberately no RunJs(code: String). Do not add one.
  ```
- **`payloadId` indexes a fixed `PayloadLibrary`** you wrote and audited. The
  model picks an id; it does not supply payload text. The deterministic layer
  owns what each id expands to and is responsible for escaping the dynamic
  parameter into the JS template (quote + escape; never string-concatenate
  model output into code).
- **Confirmation, not trust.** A `Probe` decision leads to a `probe` op, then a
  `confirm` op. Only a firing `confirm` produces a finding. Log the
  `payloadId` + injection point so the finding replays deterministically with no
  further model calls.

The existing OpenAI call in the test code is the seam to lift into the analyzer.
Reuse the HTTP/JSON mechanics; replace the free-form prompt with the
structured-decision contract above.

---

## 5. Consent and active-testing safety (hard requirements)

Active injection is legally and ethically different from crawling. Treat these
as preconditions, not features:

- **Authorization required.** No active probe runs against a target absent an
  explicit authorization record for that target (domain ownership / written
  scope). Reads of public pages are crawling; payload injection is not.
- **Observe-only default.** New code defaults to capture/passive analysis.
  Active probing is opt-in and per-target scoped.
- **No destructive actions.** Never auto-submit forms, delete records, trigger
  emails/payments, or follow obviously state-changing actions during automated
  probing. When in doubt, capture and flag for human review instead of acting.
  - **Authenticated-scan carve-out (the one exception).** The scanner may submit
    *one* explicitly-configured login form to mint a session for an
    authenticated scan, and only when all of these hold: the operator supplied
    the credentials, the login URL is named in the scan spec, the host is in the
    authorized scope, and login is the sole form submitted (no other discovered
    form is ever submitted). The LLM may *identify* which fields are username /
    password / submit (it supplies selectors, per §0.2) but never authors the
    fill or submit; a deterministic op performs them. Login is bounded and
    non-destructive (it reads, it does not mutate app data); everything else in
    this bullet still holds. Be mindful it can trip lockout / MFA / CAPTCHA.
- **Be identifiable, not evasive.** For sanctioned scanning the scanner should
  announce itself (identifiable user-agent, optional scan header) and respect
  rate limits. Do **not** route the DAST path through `stealth.js` by default;
  evasion against a consenting customer is a liability, not a feature.
- **Rate limiting / backpressure.** Reuse the in-flight cap and pool sizing to
  stay polite; a scanner that knocks over a customer's site is a failed scanner.

If a requested change would weaken any of the above, stop and surface it rather
than implementing it.

---

## 6. Output and reproducibility

The commercial value is reproducible, low-false-positive findings, so the output
model is findings, not scraped rows.

- A `Finding` carries: kind, severity, evidence, a `reproducible` flag (true for
  deterministic-tier findings), and an exact replay handle (`payloadId` +
  injection point, or the storage query). 
- Deterministic-tier findings are always `reproducible = true`.
- LLM-influenced findings are only emitted after a `confirm` op fires, and the
  logged replay handle must reproduce them without invoking the model.
- Prefer structured output (JSON/SARIF-style) over ad-hoc CSV for findings;
  keep the existing CSV path only for the legacy scraping mode.

---

## 7. Conventions

- **Scala 3 idioms.** Match the existing style (sealed traits + case classes for
  protocols, typed actors, `Behaviors.*`). Keep messages immutable; pass data,
  not behavior.
- **Pekko Typed.** Use typed actor protocols. Use `pipeToSelf` for async work
  inside actors; do not block a behavior on a `Future`.
- **Effects at the edges.** Keep browser I/O and network behind the pool / actor
  boundaries; keep checks (Tier 1, classifiers, escaping) pure and unit-testable
  with no browser or network.
- **Tests.** New deterministic logic (classifiers, escaping, sink heuristics,
  decision parsing) must have fast specs with no browser/network, in the style
  of the existing `*Spec` files. Browser-dependent behavior is exercised via the
  app, and you must say so when you have not run it.
- **Comments and docs:** explain *why*, not *what*; no em dashes.

---

## 8. When unsure

State implementation claims only about code you have actually read in this repo;
do not assert how a module behaves from its name. If a signature here disagrees
with the source, the source is right, note it and proceed. If a change touches
the pool's thread-affinity guarantee, the LLM code boundary, or the consent
rules, raise it explicitly before implementing.
