# AGENT.md — notifications

## Implemented (skeleton)

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8085 (`SERVER_PORT`).
- `GET /healthz` via `api/rest/HealthzController`.
- Kafka consumer wiring stub under `infrastructure/config/`, subscribed to all five confirmed topics via `app.kafka.consumer.topics`.
- No data store (stateless by design).
- `HealthzControllerTest` (`@WebMvcTest`, no broker needed).

## Stubbed / TODO

- `infrastructure/config/KafkaConsumerConfig#onEvent` — implement handler + idempotent dedupe.
- Outbound channel adapters (email/push/SMS) in `infrastructure/` — none exist yet.
- `infrastructure/config/KafkaProducerConfig` — intentionally empty unless this service starts publishing.

## Conventions

- Package root: `com.stocker.notifications`.
- Stateless: do NOT add a data store unless the architecture changes.
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
- Only `/healthz` (and actuator) may be exposed; keep the module structurally identical to the other services.
