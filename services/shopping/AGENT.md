# AGENT.md — shopping

## Implemented

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8083 (`SERVER_PORT`), `@EnableScheduling`.
- `GET /healthz` via `api/rest/HealthzController`.
- Postgres via `STOCKER_DB_URL`/`STOCKER_DB_USER`/`STOCKER_DB_PASSWORD` (defaults: localhost:5432/shopping, stocker/stocker). Flyway: V1 placeholder, V2 outbox scaffold, **V3 `shopping_list_items` + `shopping_item_prices`**.
- **Shopping-list items with a price comparison.** REST (reached through the gateway as `/api/shopping/**`, prefix stripped; the caller is identified by `X-User-Id`, set by the gateway from the verified JWT): `POST /shopping/items` (201 immediately, `comparisonStatus: "pending"`), `GET /shopping/items`, `GET /shopping/items/{id}` (404 for another user's item). Errors are `{ "error": "..." }`.
- **Add-item never waits on pricing.** The row is committed as PENDING; after commit `application/ComparisonDispatcher` makes ONE `CompareItemPrices` gRPC call to pricing on a bounded background executor (`app.pricing.compare-timeout`, default 8s, **no retries**).
  - prices returned -> `ComparisonUpdateService.applyPrices(SYNC)`; none yet / timeout / any failure -> item stays `pending`; pricing `INVALID_ARGUMENT` (e.g. outside the service area) -> `unavailable`.
- **Kafka backfill.** `infrastructure/config/KafkaConsumerConfig` -> `infrastructure/messaging/PriceEventHandler` consumes `PriceRecordCaptured` from `stocker.pricing.events.v1`. Pricing keys events by the itemId it was asked about, which for these items is the shopping-item UUID; anything else (ingest ids like `newworld:P123`, other event types, malformed JSON) is ignored. Applied through `ComparisonUpdateService.applyPrices(BACKFILL)`. Idempotent: a price only replaces an OLDER observation for the same (chain, store), and nothing is announced unless the visible state changed. Must remain the only listener in group `stocker.shopping`. Pricing's mapper writes `capturedAt` as numeric epoch seconds; ISO text is also accepted.
- **One notify path.** Both sources go through `ComparisonUpdateService` -> `ComparisonNotifier` (`infrastructure/notify/GatewayComparisonNotifier`), which POSTs the item's FULL state (status, cheapest, every compared price) to the gateway's `/internal/shopping/updates` with `X-Internal-Token` (`STOCKER_INTERNAL_TOKEN`), on its own executor. Best effort: state is already persisted, so failures are only logged.
- Status meanings: `pending` = not compared yet (initial, or pricing slow/down and a backfill is awaited); `available` = comparison stored; `unavailable` = pricing rejected the request. Concurrent updates to one item are serialized with a pessimistic row lock (`findByIdForUpdate`).
- `src/main/proto/price_record.proto` is a hand-kept COPY of pricing's (like the gateway's) — keep all three in sync. gRPC channel: `spring.grpc.client.channels.pricing` (`STOCKER_GRPC_PRICING_ADDRESS`).
- Tests: unit tests for the service merge rules, dispatcher, update service, Kafka handler, gRPC adapter, notifier and REST layer. `integration/ShoppingItemPostgresIT` runs the update path against a real Postgres (migration vs entities, replay/stale handling, concurrency) and is skipped unless `STOCKER_SHOPPING_DB_IT=true` (uses the default datasource, e.g. a throwaway `docker run postgres`).

## Stubbed / TODO

- `infrastructure/outbox/OutboxPublisher#publish` — poll PENDING outbox rows and publish to `stocker.shopping.events.v1`; mark published only after a successful send (at-least-once). Nothing writes to the outbox yet.
- `infrastructure/config/KafkaProducerConfig` — wire `KafkaTemplate<String,String>` when shopping starts publishing.
- No list entity yet: items belong to a user (`X-User-Id`); there are no edit/delete/check-off endpoints and no household sharing.
- The gateway push is in-memory per gateway instance; several gateway replicas would need a shared broker.

## Conventions

- Package root: `com.stocker.shopping`.
- Outbox table name: `outbox` (see `db/migration/V2__create_outbox.sql`). Keep this name stable.
- Outbox poll interval: `app.outbox.poll-interval-ms` (default 5000).
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
- Only the endpoints above (plus `/healthz` and actuator) may be exposed.
