# AGENTS.md

Guidelines for AI agents and humans working in this repository.

## Project

Stocker Backend: event-driven household-inventory microservices. Mostly skeleton
(`/healthz` endpoints, stubbed Kafka handlers) — EXCEPT `services/pricing`,
which is the reference/implemented service (gRPC + JPA + web-fetch module, in
active development).

## Non-negotiables

- All services are Java 25 / Spring Boot 4.0.7 / Spring Cloud 2025.1.2, built
  with Gradle (Groovy DSL) multi-module. Do not bump these pins without explicit
  approval. gRPC pins are also fixed: protobuf plugin 0.9.4, protoc 3.25.9,
  grpc-java 1.83.1 (see `services/pricing/build.gradle`). Playwright for Java is
  pinned at 1.63.0 for `pricing`'s `ingest/` module — same rule, ask first.
- Do NOT add a new dependency or version that is not already pinned in the root
  `build.gradle` or a module `build.gradle` — ask first.
- Modules are added ONLY via `settings.gradle` `include` + a `build.gradle`.
- Package root is `com.stocker.<service>`. Skeleton services follow the
  identical layered structure: `api/rest/`, `application/`, `domain/`,
  `infrastructure/config/` (+ `infrastructure/outbox/` for shopping,
  `infrastructure/publisher/` for pricing). `pricing` legitimately extends the
  baseline (`api/grpc/`, `fetch/`, `model/`, `repository/`, `service/`,
  `src/main/proto/`). No service diverges without updating its module
  `AGENT.md` / `README.md`.
- Kafka: `enable-auto-commit: false`, at-least-once delivery, idempotent
  consumers. Consumer group id is `stocker.<service>`.
- Kafka topic names, partitions, and producers/consumers MUST match
  `infra/kafka/topics.yml` (single source of truth). New topics go in both
  `topics.yml` and `infra/kafka/kafka-init.sh`.
- `stocker.inventory.events.v1` is reserved (24 partitions, `enabled: false` in
  `topics.yml`): do not create or scaffold the Inventory / Pricing-Engine /
  Read-Model services yet.
- Postgres schema changes go through Flyway migrations under
  `src/main/resources/db/migration`; JPA uses `ddl-auto: validate`. Current
  migrations: identity/catalog = V1 placeholder only; shopping = V1 + V2 outbox
  table; pricing = V1 `price_records` (JSONB).
- No secrets in the repo. All config is env-var driven via `application.yml`
  with local dev defaults (`stocker`/`stocker`, `localhost:9092`,
  `jdbc:postgresql://localhost:5432/<service>`).

## Pricing specifics (reference service)

- Runs PostgreSQL + `flyway-database-postgresql` + JPA. MongoDB is NOT part of
  pricing anymore — it was migrated away. The `mongo` container and the
  `STOCKER_MONGO_URI` env var for `pricing` were removed from `docker-compose.yml`
  (pricing now sets `STOCKER_DB_URL`/`STOCKER_DB_USER`/`STOCKER_DB_PASSWORD` and
  depends on `postgres`, matching identity/catalog/shopping).
- gRPC server: `PriceRecordService` (`stocker.pricing.v1`) with `Fetch`/`Save`/`Search`
  RPCs, implemented by `api/grpc/PriceRecordGrpcController`; contract in
  `src/main/proto/price_record.proto`.
- `fetch/` web-crawling module: strategy pattern (`WebFetcher` interface +
  provider enum), one active impl via `STOCKER_FETCH_PROVIDER` (default
  `spread`; also `serpapi`, `scrapingbee`, `scraperapi`, `rapidapi`),
  WebClient-based. Wired end-to-end by `service/PriceSearchService`
  (crawl -> `RawProductPriceRecordMapper` -> persist), triggered by the gRPC
  `Search` RPC. See `services/pricing/FETCH_MODULE.md`.
  Live integration test: `integration/WoolworthsSpreadIntegrationTest`
  (Woolworths crawl -> `price_records`), skipped unless `STOCKER_SPREAD_API_KEY`
  is set in the root `.env`.
- `ingest/` scheduled, self-built scraping for New World / PAK'nSave / Woolworths
  NZ (Playwright, since those sites have no public JSON API) — decoupled from
  `Search`/`fetch/`, writes straight to `price_records` on a cron. Default off
  (`app.ingest.enabled=false`); selectors and Foodstuffs store-pin cookies are
  unverified against the live sites, so three gated live-verification tests
  (`STOCKER_INGEST_<CHAIN>_LIVE_VERIFY`) must be run first. See
  `services/pricing/INGEST_MODULE.md`.
- Uses Lombok for entities/beans, SLF4J logging.

## Commands

```bash
./gradlew build                          # full build + tests (needs JDK 25 toolchain)
./gradlew :services:<name>:bootJar       # e.g. :services:pricing:bootJar
./gradlew :services:identity:test        # single module
docker compose up postgres kafka kafka-init          # infra only
docker compose up --build                # infra + all services
```

JDK 25 must be installed; if auto-detection fails, add its path to
`org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`.

## Conventions

- Skeleton-first: keep stubbed services as stubs with `// TODO`, never scope-creep
  into business logic without being asked. Use `pricing` as the reference for a
  fully-built service.
- No comments in code unless required for clarity or a TODO.
- Tests: JUnit 5 + `@WebMvcTest` (services) / `@WebFluxTest` (gateway) using
  Spring Boot 4 packages (`org.springframework.boot.webmvc.test.autoconfigure.*`
  and `org.springframework.boot.webflux.test.autoconfigure.*`).
- Dockerfiles: multi-stage, Temurin 25, `curl` healthcheck on `/healthz`.
- Root-level `AGENTS.md` mirrors per-module `AGENT.md` files (gateway/,
  services/*/); keep them in sync when this file changes.