# AGENT.md — inventory (pantry service)

## Implemented

- Spring Boot 4.0.7 app on port 8087 (`SERVER_PORT`). This is the **pantry** service (`PantryItem`), not the reserved Inventory
  read-model service (`stocker.inventory.events.v1` stays unscaffolded). Reached through the gateway as `/api/pantry-items/**`.
- **User scoping.** Every `/pantry-items` call reads the caller from the `X-User-Id` header, which the gateway sets from the verified
  JWT (any client-sent value is stripped there). A missing or non-UUID header is 401 `{"message": ...}`. A `userId` parameter or
  body field is **ignored** (older frontends may still send it). Another user's or an unknown item id is 404 `{"message":"Pantry item not found"}`.
  This is why port 8087 is bound to 127.0.0.1 in compose: calling it directly means setting `X-User-Id` yourself.
- Endpoints (`api/rest/PantryItemController`, errors are `{"message": ...}`: 400 validation, 401 header, 404 not found, 409 concurrent add):
  - `POST /pantry-items` — add an item. Original form: query parameters `productId`, `quantity`, `[expirationDate=YYYY-MM-DD]`,
    `[notifyDaysBefore]`. **Free-text form:** a JSON body `{productId?, name?, category?, unit?, quantity, expirationDate?, notifyDaysBefore?}`
    (body values win over query parameters). Either `productId` or `name` is required; `quantity` 1..100000; name up to 255, category 100,
    unit 50 characters. With a `productId` the catalog is only a best-effort name source (an outage or 404 never blocks the add); the same
    `productId` for the same user merges into one row (quantities add up, a new expiry replaces the old one); free-text items are always new rows.
  - `GET /pantry-items` — the caller's items. `PUT /pantry-items/{id}?quantity=` — change the quantity (>= 1). `DELETE /pantry-items/{id}` — 200, empty body.
  - `PUT /pantry-items/{id}/expiry?[expirationDate=]&[notifyDaysBefore=]` — set the expiry; omitting both clears it. A threshold without a date, or a
    negative one, is 400. `notifyDaysBefore` defaults to 3 when unset, 0 = only on the day.
- Item JSON: ids keep legacy snake_case (`pantry_item_id`, `user_id`, `product_id`), the rest is camelCase (`name`, `category`, `unit`, `quantity`,
  `expirationDate`, `notifyDaysBefore`). `product_id`, `name`, `category`, `unit`, `expirationDate`, `notifyDaysBefore` can be null.
- `GET /internal/pantry-items/expiring?asOf=YYYY-MM-DD` lists every user's items whose notification window has opened, with `pantryItemId`,
  `userId`, `productId`, `quantity`, `expirationDate`, `notifyDaysBefore`, `name`, `category`, `unit`. **Service-to-service only** (read by
  notifications); the gateway never routes `/internal/**`.
- Schema is managed by Flyway (`db/migration`), history table `flyway_schema_history_inventory`, `baseline-on-migrate` at version 1 (the shared
  Neon database already has a hand-made `pantry_items`, so V1 is skipped there). `V1__create_pantry_items.sql`; `V2__add_expiry_to_pantry_items.sql`
  (`expiration_date`, `notify_days_before`, additive); `V3__custom_pantry_items.sql` (`product_id` becomes nullable, new nullable `name`, `category`,
  `unit`). A fresh database gets all three. Use `STOCKER_DB_MIGRATE_URL` for Neon's direct (non-pooler) endpoint if needed.
- `CatalogClient` (`STOCKER_CATALOG_URL`, compose passes `http://catalog:8082`) is used by `PantryItemService` only to look up a product name.
- `GET /healthz`. Feature note: `docs/features/inventory-custom-pantry-items.md`.

## Tests

See `docs/testing.md`. `./gradlew :services:inventory:test` is offline. `PantryItemPostgresIT` runs only with `STOCKER_INVENTORY_DB_IT=true` plus
`STOCKER_DB_URL/USER/PASSWORD` pointing at a throwaway Postgres (never Neon).

## Known gaps

- `infrastructure/config/KafkaConsumerConfig` / `KafkaProducerConfig` are leftovers copied from analytics (stubs).
- Rolling V3 back is only safe once the free-text rows are deleted (`product_id` cannot be made NOT NULL while rows without a product exist).
