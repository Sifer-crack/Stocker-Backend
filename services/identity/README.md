# identity

Owns: authentication, users, and households.
Data store: PostgreSQL (`identity` database), schema managed by Flyway.
Status: **skeleton only** — boots, connects to Postgres/Kafka, exposes `GET /healthz`. No auth logic implemented yet (module boundary only).

## Confirmed event topics

| Topic | Direction | Key |
|---|---|---|
| `stocker.household.events.v1` | produces | `householdId` |

Consumes are unconfirmed for now (a stub consumer exists on the household topic).

## Structure

- `api/` — HTTP surface (only `/healthz` today)
- `application/` — use cases (empty, TODO)
- `domain/` — entities/aggregates (empty, TODO)
- `infrastructure/` — persistence, Kafka wiring (stubs)

See `AGENT.md` for implementation TODOs.
