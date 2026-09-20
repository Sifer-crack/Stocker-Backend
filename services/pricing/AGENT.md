# AGENT.md — pricing

## Implemented

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8084 (`SERVER_PORT`).
- `GET /healthz` via `api/rest/HealthzController`.
- PostgreSQL via `spring.datasource.*` / env `STOCKER_DB_URL`, `STOCKER_DB_USER`, `STOCKER_DB_PASSWORD`
  (default: `jdbc:postgresql://localhost:5432/pricing`, `stocker`/`stocker`). Schema managed by Flyway
  under `db/migration` (`V1__create_price_records.sql`); JPA `ddl-auto: validate`.
- gRPC server: `PriceRecordService` (`stocker.pricing.v1`) with `Fetch`, `Save`, `Search`, and
  `CompareShoppingList` RPCs, implemented by `api/grpc/PriceRecordGrpcController` (`@GrpcService`,
  Spring gRPC server starter, `spring.grpc.server.port` default `9094`). `Save` validates `channel`
  against the DB CHECK values and rejects negative `price_amount`; `raw_attributes`/`captured_at`
  are parsed/defaulted before persisting. `CompareShoppingList` is the first RPC in this codebase to
  signal a request-cannot-be-served condition via `Status.INVALID_ARGUMENT`/`onError` rather than a
  response-payload flag (which `Save` uses for its persistence-validation failures) — a precedent
  for future RPCs needing this distinction.
- `model/PriceRecord` JPA entity (Lombok) mapping the `price_records` table (JSONB `raw_attributes`).
- `repository/PriceRecordRepository` (Spring Data JPA).
- `service/PriceFetcherService` — fetches/saves price records via the repository (SLF4J logging).
- `service/PriceSearchService` — on-demand `Search`: cache-aside via `service/cache/PriceCache`
  (in-process L1 + Postgres freshness-window L2); on miss, a bounded-timeout synchronous fallback
  to the active `WebFetcher`, falling back further to best-effort stale/empty data plus an async
  refresh request on `stocker.pricing.refresh-requests.v1` if the fallback times out or fails.
  `refresh/PriceRefreshRequestConsumer` retries that fetch off the request path (no tight timeout)
  and shares the same persist/publish/cache-warm logic (`PriceSearchService.refreshFromProvider`).
  See `CACHE_MODULE.md`.
- `domain/port/EventPublisher` + `infrastructure/messaging/KafkaEventPublisher` — publish
  `PriceRecordCaptured` to `stocker.pricing.events.v1` on every persist (on-demand and async refresh).
- `service/PriceStatsService` + `model/PriceStats` (`price_stats` table) — a derived per-(itemId,
  storeId) summary (current/lowest/highest/median price + `currentPromoFlag` + a JSONB history),
  recomputed alongside every `price_records` write. `price_records` remains the raw, append-only
  source of truth. `currentPromoFlag` (V4 migration) mirrors `currentPrice`'s "always the latest
  observation" semantics and is what `SavingsCalculatorService` uses to derive discount-only savings.
- `service/ShoppingListComparisonService` + `service/SavingsCalculatorService` — back the
  `CompareShoppingList` RPC. The former sums `price_stats.currentPrice` per requested item across
  chains (min price wins if a chain has multiple stores carrying the same item), validates the
  request against `service/servicearea/ServiceAreaProperties`, and recommends the cheapest
  full-item-coverage chain (falling back to the lowest partial total if no chain has full coverage).
  The latter is a pure calculation with no dependencies: `savingsAmount(chain) = (selectedTotal -
  chainTotal) + discountAmount(chain)`, so an all-chains-tied-on-total case still surfaces
  discount-driven savings instead of a flat zero.
- `fetch/` web-crawl module (strategy pattern): `WebFetcher` interface + the active provider selected by
  `app.fetch.provider` (`spread` default). See `FETCH_MODULE.md`.
- `ingest/` scheduled self-built supermarket scraping (New World / PAK'nSave / Woolworths NZ) via
  Playwright for Java, fully decoupled from `Search`/`fetch/`. Default off (`app.ingest.enabled=false`);
  selectors/store-pin cookies are unverified against the live sites — three live-verification tests
  gated per-chain (`STOCKER_INGEST_<CHAIN>_LIVE_VERIFY`) must be run first. See `INGEST_MODULE.md`.
  Dockerfile now installs headless Firefox + its system libs for this module
  (Firefox, not Chromium: verified against the live sites, see INGEST_MODULE.md).
- `ingest/sitemap/` two-tier (in-memory + Postgres `sitemap_category_cache`, V2 migration)
  cache of each chain's sitemap-derived category URLs, keyed by keyword; 7-day TTL by default
  (`app.ingest.sitemap-cache-ttl-days`). See `INGEST_MODULE.md`.
- `infrastructure/config/KafkaProducerConfig` — real `KafkaTemplate<String, String>` bean (not a stub).
- `infrastructure/config/KafkaConsumerConfig` — still a stub; consuming `stocker.catalog.events.v1`
  (`application/PricingEventService`) is not implemented.

> TODO (tracked in root `TASKS.md`):
> - `model/` + `repository/` + `service/` layouts still diverge from the repo-wide layered convention.
> - `stocker.catalog.events.v1` consumption is unimplemented (stub listener only).
> - Redis as an additive, fail-open accelerator in front of `PriceCache`'s L2 — deferred, see `CACHE_MODULE.md`.
> - A canonical cross-chain `Item` model in `catalog` — on-demand `Search` currently relies on the
>   caller-supplied `itemId` as its only cross-chain grouping key (see `CACHE_MODULE.md`).

## Conventions

- Package root: `com.stocker.pricing`.
- PostgreSQL, JSONB, Flyway migrations under `src/main/resources/db/migration` — MongoDB is NOT part of
  the pricing tech stack.
- gRPC contract defined in `src/main/proto/price_record.proto`, generated into `com.stocker.pricing.api.grpc.v1`.
- Lombok is used for model/repository beans; logging via SLF4J.
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
- Only `/healthz` (and actuator) may be exposed; keep the module structurally identical to the other services.
