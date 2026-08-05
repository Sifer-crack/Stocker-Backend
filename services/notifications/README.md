# notifications

Owns: outbound notifications (email/push/SMS) derived from domain events.
Data store: **none** — stateless; consumes events from Kafka and fans out.
Status: **skeleton only** — boots, connects to Kafka, exposes `GET /healthz`. Consumers are stubs.

## Confirmed event topics

| Topic | Direction | Key |
|---|---|---|
| `stocker.household.events.v1` | consumes | `householdId` |
| `stocker.catalog.events.v1` | consumes | `householdId` or `itemId` |
| `stocker.shopping.events.v1` | consumes | `householdId` |
| `stocker.promotions.events.v1` | consumes | `householdId` |
| `stocker.pricing.events.v1` | consumes | `householdId` |

## Structure

- `api/` — HTTP surface (only `/healthz` today)
- `application/` — use cases (empty, TODO)
- `domain/` — entities/aggregates (empty, TODO)
- `infrastructure/` — Kafka wiring stubs, outbound channel adapters (TODO)

See `AGENT.md` for implementation TODOs.
