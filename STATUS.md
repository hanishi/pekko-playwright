# DAST engine — status

An honest account of what this branch builds, what is validated, and what is
deliberately out of scope. Read alongside `CLAUDE.md` (the design rules) and the
package docs under `src/main/scala/dast`.

## What it is

A browser-driven, LLM-directed dynamic application security testing (DAST)
engine built on the thread-affine Playwright pool (`crawler.pool`). It scans one
authorized URL (or crawls a seed and scans each in-scope URL), composing
deterministic checks with one execution-confirmed active probe, and emits
structured, reproducible findings.

## Checks implemented

| Check | Kind | Tier | Confirmation | Browser? | LLM? |
|---|---|---|---|---|---|
| Insecure cookies | `InsecureCookie` | deterministic | cookie flags read over CDP | yes (capture) | no |
| Secrets in storage | `SecretInStorage` | deterministic | key/value classification | yes (capture) | no |
| Missing security headers | `MissingSecurityHeader` | deterministic | response headers read | yes (capture) | no |
| Open redirect | `OpenRedirect` | active, gated | no-follow request, `Location` targets sentinel | no (HTTP) | no |
| SQL injection | `SqlInjection` | active, gated | error signature (vs baseline) **or** time delay (re-tested) | no (HTTP) | no |
| SSRF | `Ssrf` | active, gated | out-of-band callback to a listener we control | no (HTTP) | no |
| Reflected XSS | `Xss` | active, gated | payload **executes** in the browser (marker fires) | yes | yes (directs) |
| DOM XSS (sink reach) | `Xss` | active, gated | injected marker reaches a dangerous DOM sink | yes | no |

## What is validated, and how

- **Live, against a consenting/local target:** insecure cookies (assured.jp,
  jp.stanby.com), reflected XSS, open redirect, SQLi (error-based), and SSRF
  (out-of-band) — all confirmed end to end against `scripts/vuln-target.py`,
  which intentionally exposes `/?q=` (XSS), `/redirect?next=` (open redirect),
  `/item?id=` (SQLi), and `/fetch?url=` (SSRF).
- **Unit tested (pure logic):** every check's decision logic — header rules,
  redirect/SQLi/SSRF confirm predicates, payload shapes, the analyzer decision
  parser, scope/frontier, the orchestrator loop (stubbed effects). ~136 tests.
- **Not unit tested (live-only, stated):** the browser-driving ops
  (`CaptureOp`, `ProbeOp`, `SinkScanOp`), the HTTP probers, the OAST listener,
  and the live Claude call. These are wiring around tested logic.
- **Time-based SQLi and DOM sink-scan** are implemented and unit tested but were
  not observed firing live in this branch (error-based SQLi confirms first; no
  live DOM-XSS target was exercised).

## Safety model (non-negotiable, per CLAUDE.md)

- **Observe-only by default.** Active probing (XSS/redirect/SQLi/SSRF) runs only
  when the target host is in `DAST_AUTHORIZED_HOSTS`; the consent gate is
  re-checked at the orchestrator. Observe-only never calls the model.
- **The LLM never authors executed code.** It selects one action from a closed
  ADT (`probe`/`navigate`/`classify`/`done`) and supplies only an audited
  `payloadId`. `DecisionParser` rejects anything off-menu and fails closed.
- **Confirmed, not guessed.** XSS by execution, SSRF out-of-band, SQLi by error
  signature/timing, redirect by `Location`. Findings carry a model-free replay
  handle.
- **Identifiable, not evasive.** The scanner sends a `pekko-dast-scanner` User-
  Agent and does not route through stealth.js. No form auto-submit, no
  state-changing actions.

## Configuration

All read from the environment or `.env.local` (no shell export needed), via
`DastConfig`:

- `ANTHROPIC_API_KEY` (analyzer; XSS probe path fails closed without it)
- `ANTHROPIC_MODEL` (default `claude-opus-4-8`)
- `DAST_AUTHORIZED_HOSTS` (comma-separated; enables active probing)
- `DAST_OAST_BASE_URL` (enables SSRF; must be reachable by the target)
- `DAST_NAV_TIMEOUT_MS`, `DAST_MAX_PAGES`, `DAST_MAX_DEPTH` (tuning)

Run: `sbt 'runMain dast.scan.ScannerMain <url>'` (single) or
`dast.scan.SiteScannerMain <seed>` (crawl + scan each).

## Out of scope (named, not hidden)

- **IDOR / broken access control / auth** — needs real credentials (two
  sessions) and per-app knowledge of protected endpoints and object IDs; §5
  forbids auto-login. The honest form is an *assisted* tool (operator supplies
  tokens + a URL template, it diffs responses), not a scanner-automatic check.
  Not built.
- **Stored XSS** — confirmation requires persisting via a form/state change,
  which §5 forbids.
- **Injection surfaces** are GET-only: query params, URL fragment, path
  segments. No POST bodies, no form fields.
- **Present-but-weak** policy analysis (e.g. a CSP with `unsafe-inline`) — the
  header check only flags fully-absent headers, to keep false positives near
  zero.
- Cross-host scope; `robots.txt` during discovery; finding dedup across URLs.

## Honest architecture notes

- The **browser earns its place only for execution-confirmed XSS** and DOM
  sink-reach. Cookies/headers are read off a normal visit; redirect/SQLi/SSRF
  are pure HTTP and run off the browser pool entirely.
- The **LLM is the least load-bearing part.** It directs reflected-XSS probes,
  but every deterministic finding (and SSRF) is model-free. On the two real
  targets exercised, the model contributed nothing to the findings.
- **Pekko is heavier than the current scope needs** (scan a handful of URLs).
  It buys clean concurrency and the pinned-thread invariant, but a simpler
  runtime would also serve.
