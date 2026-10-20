# AGENT.md — notifications

## Implemented

- Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8085 (`SERVER_PORT`).
- **Pantry expiry notifications** (Sprint 2). Own PostgreSQL store (the `notifications` database **on Neon**, Flyway
  `V1__create_expiration_notifications.sql`, history table `flyway_schema_history_notifications`).
  - `application/ExpirationScanService` asks inventory (`GET /internal/pantry-items/expiring?asOf=`, via
    `infrastructure/client/InventoryPantryClient`, base URL `STOCKER_INVENTORY_URL`) which items have entered
    their notification window, and stores one notification per (pantry item, expiration date). Unique
    constraint in the database as a backstop.
  - `infrastructure/scheduling/ExpirationScanScheduler` runs the scan every `app.notifications.scan-interval`
    (default 15 minutes). "Today" is the calendar day in `app.notifications.zone` (default Pacific/Auckland).
  - `api/rest/NotificationController`: `GET /notifications` and `POST /notifications/{id}/acknowledge`. The user
    comes from the `X-User-Id` header the gateway sets from the JWT (401 without it). The gateway exposes these as
    `/api/notifications/**`.
- `GET /healthz` via `api/rest/HealthzController`.
- Kafka consumer wiring stub under `infrastructure/config/` (unchanged, not used by expiry).

## Tests

See `docs/testing.md`. `./gradlew :services:notifications:test` is offline. The database test
`ExpirationNotificationPostgresIT` runs only with `STOCKER_NOTIFICATIONS_DB_IT=true` and `STOCKER_DB_URL` pointing at
a database you can write to (a throwaway test fixture; runtime always uses Neon).

## Stubbed / TODO

- `infrastructure/config/KafkaConsumerConfig#onEvent` — implement handler + idempotent dedupe.
- Push delivery (SSE/email) — today the frontend polls `GET /notifications`.
- `infrastructure/config/KafkaProducerConfig` — intentionally empty unless this service starts publishing.

## Conventions

- Package root: `com.stocker.notifications`.
- The inventory call is service-to-service only; never route `/internal/**` through the gateway.
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
- Database: Neon only. `STOCKER_NOTIFICATIONS_DB_URL` (root `.env`) is the `notifications` database; the role and
  password are the shared `STOCKER_DB_USER` / `STOCKER_DB_PASSWORD`. No local Postgres default exists on purpose.
