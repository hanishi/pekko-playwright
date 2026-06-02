# 🕷️ Crawler

An Apache Pekko–based web crawler that uses Microsoft Playwright to extract structured text and link data from dynamic, JavaScript-heavy websites.

It combines the actor concurrency model of [Apache Pekko](https://pekko.apache.org/) with the browser-automation power of [Playwright](https://playwright.dev/java/) to build a scalable, breadth-first scraping system where a small, fixed pool of real Chromium browsers is shared safely across the whole crawl.

It supports:

- Headless browser automation (real Chromium, stealth-hardened)
- DOM content + link extraction scoped to CSS selectors
- Click-based interaction before scraping (e.g. expand buttons, cookie walls)
- `robots.txt` parsing and URL normalization for polite, dedup-friendly crawling
- Retry with backoff and graceful error handling
- A **thread-affine browser pool** that caps total Chromium processes regardless of crawl concurrency
- Proxy support for IP rotation (see [`application.conf`](src/main/resources/application.conf))

---

## 🚀 Running

The crawler is a one-shot app: it crawls the configured seed, prints each scraped page, and exits when the crawl completes.

```bash
# Crawl the seed configured in application.conf
sbt run

# Override the seed URL (domain is re-derived from the URL host)
sbt "run https://edition.cnn.com/business"

# Override the seed URL and max depth
sbt "run https://edition.cnn.com/business 2"
```

> **First run note:** Playwright downloads the Chromium binary into `~/.cache/ms-playwright` on first launch, which can take a few minutes. Subsequent runs start immediately.

### Requirements

- JDK 21+
- sbt 1.10+
- Scala 3.3.4 / Pekko 1.1.5 / Playwright 1.53.0 (managed by the build)

---

## ⚙️ Configuration

All settings live under the `crawler` block in [`src/main/resources/application.conf`](src/main/resources/application.conf):

| Key | Meaning |
| --- | --- |
| `seed-url` | Where the crawl starts (overridable via CLI arg). |
| `domain` | Site domain used to build the link-acceptance regex. |
| `max-depth` | BFS crawl depth (overridable via CLI arg). |
| `concurrency` | In-flight page cap against the shared browser pool (not a browser count). |
| `host-regex` | Regex a discovered link's path must match to be followed. |
| `target-elements` | CSS selectors whose text is scraped; links inside them are the crawl frontier. |
| `click-selector` | *(optional)* element to click before scraping. |
| `cron-schedule` | Read for completeness; unused in one-shot mode. |
| `browser-pool.size` | Number of pinned Chromium processes (default `4`). |
| `useProxy` / `proxyProviders` | Optional per-session proxy rotation. |

---

## 🧵 Architecture

The core design problem: **Playwright Java's driver is single-threaded** — every API call must run on the thread that created it. A plain Pekko actor's `receiveMessage` can hop threads between messages, so hosting a browser in an ordinary actor is unsafe.

This project solves it with a **thread-affine resource pool** (based on [hanishi/pekko-thread-affine-pool](https://github.com/hanishi/pekko-thread-affine-pool)):

- **[`ResourcePool[R]` / `ResourceSession[R]`](src/main/scala/crawler/pool)** — a generic, node-local pool. Each session owns one `AutoCloseable` resource, is built on its own `PinnedDispatcher` thread, and runs all submitted work there. Callers get a typed `Future[T]` via `pool.submit(work)`.
- **[`BrowserResource`](src/main/scala/crawler/BrowserResource.scala)** — the resource: one Playwright + Chromium pinned to one thread. Hardened launch flags, [`stealth.js`](src/main/resources/stealth.js) + [`crawler.js`](src/main/resources/crawler.js) init scripts, optional proxy, request-type blocking, non-HTML handling, and `BrowserContext` rotation.
- **[`PlaywrightCrawler`](src/main/scala/crawler/PlaywrightCrawler.scala)** — routes scrape work through the pool, keeps an in-flight cap, and retries with backoff.
- **[`Crawler`](src/main/scala/crawler/Crawler.scala)** — BFS orchestration: fetches/parses `robots.txt` once ([`RobotsTxtParser`](src/main/scala/crawler/RobotsTxtParser.scala)), normalizes URLs before dedupe ([`UrlNormalizer`](src/main/scala/crawler/UrlNormalizer.scala)), and stops itself when the frontier is empty.
- **[`Main`](src/main/scala/crawler/Main.scala)** — boots the system, watches the crawler, prints pages, and shuts down cleanly on completion.

The upshot: total Chromium processes == `browser-pool.size` no matter how high `concurrency` goes, and each browser lives safely on one OS thread for its lifetime.

---

## 🧪 Tests

```bash
# Fast, deterministic unit + pool specs (no browser/network)
sbt "testOnly crawler.UrlNormalizerSpec crawler.RobotsTxtParserSpec crawler.pool.ResourcePoolSpec"
```

`ResourcePoolSpec` asserts the key property — work runs on the resource's construction thread — plus round-robin routing, `submitTo`/`submitAll`, failure propagation, and close-on-stop.

To watch the full pipeline end-to-end against a real site, just run the app (`sbt run`).

🎥 **Scraping in action:**

https://github.com/user-attachments/assets/2a466d0a-dacc-4478-b571-b12556a7bdc8

---

## 🚧 Status

Active work in progress. The crawler core and thread-affine browser pool are functional and tested.
