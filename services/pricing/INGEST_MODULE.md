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
4. De-dupe the batch by `nativeProductCode`.
5. Map via `RawProductPriceRecordMapper.toPriceRecord(RawProduct)` (an ingest-only
   overload — see below), skipping + logging any product with a blank native code.
6. Persist via `PriceRecordRepository` **only if `app.ingest.dry-run=false`**
   (default `true`).

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

## Configuration

```yaml
app:
  ingest:
    enabled: ${STOCKER_INGEST_ENABLED:false}
    dry-run: ${STOCKER_INGEST_DRY_RUN:true}
    cron: ${STOCKER_INGEST_CRON:0 0 3 * * *}
    navigation-timeout-seconds: 30
    max-pages-per-category: 5
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

1. `./gradlew :services:pricing:installPlaywrightBrowsers` (downloads Firefox).
2. Run the three live-verification tests one at a time — selectors are already
   verified, so these should pass; if one doesn't, the site's DOM has likely
   changed since this was written:
   ```bash
   STOCKER_INGEST_PAKNSAVE_LIVE_VERIFY=true ./gradlew :services:pricing:test --tests '*PaknsavePlaywrightIngest*'
   STOCKER_INGEST_NEWWORLD_LIVE_VERIFY=true ./gradlew :services:pricing:test --tests '*NewWorldPlaywrightIngest*'
   STOCKER_INGEST_WOOLWORTHS_LIVE_VERIFY=true ./gradlew :services:pricing:test --tests '*WoolworthsPlaywrightIngest*'
   ```
3. If any fails, read the logged first-card HTML and correct the selectors in
   `FoodstuffsChainScraper` / `WoolworthsNzChainScraper`.
4. Populate real, robots.txt-safe `category-urls` per chain (the sitemap
   category-URL cache, above, can discover these by keyword).
5. Confirm the Foodstuffs store-pin cookie hypothesis: run with a real
   `store-id` set and check whether the returned products/prices actually
   change (e.g. against a different store's known specials) — if not, the
   cookie names or values need correcting.
6. Run with `app.ingest.enabled=true`, `dry-run=true` and confirm realistic
   product counts with no `zeroResultCategoryUrls`/warnings in the logs.
7. Only then set `dry-run=false` against a real, migrated database.

## Dependencies

- `com.microsoft.playwright:playwright:1.63.0` — pinned in `build.gradle`; do
  not bump without approval (see root `AGENTS.md`).

## Verification

```bash
./gradlew :services:pricing:compileJava
./gradlew :services:pricing:test --tests '*ProductCardExtractorTest*' --tests '*RawProductPriceRecordMapperTest*'
```

These unit tests need no Chromium install. The live-verification tests above do.
