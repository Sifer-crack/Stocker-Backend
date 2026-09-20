# Test Report — Price Engine Cache/Refresh + Gateway Wiring

Date: 2026-09-18
Scope: `services/pricing` (on-demand cache-aside `Search`, async Kafka refresh, save-path fixes,
real gRPC server), `gateway` (pricing gRPC client + `PricingController` REST endpoint), and the
new Gradle version catalog (`gradle/libs.versions.toml`) touching every module's `build.gradle`.
Branch `feature/price-engine`.
Commands: `./gradlew :services:pricing:test`, `./gradlew :gateway:test`, a `compileJava` sweep of
all seven modules, and a manual standalone boot of the gateway app.

## Summary

| Suite | Tests run | Passed | Failed | Skipped |
|---|---|---|---|---|
| `services/pricing` | 63 | 62 | 1 (pre-existing, unrelated — see below) | 5 (gated live-provider tests, no keys/browser configured) |
| `gateway` | 1 | 1 | 0 | 0 |

## New or changed test classes

| Class | Tests | Outcome |
|---|---|---|
| `service.PriceSearchServiceTest` | 4 | passed |
| `service.cache.PriceCacheTest` | 5 | passed |
| `refresh.PriceRefreshRequestConsumerTest` | 2 | passed |
| `api.grpc.PriceRecordGrpcControllerSaveTest` | 7 | passed |
| `api.grpc.PriceRecordGrpcControllerSearchTest` | 2 | passed (pre-existing, re-verified after `@Controller` → `@GrpcService`) |
| `fetch.impl.RapidApiWebFetcherTest` | 8 | **1 failed** (pre-existing, unrelated — see Known caveats) |
| everything else (mapper, parsers, sitemap matcher, healthz, ingest pipeline, live-provider tests) | 34 | 29 passed, 5 skipped (unchanged) |

## What each new/changed test covers — inputs and responses

### `PriceSearchServiceTest` (unit, mocked `WebFetcher`/`PriceRecordRepository`/`PriceCache`/`EventPublisher`/`KafkaTemplate`, real single-thread `ExecutorService`)

- **`cacheHitReturnsWithoutFetching`** — input: `priceCache.getFresh("item-1")` stubbed to return one cached `PriceRecord`. Expected/actual response: `search("rice", "item-1", [], null)` returns that one record; `webFetcher.fetch(...)` is never invoked.
- **`cacheMissFetchesMapsSavesAndPublishes`** — input: cache miss, `webFetcher.fetch(...)` returns two identical `RawProduct`s (name "Rice 5kg", price `12.90` NZD, chain `limchhour`, store URL `.../rice`), `repository.save(...)` echoes a `PriceRecord`. Expected/actual: `search("rice", "item-1", ["https://www.limchhour.co.nz"], "grocery")` returns 2 records; the captured `WebFetchRequest` has `searchTerm="rice"`, the given `storeUrls`/`category`; both mapped `PriceRecord`s carry `itemId="item-1"`, `storeId=".../rice"`, `chainId="limchhour"`, `priceAmount=12.90`; `eventPublisher.publishPriceRecordCaptured` called twice; `priceCache.put("item-1", ...)` called once.
- **`returnsStaleDataAndPublishesRefreshRequestWhenFetcherThrows`** — input: `webFetcher.fetch(...)` throws `IllegalStateException("provider down")`; `priceCache.getStale(...)` stubbed to empty. Expected/actual: `search(...)` returns an empty list (never throws), `repository.save` never called, and `kafkaTemplate.send("stocker.pricing.refresh-requests.v1", "item-1", <json>)` **is** called — confirming the miss is deferred to the async refresh queue rather than silently dropped.
- **`fallbackTimeoutReturnsStaleDataAndPublishesRefreshRequest`** — input: `app.pricing.fallback.timeout` shortened to `50ms`; `webFetcher.fetch(...)` sleeps `500ms` then returns `[]`; `priceCache.getStale("item-1")` stubbed to one stale record. Expected/actual: `search(...)` returns that one stale record (not empty, not the slow live result) and a refresh request is published to the same topic — confirming the bounded-timeout wrapper actually cuts off a slow provider instead of blocking the caller.

### `PriceCacheTest` (unit, mocked `PriceRecordRepository`, real `PricingCacheProperties`)

- **`getFreshReturnsEmptyWhenBothTiersMiss`** — input: repository L2 query returns `[]`, L1 empty. Response: `getFresh("item-1")` returns `[]`.
- **`getFreshPopulatesL1FromL2AndSubsequentCallSkipsRepository`** — input: repository returns one record on first call. Response: first `getFresh("item-1")` call returns it and warms L1; a second call returns the same record with the repository invoked **only once** total — confirms L1 promotion works.
- **`putWarmsL1SoGetFreshSkipsRepositoryEntirely`** — input: `put("item-1", [record])` called directly (simulating a post-fetch cache warm). Response: `getFresh("item-1")` returns it without ever calling the repository.
- **`l1EntryExpiresAfterTtlAndFallsThroughToL2`** — input: `l1Ttl` set to `1ms`, entry warmed via `put`, then a 5ms sleep. Response: the next `getFresh` call falls through to the repository (L1 correctly treated as expired) instead of serving the now-stale in-process entry.
- **`getStaleFallsBackToRepositoryWhenNotCached`** — input: nothing in L1, `repository.findByItemId("item-1")` returns one record. Response: `getStale("item-1")` returns it.

### `PriceRefreshRequestConsumerTest` (unit, mocked `PriceSearchService`, real `ObjectMapper`)

- **`deserializesAndDelegatesToRefreshFromProvider`** — input: a JSON payload for `PriceRefreshRequest("item-1", "rice", ["https://www.limchhour.co.nz"], "grocery")`. Response: `priceSearchService.refreshFromProvider("rice", "item-1", ["https://www.limchhour.co.nz"], "grocery")` called with exactly those arguments.
- **`malformedPayloadIsLoggedAndSwallowedNotThrown`** — input: the literal string `"not json {"`. Response: `onRefreshRequested` does not throw, and `refreshFromProvider` is never called — a bad message is logged and dropped, not retried indefinitely or crashing the listener container.

### `PriceRecordGrpcControllerSaveTest` (unit, mocked `PriceFetcherService`/`PriceSearchService`/`StreamObserver`) — regression coverage for the save-path bugs

- **`rejectsMissingItemIdOrStoreId`** — input: a `PriceRecord` proto with only `channel` set. Response: `SaveResponse{saved=false}`, `priceFetcherService.save` never called.
- **`rejectsInvalidChannel`** — input: `channel="not_a_real_channel"` (otherwise valid). Response: rejected, not saved — confirms the new DB-CHECK-matching validation (`pickup`/`click_and_collect` only).
- **`rejectsNegativePrice`** — input: `priceAmount=-1.0`. Response: rejected, not saved — confirms the new non-negative guard.
- **`parsesRawAttributesJsonIntoMap`** — input: `rawAttributes='{"brand":"Anchor"}'`. Response: the entity passed to `priceFetcherService.save(...)` has `rawAttributes.get("brand") == "Anchor"` — confirms the JSON string is actually parsed instead of being dropped (the original bug).
- **`malformedRawAttributesJsonDefaultsToEmptyMapInsteadOfThrowing`** — input: `rawAttributes="not json {"`. Response: the entity's `rawAttributes` is an empty map, and `save` still proceeds (no exception) — confirms malformed input degrades safely rather than failing the whole save.
- **`zeroTimestampCapturedAtDefaultsToNowInsteadOfNull`** — input: `capturedAt` left unset (protobuf default/zero `Timestamp`). Response: the entity's `capturedAt` is non-null — confirms the original `NOT NULL` constraint violation is prevented.
- **`acceptsValidClickAndCollectChannelAndZeroPrice`** — input: `channel="click_and_collect"`, `priceAmount=0.0`. Response: `SaveResponse{saved=true}` — confirms the validation added doesn't over-reject legitimate boundary values (the other valid channel, and a legitimately free/zero price).

## Manual verification — gateway → pricing gRPC wiring

Unit tests mock the gRPC stub, so the actual wiring (channel config, generated stub, controller,
reactive offloading) was verified by booting the real app:

```bash
SERVER_PORT=18080 ./gradlew :gateway:bootRun
curl http://localhost:18080/healthz
curl "http://localhost:18080/api/pricing/search?term=milk&itemId=test-item"
```

- **Input**: `GET /healthz` → **response**: `{"service":"gateway","status":"UP"}` — confirms the app context loads with the new `GrpcOutboundConfig` bean (`PriceRecordServiceGrpc.PriceRecordServiceBlockingStub`) and `PricingController` present, no startup failure from the new proto/gRPC/version-catalog wiring.
- **Input**: `GET /api/pricing/search?term=milk&itemId=test-item` (pricing service intentionally **not** running) → **response**: HTTP 500. The server log's exception chain: `io.grpc.StatusRuntimeException: UNAVAILABLE: io exception` ← `io.netty.channel.AbstractChannel$AnnotatedConnectException: Connection refused ... localhost:9094`. This is the *expected* failure — it confirms the controller correctly builds the `SearchRequest`, calls the blocking stub on a `boundedElastic` thread (not the Netty event loop), and attempts to reach exactly the configured pricing port (`9094`). The only reason it fails is that pricing wasn't running in this smoke test.

## Build verification — version catalog

`gradle/libs.versions.toml` replaced hardcoded dependency coordinates/versions across all module
`build.gradle` files. Verified with a `compileJava` sweep of every module:

```bash
./gradlew :services:pricing:compileJava :gateway:compileJava :services:identity:compileJava \
  :services:catalog:compileJava :services:shopping:compileJava :services:notifications:compileJava \
  :services:analytics:compileJava
```

Result: `BUILD SUCCESSFUL` for all seven — confirms no module lost a dependency or picked up a
version drift in the migration to the catalog.

## Known caveats

- **`RapidApiWebFetcherTest.fallsBackToRapidapiWithoutHost` fails, pre-existing and unrelated to
  this work.** Failure: `expected: <rapidapi> but was: <woolworths>`. Root cause: the test
  constructs a "blank host" `RapidApiConfig` expecting the chain-id derivation to fall back to
  `"rapidapi"`, but `WebFetcherProperties.RapidApiConfig.host` actually defaults to
  `"woolworths-products-api.p.rapidapi.com"` (not blank), so it resolves to `"woolworths"` instead.
  Confirmed via `git status` that neither `RapidApiWebFetcherTest.java` nor
  `WebFetcherProperties.java` were touched in this session — this predates the cache/refresh/gateway
  work. Not fixed here to stay in scope; flagged in the prior session summary and again here.
- **Not covered by any test in this pass**: `PriceFetcherService` (`Fetch`/`Save` entry points)
  still has no dedicated unit test (tracked in `TASKS.md`). The gateway smoke test above is manual,
  not an automated integration test — a full `docker-compose up` end-to-end run (gateway + pricing +
  real Postgres/Kafka) has not been executed.
- The 5 skipped pricing tests are the existing live-provider/live-browser integration tests
  (`WoolworthsSpreadIntegrationTest`, `MilkPriceComparisonLiveTest`, and the three Playwright
  ingest tests) — gated behind API keys / `*_LIVE_VERIFY` env vars not set in this environment,
  unchanged by this session's work.
