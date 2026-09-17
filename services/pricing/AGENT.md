# AGENT.md — pricing

## Implemented

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8084 (`SERVER_PORT`).
- `GET /healthz` via `api/rest/HealthzController`.
- PostgreSQL via `spring.datasource.*` / env `STOCKER_DB_URL`, `STOCKER_DB_USER`, `STOCKER_DB_PASSWORD`
  (default: `jdbc:postgresql://localhost:5432/pricing`, `stocker`/`stocker`). Schema managed by Flyway
  under `db/migration` (`V1__create_price_records.sql`); JPA `ddl-auto: validate`.
- gRPC server: `PriceRecordService` (`stocker.pricing.v1`) with `Fetch`, `Save` and `Search` RPCs, implemented by
  `api/grpc/PriceRecordGrpcController`.
- `model/PriceRecord` JPA entity (Lombok) mapping the `price_records` table (JSONB `raw_attributes`).
- `repository/PriceRecordRepository` (Spring Data JPA).
- `service/PriceFetcherService` — fetches/saves price records via the repository (SLF4J logging).
- `service/PriceSearchService` — web search pipeline: runs the active `WebFetcher`, maps crawled
  `RawProduct`s to `PriceRecord`s via `service/RawProductPriceRecordMapper`, and persists them.
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
- Kafka producer/consumer wiring stubs under `infrastructure/config/` (no logic).
- Infrastructure/publisher `EventPublisher` port retained for future event emission.

## Conventions

- Package root: `com.stocker.pricing`.
- PostgreSQL, JSONB, Flyway migrations under `src/main/resources/db/migration` — MongoDB is NOT part of
  the pricing tech stack.
- gRPC contract defined in `src/main/proto/price_record.proto`, generated into `com.stocker.pricing.api.grpc.v1`.
- Lombok is used for model/repository beans; logging via SLF4J.
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
- Only `/healthz` (and actuator) may be exposed; keep the module structurally identical to the other services.
