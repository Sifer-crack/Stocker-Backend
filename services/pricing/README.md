# pricing

Owns: promotions and pricing.
Data store: PostgreSQL (`pricing` database, `price_records` table with JSONB). Flyway manages the schema.
Status: boots, connects to Postgres/Kafka, exposes `GET /healthz`, and serves a real gRPC
`PriceRecordService` (`@GrpcService`, Spring gRPC server on `spring.grpc.server.port`) with `Fetch`,
`Save`, `Search`, and `CompareShoppingList`. `Search` is a cache-aside read over `price_records`
(in-process L1 + Postgres freshness-window L2) with a bounded synchronous fallback to the `fetch/`
web-crawl module and an async Kafka-driven refresh escalation on cache miss — see `CACHE_MODULE.md`.
`CompareShoppingList` sums `price_stats` per requested item across chains, recommends the cheapest
chain (full item coverage preferred; falls back to the lowest partial total otherwise), and
computes a savings amount per chain relative to a selected/cheapest baseline plus any currently
active discounts — see `service/ShoppingListComparisonService` and `service/SavingsCalculatorService`.
Requests outside `app.service-area.supported-regions` (or with an empty item list) are rejected with
`Status.INVALID_ARGUMENT`. A scheduled, self-built `ingest/` module (New World / PAK'nSave /
Woolworths NZ via Playwright) populates `price_records` broadly on a cron, decoupled from `Search`;
default off behind `app.ingest.enabled`. See `INGEST_MODULE.md`.

## Confirmed event topics

| Topic | Direction | Key | Purpose |
|---|---|---|---|
| `stocker.pricing.events.v1` | produces | `itemId` | Public `PriceRecordCaptured` domain event (`infrastructure/messaging/KafkaEventPublisher`) |
| `stocker.promotions.events.v1` | produces (unused) | `householdId` | Reserved, no producer yet |
| `stocker.pricing.refresh-requests.v1` | produces + consumes (internal) | `itemId` | Pricing's own cache-miss retry queue — not a public event stream, see `CACHE_MODULE.md` |
| `stocker.catalog.events.v1` | consumes (stub only) | `householdId` or `itemId` | `infrastructure/config/KafkaConsumerConfig` — TODO, no business logic yet |

## Structure

- `api/grpc/` — `@GrpcService` implementing `PriceRecordService` (`api/grpc/v1`, generated from proto)
- `api/rest/` — HTTP surface (only `/healthz` today)
- `model/` — `PriceRecord`, `PriceStats` JPA entities (Lombok)
- `repository/` — `PriceRecordRepository`, `PriceStatsRepository` (Spring Data JPA)
- `service/` — `PriceFetcherService`, `PriceSearchService`, `RawProductPriceRecordMapper`,
  `ShoppingListComparisonService`, `SavingsCalculatorService`
- `service/cache/` — on-demand cache-aside (`PriceCache`, `PricingCacheProperties`); see `CACHE_MODULE.md`
- `service/servicearea/` — `ServiceAreaProperties` (`app.service-area.supported-regions`),
  `ServiceAreaException` (shared by `CompareShoppingList`'s two "outside service area" checks)
- `refresh/` — async cache-miss refresh via Kafka (`PriceRefreshRequestConsumer`); see `CACHE_MODULE.md`
- `domain/port/` — `EventPublisher` port
- `infrastructure/messaging/` — `KafkaEventPublisher` (publishes `PriceRecordCaptured`)
- `infrastructure/config/` — Kafka producer/consumer wiring
- `fetch/` — web-crawl module (providers, config, models); see `FETCH_MODULE.md`
- `ingest/` — scheduled self-built supermarket scraping (Playwright); see `INGEST_MODULE.md`

> TODO: `model/`, `repository/`, `service/` still don't match the repo-wide `domain`/`application`
> layered convention used by the other services — a structural migration, tracked separately in
> root `TASKS.md`, not part of the cache/refresh work above.

See `AGENT.md` for implementation TODOs, `CACHE_MODULE.md` for the on-demand cache/refresh design.
