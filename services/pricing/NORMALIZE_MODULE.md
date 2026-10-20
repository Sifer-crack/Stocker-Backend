# Normalize Module — common NZ products, normalised store products, price map

Makes the price engine able to price any product a user puts in the pantry **from data it already
holds**, without sending a request to a supermarket per search.

## Pieces

| Piece | Where | Role |
|---|---|---|
| Pack size parser | `normalize/PackSizeParser`, `PackSize` | "2L", "1.5kg", "5 x 85g", "30pack" -> grams / ml / count, plus unit price (per kg / L / item). |
| Name normalizer | `normalize/ProductNormalizer` | Drops sizes and noise ("not bagged", "fresh"), singularises, collapses NZ/AU/UK spellings (yoghurt/yogurt, zucchini/courgette). |
| Common products | `resources/common-products.txt`, `normalize/CommonProductCatalog` | ~180 staples: key, aliases, sitemap keyword, typical size, exclude words. Longest matching alias wins ("coconut milk" beats "milk"). |
| Normalised index | table `normalized_products` (V5), `service/normalize/NormalizedProductService` | One row per store product: canonical key, parsed pack size, unit price. Maintained by `PriceStatsService.recordObservation`, so every `price_records` write is covered (same invariant as `price_stats`). |
| Price map | view `common_product_price_map` (V5) | Per common product and chain: product count, cheapest price, cheapest unit price, freshest observation. |
| Miss log | table `unmatched_search_terms` (V5) | Terms nothing matched; input for extending the list. Never triggers a scrape. |

The list in `common-products.txt` is hand-curated, **not** an official Stats NZ list (the Food Price
Index item list is only published through Infoshare, which could not be downloaded here).

## Known limits

- Variants share a key: trim / lite / standard / A2 / lactose-free milk are all `milk`, so the key-level price map
  mixes them; the request's own words (e.g. "full cream") only influence ranking inside the key.
- Catch-all keys (soft-drink, biscuits, cereal, chocolate, lollies, ...) hold many different products. A match must
  share a word with the request, but `min_unit_price` in `common_product_price_map` is only meaningful for narrow keys.

## Search path (`ItemMatchService`)

1. Query parsing (`normalize/QueryParser` -> `ParsedQuery`): the term's size (2L, 1.5 litres, 500g, 330ml, 12 x 330ml), pack count (12 pack, 12pk, 12-pack, 6 pk, x12, dozen = 12, half dozen = 6) and NZ egg grade (size 7, s7) are taken out of its words. A request hint (`weightKg` / `volumeL`) replaces the parsed size.
2. Term -> common product -> `normalized_products` rows for that key (indexed lookup). A row must pass the product's exclude words and the dish guard (`CommonProductCatalog.accepts`: "Egg Fried Rice", "Egg Noodles", "Egg Mayonnaise" are not eggs; a dish word BEFORE the category word, as in "Sandwich Bread", is fine; the category's own words and the shopper's words are exempt), and share a word with the term. Per chain: with a qualifier only products that have every stated qualifier are matches (ranked word coverage, unasked variants, unit price) - the rest go to `alternatives` and to `MatchOutcome.otherSizes` (`ItemMatchService.matchDetailed`); without a qualifier all sizes compete on word coverage, then unit price. There is no typical-size fallback. `matchMethod = "canonical"`.
3. Nothing indexed / not a common product -> the older word-overlap match over stored ingest rows (`"lexical"`).
4. Still nothing -> empty result + a row in `unmatched_search_terms`. **No scrape** unless `app.pricing.match.on-demand-ingest=true` (default `false`).

Products with no supermarket equivalent (kimchi brands, goji berries, ...) correctly return empty or
only alternatives; the engine never forces a wrong match.

## Freshness check and precise fetch (on search / add item)

`ItemMatchService` (behind `GET /api/pricing/match` and the add-item `CompareItemPrices` RPC) first maps the term to a
common product, then asks `refresh/OnDemandRefreshService` whether each chain's newest stored price for it is older
than `stale-after` (26 h, `app.pricing.on-demand.stale-after`). For stale chains only, it loads just the one category page that product lives on
(`category-map.txt`: product -> New World/PAK'nSave path and Woolworths path); the answer itself never waits for it.
- The `deadline` is `PT0S` (since 2026-10-20): the answer carries the stored prices with `stale: true` at once, the page
  load runs in the background, and the match is re-run so the fresh prices are stored under the item id and announced
  (`PriceRecordCaptured` -> shopping -> SSE). Polling the match endpoint again also returns the fresh data. (Earlier versions
  waited up to 6 s.)
- Guard rails: a page is never re-requested within `category-cooldown` (30 min), at most
  `max-page-loads-per-chain-per-hour` (20) on-demand loads per chain, one load at a time per chain, the 140-pages-per-chain-per-day budget shared with the crawl, and the circuit breaker
  (an open breaker means no load: that chain shows as `skipped`). `STOCKER_PRICING_ON_DEMAND_ENABLED=false` switches the live fetch off (stale flag still set).
- **Any other item** (not in the common list): the term is scored against every stored product (`normalized_products`);
  chains with no good match, or only an old one, get the one category page `refresh/CategoryRouter` routes the term to
  (term words vs. category path words, leaf words count most; the optional `category` request hint breaks ties; no hit =
  no page loaded). The supermarkets' search pages are off limits (robots.txt), so routing uses their category taxonomy
  (`category-index.txt`: 792 New World / PAK'nSave pages present on both chains, plus the known Woolworths pages;
  drop the full Woolworths categories.xml into `src/main/resources/woolworths-categories.txt` to cover all of Woolworths).
  The same cooldown / hourly cap / deadline / one-browser-at-a-time limits apply. An on-demand refresh loads one page; the scheduled
  crawl now walks multi-page categories (`?pg=N`, New World / PAK'nSAVE up to `max-pages-per-category` = 8, Woolworths `?page=N` up to 10,
  driven by the "Showing A - B of M" line; see `docs/features/price-engine-reliability.md`).
- `stale` means a chain shows the product with old data; a chain that simply doesn't carry it is not stale.

## Rate limiting (supermarkets)

Supermarket traffic comes only from the scheduled ingest, and now:
- waits a random `page-delay-min/max-millis` (default **20-40 s**; 4-9 s drew HTTP 429 on 2026-10-20) between two page loads on a chain;
- stops a chain after 3 blocked responses in a row (circuit breaker, 6 h cool-down doubling to at most 24 h);
- loads at most 140 pages per chain per Auckland day (`max-pages-per-chain-per-day`, cron + on-demand + further pages). `max-categories-per-run`
  defaults to **0** = no fixed window: the run plans as many first pages as the remaining daily budget allows (minus the 20 held back for
  further pages), common products first, and the rest rotates from day to day;
- is off by default (`STOCKER_INGEST_ENABLED=false`) and each crawl needs the owner's approval;
- sitemap category URLs stay cached for 7 days (two-tier cache);
- normalising, backfilling, the price map and every search are database-only.

### Scrape plan (needs approval before anything is run)

Status (2026-10-18, approved by the user): 6 sitemap requests were made. New World and PAK'nSave
worked (845 / 863 category URLs; both sitemap indexes list `ecom_sitemap_categories.xml`). Woolworths NZ failed
twice with an HTTP/2 stream error on `sitemap.xml` and `sitemaps/categories.xml` and was not retried; its
categories are still unknown. Nothing was cached in the database (the requests were made with curl, not the app).

Plain substring keywords over-match on real URLs (`oil` hits `toilet-cleaners`, `pie` hits `nappies`, `nut` hits
`donuts`), so the proposal in `scrape-plan-proposed.txt` uses **62 exact leaf categories** instead
(e.g. `pantry/baking-supplies--sugar/flour`). 124 page loads in total on the two Foodstuffs chains; at
the old `max-categories-per-run=12` that was about 6 daily runs. (Historical: the pages are in `application.yml` now and the pace is
20-40 s; see the daily-cap description above.)

## Coverage report (no supermarket contact)

```bash
STOCKER_COVERAGE_REPORT=/path/to/report.txt ./gradlew :services:pricing:test --tests '*CommonProductCoverageReportTest*'
```
Reads `products` / `pantry_items` from the configured database (read-only).

## Re-keying stored rows

`normalized_products` columns derived from the name (`canonical_key`, `normalized_name`, `pack_quantity`, `pack_unit`,
`unit_price`) are computed when a row is observed, so after a catalog change old rows keep their old keys until the next
scrape. `NormalizedProductService.rekeyAll(batchSize)` re-derives them from `display_name` with the same function
`observe` uses and saves ONLY rows whose values changed (a second run changes nothing). It pages through all rows
(`findAll(PageRequest, sort id)`, 200 per page, one read plus one `saveAll` of the changed rows per page), logs one
summary line (read / changed / key changed / failed pages, up to 20 `name old -> new` examples), and a failed page is
logged and counted, never thrown. `brand` is not touched (it comes from the scraped attributes, not the display name).
Database only: never deletes, never touches `price_records` / `price_stats`.

It runs once at startup on a daemon thread (`normalize-rekey`) after the backfill thread finishes (waits up to 10 min),
controlled by `app.normalize.rekey-on-startup` (env `STOCKER_NORMALIZE_REKEY`, default true). The in-memory
`NormalizedProductSnapshot` reloads itself every `app.pricing.snapshot.refresh-interval` (15 s), so corrected keys
reach lookups within about that long without explicit invalidation.

## Database / Neon

- Pricing uses PostgreSQL via `STOCKER_DB_URL/USER/PASSWORD`. In this setup that is **Neon**: project `stocker`
  (`holy-cake-97405541`), branch `main`, database `neondb`, `public` schema, shared with catalog, inventory
  and identity. `shopping` and `notifications` have their own Neon databases. A dedicated `pricing` database was never created (older docs said otherwise).
- Flyway V1-V5 are plain SQL (no extensions). Neon recommends the direct (no `-pooler`) endpoint for migrations
  (the pooled one lacks session-level features); V1-V4 did apply through the pooler here, so
  `STOCKER_DB_MIGRATE_URL` is an optional precaution. Unset = same datasource as before.
- Hikari is tuned for auto-suspend (no idle connections, 4-minute max lifetime, 30 s connect timeout).
- Neon branch storage is capped (1 GiB on this project); `price_records` is append-only with a GIN index, so
  a retention policy is needed before many categories are scraped nightly (not implemented).
- The shared `flyway_schema_history` in `public` would collide if catalog or identity enabled Flyway on the
  same database; prefer one schema per service before that.
- `./gradlew :services:pricing:test` injects the root `.env`, so DB-touching tests hit the shared `neondb`;
  use a Neon branch for destructive tests.
