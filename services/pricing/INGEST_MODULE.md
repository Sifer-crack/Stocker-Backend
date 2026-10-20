# Ingest Module — Self-Built Supermarket Scraping

Scheduled, self-built ingestion of New World, PAK'nSave and Woolworths NZ prices,
fully decoupled from the `fetch/` module and the gRPC `Search` RPC. `fetch/` stays
the on-demand path (third-party providers, called synchronously from `Search`);
`ingest/` is a periodic batch crawl that writes straight into `price_records`.

## Why a separate module, and why Playwright

New World, PAK'nSave (same Foodstuffs/Sitecore platform) and Woolworths NZ are all
JS-rendered storefronts with no stable public JSON API. Every real-world scraper
found for these chains during research (`Jason-nzd/pakn-scraper` — .NET +
Playwright; `Jason-nzd/countdown-scraper` — Node + Playwright + Cheerio, for
Woolworths/Countdown NZ) drives a real headless browser reading rendered DOM, not
a REST call. That's a different shape from `fetch/`'s per-request `WebClient`
calls: seconds per page, needs a pinned browser + store session, and doesn't fit
synchronously inside a gRPC call — hence a scheduled batch job instead.

Confirmed directly (plain `curl` against all three sites): there is no
unprotected JSON API hiding behind the page either. New World/PAK'nSave return
the same Cloudflare challenge to `curl` as to a browser; Woolworths NZ returns
HTTP 200 to `curl` but only the page shell — the product grid is rendered
client-side after load, matching `countdown-scraper`'s own approach (wait for a
rendered price element, not a JSON call). A real browser is genuinely required.

**Playwright's browser engine is Firefox, not Chromium** — verified directly
against all three live sites. Chromium gets a Cloudflare Turnstile challenge on
New World/PAK'nSave and fails outright with `net::ERR_HTTP2_PROTOCOL_ERROR` on
Woolworths NZ; Firefox reaches all three cleanly, with real product data
confirmed in the rendered DOM. This matches `Jason-nzd/countdown-scraper`,
which also launches `playwright.firefox`. See `ingest/config/PlaywrightConfig`.

## Architecture

```
com.stocker.pricing.ingest/
├── ChainScraper.java                       # interface: ScrapeResult scrape(Browser, ChainScraperConfig)
├── ChainId.java                             # enum NEWWORLD, PAKNSAVE, WOOLWORTHS
├── IngestScheduler.java                     # @Scheduled job (registered via config/IngestSchedulerConfig)
├── model/
│   ├── ChainScraperConfig.java              # storeId, storeName, categoryUrls, maxPagesPerCategory, navigationTimeoutSeconds
│   ├── ScrapeResult.java                    # chainId, products, categoriesVisited, zeroResultCategoryUrls, warnings
│   └── ScrapeException.java
├── parse/
│   ├── ProductCardFields.java               # raw strings off one product card, no Playwright types
│   ├── FoodstuffsProductCardExtractor.java  # pure: parse(ProductCardFields, ChainId, storeId) -> Optional<RawProduct>
│   └── WoolworthsProductCardExtractor.java  # same, for Woolworths' stockcode-based DOM
├── impl/
│   ├── FoodstuffsChainScraper.java          # one parameterized impl for New World + PAK'nSave
│   └── WoolworthsNzChainScraper.java
└── config/
    ├── IngestProperties.java                # @ConfigurationProperties("app.ingest")
    ├── PlaywrightConfig.java                # Playwright + shared Browser bean, gated on app.ingest.enabled
    ├── ChainScraperBeansConfig.java          # wires the 3 ChainScraper beans, gated
    ├── IngestSchedulerConfig.java            # wires IngestScheduler, gated
    └── IngestModuleConfig.java               # unconditional @Import wrapper, mirrors FetchModuleConfig
```

DOM-locating (Playwright) is kept separate from parsing (pure Java): each
`ChainScraper.scrape` navigates only configured category-listing URLs, waits
briefly for the (client-rendered) product grid to appear, locates product cards,
and hands raw strings to the pure `parse/*Extractor` classes for normalization
(price parsing, native-code validation, name cleanup). That split is what makes
the parsing logic unit-testable without a browser — the same role
`SpreadWebFetcher.parseResponse` plays in `fetch/`.

## Rate limiting

Random pause between page loads of one chain: `page-delay-min/max-millis`, **20000-40000 ms** (was 4000-9000 until
2026-10-20, when that pace drew HTTP 429 from New World). Each chain can override it with
`app.ingest.<chain>.page-delay-min/max-millis` (`IngestProperties.pageDelayMinMillis(chain)`); empty = the shared value.
Also a per-run cap on category pages per chain (`max-categories-per-run`, rotating daily), the daily page cap, and no scrape
on a search miss (`app.pricing.match.on-demand-ingest=false`). See `NORMALIZE_MODULE.md`.

**Circuit breaker.** `CrawlBreaker` keeps a per-chain state (in memory). The scrapers report every response through
`ChainScraperConfig.crawlBrake` (`CrawlBrake`): `BlockGuard` turns HTTP 429/403/503 and captcha pages (no tiles +
`BlockDetector.looksLikeChallenge`) into `onBlocked` plus a `PlaywrightException` (page failed: not visited, not zero-result,
not retried), any other page into `onSuccess`. `Retry-After` is read from `Response.headers()` (`BlockDetector.parseRetryAfter`).
After `breaker-blocked-threshold` (3) blocked pages in a row the scraper loop stops and the chain stays closed to every
caller for `breaker-cooldown` (PT6H), doubling per consecutive trip to `breaker-max-cooldown` (PT24H); a longer Retry-After
wins. `DailyPageBudget.reserve` returns 0 while open, so the cron, the startup run and `OnDemandRefreshService` (which reports
the chain as `skipped`; `IngestScheduler.isChainBlocked`) share one gate. Details: `docs/features/price-engine-reliability.md`.

### Parallel chains (`app.ingest.parallel-chains`)

Default `false`: `runAll()` crawls New World, PAK'nSave and Woolworths one after another on ONE shared `Browser`, guarded by
one fair `ReentrantLock` (Playwright objects are not thread-safe). With `STOCKER_INGEST_PARALLEL_CHAINS=true` the scheduled
and startup run start one daemon thread per chain (`ingest-newworld`, ...) and wait for all three. Each chain has its own
Playwright driver + headless Firefox, started on first use (`ChainBrowsers` / `PerChainBrowsers`; the shared `Browser`
and `Playwright` beans are not created in this mode) and its own lock. Pacing, `DailyPageBudget` and `CrawlBreaker` were
already per chain and stay independent: a breaker trip, a failure or a browser that fails to start in one chain is logged
and the other two carry on. On-demand refresh (`refreshCategories`) takes the chain's own lock in both modes, so it never
runs two scrapes on one Browser, and in parallel mode does not wait for another chain's scrape. Spring closes every browser
and driver on shutdown (`PerChainBrowsers.close()`, one failing close does not skip the rest). Browsers stay open between
runs (idle Firefox is a few hundred MiB each).

Memory: three Firefox instead of one. Use `docker-compose.crawl.yml` (pricing 4608m, `shm_size` 768m, heap 25%) only when
the other services are stopped; the normal limit (2304m) is sized for one Firefox. Exact command in
`docs/features/price-engine-reliability.md`. Wall-clock time of a full crawl drops to roughly the slowest chain; the
per-chain page rate (20-40 s pause) and the 140 pages/chain/day cap are unchanged, so each supermarket sees no more load
than before.

## Pagination, crawl order and the daily cap

- **Pagination (New World, PAK'nSAVE).** Live probe 2026-10-20 (`PaginationLiveProbeTest`, `chips-nuts--snacks`): every
  category page prints `Showing A - B of M products` (50 per page), has a `<nav aria-label="pagination">` with
  `data-testid="pagination-number"` items, a `Next page` link and `?pg=N` hrefs, and `?pg=N` returns the next 50
  (`Showing 51 - 100 of ...`; the last page ends `Showing 401 - 449 of 449`, mobile text `Page 10 of 11`). The tile selector
  also matches ~18 promoted/repeated tiles per page (68 tiles for 50 products), so the walk is driven by the Showing line,
  never by the tile count; duplicates are removed by native product code. `FoodstuffsChainScraper` walks pages through
  `PaginatedCategoryWalker`: page 1, then `?pg=2..` while B < M, up to `app.ingest.max-pages-per-category` (default 8; the
  probe category needs 9-11, so it is read up to page 8 with a WARN). Each page after the first asks
  `ChainScraperConfig.extraPageGate`, which reserves one page from `DailyPageBudget`; an error, 403/429/503, empty page or
  refused budget stops that category and keeps what was read. A one-page category costs exactly one page.
- **Woolworths.** The scraper loads only the first page of each category. The probe loaded `biscuits-cookies` once: 36 tiles,
  no "Showing" text and no pagination element in the DOM (it may be infinite scroll or rendered later). Not guessed; a
  further look (scrolling) needs the owner's approval.
  **Re-checked live on 2026-10-20 (one approved page load, `WoolworthsLiveProbeTest`):** HTTP 200, no bot check, "1 to 36 of
  264 products", 44 `div[class^='product-tile_imgAndTextArea_']` tiles and also 44 description links and 44 price spans, so
  every tile of a fully rendered page carries name, price and link and the old selectors still parse (hash suffixes changed,
  prefixes did not). Changes found: product links are `/shop/product-details/<stockcode>/<slug>` (with a hyphen; the code is
  still the second-to-last segment) and images come from `assets.woolworths.com.au`. The crawl's "no name (stockcode=null)"
  cards were therefore tiles read before hydration (wrapper present, content not yet): the loader now waits for tiles that
  show a name (max 15 s, then up to 3 scrolls) and a page with tiles but no product is logged as ONE summary WARN
  (`WOOLWORTHS: N of M cards unusable at <url> (fields missing: name x, code y, price z); template selectors: ...`) and,
  when none is usable, as zero-result (CategoryHealth) plus a "template stale or tiles not hydrated" WARN. A page that is
  blocked (403/429/captcha) never reaches this: it fails earlier as a blocked page.
  **How to re-check:** `STOCKER_PROBE_WOOLWORTHS_LIVE=true ./gradlew :services:pricing:test --tests '*WoolworthsLiveProbeTest'`
  loads ONE page (ask the owner first) and writes tile excerpts to `services/pricing/build/woolworths-probe/`; compare with
  `src/test/resources/ingest/woolworths-real-cards.html` and adjust `ChainPageTemplate.woolworths()`.
- **Order and cap.** `DailyPageBudget` default 140 pages per chain per Auckland day (`app.ingest.max-pages-per-chain-per-day`).
  `CrawlPlanner` orders a run: the catalogue's category pages, configured pages that match a staple keyword and
  sitemap-discovered staple pages first; the other configured pages after them as a window that moves on each day. Pages
  crawled in the last `recrawl-freshness-hours` (20) go to the back. When the list is longer than the budget,
  `extra-pages-reserve-per-run` (20, at most a fifth of what is left) pages are held back for extra pages.
- **Sitemap staple discovery.** `StapleCategoryMatcher` picks category URLs from the cached sitemap list whose last path
  segment contains a staple keyword (`staple-category-keywords.txt` + the sitemap-keyword column of `common-products.txt`),
  at most `max-discovered-pages-per-chain` (40), best match first. They are crawled unverified; `CategoryHealth` quarantines
  a page after 2 zero runs. Off with `app.ingest.staple-discovery-enabled=false`.

## Wiring / scheduling

`IngestScheduler` (one shared `Browser` bean, `@Scheduled(cron = "${app.ingest.cron}")`)
runs each chain in turn:

1. Skip + log WARN if that chain is disabled or has no `category-urls` configured
   — never silently no-op as if it succeeded.
2. Call `scraper.scrape(browser, config)`; a `ScrapeException` for one chain is
   caught so it doesn't abort the others.
3. Log WARN if any category page returned zero product cards, or if any
   navigation warnings occurred — the loud-failure behaviour this module leans on
   given the still-unverified store-pin mechanisms (see below).
4. De-dupe by `nativeProductCode` across the whole run.
5. Map via `RawProductPriceRecordMapper.toPriceRecord(RawProduct)` (an ingest-only
   overload — see below), skipping + logging any product with a blank native code.
6. Persist via `PriceRecordRepository` **only if `app.ingest.dry-run=false`**
   (default `true`).

**Incremental save (2026-10-20).** Steps 4-6 run after every category, not when the chain's run ends: the scraper
calls `ChainScraperConfig.getCategoryResultSink().onCategory(url, products)` once a category (all its pages) is read,
and the scheduler's sink stores that category's rows at once (same path: `price_records` save plus
`PriceStatsService.recordObservation`; run-wide dedupe by native code). A restart, crash, OOM or breaker trip later
in the run therefore loses only the category being read. Logged as `CHAIN: stored N rows from <url> (run total M)`.
A storing failure in one category is logged and counted, and the crawl carries on. `CategoryHealth`, `DailyPageBudget`
and breaker accounting are unchanged and still run at the end of the scrape. A scraper that never calls the sink
(an older one, or Woolworths until its extractor branch adds the one-line call) is stored at the end of the run as
before. The persisted data format is unchanged.

## Sitemap category-URL cache

Category URLs can be discovered from a chain's own sitemap instead of hand-maintaining
every URL in `application.yml`: `ingest/sitemap/SitemapCategoryDiscoveryService` fetches
each chain's sitemap index (`ecomsitemap_index.xml` → `ecom_sitemap_categories.xml` for
Foodstuffs; `sitemap.xml` → `sitemaps/categories.xml` for Woolworths NZ), and
`findCategoryUrls(chainId, keyword)` returns every category URL whose last path segment
contains that keyword (e.g. `"milk"` → `.../milk/fresh-milk`).

This is **aggressively, two-tier cached** so a lookup doesn't mean re-downloading and
re-parsing a chain's full sitemap every time:

1. **L1 (in-memory)** — a `ConcurrentHashMap` inside the service, process-lifetime.
2. **L2 (Postgres)** — table `sitemap_category_cache` (`db/migration/V2__create_sitemap_category_cache.sql`),
   one row per chain, `category_urls` as JSONB. Survives restarts.

Both tiers are read-through: L1 miss → L2 miss/stale → live sitemap fetch, populating
both tiers going forward. A tier is considered stale after
`app.ingest.sitemap-cache-ttl-days` (default **7 days**) — long on purpose, since a
chain's category taxonomy changes rarely and the entire point is minimizing sitemap
hits.

Sitemap XML is parsed with the JDK's built-in `DocumentBuilderFactory`
(`ingest/sitemap/SitemapXmlFetcher`), hardened against XXE (DOCTYPE declarations
disallowed, external general/parameter entities and external DTD/schema resolution all
disabled) since it's parsing XML fetched from a third-party site. The keyword-matching
itself is a small pure class, `ingest/sitemap/CategoryUrlMatcher`, unit-tested without
any network or DB dependency.

Per-chain sitemap index URLs are hardcoded (not `application.yml`-configurable) since
they're a fixed fact about each site, consistent with keeping config for things that
actually vary.

## Chains

| Chain | Platform | Product card selector | Native product code | Store-pin mechanism |
|---|---|---|---|---|
| New World | Foodstuffs / Sitecore | `div[itemtype='https://schema.org/Product']` | digits from card's own `data-testid` (`product-5000518-EA-000` → `5000518`) | Cookies `STORE_ID_V2` / `eCom_STORE_ID` — **unverified**, see below |
| PAK'nSave | Foodstuffs / Sitecore (same platform as New World) | same as New World | same as New World | same as New World |
| Woolworths NZ | woolworths.co.nz | `div[class^='product-tile_imgAndTextArea_']` | 2nd-to-last path segment of the product detail URL | **none found** — not applied yet |

Foodstuffs price comes from `meta[itemprop='price']`'s `content` attribute — a
clean decimal (e.g. `"3.73"`), no string parsing needed. **PAK'nSave omits this
meta tag** despite otherwise-identical markup to New World; `FoodstuffsChainScraper.extractPriceText`
falls back to combining `[data-testid='price-dollars']` + `[data-testid='price-cents']`
text when the meta tag is absent, so one code path covers both banners. Woolworths
price comes from `span[class^='product-price_value_']`'s text (e.g. `"$4.99"`),
`$`-stripped. All selectors above were captured from the live, rendered DOM this
session (New World/PAK'nSave: direct inspection; Woolworths: sourced from
`Jason-nzd/countdown-scraper`'s working `parser.ts`/`index.ts`), not guessed, and
confirmed end-to-end with real milk prices from all three chains.

## The itemId gap: `RawProduct.nativeProductCode`

Batch-crawled products have no caller-supplied `itemId` the way on-demand
`Search` calls do (there the caller already knows the catalog item it's looking
for; catalog-matching doesn't exist yet and is out of scope here). `RawProduct`
gained one additive field, `nativeProductCode` (the chain's own product code),
and `RawProductPriceRecordMapper` gained a new overload:

```java
RawProductPriceRecordMapper.toPriceRecord(RawProduct product)
// -> itemId = "{chainId}:{nativeProductCode}", e.g. "paknsave:P1234567"
```

The existing 2-arg `toPriceRecord(itemId, product)` and every existing caller
(`PriceSearchService`, `WoolworthsSpreadIntegrationTest`) are unchanged.

## Compliance

`robots.txt` on all three domains (checked directly) disallows `/search` and
`/shop/search`:

```
paknsave.co.nz:   Disallow: /search /Search /shop/search /shop/Search
newworld.co.nz:   Disallow: /search /Search /shop/search /shop/Search
woolworths.co.nz: Disallow: /shop/search
```

`ChainScraper` implementations only ever navigate URLs from
`ChainScraperConfig.categoryUrls` — there is no code path that constructs a
search URL. `app.ingest.*.category-urls` ships empty by default (see below);
populate it with real category/listing pages, not search URLs, mirroring
`pakn-scraper`'s `categories.txt` approach.

## Lean pages and page templates

Added 2026-10-20; the full description, defaults and log guide are in `docs/features/price-engine-reliability.md`
("Lean page loads and page templates"). In short:

- `impl/LeanPageRouter` (one per scrape, installed on the Playwright context) aborts `image` / `media` / `font` requests (and
  `stylesheet` only if configured) and requests to third-party tracker hosts; never the navigation, the chain's own domain by
  host pattern, or script / xhr / fetch / document by type. Options: `model/LeanPageOptions`, built from
  `IngestProperties.leanOptions(chain)`; the scraper beans get them from `ChainScraperBeansConfig`. The older constructors
  (`new FoodstuffsChainScraper(chain, baseUrl)`, `new WoolworthsNzChainScraper()`) mean "lean off".
- `parse/ChainPageTemplate` holds every selector / regex of a chain page: tile, name, brand-slot (pack size), price (schema meta,
  split dollars + cents, or price text), image, product code (card test id regex, or a link segment), the "Showing A - B of M
  products" regex and the pagination nav. `impl/TemplatedPageLoader` reads a page through it in one `page.evaluate`, checks that
  the mandatory fields (name, code, price) appear on at least one tile and logs `chain page url tiles claimed blocked-requests`.
  `impl/PageCountMonitor` warns when a URL's tile count falls below 80% of the last one seen in this process.
- Env: `STOCKER_INGEST_LEAN_PAGES` (true), `STOCKER_INGEST_{NEWWORLD,PAKNSAVE,WOOLWORTHS}_LEAN_ENABLED` (true),
  `STOCKER_INGEST_BLOCK_RESOURCE_TYPES` (image,media,font), `STOCKER_INGEST_BLOCK_HOST_PATTERNS`, `STOCKER_INGEST_LOG_REQUESTS`
  (false).
- Tests: `ChainPageTemplateTest`, `LeanPageRouterTest`, `PageCountMonitorTest`, `IngestLeanPropertiesTest` (no browser) and
  `TemplatePageBrowserTest` (headless Firefox on localhost fixtures, incl. the probe captures in
  `src/test/resources/ingest/probe/`; those are text-only probes, so the browser test rebuilds a page from their tile count,
  visible text and pagination markup). Woolworths: `woolworths-milk.html` was written from the reference scraper's
  selectors; `woolworths-real-cards.html` holds 3 tiles captured live on 2026-10-20.
- Does not reduce the number of page loads; correctness of blocking on the real sites is proven only by the first real crawl.

## Configuration

```yaml
app:
  ingest:
    enabled: ${STOCKER_INGEST_ENABLED:false}
    dry-run: ${STOCKER_INGEST_DRY_RUN:true}
    cron: ${STOCKER_INGEST_CRON:0 0 3 * * *}
    navigation-timeout-seconds: 30
    max-pages-per-category: ${STOCKER_INGEST_MAX_PAGES_PER_CATEGORY:8}
    max-pages-per-chain-per-day: ${STOCKER_INGEST_MAX_PAGES_PER_CHAIN_PER_DAY:140}
    extra-pages-reserve-per-run: ${STOCKER_INGEST_EXTRA_PAGES_RESERVE_PER_RUN:20}
    staple-discovery-enabled: ${STOCKER_INGEST_STAPLE_DISCOVERY_ENABLED:true}
    max-discovered-pages-per-chain: ${STOCKER_INGEST_MAX_DISCOVERED_PAGES_PER_CHAIN:40}
    newworld:
      enabled: ${STOCKER_INGEST_NEWWORLD_ENABLED:false}
      store-id: ${STOCKER_INGEST_NEWWORLD_STORE_ID:}
      store-name: ${STOCKER_INGEST_NEWWORLD_STORE_NAME:}
      category-urls: []
    paknsave:
      enabled: ${STOCKER_INGEST_PAKNSAVE_ENABLED:false}
      store-id: ${STOCKER_INGEST_PAKNSAVE_STORE_ID:}
      store-name: ${STOCKER_INGEST_PAKNSAVE_STORE_NAME:}
      category-urls: []
    woolworths:
      enabled: ${STOCKER_INGEST_WOOLWORTHS_ENABLED:false}
      store-id: ${STOCKER_INGEST_WOOLWORTHS_STORE_ID:}
      store-name: ${STOCKER_INGEST_WOOLWORTHS_STORE_NAME:}
      category-urls: []
```

`category-urls` ships **empty** on purpose, not pre-filled with guessed URLs —
shipping a plausible-but-unverified list risks silently "succeeding" with wrong
or empty data. With `enabled: false` and empty lists, every chain is inert even
if accidentally toggled on, and the scheduler's "no category-urls configured"
WARN makes the gap visible instead of hiding it.

## Environment variables

```bash
STOCKER_INGEST_ENABLED=false             # launches Playwright/Firefox when true - see Known Risks
STOCKER_INGEST_DRY_RUN=true              # true = scrape + log only, never persist
STOCKER_INGEST_CRON="0 0 3 * * *"
STOCKER_INGEST_PARALLEL_CHAINS=false     # true = three chains at once, one Firefox each (~3x browser memory)

STOCKER_INGEST_NEWWORLD_ENABLED=false
STOCKER_INGEST_NEWWORLD_STORE_ID=
STOCKER_INGEST_NEWWORLD_STORE_NAME=

STOCKER_INGEST_PAKNSAVE_ENABLED=false
STOCKER_INGEST_PAKNSAVE_STORE_ID=
STOCKER_INGEST_PAKNSAVE_STORE_NAME=

STOCKER_INGEST_WOOLWORTHS_ENABLED=false
STOCKER_INGEST_WOOLWORTHS_STORE_ID=      # accepted but NOT applied yet - no confirmed store-pin
STOCKER_INGEST_WOOLWORTHS_STORE_NAME=

STOCKER_INGEST_SITEMAP_CACHE_TTL_DAYS=7  # sitemap category-URL cache TTL (both tiers)
STOCKER_INGEST_MAX_PAGES_PER_CHAIN_PER_DAY=140
STOCKER_INGEST_MAX_PAGES_PER_CATEGORY=8
STOCKER_INGEST_EXTRA_PAGES_RESERVE_PER_RUN=20
STOCKER_INGEST_STAPLE_DISCOVERY_ENABLED=true
STOCKER_INGEST_MAX_DISCOVERED_PAGES_PER_CHAIN=40

STOCKER_INGEST_PAGE_DELAY_MIN_MILLIS=20000   # random pause between two page loads of a chain
STOCKER_INGEST_PAGE_DELAY_MAX_MILLIS=40000
STOCKER_INGEST_NEWWORLD_PAGE_DELAY_MIN_MILLIS=    # per-chain override (also PAKNSAVE_, WOOLWORTHS_ and _MAX_MILLIS); empty = shared
STOCKER_INGEST_BREAKER_BLOCKED_THRESHOLD=3   # consecutive 429/403/503/captcha responses that open a chain's breaker
STOCKER_INGEST_BREAKER_COOLDOWN=PT6H         # first cool-down; doubles per consecutive trip
STOCKER_INGEST_BREAKER_MAX_COOLDOWN=PT24H
STOCKER_INGEST_LEAN_PAGES=true               # kill switch for request blocking (images / media / fonts / trackers)
STOCKER_INGEST_NEWWORLD_LEAN_ENABLED=true    # per-chain (also PAKNSAVE_, WOOLWORTHS_)
STOCKER_INGEST_BLOCK_RESOURCE_TYPES=image,media,font
STOCKER_INGEST_BLOCK_HOST_PATTERNS=...       # comma list of host substrings; default in application.yml
STOCKER_INGEST_LOG_REQUESTS=false            # true = per page: request hosts x resource types x aborted
```

`category-urls` per chain is list-valued config, not an env var — set it in
`application.yml` (or an override file), one URL per category page.

## Known Risks / Unverified

- **RESOLVED: Chromium was blocked, Firefox is not.** Chromium got a Cloudflare
  Turnstile challenge on New World/PAK'nSave and `net::ERR_HTTP2_PROTOCOL_ERROR`
  on Woolworths NZ. Switching Playwright to `firefox` (see
  `ingest/config/PlaywrightConfig`) was tested directly against all three live
  milk category pages and reaches every one cleanly, with real product data
  confirmed in the rendered DOM (schema.org `Product` nodes on
  New World/PAK'nSave, `product-tile_*` elements on Woolworths NZ). No
  stealth/evasion techniques were needed or used — this was a browser-engine
  choice, not a fundamental anti-bot blocker.
- **RESOLVED: product-card selectors are now verified**, not guessed — see the
  Chains table above. Captured from the real rendered DOM (New World/PAK'nSave)
  and from `Jason-nzd/countdown-scraper`'s live, working implementation
  (Woolworths NZ).
- **Foodstuffs store-pin cookies** (`STORE_ID_V2`, `eCom_STORE_ID`) are still an
  unverified hypothesis, sourced from a scraper repo whose own README admits it
  was written and tested in a network-sandboxed environment and never confirmed
  against the live site. Isolated in `FoodstuffsChainScraper.applyStorePin` so
  correcting it later is localized. The milk-price verification runs so far
  were done without a store pinned (fine for confirming extraction works at
  all; not yet evidence the cookie mechanism itself does anything).
- **No Woolworths NZ store-pin mechanism was found.** `WoolworthsNzChainScraper`
  does not apply a configured `storeId` at all yet — results reflect whatever
  store the site defaults to. This may mean "one configurable store" isn't
  actually achievable for Woolworths until investigated further.
- **`STOCKER_DB_URL` in the (gitignored) root `.env` points at a real Neon
  Postgres project** (not local Docker Postgres), and as of this module's first
  live test run, that database had never had this service's Flyway migrations
  applied (`price_records` itself was missing, blocking any JPA-backed test).
  This is a pre-existing gap unrelated to `ingest/`, affecting every JPA-backed
  test against that database (including the pre-existing
  `WoolworthsSpreadIntegrationTest`), not just this module.
- **Docker: Firefox cross-stage compatibility.** The browser binary is
  downloaded in the `eclipse-temurin:25-jdk` build stage and copied into the
  `eclipse-temurin:25-jre` runtime stage; the Debian package list installed
  there for Firefox's runtime libs is a starting hypothesis, not a confirmed
  fit for that image's exact Debian release. `docker build` is the first real
  test of this.

## Day-1 checklist

**Executed 2026-09-18 — status: done for the "milk" category, all three chains.**
Steps 1-4 and 6-7 below are complete; step 5 (Foodstuffs store-pin) remains
unverified (no store-id configured yet — every chain currently returns
whatever store the site defaults to, tracked as a known risk above).

1. ~~`./gradlew :services:pricing:installPlaywrightBrowsers` (downloads Firefox).~~ done.
2. ~~Run the three live-verification tests one at a time~~ — all three passed
   against the current live DOM with no selector changes needed:
   ```bash
   STOCKER_INGEST_PAKNSAVE_LIVE_VERIFY=true ./gradlew :services:pricing:test --tests '*PaknsavePlaywrightIngest*'    # 32 cards
   STOCKER_INGEST_NEWWORLD_LIVE_VERIFY=true ./gradlew :services:pricing:test --tests '*NewWorldPlaywrightIngest*'    # 24 cards
   STOCKER_INGEST_WOOLWORTHS_LIVE_VERIFY=true ./gradlew :services:pricing:test --tests '*WoolworthsPlaywrightIngest*' # 36 cards (28 named products after filtering non-product tiles)
   ```
3. Not needed this run — no selector corrections required.
4. ~~Populate real, robots.txt-safe `category-urls` per chain~~ — done, sourced
   directly from the live-verification tests' own verified URLs (`application.yml`):
   New World/PAK'nSave `.../fridge-deli-and-eggs/milk/fresh-milk?pg=1`,
   Woolworths NZ `.../fridge-deli/milk/full-cream-milk`.
5. **Still open**: Foodstuffs store-pin cookie hypothesis unconfirmed; no
   Woolworths store-pin mechanism exists yet. Every chain currently reflects
   whatever store the site defaults to (IP-geolocated for Foodstuffs).
6. ~~Run with `app.ingest.enabled=true`, `dry-run=true`~~ — done via
   `IngestMilkCategoryLiveVerificationTest` (a permanent, gated regression
   test — `STOCKER_INGEST_MILK_LIVE_VERIFY=true`), driving the real
   `IngestScheduler` + real `FoodstuffsChainScraper`/`WoolworthsNzChainScraper`
   against the exact configured URLs: 24/32/28 realistic product counts, zero
   `zeroResultCategoryUrls`, zero scheduler-level warnings.
7. ~~Only then set `dry-run=false` against a real, migrated database~~ — done,
   via `IngestMilkCategoryLivePersistTest` (`STOCKER_INGEST_MILK_LIVE_PERSIST=true`),
   against a **new, dedicated** Neon `pricing` database (see below) — 84 rows
   landed in `price_records`, 84 in `price_stats`. `app.ingest.enabled` and
   each chain's `enabled` now default to `true` in `application.yml`;
   `dry-run` still defaults to `true` (explicit env var opt-out required to
   actually persist, unchanged from the original design).

### A note on the database this ran against

Correction (2026-10-18): an earlier version of this note said a dedicated `pricing` database had been
created. Neon's project `stocker` only has `neondb`; all services, pricing included, share its `public`
schema. See `NORMALIZE_MODULE.md` ("Database / Neon").

## Dependencies

- `com.microsoft.playwright:playwright:1.63.0` — pinned in `build.gradle`; do
  not bump without approval (see root `AGENTS.md`).

## Verification

```bash
./gradlew :services:pricing:compileJava
./gradlew :services:pricing:test --tests '*ProductCardExtractorTest*' --tests '*RawProductPriceRecordMapperTest*'
```

These unit tests need no Chromium install. The live-verification tests above do.
