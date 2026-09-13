# AGENT.md — pricing

## Implemented

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8084 (`SERVER_PORT`).
- `GET /healthz` via `api/rest/HealthzController`.
- PostgreSQL via `spring.datasource.*` / env `STOCKER_DB_URL`, `STOCKER_DB_USER`, `STOCKER_DB_PASSWORD`
  (default: `jdbc:postgresql://localhost:5432/pricing`, `stocker`/`stocker`). Schema managed by Flyway
  under `db/migration` (`V1__create_price_records.sql`); JPA `ddl-auto: validate`.
- gRPC server: `PriceRecordService` (`stocker.pricing.v1`) with `Fetch` and `Save` RPCs, implemented by
  `api/grpc/PriceRecordGrpcController`.
- `model/PriceRecord` JPA entity (Lombok) mapping the `price_records` table (JSONB `raw_attributes`).
- `repository/PriceRecordRepository` (Spring Data JPA).
- `service/PriceFetcherService` — fetches/saves price records via the repository (SLF4J logging).
- Kafka producer/consumer wiring stubs under `infrastructure/config/` (no logic).
- Infrastructure/publisher `EventPublisher` port retained for future event emission.

> TODO: items below are stale and must be reconciled with the code (tracked in root `TASKS.md`):
> - The gRPC server is **not** currently started (no Spring gRPC server starter / `spring.grpc.server`),
>   and `PriceRecordGrpcController` is `@Controller`, not `@GrpcService`.
> - `infrastructure/config/` Kafka stubs and the `infrastructure/publisher` `EventPublisher` port were
>   deleted; the replacements (`domain/port/EventPublisher`, `infrastructure/messaging/*`,
>   `application/PricingEventService`) do not exist yet.
> - `model/` + `repository/` + `service/` layouts still diverge from the repo-wide layered convention.

## Conventions

- Package root: `com.stocker.pricing`.
- PostgreSQL, JSONB, Flyway migrations under `src/main/resources/db/migration` — MongoDB is NOT part of
  the pricing tech stack.
- gRPC contract defined in `src/main/proto/price_record.proto`, generated into `com.stocker.pricing.api.grpc.v1`.
- Lombok is used for model/repository beans; logging via SLF4J.
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
- Only `/healthz` (and actuator) may be exposed; keep the module structurally identical to the other services.
