# AGENT.md — inventory (pantry service)

## Implemented

- Spring Boot 4.0.7 app on port 8087 (`SERVER_PORT`). This is the **pantry** service (`PantryItem`), not the
  reserved Inventory read-model service.
- Pantry CRUD under `/pantry-items` (gateway: `/api/pantry-items/**`): add (merges quantity for the same product),
  list, update quantity, delete. `CatalogClient` calls catalog over REST.
- **Expiry fields** (Sprint 2): `expirationDate` and `notifyDaysBefore` (threshold, default 3 days when unset, 0 =
  only on the day). `POST /pantry-items` accepts them, `PUT /pantry-items/{id}/expiry` changes or clears them.
  A threshold without an expiration date, or a negative one, is a 400.
- `GET /internal/pantry-items/expiring?asOf=YYYY-MM-DD` lists every user's items whose notification window has
  opened. **Service-to-service only** (read by notifications); the gateway never routes `/internal/**`.
- Schema is managed by Flyway (`db/migration`), history table `flyway_schema_history_inventory`.
  `baseline-on-migrate` at version 1: the shared Neon database already has a hand-made `pantry_items`, so V1 is
  skipped there and only V2 (expiry columns, additive, `IF NOT EXISTS`) runs. A fresh database gets both.
  Use `STOCKER_DB_MIGRATE_URL` for Neon's direct (non-pooler) endpoint if needed.
- `GET /healthz`.

## Tests

See `docs/testing.md`. `./gradlew :services:inventory:test` is offline. `PantryItemPostgresIT` runs only with
`STOCKER_INVENTORY_DB_IT=true` plus `STOCKER_DB_URL/USER/PASSWORD` pointing at a throwaway Postgres (never Neon).

## Known gaps

- Controllers take `userId` from request params and do not check `X-User-Id`; any signed-in user can read another
  user's pantry through the gateway. Fix before relying on pantry privacy.
- `infrastructure/config/KafkaConsumerConfig` / `KafkaProducerConfig` are leftovers copied from analytics (stubs).
