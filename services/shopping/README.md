# shopping

Owns: shopping lists.
Data store: PostgreSQL (`shopping` database), schema managed by Flyway, **transactional outbox** for outbound events.
Status: **skeleton only** — boots, connects to Postgres/Kafka, exposes `GET /healthz`. Outbox table exists; publisher is a stub.

## Confirmed event topics

| Topic | Direction | Key |
|---|---|---|
| `stocker.shopping.events.v1` | produces (via outbox) | `householdId` |
| `stocker.pricing.events.v1` | consumes (to confirm) | `householdId` |
| `stocker.promotions.events.v1` | consumes (to confirm) | `householdId` |

## Structure

- `api/` — HTTP surface (only `/healthz` today)
- `application/` — use cases (empty, TODO)
- `domain/` — entities/aggregates (empty, TODO)
- `infrastructure/outbox/` — `OutboxPublisher` stub (`@Scheduled`)
- `infrastructure/config/` — Kafka wiring stubs
- `db/migration/` — `V1__placeholder.sql`, `V2__create_outbox.sql`

See `AGENT.md` for implementation TODOs.
