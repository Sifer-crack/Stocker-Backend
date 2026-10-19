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

1. Pack size: request hint (`weightKg` / `volumeL`, e.g. the pantry product's selling weight/volume) -> size in the term -> the common product's typical size.
2. Term -> common product -> `normalized_products` rows for that key (indexed lookup) -> per chain, same-size first, then word coverage, then lowest unit price. `matchMethod = "canonical"`.
3. Nothing indexed / not a common product -> the older word-overlap match over stored ingest rows (`"lexical"`).
4. Still nothing -> empty result + a row in `unmatched_search_terms`. **No scrape** unless `app.pricing.match.on-demand-ingest=true` (default `false`).

Products with no supermarket equivalent (kimchi brands, goji berries, ...) correctly return empty or
only alternatives; the engine never forces a wrong match.

## Freshness check and precise fetch (on search / add item)

`ItemMatchService` (behind `GET /api/pricing/match` and the add-item `CompareItemPrices` RPC) first maps the term to a
common product, then asks `refresh/OnDemandRefreshService` whether each chain's newest stored price for it is older
than `stale-after` (12 h). For stale chains only, it loads just the one category page that product lives on
(`category-map.txt`: product -> New World/PAK'nSave path and Woolworths path), then answers from the fresh data.
- If the refresh takes longer than `deadline` (6 s), the answer carries the stored prices with `stale: true`, the page
  load finishes in the background, and the match is re-run so the fresh prices are stored under the item id and announced
  (`PriceRecordCaptured` -> shopping -> SSE). Polling the match endpoint again also returns the fresh data.
- Guard rails: a page is never re-requested within `category-cooldown` (30 min), at most
  `max-page-loads-per-chain-per-hour` (20) on-demand loads per chain, one load at a time per chain, plus the ingest's
  4-9 s politeness delay. `STOCKER_PRICING_ON_DEMAND_ENABLED=false` switches the live fetch off (stale flag still set).
- **Any other item** (not in the common list): the term is scored against every stored product (`normalized_products`);
  chains with no good match, or only an old one, get the one category page `refresh/CategoryRouter` routes the term to
  (term words vs. category path words, leaf words count most; the optional `category` request hint breaks ties; no hit =
  no page loaded). The supermarkets' search pages are off limits (robots.txt), so routing uses their category taxonomy
  (`category-index.txt`: 792 New World / PAK'nSave pages present on both chains, plus the known Woolworths pages;
  drop the full Woolworths categories.xml into `src/main/resources/woolworths-categories.txt` to cover all of Woolworths).
  The same cooldown / hourly cap / deadline / one-browser-at-a-time limits apply. Only page 1 of a category is read.
- `stale` means a chain shows the product with old data; a chain that simply doesn't carry it is not stale.

## Rate limiting (supermarkets)

Supermarket traffic comes only from the scheduled ingest, and now:
- waits a random `page-delay-min/max-millis` (default 4-9 s) between two page loads on a chain;
- loads at most `max-categories-per-run` (default 12) category pages per chain per run, taking the next window of the configured list each day;
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
`max-categories-per-run=12` per chain that is about 6 daily runs, paced 4-9 s apart. A second approval is needed
before any of them is added to `application.yml` or loaded.

## Coverage report (no supermarket contact)

```bash
STOCKER_COVERAGE_REPORT=/path/to/report.txt ./gradlew :services:pricing:test --tests '*CommonProductCoverageReportTest*'
```
Reads `products` / `pantry_items` from the configured database (read-only).

## Database / Neon

- Pricing uses PostgreSQL via `STOCKER_DB_URL/USER/PASSWORD`. In this setup that is **Neon**: project `stocker`
  (`holy-cake-97405541`), branch `main`, database `neondb`, `public` schema, shared with catalog, inventory,
  shopping and identity. A dedicated `pricing` database was never created (older docs said otherwise).
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
