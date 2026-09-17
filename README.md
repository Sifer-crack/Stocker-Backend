# Stocker Backend

Household-inventory / stock-tracking microservices monorepo. Event-driven
skeleton built with Java 25, Spring Boot 4, Spring Cloud Gateway, Spring gRPC,
Kafka, and Postgres/Mongo.

Skeleton only: every service exposes a `/healthz` endpoint, Kafka handlers are
stubs (`// TODO`), and there is no business logic or auth yet.

## Architecture

```
[Frontend] --HTTP--> [Gateway :8080] --gRPC--> [services]
```

| Service       | Port | Storage              | Kafka events                       |
|---------------|------|----------------------|------------------------------------|
| `gateway`     | 8080 | -                    | -                                  |
| `identity`    | 8081 | Postgres (`identity`) | produces `stocker.household.events.v1` |
| `catalog`     | 8082 | Postgres (`catalog`)  | produces `stocker.catalog.events.v1`   |
| `shopping`    | 8083 | Postgres (`shopping`) + Outbox | produces `stocker.shopping.events.v1` |
| `pricing`     | 8084 | MongoDB               | produces `stocker.pricing.events.v1`   |
| `notifications` | 8085 | -                   | consumes all event topics              |
| `analytics`   | 8086 | -                     | consumes all event topics              |

Event-driven flows, Kafka topic registry, and planned C4 docs live in
`infra/kafka/topics.yml` and `docs/architecture`.

> `stocker.inventory.events.v1` (24 partitions) is **reserved** and must not be
> created until the Inventory read-model service is scaffolded.

## Prerequisites

- JDK 25 (toolchain; add your install path to `org.gradle.java.installations.paths`
  in `~/.gradle/gradle.properties` or rely on auto-detection)
- Docker + Docker Compose (for the infra stack)

## Build & test

```bash
./gradlew build
./gradlew :services:identity:test      # single module
./gradlew clean build
```

## Run

```bash
# infrastructure only (Postgres, Mongo, Kafka + topic init)
docker compose up postgres mongo kafka kafka-init

# everything, including all services (builds images)
docker compose up --build
```

Defaults: DB `stocker`/`stocker`, Kafka at `localhost:9092`, gateway `http://localhost:8080`.

Each service is configurable via env vars (`STOCKER_DB_URL`, `STOCKER_DB_USER`,
`STOCKER_DB_PASSWORD`, `STOCKER_MONGO_URI`, `KAFKA_BOOTSTRAP_SERVERS`, `SERVER_PORT`).

## Repository layout

```
build.gradle              parent build: pinned versions, toolchain, conventions
settings.gradle           module registry (add new services here)
gateway/                  REST in / gRPC out gateway (WebFlux + Spring gRPC)
services/
  identity/ catalog/ shopping/ pricing/ notifications/ analytics/
    src/main/java/com/stocker/<service>/api|application|domain|infrastructure
    Dockerfile, application.yml, db/migration (Flyway), README.md, AGENT.md
infra/                    kafka (topic registry), postgres, observability, ingress
docs/architecture/        planned C4 diagrams
```

Service-internal layers are identical across modules: `api/rest/`, `application/`,
`domain/`, `infrastructure/config/` (+ `outbox/` or `publisher/` where noted).
See root `AGENTS.md` for the enforced conventions.
