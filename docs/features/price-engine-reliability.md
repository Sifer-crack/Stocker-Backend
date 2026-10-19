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
  `STOCKER_INGEST_BREAKER_MAX_COOLDOWN` (default **PT24H**). A good page resets the consecutive count and the trip history.
  After a cool-down the chain is half-open: one blocked response opens it again straight away (next, longer cool-down), one
  good page clears it. A `Retry-After` header on the blocked response (seconds or HTTP date) is honoured when it is longer
  than the cool-down, bounded by the maximum. One WARN is logged when the breaker opens and one when it closes.
- **One gate.** The cron run, the startup run (`STOCKER_INGEST_RUN_ON_STARTUP`) and on-demand refreshes all reserve pages
  from `DailyPageBudget`, which now grants nothing for a chain whose breaker is open; the scrapers also ask the breaker
  before every page. Other chains are not affected.
- **Search `refresh` map.** An on-demand refresh of a chain with an open breaker loads nothing, spends no cooldown or hourly
  slot, and that chain shows as `skipped` (the same state as cooldown / hourly cap / refresh off). If the breaker opens
  while a refresh is due, that chain also ends as `skipped`. Stored prices are served as before (`stale: true`).
- **Blocked is not empty.** A blocked page is neither visited nor a zero-result page, so `CategoryHealth` never
  quarantines a category because of a block.

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

## Limits to know

- The catalog map plus the configured pages come to about 153 pages per Foodstuffs chain and 137 for Woolworths (first pages
  only), before any sitemap-discovered staple pages (up to 40 per chain). With the cap of 140 and 20 pages held back for the
  further pages of multi-page categories (`extra-pages-reserve-per-run`), a run plans 120 first pages: the common-product and
  staple pages come first, the rest rotates daily, so each page is refreshed every 1-2 days. Raise
  `max-pages-per-chain-per-day` only with the owner's approval (it is more load on the supermarkets).
- Multi-page categories: New World and PAK'nSAVE show "Showing A - B of M products" at 50 products per page and link
  `?pg=2,3,...` (confirmed live 2026-10-20: chips-nuts--snacks is 451-520 products over 9-11 pages). The crawler reads the
  line and follows `?pg=N` until B reaches M or `max-pages-per-category` (8) is hit; each extra page is paid from the daily
  budget, a single-page category still costs exactly one page, and any error / block / empty page / used-up budget ends
  that category with a WARN ("read B of M listed products"). Woolworths NZ is not paginated by the crawler yet (its
  pagination markup was not found; see INGEST_MODULE.md).
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
