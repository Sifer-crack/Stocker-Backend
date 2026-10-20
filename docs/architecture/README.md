# Architecture

Stocker is a set of Spring Boot 4 services (Java 25) behind one gateway, with Kafka for events and Neon
(serverless PostgreSQL) for storage. The service table and run instructions are in the root `README.md`; this page
shows how the pieces talk to each other.

## Request paths

```
Browser/SPA
   |  HTTPS/JSON, Authorization: Bearer <15 min access JWT>
   v
gateway :8080 (WebFlux)  -- validates the HS256 JWT (access tokens only), strips client X-User-Id/X-Household-Id/X-Roles,
   |                        sets X-User-Id from the token subject
   |-- /api/identity/**       -> identity :8081        (register, login, refresh, logout, me, spending report)
   |-- /api/products/**       -> catalog :8082         (GET only; writes are denied at the gateway)
   |-- /api/pantry-items/**   -> inventory :8087
   |-- /api/notifications/**  -> notifications :8085
   |-- /api/shopping/**       -> shopping :8083        (items, favorite-stores)
   |-- /api/pricing/**        -> gRPC -> pricing :9094 (match, search, products, suggest, compare, items/{id}/product, stores/{chain}/products)
   `-- /api/updates/shopping  -> server-sent events, fed by shopping through POST /internal/shopping/updates
```

Service-to-service calls (never through the gateway's public routes):

- `shopping` -> `pricing` over gRPC (`CompareItemPrices`) when an item is added; `pricing` -> Kafka `stocker.pricing.events.v1`
  -> `shopping` backfills the item; `shopping` -> `gateway` `POST /internal/shopping/updates` (shared `X-Internal-Token`,
  private-network callers only) -> browser SSE.
- `notifications` -> `inventory` `GET /internal/pantry-items/expiring?asOf=` on a schedule.
- `inventory` -> `catalog` REST, as a best-effort name lookup when a pantry item is added with a `productId`.

## Data

| Database (Neon) | Used by | Schema management |
|---|---|---|
| `neondb` (shared) | identity, catalog, inventory, pricing | pricing and inventory run Flyway (inventory has its own history table `flyway_schema_history_inventory`); identity and catalog tables are managed in Neon by hand (Flyway is disabled for them in compose) |
| `shopping` | shopping | Flyway V1-V4 |
| `notifications` | notifications | Flyway (own history table `flyway_schema_history_notifications`) |

Pricing keeps `price_records` (append-only crawl and lookup history), `price_stats` (current/min/max/median per item, read by
list comparison), `normalized_products` (the search index) and the sitemap category cache. Prices are tracked per supermarket
chain, not per store.

## Kafka topics (`infra/kafka/topics.yml`, created by `infra/kafka/kafka-init.sh`)

| Topic | Purpose |
|---|---|
| `stocker.household.events.v1`, `stocker.catalog.events.v1`, `stocker.promotions.events.v1`, `stocker.shopping.events.v1` | domain event streams (mostly publisher/consumer stubs today) |
| `stocker.pricing.events.v1` | `PriceRecordCaptured`; consumed by shopping to backfill comparisons |
| `stocker.pricing.refresh-requests.v1` | pricing's own retry queue for the legacy provider lookup |
| `stocker.inventory.events.v1` | reserved, not created |

## Price engine in one paragraph

A Playwright crawler (off by default) reads category pages of New World, PAK'nSAVE and Woolworths on a schedule within a
daily page budget, a 20-40 s pace and a per-chain circuit breaker, and stores prices. Lookups never wait for a supermarket:
they are answered from an in-memory snapshot of `normalized_products`, flagged `stale` when old, and a page refresh may run in
the background (`status` / `refresh` in the search answer tell the UI). Details: `docs/features/price-engine-reliability.md`,
`docs/features/price-search-v2.md`, `services/pricing/NORMALIZE_MODULE.md`, `services/pricing/INGEST_MODULE.md`.

## Not built

C4 diagrams were planned and not drawn; the diagram above is the whole picture. The inventory read-model service
(`stocker.inventory.events.v1`) is reserved and not scaffolded; `services/inventory` is the pantry service, not that service.
