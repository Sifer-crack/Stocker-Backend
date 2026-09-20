# Cache/Refresh Module — On-Demand Search

Cache-aside layer in front of on-demand `Search`, plus the async Kafka-driven
refresh path for cache misses. Neither `fetch/` nor `ingest/` change shape —
this module sits between the gRPC `Search` RPC and both of them.

## Why

Before this module existed, every `Search` call fetched live from a
third-party provider (`fetch/`) synchronously, on the gRPC request thread,
with no cache and no resilience anywhere in the codebase. That's fragile:
Spread/RapidAPI are paid/rate-limited, prices don't change per-request, and a
slow/down provider directly became the caller's latency/failure. See root
`TASKS.md` and the price-engine plan discussion for the full rationale.

`ingest/`'s Playwright scrapers were never a fit for the request path either —
seconds per page, shared browser, and blocked from arbitrary keyword search by
`robots.txt`. So "on-demand reads from ingest" means treating `price_records`
(which `ingest/`'s nightly crawl already populates broadly) as the primary
read source for `Search`, not triggering a live scrape per request.

## Architecture

```
com.stocker.pricing.service.cache/
├── PriceCache.java              # two-tier cache-aside (L1 in-process, L2 price_records)
├── PricingCacheProperties.java  # @ConfigurationProperties("app.pricing")
└── PricingCacheModuleConfig.java# registers properties + the bounded fallback executor

com.stocker.pricing.refresh/
├── PriceRefreshRequest.java          # Kafka payload record
└── PriceRefreshRequestConsumer.java  # @KafkaListener, async retry off the request path
```

## Cache key: the caller-supplied `itemId`

`SearchRequest.item_id` is already required by `PriceRecordGrpcController`.
Every `RawProduct` a single `search()` call fetches — across however many
chains `fetch/`'s active provider returns — is persisted under that *same*
caller-supplied `itemId` (see `RawProductPriceRecordMapper.toPriceRecord(itemId,
product)`, unchanged). That makes `itemId` a ready-made cross-chain grouping
key for on-demand results, with no fuzzy name-matching or catalog dependency
needed *within one search's own results*.

This does **not** reconcile with `ingest/`'s chain-scoped IDs
(`{chainId}:{nativeProductCode}`, e.g. `paknsave:P1234567`) — an ingest-sourced
row for "the same" product is a different `itemId` than whatever a client
passes to `Search`. Bridging the two needs either fuzzy name-matching or a
canonical `Item` model in `catalog` (currently a placeholder table) — both
deferred. Until then, `PriceCache`'s L2 read only accelerates *repeat
on-demand searches using the same itemId*; it does not automatically surface
ingest data for a logically-equivalent item under a different id.

## Cache-aside flow (`PriceSearchService.search`)

1. **L1** (`PriceCache`, in-process `ConcurrentHashMap`, TTL
   `app.pricing.cache.l1-ttl`) — hit returns immediately.
2. **L2** (`price_records`, fresh = `captured_at` within
   `app.pricing.cache.freshness-window`) — hit populates L1, returns.
3. **Miss** — bounded synchronous fallback: `webFetcher.fetch(...)` submitted
   to a fixed-size executor (`priceFallbackExecutor`), with
   `Future.get(app.pricing.fallback.timeout, ...)`.
   - Success: persist via `PriceRecordRepository`, publish
     `PriceRecordCaptured` (`EventPublisher`), warm L1/L2, return real data.
   - Timeout or failure: publish a `PriceRefreshRequest` to
     `app.pricing.refresh.topic`, return best-effort stale/empty data
     (`PriceCache.getStale`), and return immediately — never block further.

`PriceSearchService.refreshFromProvider(...)` holds the fetch/persist/publish/
cache-warm logic shared by both the bounded on-demand fallback above and the
unbounded async consumer below — the only difference between the two callers
is the timeout wrapped around it.

## Async refresh (`stocker.pricing.refresh-requests.v1`)

Deliberately a **separate topic** from `stocker.pricing.events.v1`: this is
pricing's own internal cache-miss retry plumbing, not a public domain-event
stream other services should subscribe to.

`PriceRefreshRequestConsumer` (`@KafkaListener`, same consumer group as the
rest of pricing, `stocker.pricing`) deserializes the request and calls
`refreshFromProvider` directly, with no timeout — the *next* search for that
item benefits, not the one that triggered the refresh.

No dedup of duplicate in-flight refresh requests for the same `itemId` yet —
harmless (idempotent re-fetch), just occasionally wasteful if many identical
misses land in the same window. A follow-up if refresh volume warrants it.

**Scoped down from the original design discussion**: escalating to `ingest/`'s
`ChainScraper`s as a second attempt inside the consumer (for chains ingest
already knows how to crawl) was considered but not implemented — it needs a
shared `Browser` instance and per-chain config that don't fit a per-request
consumer cleanly. The consumer currently only retries via `fetch/`'s
`WebFetcher`, same as the synchronous path.

## Configuration

```yaml
app:
  pricing:
    cache:
      freshness-window: ${STOCKER_PRICING_CACHE_FRESHNESS_WINDOW:PT24H}
      l1-ttl: ${STOCKER_PRICING_CACHE_L1_TTL:PT5M}
    fallback:
      timeout: ${STOCKER_PRICING_FALLBACK_TIMEOUT:PT3S}
    refresh:
      topic: ${STOCKER_PRICING_REFRESH_TOPIC:stocker.pricing.refresh-requests.v1}
```

`freshness-window` is long on purpose — `IngestScheduler`'s nightly crawl is
what's meant to keep this warm, not repeated live fetches.

## Deferred: Redis

Not built. `PriceCache` is strict cache-aside against Postgres by design
specifically so a Redis layer can slot in front of L2 later as a strictly
additive, fail-open accelerator (Redis miss/down → fall through to L2 exactly
as if Redis were absent) without changing this contract. Decided against
building it now because the single-instance deployment (`docker-compose.yml`)
can't cash in Redis's main benefit — a cache shared across instances — yet.

## Verification

```bash
./gradlew :services:pricing:test --tests '*PriceCacheTest*' --tests '*PriceSearchServiceTest*'
```

`PriceSearchServiceTest` covers cache-hit-skips-fetch, cache-miss success
(fetch/persist/publish/cache-warm), fetch failure (stale fallback + refresh
publish), and fallback timeout (stale fallback + refresh publish, via an
injected slow `WebFetcher` and a shortened `app.pricing.fallback.timeout`).
`PriceCacheTest` covers L1/L2 hit/miss/expiry in isolation.
