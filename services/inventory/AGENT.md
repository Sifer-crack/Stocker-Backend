# AGENT.md — analytics

## Implemented (skeleton)

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8086 (`SERVER_PORT`).
- `GET /healthz` via `api/rest/HealthzController`.
- Kafka consumer wiring stub under `infrastructure/config/`, subscribed to all five confirmed topics via `app.kafka.consumer.topics`.
- No data store (optional store deliberately NOT wired).
- `HealthzControllerTest` (`@WebMvcTest`, no broker needed).

## Stubbed / TODO

- `infrastructure/config/KafkaConsumerConfig#onEvent` — implement handler + idempotent dedupe.
- Event sink adapter in `infrastructure/` — decide store (MongoDB/ClickHouse/object storage) with the architecture team first, then add the dependency + adapter here.
- `infrastructure/config/KafkaProducerConfig` — intentionally empty unless this service starts publishing.

## Conventions

- Package root: `com.stocker.analytics`.
- Optional store is unconfirmed — do NOT add a store dependency without updating this file and `docs/architecture`.
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
- Only `/healthz` (and actuator) may be exposed; keep the module structurally identical to the other services.
