# Price engine: speed, reliability and coverage (Sprint 2)

Goal: prices for the common NZ grocery items appear almost immediately, across all three chains, without crutches.

## What changed and why

| Problem | Change | Where |
|---|---|---|
| A lookup waited up to 6 s for a live supermarket page when data was "stale" (it nearly always was) | A lookup **never waits** for a page: it answers from stored data (flagged `stale`) and the refresh finishes in the background | `OnDemandRefreshService`, `app.pricing.on-demand.deadline=PT0S` |
| Comparing a list could start live refreshes and lost the size hint | Compare **reads stored data only**; third-party provider fallback is opt-in | `ItemMatchService.matchStored`, `app.pricing.match.provider-fallback=false` |
| ~20 database round trips per lookup (about 190 ms each from NZ to Neon us-east-1) | In-memory **snapshot** of `normalized_products` (one query per 30 s, refreshed in the background); matched copies are stored **after** the answer, built from the rows (no per-chain source lookups) | `NormalizedProductSnapshot`, `ItemMatchService` |
| Crawl covered 12 of 63 pages per run, 2.6 days per page; Woolworths had 1 page | The crawl list is the **catalog map** (`category-map.txt`) plus configured extras; once a day at 03:00 NZ; a window that moves each day when the list is longer than the cap | `IngestScheduler`, `CategoryMap.urls` |
| No guarantee on supermarket load | **Hard cap** of 80 pages per chain per Auckland day, shared by the scheduled crawl and on-demand refresh | `DailyPageBudget`, `app.ingest.max-pages-per-chain-per-day` |
| `stale` flagged for normal daily data | `stale-after` is 26 h | `app.pricing.on-demand.stale-after` |

Review fixes: Woolworths sitemap paths containing `loc` were corrupted; one price write held two pooled connections; the
startup backfill re-read unfixable rows forever; the startup crawl thread blocked shutdown; shopping had a second
no-op Kafka listener in the price-events consumer group; compose scraped live sites by default (now opt-in).

## Adding a grocery item

1. One line in `services/pricing/src/main/resources/common-products.txt`.
2. One line in `category-map.txt` (the page it lives on for New World/PAK'nSave and Woolworths; `-` = not sold).
3. `./gradlew :services:pricing:test` fails if a product has no page, a page is not in `category-index.txt`, or keys clash.

The next daily crawl picks the page up. Use `unmatched_search_terms` to find what shoppers search for that is missing.

## Limits to know

- The catalog needs about 134 pages per Foodstuffs chain and 138 for Woolworths; with the cap of 80 each page is
  refreshed every 2 days at most. Raise `max-pages-per-chain-per-day` only with the owner's approval (it is more load on the supermarkets).
- Pages that return zero products are logged (`zeroResultCategoryUrls`) but not yet skipped automatically.
- Copies stored under a caller's item id are written in the background; a `POST /api/pricing/compare` for an item id
  within about a second of its `/match` can see no stored price yet.
- Neon is in us-east-1; from New Zealand each query costs about 190 ms (Sydney, ap-southeast-2: about 31 ms). The snapshot hides this
  for reads; writes and `CompareShoppingList` still pay it. Moving the project to Sydney is the biggest remaining lever.
