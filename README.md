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

The crawler is a one-shot app: it crawls the configured seed, writes each scraped page to a two-column (`url,text`) CSV, and exits when the crawl completes.

### With Docker (recommended — no JVM or browser setup)

The [`Dockerfile`](Dockerfile) is a multi-stage build on Microsoft's Playwright base image, so Chromium and its system libraries are already inside — nothing to download at runtime.

```bash
# Build the image
docker build -t pekko-crawler:latest .

# Crawl and write the CSV to ./out on the host
mkdir -p out
docker run --rm --ipc=host -v "$PWD/out:/data" pekko-crawler:latest \
  https://edition.cnn.com/business 2

# -> out/crawler-output.csv
```

Args are `[seed-url] [max-depth] [output-csv]`; `--ipc=host` is recommended so Chromium doesn't run out of `/dev/shm`. The container sets `CHROMIUM_NO_SANDBOX=true` and defaults the CSV to `/data/crawler-output.csv` (mount `/data` to keep it).

### With sbt

```bash
sbt run                                          # config defaults
sbt "run https://edition.cnn.com/business"       # override seed URL
sbt "run https://edition.cnn.com/business 2"     # + max depth
sbt "run https://edition.cnn.com/business 2 out.csv"  # + output path
```

> **First run note (sbt only):** Playwright downloads the Chromium binary into `~/.cache/ms-playwright` on first launch, which can take a few minutes. Subsequent runs start immediately. (The Docker image has it preinstalled.)

### Requirements

- **Docker** — only Docker is needed for the image.
- **sbt** path: JDK 21+, sbt 1.10+ (Scala 3.3.4 / Pekko 1.1.5 / Playwright 1.53.0 are managed by the build).

---

## ⚙️ Configuration

All settings live under the `crawler` block in [`src/main/resources/application.conf`](src/main/resources/application.conf):

| Key | Env override | Meaning |
| --- | --- | --- |
| `seed-url` | `CRAWLER_SEED_URL` | Where the crawl starts (also CLI arg 0). |
| `domain` | `CRAWLER_DOMAIN` | Site domain used to build the link-acceptance regex. |
| `max-depth` | `CRAWLER_MAX_DEPTH` | BFS crawl depth (also CLI arg 1). |
| `concurrency` | `CRAWLER_CONCURRENCY` | In-flight page cap against the shared browser pool (not a browser count). |
| `host-regex` | `CRAWLER_HOST_REGEX` | Regex a discovered link's path must match to be followed. Varies per site. |
| `target-elements` | *(conf only)* | CSS selectors whose text is scraped; links inside them are the crawl frontier. |
| `click-selector` | `CRAWLER_CLICK_SELECTOR` | *(optional)* element to click before scraping. |
| `output-csv` | `CRAWLER_OUTPUT` | CSV output path (also CLI arg 2). |
| `browser-pool.size` | `CRAWLER_BROWSER_POOL_SIZE` | Number of pinned Chromium processes (default `4`). |
| `useProxy` / `proxyProviders` | `CRAWLER_USE_PROXY` | Optional per-session proxy rotation (`proxyProviders` is conf only). |

### Overriding settings per run

You don't need to edit `application.conf` (or rebuild the image) to retarget a
crawl. Precedence, low → high: **bundled defaults < `CRAWLER_*` env vars <
external conf file (`CRAWLER_CONFIG`) < CLI args**.

**Env vars** — quick one-offs (see the table above). Note `CRAWLER_HOST_REGEX`
is a literal JS `RegExp`, so use **single** backslashes (unlike the
double-escaped form in `application.conf`):

```bash
docker run --rm --ipc=host -v "$PWD/out:/data" \
  -e CRAWLER_HOST_REGEX='\/tech\/[^ ]*' \
  pekko-crawler:latest https://www.theverge.com/tech 2
```

**External conf file** — best for many settings or list-valued ones like
`target-elements`. Point `CRAWLER_CONFIG` at a HOCON file; it's overlaid on the
bundled defaults, so a partial file is fine (omitted keys keep their defaults):

```hocon
# my-crawl.conf
crawler {
  seed-url        = "https://www.theverge.com/tech"
  domain          = "theverge.com"
  max-depth       = 2
  host-regex      = "\\/tech\\/[^ ]*"   # HOCON: double-escape backslashes
  target-elements = ["article", "main"]
}
```

```bash
# Docker — mount the file and point CRAWLER_CONFIG at it
docker run --rm --ipc=host -v "$PWD/out:/data" -v "$PWD/my-crawl.conf:/cfg.conf" \
  -e CRAWLER_CONFIG=/cfg.conf pekko-crawler:latest

# sbt
CRAWLER_CONFIG=my-crawl.conf sbt run
```

The startup log prints the effective `seed`, `domain`, `maxDepth`,
`concurrency`, and `hostRegex`, so you can confirm what's actually in effect.

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