# TASKS.md

Outstanding work for the Stocker Backend, grouped by area.

Last updated: 18/09/2026

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
