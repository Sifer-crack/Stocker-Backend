# Test Report — Price Engine (services/pricing)

Date: 2026-09-17
Scope: `services/pricing` (Java 25 / Spring Boot 4.0.7), branch `feature/price-engine`.
Command: `./gradlew :services:pricing:test --console=plain`

## Summary

| Result | Count |
|---|---|
| Tests run | 8 |
| Passed    | 7 |
| Skipped   | 1 |
| Failed    | 0 |
| Errors    | 0 |

The single skipped test is the live Woolworths scraper integration test — it is
gated behind `STOCKER_SPREAD_API_KEY` (see `.env`) and was skipped because no key
was configured at report time.

## Test classes

| Class | Tests | Outcome |
|---|---|---|
| `service.RawProductPriceRecordMapperTest` | 2 | passed |
| `service.PriceSearchServiceTest` | 2 | passed |
| `api.grpc.PriceRecordGrpcControllerSearchTest` | 2 | passed |
| `api.rest.HealthzControllerTest` | 1 | passed |
| `integration.WoolworthsSpreadIntegrationTest$ScraperPersistsPrices` | 1 | skipped (no API key) |

## What each test covers

### RawProductPriceRecordMapperTest (unit)
- Maps a fully-populated `RawProduct` to a `PriceRecord` with correct
  `itemId`/`storeId` (store URL), `chainId`, channel `pickup`, `priceAmount`,
  `currency`, `capturedAt`, `rawAttributes`.
- Applies fallbacks when fields are blank: `chainId`/`storeId` → `unknown`,
  missing price → `0`, default currency `NZD`, empty `rawAttributes`.

### PriceSearchServiceTest (unit, mocked `WebFetcher` + repository)
- Fetches via the active `WebFetcher`, maps `RawProduct` → `PriceRecord`, and
  persists every crawled product (`searchTerm`, `storeUrls`, `category`
  propagated into the `WebFetchRequest`).
- Returns an empty list (does not throw) when the fetch provider fails.

### PriceRecordGrpcControllerSearchTest (unit, mocked services)
- `Search` RPC maps persisted entities to proto `PriceRecord`s and streams a
  `SearchResponse`.
- Rejects requests without `itemId` (empty default response) and does not call
  the search service.

### HealthzControllerTest (WebMvc slice)
- `GET /healthz` returns `{"status":"UP"}`.

### WoolworthsSpreadIntegrationTest (live, gated)
Scrapes Woolworths via the active provider (default `spread`) for **Tim Tam**,
**Coca-Cola 2.25L**, **Copper Kettle**, filters Woolworths/Countdown results,
maps them with `RawProductPriceRecordMapper`, and persists to `price_records`
(`STOCKER_DB_URL`, e.g. NEON). Fails if any term returns no products, no
Woolworths matches, nothing persisted, or a non-positive price.
- Skipped unless `STOCKER_SPREAD_API_KEY` is set in the root `.env`.
- To run live:
  `./gradlew :services:pricing:test --tests '*WoolworthsSpreadIntegrationTest*'`

## Notable fixes during this cycle

- Corrected the spread provider base URL from the marketing site
  (`https://spread.butterup.app/api`) to the real API root
  (`https://spread.butterup.app/spread-api/v1`) in `application.yml`,
  `WebFetcherProperties`, and `FETCH_MODULE.md`.
- The root `.env` is injected into the pricing test JVM via
  `services/pricing/build.gradle` (`.env` remains git-ignored).

## Known caveats

- Live provider response fields (`products[]`, `store`, `chain`) are parsed
  heuristically and could not be verified end-to-end without a real API key;
  `SpreadWebFetcher.parseResponse` may need tuning against live data.
- Manual `curl` probes of `api.woolworths.co.nz` returned 404 for every
  documented path; direct site scraping was not feasible in this environment,
  so the scraper test depends on a third-party provider (spread) as designed.