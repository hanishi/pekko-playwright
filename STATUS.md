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
| Access control / IDOR (spec) | `BrokenAccessControl` | active, gated, **assisted** | request as a given identity returns restricted data (2xx + operator discriminator) | no (HTTP) | no |
| IDOR (LLM-planned) | `BrokenAccessControl` | active, gated | model proposes param / neighbour values / per-user field from an observed authenticated page; confirmed when a neighbour returns a 2xx whose field differs from the caller's own | login only | **yes (plans)** |

## What is validated, and how

- **Live, against a consenting/local target:** insecure cookies (assured.jp,
  jp.stanby.com), reflected XSS, open redirect, SQLi (error-based), SSRF
  (out-of-band), and access control / IDOR — all confirmed end to end against
  `scripts/vuln-target.py`, which intentionally exposes `/?q=` (XSS),
  `/redirect?next=` (open redirect), `/item?id=` (SQLi), `/fetch?url=` (SSRF),
  `/account?id=` (IDOR: session required, ownership not checked), and `/admin`
  (missing auth). Access-control cases are described in an operator spec
  (`scripts/access-spec.example.json`) and run via `dast.scan.AccessScannerMain`.
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
- `DAST_ACCESS_SPEC` (path to an access-control / IDOR spec, or pass as an arg)
- `DAST_NAV_TIMEOUT_MS`, `DAST_MAX_PAGES`, `DAST_MAX_DEPTH` (tuning)

Run: `sbt 'runMain dast.scan.ScannerMain <url>'` (single URL),
`dast.scan.SiteScannerMain <seed>` (crawl + scan each), or
`dast.scan.AccessScannerMain <spec.json>` (assisted access-control / IDOR).

Access control is the one **assisted** check: it is operator-driven, not
"point at a URL and go". You describe identities and assertion cases (URL +
identity + a discriminator) in a JSON spec; the tool automates the
swap-and-diff matrix and confirms by the oracle. An identity is either a
pre-captured `cookie`/`headers`, or a `login` block (loginUrl + username +
password) — in which case the scanner submits that one login form to mint the
session (instruction.md §5 authenticated-scan carve-out: operator credentials,
gated host, login the sole form submitted; login fields are detected
deterministically). Real specs hold credentials/sessions, so keep them in a
gitignored `*.local.json`.

## Out of scope (named, not hidden)

- **Stored XSS** — confirmation requires persisting via a form/state change,
  which §5 forbids.
- **Injection surfaces** are GET-only: query params, URL fragment, path
  segments. No POST bodies, no form fields.
- **Present-but-weak** policy analysis (e.g. a CSP with `unsafe-inline`) — the
  header check only flags fully-absent headers, to keep false positives near
  zero.
- Cross-host scope; `robots.txt` during discovery; finding dedup across URLs.

## Honest architecture notes

- The **browser earns its place only for execution-confirmed XSS**, DOM
  sink-reach, and authenticated login. Cookies/headers are read off a normal
  visit; redirect/SQLi/SSRF are pure HTTP and run off the browser pool entirely.
- **Where the LLM earns its place: LLM-planned IDOR** (`IdorScannerMain`). The
  model is the navigator -- from an observed authenticated page it proposes
  which parameter to tamper, neighbour values, and the per-user discriminator
  field; deterministic code confirms by cross-value comparison. The finding
  cannot exist without the model's judgment, yet the model cannot fabricate one
  (a secured endpoint yields no diff). This is the one place the model is
  load-bearing rather than garnish.
- Elsewhere the **LLM is still the least load-bearing part.** It directs
  reflected-XSS probes, but every other deterministic finding (and SSRF) is
  model-free.
- **Pekko is heavier than the current scope needs** (scan a handful of URLs).
  It buys clean concurrency and the pinned-thread invariant, but a simpler
  runtime would also serve.
