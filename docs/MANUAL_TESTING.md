# Manual testing runbook (Sprint 2)

Branch `integration/sprint2-manual-test`. This runs the whole backend in Docker against **Neon** (no local
Postgres anywhere) and walks through every Sprint 2 feature. Scripts live in `scripts/manual-test/`.

## 1. Prerequisites

- Docker with Compose v2. If Docker Desktop is not running, the scripts automatically use the `default` context
  (`docker --context default ...`). The first start builds 8 images (JDK 25, Gradle) and uses a lot of memory and
  disk: close heavy programs first and do not run other builds in parallel.
- Free ports: 8080-8087, 9092, 9094. Only the gateway (8080) is reachable from other machines; every other published port is bound to 127.0.0.1.
- Memory and cores: the stack is capped at 8 GiB RAM and 6 cores (see "Resource limits" below). Check free memory before starting; do not run other builds in parallel.
- A root `.env` (gitignored; copy `.env.example`). Variables, by name only:

| Variable | Required | Meaning |
|---|---|---|
| `STOCKER_DB_URL`, `STOCKER_DB_USER`, `STOCKER_DB_PASSWORD` | yes | Main Neon database (`neondb`): identity, catalog, inventory, pricing |
| `STOCKER_SHOPPING_DB_URL` | yes | Same host/role as `STOCKER_DB_URL`, with `/neondb` replaced by `/shopping` |
| `STOCKER_NOTIFICATIONS_DB_URL` | yes | Same, with `/notifications` |
| `STOCKER_JWT_SECRET` | yes | At least 32 bytes; gateway and identity share it |
| `STOCKER_CORS_ALLOWED_ORIGINS` | no | Default `http://localhost:5173` |
| `STOCKER_AUTH_COOKIE_SECURE` | no | Keep `false` on plain http |
| `STOCKER_INTERNAL_TOKEN` | yes | Shopping-to-gateway push secret; the gateway refuses to start without it, and both sides must use the same value |
| `STOCKER_NOTIFICATIONS_SCAN_INTERVAL` / `..._SCAN_INITIAL_DELAY` | no | `PT30S` / `PT10S` for testing (production `PT15M`) |
| `STOCKER_FETCH_PROVIDER`, `STOCKER_SPREAD_API_KEY` | no | Live price provider for `/api/pricing/search` only |
| `STOCKER_INGEST_ENABLED`, `STOCKER_INGEST_DRY_RUN` | no | Supermarket crawl, **OFF** by default (section 5) |

- The two new Neon databases (`shopping`, `notifications`) must exist in the Neon project before the first start
  (create them in the Neon console; Flyway creates the tables). Use the **direct** endpoint (host without
  `-pooler`) for these URLs because Flyway needs it.

## 2. Start, stop, URLs

```bash
scripts/manual-test/preflight.sh   # optional, stack-up.sh runs it
scripts/manual-test/stack-up.sh    # preflight + build + start + wait for health
scripts/manual-test/smoke.sh       # automated check through the gateway
scripts/manual-test/logs.sh pricing -f
scripts/manual-test/stack-down.sh
```

| Service | Port | Notes |
|---|---|---|
| gateway | 8080 | The only entry point for the frontend and the smoke test |
| identity / catalog / shopping / pricing / notifications / analytics / inventory | 8081 / 8082 / 8083 / 8084 / 8085 / 8086 / 8087 | Each has `GET /healthz`; pricing also gRPC 9094 |
| kafka | 9092 (host), 29092 (inside compose) | `kafka-init` creates the topics and exits |

Everything a user does goes through `http://localhost:8080`. `/api/identity/register|login|refresh|logout`,
`GET /healthz` and `GET /api/products/**` are public; everything else needs `Authorization: Bearer <accessToken>`.

## 3. What the first start does to the SHARED Neon database

| Service | Database | Change on first start | Reversible? |
|---|---|---|---|
| inventory | `neondb` | Flyway history table `flyway_schema_history_inventory`, baseline at V1, then V2: adds columns `expiration_date` and `notify_days_before` to `pantry_items` (additive, `IF NOT EXISTS`); then V3: `product_id` becomes nullable and the nullable columns `name`, `category`, `unit` are added (free-text pantry items) | Yes. V3: `ALTER TABLE pantry_items DROP COLUMN name, DROP COLUMN category, DROP COLUMN unit;` and, only after deleting every free-text row (`DELETE FROM pantry_items WHERE product_id IS NULL;`, otherwise it fails), `ALTER TABLE pantry_items ALTER COLUMN product_id SET NOT NULL;`. V2: `ALTER TABLE pantry_items DROP COLUMN expiration_date, DROP COLUMN notify_days_before;` and drop the history table. Existing rows are untouched. |
| pricing | `neondb` | Flyway V5 creates `normalized_products` and backfills it from `price_records` (`STOCKER_NORMALIZE_BACKFILL`, default on; database work only). Earlier versions (V1-V4) are already applied. | Yes: drop `normalized_products` (derived data; `price_records` is never modified) and remove the V5 row from pricing's Flyway history. |
| identity, catalog | `neondb` | Flyway disabled in compose (`SPRING_FLYWAY_ENABLED=false`): tables are managed in Neon by hand. No schema change. | n/a |
| shopping | `shopping` (new) | Flyway V1-V4 (outbox, shopping list items, favorite stores) | Drop the database |
| notifications | `notifications` (new) | Flyway V1 (`expiration_notifications`) and V2 (item name, category and unit columns, so free-text items can be notified) | Drop the database |

Data the tests create in `neondb`: a throwaway user per smoke run (`manual-test-<timestamp>@example.test`; there
is no API to delete a user), plus pantry items and products that the script deletes again. Manual testing creates
whatever you add by hand. Nothing deletes existing data.

## 4. Manual test checklist

Set up once (swap in your own values; keep the token out of screen shares):

```bash
B=http://localhost:8080
curl -s -X POST $B/api/identity/register -H 'Content-Type: application/json' \
  -d '{"email":"me@example.test","password":"a-long-password","firstName":"Me"}'
TOKEN=$(curl -s -X POST $B/api/identity/login -H 'Content-Type: application/json' \
  -d '{"email":"me@example.test","password":"a-long-password"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
AUTH="Authorization: Bearer $TOKEN"
```
You never send a user id: the gateway takes it from the token and sets `X-User-Id` for inventory, notifications and shopping
(any `userId` parameter is ignored). Refresh tokens are not accepted as Bearer tokens, and the access token lives 15 minutes:
log in again (or call `POST $B/api/identity/refresh` with the cookie) when you get 401. The same steps work from the frontend UI.
`smoke.sh` automates most of them.

### 4.1 Expiry notifications (8 acceptance tests)

Add pantry items as free text, so no catalog product is needed (catalog writes return 403 through the gateway for ordinary
users; to use a catalog product, create one directly against the catalog service on `127.0.0.1:8082`, or pick an existing one with
`GET $B/api/products`). Notifications use Pacific/Auckland dates.

```bash
# free-text pantry item through the gateway: Authorization only, no user id and no X-User-Id (the gateway sets it)
curl -s -X POST $B/api/pantry-items -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"Nana'"'"'s jam","category":"preserves","unit":"jar","quantity":2,"expirationDate":"<today+2>","notifyDaysBefore":3}'
# the same with a catalog product: {"productId":"<uuid>","quantity":1,...}; either productId or name is required
# calling inventory directly on 127.0.0.1:8087 instead: add -H "X-User-Id: <your user uuid>" yourself
```

| # | Acceptance test | Steps | Expected |
|---|---|---|---|
| 1 | Expiry date saved | `POST $B/api/pantry-items` with the free-text body above (or the query form `?productId=<id>&quantity=1&expirationDate=<today+2>&notifyDaysBefore=3`) | Response and `GET $B/api/pantry-items` show `expirationDate` |
| 2 | Threshold saved | Same call | `notifyDaysBefore` is 3 (default 3 if omitted; 0 = only on the day) |
| 3 | Notified inside threshold | Wait for the scan (up to ~40 s), `GET $B/api/notifications` | One notification, `status` `NEW`, for that pantry item; for a free-text item it carries `itemName`, `category` and `unit` |
| 4 | Not notified outside threshold | Add another item with expiry `<today+30>` and threshold 3 | No notification for it, even after several scans |
| 5 | Acknowledge | `POST $B/api/notifications/<id>/acknowledge` | `status` `ACKNOWLEDGED`, `acknowledgedAt` set |
| 6 | No duplicate | Wait for 2+ more scans | Still exactly one notification for the item (also after acknowledging) |
| 7 | No date, no notification | Add an item with no `expirationDate` | No notification |
| 8 | Delivered on time | Item expiring in exactly `notifyDaysBefore` days appears on the first scan after it enters the window | Appears within one scan interval (30 s here) |

Change or clear an expiry: `PUT /api/pantry-items/<id>/expiry?expirationDate=...&notifyDaysBefore=...`
(omit both to clear). A threshold without a date, or a negative one, is HTTP 400.

### 4.2 Favorite stores

| Step | Call | Expected |
|---|---|---|
| Save to favorites | `POST $B/api/shopping/favorite-stores` `{"chainId":"paknsave","storeId":null}` | 201 with `id`; saving again returns the same favorite |
| Favorites section | `GET $B/api/shopping/favorite-stores` | The saved store, once |
| Select a favorite and see its items | `GET $B/api/pricing/stores/paknsave/products?query=milk&limit=20` | `products[]` with name, price, `promoFlag`, `capturedAt`; unknown chain is 422 |
| Add its items to the shopping list | `POST $B/api/shopping/items` `{"name":"<product name>","region":"auckland","quantity":1}` | 201, item appears in `GET /api/shopping/items` (status `pending`, later `available` once prices arrive) |
| Remove | `DELETE $B/api/shopping/favorite-stores/<id>` | 204, gone from the list |

### 4.3 List comparison and cheapest recommendation

1. For each list item call `GET $B/api/pricing/match?term=milk&itemId=<any-id-you-choose>` (stores the matched products under that id).
2. `POST $B/api/pricing/compare` `{"items":[{"itemId":"<id>","quantity":2}],"region":"auckland"}`.
3. Expected: `storeTotals[]` per chain with `totalAmount` (items), `totalWithFulfilment`, `savingsAmount`; `cheapestChainId` is the chain with the lowest `totalWithFulfilment`. Verify one total by hand (price x quantity).
4. Outside service area: `"region":"narnia"` gives 422 `{"error":"You are outside Stocker's service area. Try again from a supported region."}`. Supported regions: auckland, wellington, christchurch.

### 4.4 Savings per option versus the selected one

| Case | Request | Expected |
|---|---|---|
| Relative savings | compare with `"selectedChainId":"paknsave"` | `savingsAmount` of each chain = (selected `totalWithFulfilment` - its `totalWithFulfilment`) + discounts; negative if it is dearer; the selected chain is 0 |
| Equal prices | two chains with the same total (needs equal price data) | Savings come only from discounts (`promoFlag` prices) |
| Empty list | `"items":[]` | 422 with the service-area message |

### 4.5 Fulfilment costs (delivery, click and collect)

Shipped fees (pricing `application.yml`, see `docs/features/fulfilment-costs.md`): New World north delivery 14.00
(9.00 from 200), click and collect 5.00 (free from 50); New World south delivery 15.00 (10.00 from 200), click and
collect flat 3.50; PAK'nSAVE click and collect 5.00 only (no delivery); Woolworths delivery 14.00 (9.00 from 200),
click and collect 3.50.

| Check | How | Expected |
|---|---|---|
| Fees included | compare, read `fulfilmentOptions` / `bestMethod` / `fulfilmentCost` | `totalWithFulfilment` = `totalAmount` + `fulfilmentCost` |
| Delivery vs click and collect | `allowedMethods:["delivery"]`, then `["click_and_collect"]` | Totals use only the allowed method |
| New World north vs south | same list, region `auckland` then `christchurch` | New World fees differ (north 14.00 / 5.00, south 15.00 / 3.50) |
| Tier boundaries | basket totals of 49.99 vs 50.00 (click and collect, New World north) and 199.99 vs 200.00 (delivery) | 5.00 -> 0.00 at exactly 50.00; 14.00 -> 9.00 at exactly 200.00 (inclusive). Needs stored prices that add up to those totals; otherwise rely on the automated tests |
| PAK'nSAVE, delivery only | `allowedMethods:["delivery"]` | PAK'nSAVE is left out entirely (not in `storeTotals`, not in savings) |
| Unknown method | `allowedMethods:["in_store"]` | 422 listing valid methods `click_and_collect, delivery` |

## 5. Price data (important)

- The crawl is **OFF** (`STOCKER_INGEST_ENABLED=false`), so a plain start sends **no request to any supermarket**.
  On-demand refresh also needs the crawler, so it is off too. Prices come only from rows already in Neon. I cannot
  query Neon from here, so check what exists through the app:
  `GET /api/pricing/stores/{newworld|paknsave|woolworths}/products?limit=5` (database only, rows newer than the
  match max-age). An empty list means that chain has no fresh data; `match` and `compare` will then return empty
  results (not an error) and the smoke test marks the price steps as skipped.
- Do not use `GET /api/pricing/search` in testing: it calls the live price provider (Spread) when its key is set.
- To crawl: set `STOCKER_INGEST_ENABLED=true` (scheduled crawl 03:00 Auckland) and `STOCKER_INGEST_DRY_RUN=false`
  (store the rows; default is scrape-and-log only), then restart pricing. **Standing rule: any live supermarket
  request needs the owner's explicit approval first** (one paragraph, 5-10 sentences, with the reason). Keep it to
  the absolute minimum (rate limits). When on, the crawl pauses 20-40 s between page loads of a chain, loads at most 140
  pages per chain per Auckland day, stops a chain for 6 h (doubling to 24 h) after 3 blocked responses in a row, and is
  slow: a full run of the three chains one after another takes roughly 3.5-4 h (see `docs/features/price-engine-reliability.md`).
  Lean page loads are off by default (`STOCKER_INGEST_LEAN_PAGES`).

## Resource limits

- `docker-compose.limits.yml` caps the whole stack at 8 GiB RAM and 6 CPU cores (every container pinned to cores 0-5, memory
  limits per service, pricing 2304 MiB because it also runs headless Firefox). `scripts/manual-test/common.sh` adds it to every
  `docker compose` call automatically, so `stack-up.sh`, `stack-down.sh` and `logs.sh` use it without further flags.
- `STACK_LIMITS=off scripts/manual-test/stack-up.sh` runs without the caps; use it on a machine with fewer than 6 cores (the
  `cpuset: "0-5"` pin would otherwise fail).
- `stack-up.sh` builds the eight images one at a time (`COMPOSE_PARALLEL_LIMIT=1`; `BUILD_ALL_AT_ONCE=1` skips that) and, if it exists,
  uses a BuildKit builder named `stocker-limited` capped at 4 GiB and cores 0-5. Create it once:
  `docker buildx create --name stocker-limited --driver docker-container --driver-opt memory=4g --driver-opt cpuset-cpus=0-5 --driver-opt default-load=true`.
- `docker-compose.crawl.yml` is for a crawl-only run: it crawls the three chains at the same time (one Firefox each) and gives
  pricing 4608 MiB and a 768 MiB `/dev/shm`. Use it only with every other service stopped, after `docker-compose.limits.yml`, and only
  with the owner's approval of the crawl:

  ```bash
  docker compose -f docker-compose.yml -f docker-compose.limits.yml -f docker-compose.crawl.yml up -d --build kafka kafka-init pricing
  ```

  with `STOCKER_INGEST_ENABLED=true STOCKER_INGEST_DRY_RUN=false` (and `STOCKER_INGEST_RUN_ON_STARTUP=true` to start at once) in `.env`.
  Kafka plus this pricing come to about 5.5 GiB; do not start the other services on top of it.

## 6. Troubleshooting

- A service will not start: `scripts/manual-test/logs.sh <service>`; health: `curl localhost:<port>/healthz`.
- Missing/invalid Neon variables: identity, catalog, inventory, notifications and shopping fail at startup with a
  datasource error; run `preflight.sh`.
- `unable to connect` to Neon after idle: Neon computes suspend; the first request after a pause can be slow. Retry.
- Flyway errors on `shopping`/`notifications`: the database does not exist, or the URL is the pooled endpoint.
- Kafka: `docker --context default compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list`
  should show stocker.household.events.v1, stocker.catalog.events.v1, stocker.promotions.events.v1,
  stocker.shopping.events.v1, stocker.pricing.events.v1, stocker.pricing.refresh-requests.v1 (created by `kafka-init`).
- HTTP 401 from the gateway: missing/expired token (access tokens last 15 minutes: log in again), a refresh token used as a
  Bearer token (only access tokens are accepted), or the gateway and identity have different `STOCKER_JWT_SECRET`. 403: forbidden. Compose passes the same variable to both.
- Browser CORS errors: the page origin must be in `STOCKER_CORS_ALLOWED_ORIGINS` (default `http://localhost:5173`),
  then recreate gateway and identity.
- Frontend (`../Stocker-Frontend`): `cp .env.example .env`; the variable is `VITE_API_BASE_URL=http://localhost:8080`
  (read in `src/lib/apiConfig.ts`; in a container it is `API_BASE_URL`). Then `npm run dev` (http://localhost:5173).
  Pantry and Dashboard must call the gateway (`/api/pantry-items`, `/api/products`); a direct call to inventory `:8087` without
  `X-User-Id` is 401.

## 7. Known limitations

- Notifications are poll-only: the page must call `GET /api/notifications`; there is no push. Latency is up to one scan interval.
- Inventory is scoped by the gateway's `X-User-Id`; a `userId` parameter is ignored and another user's item is 404. Its port is bound to 127.0.0.1, so do not publish it further.
- No delete API for users, shopping items or notifications: test data stays in Neon.
- `analytics` Kafka handling is stubbed (notifications' Kafka listener is unused); shopping's outbox publisher is a stub.
- The shopping list's SSE stream (`GET /api/updates/shopping`) is relayed by the gateway, but the frontend does not use it yet.
- Catalog writes are blocked at the gateway. `SecurityConfig` denies every non-`GET` `/api/products/**` (create, delete and import are service-to-service; identity issues no admin role yet), so a signed-in caller gets 403 and an anonymous caller 401. The product seed in `smoke.sh` and the "Create a product" step in section 4.1 therefore FAIL through the gateway — seed products against the catalog service directly (`http://localhost:8082`) instead, or treat those two smoke checks as expected failures. The frontend is unaffected: it only reads `/api/products`.
- Region/service-area list is a placeholder (auckland, wellington, christchurch) pending sign-off; fees do not model express delivery, surcharges or Club+.

> `scripts/manual-test/stack-up.sh` builds the images **one at a time** (each image runs a Gradle build; all eight at once can exhaust RAM). Close heavy applications first and expect several minutes. Set `BUILD_ALL_AT_ONCE=1` only on a machine with plenty of free memory.

## Price search checks (v2)

Use the gateway with a JWT (the frontend, or `curl` with the token). The crawl is off, so only products already stored in Neon appear.

1. `GET /api/pricing/products?term=milk&itemId=test-1`: ONE entry per product with a `prices` object (lower-case chain ids), not one entry per chain.
2. `term=milk 2L` returns only 2 L products; `term=milk` lists all sizes ordered by unit price. The two results must differ.
3. `alternatives` never contains a product that is already in `products`, and are other brands, sizes or variants, not the same product at another chain.
4. `term=eggs size 7` and `term=eggs`: egg fried rice or egg noodles must NOT appear. Carton eggs appear only after the crawl has stored them (empty lists are expected before that).
5. `term=tim tam` finds Tim Tams only after biscuits have been crawled; before that the result is empty, not an error.
6. Start a search and abort it (close the tab or cancel the request): `scripts/manual-test/logs.sh pricing` shows "cancelled by the client" and no further work for it.
7. `PUT /api/pricing/items/test-1/product` with a `productKey` from the previous response returns 200; an unknown key returns 404.
8. The old `GET /api/pricing/match` still works.

