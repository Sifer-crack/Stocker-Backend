# Price engine: speed, reliability and coverage (Sprint 2)

Goal: prices for the common NZ grocery items appear almost immediately, across all three chains, without crutches.

## What changed and why

| Problem | Change | Where |
|---|---|---|
| A lookup waited up to 6 s for a live supermarket page when data was "stale" (it nearly always was) | A lookup **never waits** for a page: it answers from stored data (flagged `stale`) and the refresh finishes in the background | `OnDemandRefreshService`, `app.pricing.on-demand.deadline=PT0S` |
| Comparing a list could start live refreshes and lost the size hint | Compare **reads stored data only**; third-party provider fallback is opt-in | `ItemMatchService.matchStored`, `app.pricing.match.provider-fallback=false` |
| ~20 database round trips per lookup (about 190 ms each from NZ to Neon us-east-1) | In-memory **snapshot** of `normalized_products` (one query per 30 s, refreshed in the background); matched copies are stored **after** the answer, built from the rows (no per-chain source lookups) | `NormalizedProductSnapshot`, `ItemMatchService` |
| Crawl covered 12 of 63 pages per run, 2.6 days per page; Woolworths had 1 page | The crawl list is the **catalog map** (`category-map.txt`) plus configured extras; once a day at 03:00 NZ; a window that moves each day when the list is longer than the cap | `IngestScheduler`, `CategoryMap.urls` |
| No guarantee on supermarket load | **Hard cap** of 140 pages per chain per Auckland day (raised from 80 by the owner on 2026-10-20), shared by the scheduled crawl, on-demand refresh and the further pages of multi-page categories | `DailyPageBudget`, `app.ingest.max-pages-per-chain-per-day` |
| `stale` flagged for normal daily data | `stale-after` is 26 h | `app.pricing.on-demand.stale-after` |

Review fixes: Woolworths sitemap paths containing `loc` were corrupted; one price write held two pooled connections; the
startup backfill re-read unfixable rows forever; the startup crawl thread blocked shutdown; shopping had a second
no-op Kafka listener in the price-events consumer group; compose scraped live sites by default (now opt-in).

## Adding a grocery item

1. One line in `services/pricing/src/main/resources/common-products.txt`.
2. One line in `category-map.txt` (the page it lives on for New World/PAK'nSave and Woolworths; `-` = not sold).
3. `./gradlew :services:pricing:test` fails if a product has no page, a page is not in `category-index.txt`, or keys clash.

The next daily crawl picks the page up. Use `unmatched_search_terms` to find what shoppers search for that is missing.

## Incremental save per category (2026-10-20)

Why: the scheduled/startup crawl used to store a chain's rows only when its whole run ended, so a restart, crash, OOM
or breaker trip mid-run lost everything scraped so far and users saw no new data for up to an hour.

- Each scraper reports a finished category (all its pages) to a `CategoryResultSink` carried in `ChainScraperConfig`
  (default no-op). `IngestScheduler` stores that category's rows at once through the unchanged path (`price_records`
  append + `PriceStatsService.recordObservation`), de-duplicated by native product code across the run.
- Log line: `CHAIN: stored N rows from <url> (run total M)`; the run-end line logs totals, categories stored and failures.
- A storing failure in one category is logged and counted; the remaining categories are still crawled. Rows already
  stored stay when a later category throws or trips the breaker.
- `CategoryHealth`, `DailyPageBudget` and breaker accounting are unchanged. Dry-run still stores nothing. Persisted
  data format unchanged. Works per chain thread in `parallel-chains` mode.
- A scraper that never calls the sink still has its products stored at the end of the run (current behaviour).
  Woolworths needs the one-line `config.getCategoryResultSink().onCategory(...)` after each category in its scraper
  (not part of this change) to benefit.

## Batched persist (2026-10-20)

Why: on the live crawl, storing was the bottleneck, not scraping. Neon (us-east-1) is about 190 ms away and the old path
cost roughly 5 round trips per row (insert, stats read + write, normalized read + write): New World's 722 rows took
about 20 minutes, Woolworths' 2,975 rows would take about 80, with the CPU idle.

- `IngestScheduler` now stores each category in chunks of `app.ingest.persist-batch-size` (default 500, env
  `STOCKER_INGEST_PERSIST_BATCH_SIZE`) through `PriceStatsService.saveAndRecordAll(List)`:
  1. `PriceBatchWriter.saveAndRecord`: ONE transaction: one `findByItemIdIn` for all affected `price_stats` rows, the
     observations folded in memory in list order (several observations of one product behave as if applied one by one;
     same `PriceStatsWriter.apply` code as the single-row path), then `saveAll` for `price_records` and `price_stats`.
  2. `NormalizedProductService.observeAll`: one read, in-memory fold with the same rules as `observe`, one `saveAll`.
     Still a separate transaction after the stats one, still never fails the write (failure logged).
- Hibernate sends the `saveAll` as JDBC batches: `spring.jpa.properties.hibernate.jdbc.batch_size` (default 50, env
  `STOCKER_DB_BATCH_SIZE`), `order_inserts`, `order_updates`, and the pgjdbc `reWriteBatchedInserts=true` Hikari
  data-source property (inserts become multi-row statements). Ids are application-generated UUIDs (no sequence
  round trip), so nothing in the schema changes. The URL and credentials are untouched.
- Failure: a chunk that throws rolls back as a whole (records and stats together), is logged, and is retried row by row
  on the old path (`save` + `recordObservation`); only a row that fails there is lost: logged, counted in `failures`,
  and forgotten by the de-dupe set so a later category may carry it again. The crawl always carries on.
- Measured with the counting fakes in `PriceBatchWriterTest`: a 300-row batch costs 1,500 repository calls on the old
  path and 5 on the new one (independent of the row count). At 190 ms per call that is about 285 s versus about 1 s
  of round-trip time (plus the real write cost on the server).
- `PriceRecordCaptured` Kafka events are published by the search/match paths (`PriceSearchService`,
  `ItemMatchService`), not by the scheduled ingest, so the crawl path never had a per-row publish and is unchanged.
- On-demand/gRPC paths keep the single-row `recordObservation`. Data written is identical; no migration.

### Connection drops while storing (2026-10-20, evening)

On 2026-10-20 14:07 a Neon compute restart cut the live connections in the middle of a save (`HikariPool marked as broken`,
SQLSTATE 08006) and `WOOLWORTHS: crawl failed`. Now:

- A chunk that fails with a connection failure (SQLSTATE class 08, `EOFException`, "connection closed" / "marked as broken"
  anywhere in the cause chain) is retried whole, up to 3 times, after 2 s, 5 s and 10 s
  (`STOCKER_INGEST_PERSIST_RETRY_BACKOFF_MILLIS=2000,5000,10000`; one entry per retry). The chunk was one transaction, so it
  was rolled back and re-sending is safe. Still failing -> the old row-by-row path. Other errors skip the retries.
- A category whose save fails in any way is logged and counted; it never reaches the scraper's page loop, so the crawl goes on.
- Hikari (application.yml, env overrides, no secrets): `max-lifetime` 240000 ms (`STOCKER_DB_MAX_LIFETIME_MS`, under Neon's
  5-minute idle cut-off), `keepalive-time` 120000 ms (`STOCKER_DB_KEEPALIVE_MS`), `validation-timeout` 5000 ms; the default
  JDBC `isValid()` tests connections (no `connection-test-query`). Unverified against a real Neon restart.

## Foodstuffs price text and pagination fixes (2026-10-20, evening)

- `Skipping Foodstuffs product card with unparsable price (rawPriceText=2..99)`: the PAK'nSAVE / New World dollars and cents
  spans were joined with a dot, but some tiles' cents span already starts with `.`. See INGEST_MODULE.md; fixture
  `foodstuffs-paknsave-doubled-dot.html`. Layouts with `meta[itemprop=price]` or plain `99` cents are unchanged.
- `read 150 of 227 listed products (page 4 has no 'Showing A - B of M' line)`: the walk stopped after 3 of 5 pages. The loader
  now waits (max 5 s) for the count line when tiles exist, and the walker continues on a later page without the line using the
  last known total (see INGEST_MODULE.md); fixtures `foodstuffs-veg-pg{1,2-no-count,3}.html`.
- Lean pages default to OFF (see above).

## Crawl pace and circuit breaker (2026-10-20)

Why: on 2026-10-20 a crawl at 4-9 s between page loads drew about 30 HTTP 429 in a row from New World, and the crawler
carried on through its whole list. Two changes, for all three chains (New World, PAK'nSAVE, Woolworths):

- **Pace.** Random pause between two page loads of a chain: **20000-40000 ms** (was 4000-9000). Average 30 s, so 140 pages
  take 139 pauses x 30 s = about 70 min per chain, about 80 min with ~5 s of page loading each. Chains run one after another
  (one browser), so a full 3-chain run is roughly 3.5-4 h of wall time, started at 03:00 Auckland. Env: shared
  `STOCKER_INGEST_PAGE_DELAY_MIN_MILLIS` / `_MAX_MILLIS`; one chain only:
  `STOCKER_INGEST_{NEWWORLD,PAKNSAVE,WOOLWORTHS}_PAGE_DELAY_{MIN,MAX}_MILLIS` (empty = the shared value; a chain max is never
  below its min). The pause is between page loads of the same chain, including the extra `?pg=N` pages of a category. An
  on-demand refresh loads one page and does not pause, but it waits for the browser while a crawl holds it.
- **Circuit breaker per chain** (`ingest/CrawlBreaker`, in memory: a pricing restart resets it). A blocked response is HTTP
  429 / 403 / 503, or a page with no products that reads like a captcha / bot check ("just a moment", "verify you are human",
  "access denied", ...). After `STOCKER_INGEST_BREAKER_BLOCKED_THRESHOLD` (default **3**) in a row:
  the chain's current run stops at once (no further page load, none retried in the run) and the breaker opens for
  `STOCKER_INGEST_BREAKER_COOLDOWN` (default **PT6H**). Each consecutive trip doubles it (6 h, 12 h, 24 h) up to
  `STOCKER_INGEST_BREAKER_MAX_COOLDOWN` (default **PT24H**). A good page (HTTP below 400 with at least one product tile) resets the
  consecutive count; the trip history (the doubling) is cleared only after `STOCKER_INGEST_BREAKER_SUCCESSES_TO_RESET` (default
  **3**) good pages in a row. After a cool-down the chain is half-open: one blocked response opens it again straight away (next,
  longer cool-down). A `Retry-After` header on the blocked response (seconds or HTTP date) is honoured when it is longer
  than the cool-down, bounded by the maximum. One WARN is logged when the breaker opens and one when it closes.
- **One gate.** The cron run, the startup run (`STOCKER_INGEST_RUN_ON_STARTUP`) and on-demand refreshes all reserve pages
  from `DailyPageBudget`, which now grants nothing for a chain whose breaker is open; the scrapers also ask the breaker
  before every page. Other chains are not affected.
- **Search `refresh` map.** An on-demand refresh of a chain with an open breaker loads nothing, spends no cooldown or hourly
  slot, and that chain shows as `skipped` (the same state as cooldown / hourly cap / refresh off). If the breaker opens
  while a refresh is due, that chain also ends as `skipped`. Stored prices are served as before (`stale: true`).
- **Blocked is not empty.** A blocked page is neither visited nor a zero-result page, so `CategoryHealth` never
  quarantines a category because of a block. The same holds for every other HTTP status of 400 or more (500, 502, 504, 404 ...):
  a failed page, no `onSuccess`, no zero-result record.

## Final hardening (2026-10-20, branch feature/final-2b)

Details and the reasons are in `services/pricing/INGEST_MODULE.md` ("Reliability hardening"). In short:

- The crawl is off unless `STOCKER_INGEST_ENABLED=true`, in `application.yml` too (it defaulted to on there).
- The daily page budget counts page requests that were really sent: a scrape that throws gives back what it never requested, a
  failed first page stays charged.
- A dead Firefox is replaced on the next use instead of failing every later scrape.
- A lost database connection ends the row-by-row save at once; a category whose rows were not stored is retried first next run.
- Crawled URLs must be https on the chain's own domain.
- On-demand refreshes load at most 2 pages of a category (`STOCKER_PRICING_ON_DEMAND_MAX_PAGES_PER_CATEGORY`) and never use the
  last 30% of the day's budget (`STOCKER_PRICING_ON_DEMAND_KEEP_BUDGET_SHARE`), which is kept for the scheduled crawl.
- Search terms are capped at 100 characters; the per-term refresh progress map holds at most 1000 entries; the search persist
  queue holds at most 1000 pending stores (the oldest is dropped, with a WARN).
- `Showing 1 - 50 of 1,234 products` (thousands separators) is read correctly.

## Crawl all three chains at once (crawl-only profile)

`STOCKER_INGEST_PARALLEL_CHAINS=true` (default `false`) makes the scheduled/startup crawl run New World, PAK'nSave and
Woolworths concurrently, each with its own Firefox, lock, pace, daily budget and circuit breaker (design: `INGEST_MODULE.md`,
"Parallel chains"). It needs about three times the browser memory, so it ships as a separate override file that is meant only
for a machine where every other service is stopped:

```bash
# .env: STOCKER_INGEST_ENABLED=true  STOCKER_INGEST_DRY_RUN=false  STOCKER_INGEST_RUN_ON_STARTUP=true
docker compose -f docker-compose.yml -f docker-compose.limits.yml -f docker-compose.crawl.yml \
    up -d --build kafka kafka-init pricing
```

Pricing then gets 4608m (normal 2304m), `shm_size` 768m and `MaxRAMPercentage=25`; with Kafka 1024m the total is 5632 MiB
(5888 MiB while kafka-init runs), under the 8 GiB cap. Do not start gateway/identity/... on top of it. Going back: stop it and
start the normal stack without `docker-compose.crawl.yml`.

## Lean page loads and page templates (2026-10-20)

What it is: each chain now loads its category pages with a **request filter** and reads them with a **page template**.
This makes a page load lighter (time, bandwidth, Firefox memory). It does **not** reduce the number of page loads (pace,
daily cap and circuit breaker are unchanged), and it does not make a blocked/429 response less likely by itself.

**What is blocked** (per Playwright browser context, one filter per scrape; `ingest/impl/LeanPageRouter`):

- Resource types `image`, `media`, `font` (default). `stylesheet` is **off** by default: add it only after a real crawl shows
  the tiles are read the same without it (the saved-fixture tests pass without stylesheets, but they have no real layout).
  Only passive types can be blocked; `document`, `script`, `xhr`, `fetch` are never aborted by type, whatever is configured.
- Third-party analytics / ads / tracking hosts: a case-insensitive substring of the request **host** (google-analytics,
  googletagmanager, doubleclick, facebook, hotjar, segment, optimizely, newrelic / nr-data, clarity.ms, criteo, sentry, ...; the
  list is `app.ingest.lean.block-host-patterns` in `application.yml`).
- Never blocked: the main-frame navigation, and anything on the chain's own domain (newworld.co.nz, paknsave.co.nz,
  woolworths.co.nz and subdomains) by host pattern, so the chain's own HTML, scripts and product API keep working. HTTP status
  handling (429 / 403 / 503 -> circuit breaker) and the `?pg=N` walker are untouched: the filter only decides about
  sub-requests; the response of the page itself is read as before.

**Settings** (env, default):

| Env | Default | Meaning |
|---|---|---|
| `STOCKER_INGEST_LEAN_PAGES` | `false` | Master switch. OFF by default since 2026-10-20 (blocking is unproven on the real sites and could look more bot-like); `true` turns request blocking on. `false` = every request is made. |
| `STOCKER_INGEST_{NEWWORLD,PAKNSAVE,WOOLWORTHS}_LEAN_ENABLED` | `true` | Switch one chain off (only matters when `LEAN_PAGES=true`). |
| `STOCKER_INGEST_BLOCK_RESOURCE_TYPES` | `image,media,font` | Types to abort (allowed: image, media, font, stylesheet, texttrack, manifest). |
| `STOCKER_INGEST_BLOCK_HOST_PATTERNS` | list in `application.yml` | Comma list of host substrings. |
| `STOCKER_INGEST_LOG_REQUESTS` | `false` | Request log (below). |

A chain can have its own lists under `app.ingest.<chain>.lean.block-resource-types` / `.block-host-patterns` in
`application.yml` (unset = the shared list). Changes need a pricing restart.

**Template.** The selectors and regexes that used to sit in the scraper bodies are in `ingest/parse/ChainPageTemplate`: the
Foodstuffs template (shared by New World and PAK'nSAVE, `withField(...)` makes a chain-specific copy) and the Woolworths one.
One `page.evaluate` returns compact JSON (one small object per tile plus the "Showing A - B of M products" line); no page
HTML goes to Java. Unit price and promo / multibuy badges are **not** read (no verified selectors, and no field for them yet).

**Reading the logs** (all per page, logger `com.stocker.pricing.ingest`):

- `NEWWORLD page url=... tiles=68 claimed=451 blocked-requests=12/57` - tiles read on this page, the total the page claims
  ("of 451 products"), aborted requests / all requests of the page load. `tiles` can exceed one page of products (the live grid
  has promoted / repeated tiles: 68 tiles for "Showing 1 - 50"). Bytes saved are not logged: an aborted request has no size.
  `claimed=?` = no "Showing" line (always so for Woolworths).
- WARN `... page template field 'name' ([data-testid='product-title']) found nothing on any of N product tiles at URL` -
  tiles exist but a mandatory value (name, product code, price) is missing on **all** of them: the site changed. Also
  `field 'showing'` when tiles exist but the "Showing A - B of M" line is missing (later pages would not be walked).
- WARN `zero product cards found at URL (template card selector ... may be stale)` - the tile selector found nothing
  (unchanged behaviour, now naming the selector).
- WARN `tiles dropped from 179 to 120 (below 80%) at URL` - fewer than 80% of the tiles seen **earlier in this process** for
  the same URL (memory only; a restart forgets). Blocking something the grid needs shows up exactly like this. First check:
  `STOCKER_INGEST_LEAN_PAGES=false` (the default) and compare.
- Expected reference counts from real data (tiles per page, not "claimed"): New World yoghurt **179**, cheese **183**, eggs
  **10**; PAK'nSAVE eggs **33**. A lean crawl that returns clearly fewer is broken, not "leaner".
- `STOCKER_INGEST_LOG_REQUESTS=true` adds `NEWWORLD requests url=...: cdn.host [image 12 (all aborted), script 3]; ...` per
  page: every request host with resource-type counts and how many were aborted. Run one crawl with it on, look for hosts that
  are not first party and not needed, and add their name to the host patterns. It works without blocking too
  (`LEAN_PAGES=false` + `LOG_REQUESTS=true` only observes).

**When a site changes.** The WARNs above name the chain, the template field and the URL. Open the page in a browser, find the
new selector, and change it in `ChainPageTemplate` (one field, or `withField` for one chain), then update the matching saved
fixture in `src/test/resources/ingest/` and the `ChainPageTemplateTest` / `TemplatePageBrowserTest` expectation.

**Honest limits.**

- Verified with saved fixtures served from localhost (a real Firefox page) only: tiles, prices and codes are read the same with
  the filter on, scripts and the product grid still render, trackers and images are not requested. No supermarket was contacted.
- Whether blocking is **fully safe on the real sites** is proven only by the first real crawl. Things a fixture cannot show:
  a site script that waits for a tracker or image before rendering the grid, an image `onerror` handler that swaps the
  `src` (the stored image URL would then be a placeholder), consent or bot-check scripts hosted on a pattern we block, and a
  stylesheet-dependent layout (hence `stylesheet` stays off). The `tiles dropped` WARN, the expected counts above and the kill
  switch are the safety net.
- The 80% check only has a baseline for a URL from the second load in the same process; the very first lean crawl after a
  restart compares against nothing, so compare its tile counts with the reference counts by hand.

## Limits to know

- Known, not fixed (one line each in `services/pricing/INGEST_MODULE.md` "Known limitations"): `price_records` grows on every crawl even for unchanged prices; the ingested promo flag is always false; the default store id is blank (IP-geolocated store); the normalised-product snapshot reloads every 15 s; `ChainProductService` reads 7 days of rows per request; `ItemPriceStore.replace` is not atomic.
- The catalog map plus the configured pages come to about 153 pages per Foodstuffs chain and 137 for Woolworths (first pages
  only), before any sitemap-discovered staple pages (up to 40 per chain). With the cap of 140 and 20 pages held back for the
  further pages of multi-page categories (`extra-pages-reserve-per-run`), a run plans 120 first pages: the common-product and
  staple pages come first, the rest rotates daily, so each page is refreshed every 1-2 days. Raise
  `max-pages-per-chain-per-day` only with the owner's approval (it is more load on the supermarkets).
- Multi-page categories: New World and PAK'nSAVE show "Showing A - B of M products" at 50 products per page and link
  `?pg=2,3,...` (confirmed live 2026-10-20: chips-nuts--snacks is 451-520 products over 9-11 pages). The crawler reads the
  line and follows `?pg=N` until B reaches M or `max-pages-per-category` (8) is hit; each extra page is paid from the daily
  budget, a single-page category still costs exactly one page, and any error / block / empty page / used-up budget ends
  that category with a WARN ("read B of M listed products"). Woolworths NZ is paginated the same way: it shows "1 to 36 of
  264 products" at 36 per page and links `?page=2,3,...` (confirmed live 2026-10-20, biscuits-cookies: 264 products over 8
  pages); its cap is `app.ingest.woolworths.max-pages-per-category` (default 10) and it saves each category as soon as it
  is read. Page budget: a Woolworths category of N products costs ceil(N/36) pages of the 140/day cap, so the 20 reserved
  extra pages cover only a few large categories per run; see INGEST_MODULE.md.
- Staple discovery: sitemap category pages whose name contains a staple keyword (`staple-category-keywords.txt` plus the
  keyword column of `common-products.txt`) are added to the crawl without being checked first. A dead page returns zero
  products and is quarantined after 2 runs, so it costs at most 2 pages.
- A page that returns zero products on `app.ingest.quarantine-after-zero-runs` runs in a row (default 2) is quarantined:
  the scheduled crawl and the on-demand refresh skip it for `app.ingest.quarantine-days` (default 7), so it spends no
  page budget, and it is then retried once (a zero on the retry quarantines it again at once; products clear it). A page
  that failed with a scrape error or warning is not counted as zero. Quarantine state is in memory only: a restart
  forgets it, so each empty page needs the zero runs again. Quarantined pages are logged (WARN on entry, INFO on retry,
  and a list at the end of each full run). Site-wide guard: if more than half of the pages visited in one run
  (`quarantine-site-wide-zero-share`, default 0.5; runs under 4 pages are exempt) are zero, that run is ignored for
  quarantine (one WARN) so a markup change, block page or outage cannot quarantine a whole chain.
- Copies stored under a caller's item id are written in the background; a `POST /api/pricing/compare` for an item id
  within about a second of its `/match` can see no stored price yet.
- Neon is in us-east-1; from New Zealand each query costs about 190 ms (Sydney, ap-southeast-2: about 31 ms). The snapshot hides this
  for reads; writes and `CompareShoppingList` still pay it. Moving the project to Sydney is the biggest remaining lever.

## Cancelled requests stop server-side work

A request the client abandons must not keep spending database time or, above all, supermarket page loads.

```
client aborts / disconnects
  -> gateway: WebFlux disposes the Mono -> Reactor interrupts the boundedElastic thread blocked in the gRPC stub
  -> grpc-java blocking call turns the interrupt into a CANCELLED RPC
  -> pricing: the call's io.grpc.Context is cancelled (with a cause)
  -> PriceRecordGrpcController checks Context / ServerCallStreamObserver before each step and never writes to a cancelled call
  -> CancellationToken.current() (cancel/cause) is captured by the services before they hop threads
       - ItemPriceComparisonService: checked before every item
       - PriceSearchService (legacy third-party provider path): checked before the provider call; the in-flight call is
         cancelled (future.cancel + WebClient subscription disposed via CancellableBlocking); no refresh is queued
       - OnDemandRefreshService: a job not started yet does not start; the token is passed to
         IngestScheduler.refreshCategories(chain, urls, token)
  -> IngestScheduler: a cancelled token returns the reserved DailyPageBudget; ChainScraperConfig.cancellation is read by
     the Foodstuffs and Woolworths loops before every page and during the ScrapePacer pause
```

Rules:
- Only a cancellation WITH a cause counts. gRPC also ends a call's Context (without a cause) when the call completes
  normally; the background refresh a completed lookup started must keep going.
- The scheduled (cron) crawl always uses `CancellationToken.NONE`; a client disconnect never stops it.
- Cancelling never leaves charges behind: pages not visited go back to the daily budget, and a job cancelled before
  it loaded anything gives back its on-demand cooldown stamp and hourly-cap slot.
- Not cancellable: the page that is loading when the cancel arrives (it finishes, at most one navigation timeout),
  and a database query already running. Kafka-driven refreshes (`PriceRefreshRequestConsumer`) are not request-bound.

## Old rows follow catalog fixes at startup

After a catalog change (new keys, dish guard) rows already stored kept their old `canonical_key` until re-scraped, so
e.g. egg fried rice stayed filed under `eggs`. Pricing now re-derives key, pack size and unit price of every stored
`normalized_products` row once per start (`app.normalize.rekey-on-startup`, env `STOCKER_NORMALIZE_REKEY`), writing only
rows that changed. Database only; lookups pick the corrections up within about 15 s via the snapshot refresh.

## Woolworths tiles read before they had content

The live crawl logged hundreds of `Skipping Woolworths product card with no name (stockcode=null)` lines and stored no
Woolworths products. A single approved live probe (2026-10-20, `biscuits-cookies`) showed a fully rendered page parses with
the existing selectors (44 tiles, 44 names, 44 prices; only the product link path is now `/shop/product-details/`), so the
empty cards were tiles read before the client-side render filled them.

- The loader (`TemplatedPageLoader`) now waits, for templates with `waitForNamedTiles` (Woolworths), until a tile shows its
  name (max 15 s), then scrolls up to 3 times until all tiles are named, before reading.
- One WARN per page replaces one line per card: `WOOLWORTHS: N of M cards unusable at <url> (fields missing: name x, code y,
  price z); template selectors: ...`. The per-card lines are DEBUG. The "page template field" check also logs one line per page.
- A page that loaded fine (not 403/429/captcha) with tiles but no usable product counts as zero-result for `CategoryHealth`
  and logs `template stale or tiles not hydrated`. Blocked pages are unaffected: they still fail and feed the circuit breaker.

