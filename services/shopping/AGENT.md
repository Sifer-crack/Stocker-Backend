# AGENT.md — shopping

## Implemented (skeleton)

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8083 (`SERVER_PORT`), `@EnableScheduling`.
- `GET /healthz` via `api/rest/HealthzController`.
- Postgres connection via `STOCKER_DB_URL`/`STOCKER_DB_USER`/`STOCKER_DB_PASSWORD` (defaults: localhost:5432/shopping, stocker/stocker).
- Flyway: `V1__placeholder.sql` + `V2__create_outbox.sql` (outbox table scaffold: id, aggregate_type, aggregate_id, event_type, payload jsonb, occurred_at, status, published_at).
- `infrastructure/outbox/OutboxPublisher` — `@Scheduled` stub, no dispatch logic.
- Kafka producer/consumer wiring stubs under `infrastructure/config/` (no logic).
- `HealthzControllerTest` (`@WebMvcTest`, no DB/broker needed).

## Stubbed / TODO

- `OutboxPublisher#publish` — implement polling of PENDING outbox rows and publish to `stocker.shopping.events.v1`; mark published only after a successful send (at-least-once).
- `infrastructure/config/KafkaProducerConfig` — wire `KafkaTemplate<String,String>`; prefer a transactional send so outbox rows are marked only on success.
- `infrastructure/config/KafkaConsumerConfig#onEvent` — implement handler.
- Real `domain/`, `application/`, `infrastructure/persistence` layers + real migrations.

## Conventions

- Package root: `com.stocker.shopping`.
- Outbox table name: `outbox` (see `db/migration/V2__create_outbox.sql`). Keep this name stable.
- Outbox poll interval: `app.outbox.poll-interval-ms` (default 5000).
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
- Only `/healthz` (and actuator) may be exposed; keep the module structurally identical to the other services.
