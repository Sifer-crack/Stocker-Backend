# catalog

Owns: the product catalog (items, categories, barcodes).
Data store: PostgreSQL (`catalog` database), schema managed by Flyway.
Status: **skeleton only** — boots, connects to Postgres/Kafka, exposes `GET /healthz`.

## Confirmed event topics

| Topic | Direction | Key |
|---|---|---|
| `stocker.catalog.events.v1` | produces | `householdId` or `itemId` |

Consumes are unconfirmed for now (a stub consumer exists on the catalog topic).

## Structure

- `api/` — HTTP surface (only `/healthz` today)
- `application/` — use cases (empty, TODO)
- `domain/` — entities/aggregates (empty, TODO)
- `infrastructure/` — persistence, Kafka wiring (stubs)

See `AGENT.md` for implementation TODOs.
