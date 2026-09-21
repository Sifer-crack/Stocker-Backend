# TASKS.md

Outstanding work for the Stocker Backend, grouped by area.

Last updated: 21/09/2026

## Repo-wide: Spring Boot 4 modular autoconfiguration gaps (fixed)

Spring Boot 4 split autoconfiguration into many small per-technology modules
(`spring-boot-hibernate`, `spring-boot-jdbc`, `spring-boot-kafka`, ... — a real
architecture change from Boot 2/3's single `spring-boot-autoconfigure` jar).
Two gaps this caused, discovered while getting `services/pricing` to actually
boot against a real database for the first time in this project's history:

- [x] **Flyway never actually ran** on any Postgres-backed service (identity,
  catalog, shopping, pricing) — `flyway-database-postgresql` alone no longer
  triggers Flyway's autoconfiguration; the dedicated `org.springframework.boot:
  spring-boot-flyway` module is required now. Added to all four services'
  `build.gradle` (via the version catalog: `libs.spring.boot.flyway`). Before
  this fix, `spring.flyway.enabled: true` silently did nothing and Hibernate's
  `ddl-auto: validate` would fail with "missing table" against any real,
  empty database — exactly the symptom `INGEST_MODULE.md`'s "Known Risks"
  section had already flagged (that database had simply never successfully
  booted this stack before).
- [x] **The Spring-managed `ObjectMapper` bean is Jackson 3**
  (`tools.jackson.databind.ObjectMapper`, a new groupId/major version), not
  the classic `com.fasterxml.jackson.databind.ObjectMapper` this codebase's
  Kafka payload (de)serialization already uses and is tested against.
  `services/pricing/infrastructure/config/JacksonConfig` now explicitly
  provides a classic Jackson 2 `ObjectMapper` bean (with `JavaTimeModule`
  registered) rather than migrating to the Jackson 3 API. If any other
  service starts autowiring `ObjectMapper` as a Spring bean, it will hit the
  same gap and need the same fix (or a real migration to Jackson 3).

## Shopping list -> price comparison -> frontend (`services/shopping`, `services/pricing`, `gateway`)

- [x] **End-to-end flow implemented.** Add item (`POST /api/shopping/items`, 201 + `pending`, never waits on pricing) ->
  one deadline-bound gRPC `CompareItemPrices` call to pricing (no retry) -> comparison persisted (`available`) ->
  shopping POSTs the full item state to the gateway's internal endpoint -> gateway relays it on the user's SSE stream
  (`GET /api/updates/shopping`). If pricing is slow/down the item stays `pending` and shopping backfills from
  `PriceRecordCaptured` events on `stocker.pricing.events.v1` through the same notify path. Details in each module's `AGENT.md`.
  Verified with unit tests, a real-Postgres integration test (`ShoppingItemPostgresIT`) and a real run of the shopping
  service with pricing down (201 in <0.5s, stays pending, one attempt, no retry).
- [ ] **Not yet verified together:** the full stack (gateway + pricing + Kafka + shopping) has not been run end to end; the Kafka
  consumer and the gateway push have only been tested in isolation, and pricing needs `STOCKER_SPREAD_API_KEY` (now passed through
  compose) to return real prices. The frontend does not consume any of this yet (it needs a fetch-based SSE client and calls to `/api/shopping/items`).
- [ ] Shopping has no list entity, edit/delete/check-off endpoints or household sharing; the gateway push is per-instance.
- [ ] `gateway/src/main/resources/application.yml`: the inventory route reuses `id: catalog` (duplicate route id) - one may shadow the other.
- [ ] Pricing's "cheapest" for a keyword is the cheapest matching listing, not a guaranteed identical product (see the pending match task in `CLAUDE.md`).

## Price match (`GET /api/pricing/match`) - done, ingest-based

- [x] `MatchItem` RPC + gateway endpoint: per-chain best match + labelled alternatives from the ingest's own scraped data; chosen products stored under the caller's item id for `compare`. Verified live: ingest scraped 83 milk products across New World / PAK'nSave / Woolworths, `match` returned one product per chain, `compare` priced them.
- [x] **Gateway identity-header bug fixed** (`JwtClaimForwardFilter` never ran after `StripPrefix`; identity headers were spoofable). Regression test added.
- [ ] Only milk is configured for ingest; Tim Tams / other categories need verified category URLs. `productUrl` is never populated.
- [ ] Matching is word overlap; a semantic matcher would slot in behind `matchMethod`.
- [ ] The compose pricing container: set `STOCKER_INGEST_DRY_RUN=false` in `.env` to persist scraped rows (default is dry-run).

## Price engine (`services/pricing`)

- [x] **gRPC server is now served** — Spring gRPC server starter added (approved; same pinned
  `springGrpcVersion` BOM gateway already used client-side), `spring.grpc.server.port` (default
  `9094`) configured, `PriceRecordGrpcController` is now `@GrpcService`.

- [x] **Event publishing wired** — `domain/port/EventPublisher` +
  `infrastructure/messaging/KafkaEventPublisher` publish `PriceRecordCaptured` to
  `stocker.pricing.events.v1` on every persist (on-demand and async refresh).
  `infrastructure/config/KafkaProducerConfig` now has a real `KafkaTemplate<String, String>` bean.
  Still open: consuming `stocker.catalog.events.v1` (`application/PricingEventService`,
  `infrastructure/messaging/KafkaEventConsumer`) — `KafkaConsumerConfig` remains a stub.

- [x] **Save-path bugs fixed** in `PriceRecordGrpcController` — `raw_attributes` parsed from the
  request JSON (defaults to `{}`), `captured_at` defaults to now when the zero proto timestamp is
  sent, `channel` validated against the DB CHECK values, negative `price_amount` rejected. Covered
  by `PriceRecordGrpcControllerSaveTest`.

- [x] **On-demand cache-aside + async refresh** — `Search` is no longer an uncached, unbounded
  synchronous fetch. Two-tier cache-aside (`service/cache/PriceCache`) over `price_records`, bounded
  synchronous fallback fetch, and a Kafka-driven async refresh (`refresh/PriceRefreshRequestConsumer`,
  new topic `stocker.pricing.refresh-requests.v1`) on cache miss. See `CACHE_MODULE.md`. Deferred:
  Redis as an additive accelerator; a canonical cross-chain `Item` model in `catalog` (on-demand
  currently relies on the caller-supplied `itemId` as its cross-chain grouping key).

- [x] **Gateway can now reach the price engine** — `GrpcOutboundConfig` wires a
  `PriceRecordServiceGrpc.PriceRecordServiceBlockingStub` bean, `gateway/application.yml` has a
  `pricing` channel, and `api/rest/PricingController` exposes `GET /api/pricing/search`. Gateway's
  `price_record.proto` is a duplicate of pricing's (no shared-proto mechanism in this repo yet —
  keep both in sync manually). `docker-compose.yml` now sets `STOCKER_GRPC_PRICING_ADDRESS` and
  `depends_on: pricing` for gateway so this actually resolves inside the compose network.

- [x] **CORS added to the gateway** — `infrastructure/config/CorsConfig` (a `CorsWebFilter`, not
  `spring.cloud.gateway.*.globalcors`, since that wouldn't cover a locally-implemented controller
  like `PricingController`). Allowed origins via `STOCKER_CORS_ALLOWED_ORIGINS`, defaulting to
  common local frontend dev ports.

- [x] **`price_stats` summary table added** — `model/PriceStats` + `repository/PriceStatsRepository`
  + `service/PriceStatsService` (current/lowest/highest/median price plus a JSONB history, one row
  per item_id+store_id), recomputed by `PriceStatsService.recordObservation` alongside every
  `price_records` write (ingest batch persist, on-demand search persist, and the gRPC `Save` RPC).
  `price_records` is unchanged and remains the raw, append-only source of truth this is derived
  from. Migration: `V3__create_price_stats.sql`.

- [x] **Ingest enabled and populated for real** — see `INGEST_MODULE.md`'s Day-1 checklist status.
  `app.ingest.enabled` and each chain now default to `true` with real, verified milk category URLs;
  a dedicated Neon `pricing` database was created (the previously-configured DB was a shared
  default database with unrelated tables from other in-progress work) and now holds 84 real rows
  in both `price_records` and `price_stats`.

- [x] **Shopping-list price comparison + savings display added** — new gRPC RPC
  `CompareShoppingList` on `PriceRecordService`, implemented by
  `service/ShoppingListComparisonService` (sums `price_stats.currentPrice` per requested item
  across chains, recommends the cheapest chain with full item coverage, falling back to the lowest
  partial total if no chain has full coverage) and `service/SavingsCalculatorService` (pure
  calculation: `savingsAmount(chain) = (selectedTotal - chainTotal) + discountAmount(chain)`, so an
  all-chains-tied-on-total case still shows discount-driven savings instead of a flat zero). New
  `service/servicearea/` module (`ServiceAreaProperties`, `app.service-area.supported-regions`
  placeholder list — needs real product sign-off before shipping) rejects requests outside the
  service area or with an empty item list via a shared `ServiceAreaException`. This is the first
  RPC in the repo to signal failure via `Status.INVALID_ARGUMENT`/`onError` rather than a
  response-payload flag (contrast `Save`'s `saved=false`) — future RPCs needing a
  request-cannot-be-served signal should follow this precedent. New `PriceStats.currentPromoFlag`
  column (`V4__add_price_stats_current_promo_flag.sql`) feeds the discount-savings calculation.
  Gateway exposes this via `POST /api/pricing/compare`; the `INVALID_ARGUMENT` status is mapped to
  HTTP 422 with the message in the body via a controller-scoped `@ExceptionHandler` (WebFlux's
  default error body omits the message otherwise, unless `server.error.include-message` is set
  repo-wide, which this deliberately avoids). Gateway's duplicate `price_record.proto` was kept in
  sync manually per the existing documented convention. Still open: the real supported-region list,
  and whether `shopping` should own persisted shopping lists that call this RPC (out of scope here —
  this RPC accepts an ad-hoc item list, not a persisted one).

- [ ] **Structure does not follow the convention**
  The module uses legacy `model/`, `repository/`, `service/`; other services use `api/rest`,
  `application`, `domain`, `infrastructure`. Root `AGENT.md` requires the layout to be identical, and
  the in-progress hexagonal refactor skipped pricing. (`domain/port/` and `infrastructure/messaging/`
  now exist alongside the legacy packages — the migration is still incomplete, not reconciled.)

- [ ] **Tests still missing**: `PriceFetcherService` (`Fetch`/`Save` entry points) has no dedicated
  unit test. Declare an in-process gRPC test dependency for a real `Search`/`Save` RPC-level test.

## Cross-cutting blockers (outside pricing)

These make `./gradlew build` fail but are unrelated to the price engine. `:services:pricing:build`
passes in isolation.

- [ ] `services/identity/.../application/HouseholdEventService.java:3` imports a missing
  `com.stocker.identity.domain.port.EventConsumer`.
- [ ] `services/analytics/.../test/.../HealthzControllerTest.java:12` references the deleted
  `HealthzController`.
- [ ] The uncommitted hexagonal refactor is inconsistent (pricing not migrated; docs out of sync).
