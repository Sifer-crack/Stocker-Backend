# AGENT.md — pricing

## Implemented (skeleton)

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8084 (`SERVER_PORT`).
- `GET /healthz` via `api/rest/HealthzController`.
- MongoDB connection via `spring.data.mongodb.uri` / env `STOCKER_MONGO_URI` (default: mongodb://localhost:27017/pricing). No Flyway (schemaless).
- `infrastructure/publisher/EventPublisher` interface + `KafkaEventPublisher` stub (no implementation).
- Kafka producer/consumer wiring stubs under `infrastructure/config/` (no logic).
- `HealthzControllerTest` (`@WebMvcTest`, no DB/broker needed).

## Stubbed / TODO

- `KafkaEventPublisher#publish` — implement via `KafkaTemplate<String,String>`; acks=all, key=householdId.
- `infrastructure/config/KafkaConsumerConfig#onEvent` — implement handler.
- Real `domain/`, `application/`, `infrastructure/persistence` (Mongo collections + indexes) layers.

## Conventions

- Package root: `com.stocker.pricing`.
- Event publisher port location: `infrastructure/publisher/EventPublisher` — keep it as the stable contract.
- MongoDB, not Postgres: no `db/migration/`, no Flyway. Do not add JPA/Flyway here.
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
- Only `/healthz` (and actuator) may be exposed; keep the module structurally identical to the other services.
