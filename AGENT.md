# AGENT.md

Guidelines for AI agents and humans working in this repository.

## Project

Stocker Backend: event-driven household-inventory microservices. Skeleton only
(`/healthz` endpoints, stubbed Kafka handlers, no business logic).

## Non-negotiables

- All services are Java 25 / Spring Boot 4.0.7 / Spring Cloud 2025.1.2, built
  with Gradle (Groovy DSL) multi-module. Do not bump these pins without explicit
  approval.
- Do NOT add a new dependency or version that is not already pinned in the root
  `build.gradle` or module `build.gradle` — ask first.
- Modules are added ONLY via `settings.gradle` `include` + a `build.gradle`.
- Package root is `com.stocker.<service>`. The layered structure is identical
  across services: `api/rest/`, `application/`, `domain/`,
  `infrastructure/config/` (+ `infrastructure/outbox/` for shopping,
  `infrastructure/publisher/` for pricing). No service diverges structurally
  without updating this file and its module README.
- Kafka: `enable-auto-commit: false`, at-least-once delivery, idempotent
  consumers. Consumer group id is `stocker.<service>`.
- Kafka topic names, partitions, and producers/consumers MUST match
  `infra/kafka/topics.yml` (single source of truth).
- `stocker.inventory.events.v1` is reserved (24 partitions): do not create or
  scaffold the Inventory / Pricing-Engine / Read-Model services yet.
- Postgres schema changes go through Flyway migrations under
  `src/main/resources/db/migration`. Only shopping has more than the V1
  placeholder (V2 outbox table).
- No secrets in the repo. All config is env-var driven via `application.yml`
  with local dev defaults (`stocker`/`stocker`, `localhost:9092`).

## Commands

```bash
./gradlew build              # full build + tests (needs JDK 25 toolchain)
./gradlew :services:<name>:bootJar
docker compose up --build    # infra + all services
```

## Conventions

- Skeleton-first: implement stubs with `// TODO`, never scope-creep into
  business logic without being asked.
- No comments in code unless required for clarity or a TODO.
- Tests: JUnit 5 + `@WebMvcTest` (services) / `@WebFluxTest` (gateway) using
  Spring Boot 4 packages (`org.springframework.boot.webmvc.test.autoconfigure.*`
  and `org.springframework.boot.webflux.test.autoconfigure.*`).
- Dockerfiles: multi-stage, Temurin 25, `curl` healthcheck on `/healthz`.
