# pricing

Owns: promotions and pricing.
Data store: MongoDB (`pricing` database). Schemaless — no Flyway.
Status: **skeleton only** — boots, connects to MongoDB/Kafka, exposes `GET /healthz`. Publisher is a stub interface.

## Confirmed event topics

| Topic | Direction | Key |
|---|---|---|
| `stocker.promotions.events.v1` | produces | `householdId` |
| `stocker.pricing.events.v1` | produces | `householdId` |
| `stocker.catalog.events.v1` | consumes (to confirm) | `householdId` or `itemId` |

## Structure

- `api/` — HTTP surface (only `/healthz` today)
- `application/` — use cases (empty, TODO)
- `domain/` — entities/aggregates (empty, TODO)
- `infrastructure/publisher/` — `EventPublisher` interface + `KafkaEventPublisher` stub
- `infrastructure/persistence/` — MongoDB adapters (empty, TODO)
- `infrastructure/config/` — Kafka wiring stubs

See `AGENT.md` for implementation TODOs.
