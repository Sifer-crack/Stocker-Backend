# AGENT.md — identity

## Implemented (skeleton)

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8081 (`SERVER_PORT`).
- `GET /healthz` via `api/rest/HealthzController`.
- Postgres connection via `STOCKER_DB_URL`/`STOCKER_DB_USER`/`STOCKER_DB_PASSWORD` (defaults: localhost:5432/identity, stocker/stocker).
- Flyway enabled; only `V1__placeholder.sql` exists.
- Kafka producer/consumer wiring stubs under `infrastructure/config/` (no logic).
- `HealthzControllerTest` (`@WebMvcTest`, no DB/broker needed).

## Stubbed / TODO

- `infrastructure/config/KafkaProducerConfig` — wire `KafkaTemplate<String,String>`, add produce methods.
- `infrastructure/config/KafkaConsumerConfig#onEvent` — implement handler.
- Real `domain/`, `application/`, `infrastructure/persistence` layers.
- AuthN/AuthZ — deliberately NOT wired (no `spring-boot-starter-security`). When added, keep `/healthz` and actuator unauthenticated.
- Real Flyway migrations (replace `V1__placeholder.sql`).

## Conventions

- Package root: `com.stocker.identity`.
- Layers: `api` → `application` → `domain`, with ports implemented in `infrastructure`.
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
- Only `/healthz` (and actuator) may be exposed; keep the module structurally identical to the other services.
