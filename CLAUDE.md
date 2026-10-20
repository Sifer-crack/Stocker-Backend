# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`AGENTS.md` (repo root) holds the hard constraints — version pins, "no new dependencies without approval", module registry, Kafka topic registry, package layout. Read it before changing build files or adding modules. Each service also has its own `AGENT.md` (note: singular). This file covers what those don't: commands that actually work and how the pieces fit together.

## Commands

Requires JDK 25 (Gradle toolchain). Gradle 9.5, Groovy DSL, dependency versions live in `gradle/libs.versions.toml` (not the root `build.gradle`).

```bash
./gradlew :services:pricing:test                                   # one module
./gradlew :services:pricing:test --tests '*PriceCacheTest'         # one test class
./gradlew :services:pricing:test --tests '*PriceCacheTest.someMethod'
./gradlew :services:<name>:bootJar
docker compose up kafka kafka-init                                 # infra only (no postgres/mongo service: databases are on Neon)
docker compose up --build                                          # infra + all services
scripts/manual-test/stack-up.sh                                    # preferred: sequential builds, resource limits, health wait
```

There is no linter/formatter configured.

Env / test wiring:
- Root `.env` (gitignored; template in `.env.example`) is loaded by `dotenv-java` (added to every subproject). `catalog` and `inventory` call `Dotenv` in `main()` and copy `STOCKER_DB_*` into system properties, and their `application.yml` has **no defaults** for the datasource — they won't start without those vars. `shopping` and `notifications` also have no datasource default (each gets its own Neon database from compose: `STOCKER_SHOPPING_DB_URL` / `STOCKER_NOTIFICATIONS_DB_URL`). Only `identity` and `pricing` fall back to `localhost:5432/<service>` (`stocker`/`stocker`).
- `pricing`'s `test` task injects the root `.env` into the test JVM environment.
- Live/integration tests in `services/pricing/src/test/.../integration/` are skipped unless a gate env var is set (`@EnabledIfEnvironmentVariable`): `STOCKER_SPREAD_API_KEY` (Spread crawl), `STOCKER_INGEST_{NEWWORLD,PAKNSAVE,WOOLWORTHS}_LIVE_VERIFY`, `STOCKER_INGEST_MILK_LIVE_{VERIFY,PERSIST,TEST}`, `STOCKER_PROBE_{EGGS,PAGINATION,WOOLWORTHS,WOOLWORTHS_PAGINATION}_LIVE` (they carry `@Tag("integration")`; every one of them contacts a supermarket or a paid provider, so none may run without the owner's approval). Caveat: pricing's `test` task injects the root `.env`, so a gate variable set there switches the test on. The Playwright ones need `./gradlew :services:pricing:installPlaywrightBrowsers` (Firefox) first. Default runs never hit the network. The database tests need a real (throwaway, never Neon) Postgres and run only with their gate: `STOCKER_SHOPPING_DB_IT=true` (`ShoppingItemPostgresIT`), `STOCKER_INVENTORY_DB_IT=true`, `STOCKER_NOTIFICATIONS_DB_IT=true`; see `docs/testing.md`.
- Unit tests use `@WebMvcTest` (services) / `@WebFluxTest` (gateway), with Boot 4 package names (`org.springframework.boot.webmvc.test.autoconfigure.*`).

## Architecture

```
Frontend --HTTP--> gateway :8080 --gRPC--> pricing :9094 (gRPC port; REST :8084 is /healthz only)
                          \--HTTP proxy--> identity :8081
```

Services: `identity` 8081, `catalog` 8082, `shopping` 8083, `pricing` 8084, `notifications` 8085, `analytics` 8086, `inventory` 8087. `identity`, `catalog`, `inventory`, `pricing`, `shopping` and `notifications` have real logic (notifications = pantry expiry notifications); only `analytics` is still `/healthz` + a stubbed Kafka handler (keep it a stub unless asked). Shopping's outbox publisher is still a stub, and notifications' Kafka listener is an unused stub.

**`services/inventory` is the pantry service** (`PantryItem`; `CatalogClient` is a best-effort name lookup when an item is added with a `productId`; free-text items need no catalog entry). It is *not* the reserved Inventory read-model service / `stocker.inventory.events.v1` that `AGENTS.md` forbids scaffolding.

### Gateway (WebFlux, `com.stocker.gateway`)
- Two ways to expose a service: (1) a route in `application.yml` under `spring.cloud.gateway.server.webflux.routes` (all `StripPrefix=1`): identity `/api/identity/**`, catalog `/api/products/**`, inventory `/api/pantry-items/**`, notifications `/api/notifications/**`, shopping `/api/shopping/**` (items and `/api/shopping/favorite-stores`); (2) local controllers that translate REST → gRPC, which need no route entry: `PricingController` (`GET /api/pricing/search`, `GET /api/pricing/match`, `POST /api/pricing/compare`), `ProductSearchController` (`GET /api/pricing/products`, `GET /api/pricing/suggest`, `PUT /api/pricing/items/{itemId}/product`), `StoreProductsController` (`GET /api/pricing/stores/{chainId}/products`), plus `ShoppingUpdatesController` (`GET /api/updates/shopping` SSE and the internal `POST /internal/shopping/updates`).
- Auth: gateway is an OAuth2 resource server validating **HS256 JWTs signed with the shared `STOCKER_JWT_SECRET`** (identity issues them; refresh token is an httpOnly cookie). Access tokens last 15 min (`STOCKER_JWT_ACCESS_TTL`, 900 s), the refresh token 7 days (604800 s); the gateway accepts only `type=access` tokens, so a refresh token is not valid as a Bearer token. Everything is authenticated except `/healthz`, actuator health/info, identity register/login/refresh/logout and `GET /api/products/**`; non-GET `/api/products/**` is denied (401 anonymous, 403 signed in). `JwtClaimForwardFilter` removes any client-supplied `X-User-Id` / `X-Household-Id` / `X-Roles` from every proxied request and sets them from the verified JWT; `Authorization` is left in place (identity validates it itself). `shopping`, `inventory` and `notifications` consume `X-User-Id` (they trust the header because they are only reachable through the gateway; that is why host ports other than 8080 are bound to 127.0.0.1 in compose, and why they must never be exposed directly). `/internal/**` on the gateway is limited to private-network callers (`InternalNetworkFilter`) and needs `X-Internal-Token`.
- CORS is enforced only at the gateway (`STOCKER_CORS_ALLOWED_ORIGINS`); identity's route strips the browser `Origin`/CORS request headers so it never adds a second set.
- gRPC errors: `Status.INVALID_ARGUMENT` from pricing is mapped to HTTP 422 with the message by a controller-scoped `@ExceptionHandler` in `PricingController`.

### Shopping list -> price comparison -> frontend
1. `POST /api/shopping/items` (gateway route -> shopping; user via `X-User-Id`) commits the item as `pending` and returns 201 at once.
2. After commit, shopping makes ONE gRPC `CompareItemPrices` call to pricing (8s deadline, no retry) on a background executor. Prices back -> stored, status `available`; nothing yet / failure / timeout -> stays `pending`; pricing `INVALID_ARGUMENT` -> `unavailable`.
3. Pricing publishes `PriceRecordCaptured` to `stocker.pricing.events.v1` keyed by the item id it was asked about (= the shopping-item UUID). Shopping's Kafka consumer backfills from those events (newer-observation-only merge, idempotent).
4. Both paths go through `ComparisonUpdateService` -> `GatewayComparisonNotifier`, which POSTs the item's FULL state to the gateway's `POST /internal/shopping/updates` (`X-Internal-Token` = `STOCKER_INTERNAL_TOKEN`, no JWT, blocked at the ingress). The gateway relays it on the owner's SSE stream `GET /api/updates/shopping` (event `shopping-item-updated`).
State is always persisted before any push, so a missed push is recovered by reading `GET /api/shopping/items`. Per-module details are in `services/shopping/AGENT.md` and `gateway/AGENT.md`.

### Pricing (reference service, `com.stocker.pricing`)
Data flows that span many files:
- **Search (on-demand):** gRPC `Search` → `PriceSearchService` → `service/cache/PriceCache` (L1 in-process, L2 = `price_records` freshness window) → on miss, bounded-timeout fetch via the active `fetch/WebFetcher` (strategy chosen by `STOCKER_FETCH_PROVIDER`, default `spread`) → `RawProductPriceRecordMapper` → persist. If the sync fetch times out/fails it returns stale/empty data and publishes a request to `stocker.pricing.refresh-requests.v1`; `refresh/PriceRefreshRequestConsumer` retries off the request path. See `CACHE_MODULE.md`, `FETCH_MODULE.md`.
- **Ingest (scheduled):** `ingest/` is a separate Playwright scraper (New World, PAK'nSave, Woolworths NZ) on a cron, writing straight to `price_records`. Independent of `Search`/`fetch/`. See `INGEST_MODULE.md`.
- **Write invariant:** every `price_records` write (search persist, ingest batch, gRPC `Save`) must also go through `PriceStatsService.recordObservation`, which maintains the derived `price_stats` table. `price_records` is append-only truth; `price_stats` (current/min/max/median + promo flag + JSONB history) is what `CompareShoppingList` reads (`ShoppingListComparisonService` + `SavingsCalculatorService`).
- Every persist also emits `PriceRecordCaptured` to `stocker.pricing.events.v1` via `domain/port/EventPublisher` → `infrastructure/messaging/KafkaEventPublisher`.
- Pricing declares its own Jackson 2 `ObjectMapper` bean (`infrastructure/config/JacksonConfig`) because Boot 4 auto-configures Jackson 3 (`tools.jackson.*`); any service that autowires `ObjectMapper` and uses classic `com.fasterxml` types needs the same.
- Pricing still uses legacy `model/`, `repository/`, `service/` packages alongside the newer `domain/port` + `infrastructure/messaging` — known, unreconciled divergence from the layered convention.

### Cross-module contracts to keep in sync by hand
- `gateway/src/main/proto/price_record.proto` and `services/shopping/src/main/proto/price_record.proto` are **duplicates** of `services/pricing/src/main/proto/price_record.proto` (no shared-proto module; each copy only adds a 4-line header comment). Edit pricing's, then update both copies.
- New Kafka topics: `infra/kafka/topics.yml` **and** `infra/kafka/kafka-init.sh` (compose runs the script; the yml is not read at runtime).
- Flyway is on for pricing, shopping, inventory (own history table, baseline at V1) and notifications (own history table), but **identity AND catalog in docker-compose run against Neon with `SPRING_FLYWAY_ENABLED=false`** (tables managed there directly). Boot 4 needs the `spring-boot-flyway` module (`libs.spring.boot.flyway`) in addition to `flyway-database-postgresql`, or Flyway silently doesn't run.

## Frontend (sibling repo `../Stocker-Frontend`)

React/Vite SPA. It talks only to the gateway (`:8080`): identity auth under `/api/identity/*` and the pricing BFF (`/api/pricing/{match,search,compare}`). Its response types in `src/pricing/types.ts` are hand-mirrored from the gateway JSON, so a change to `PricingController` or `price_record.proto` responses needs a matching frontend change. Check `../Stocker-Frontend/CLAUDE.md` for what it already uses: inventory now requires the gateway-set `X-User-Id`, so any page that still calls `:8087` or `:8082` directly gets 401 and must move to `/api/pantry-items` and `/api/products`. Its origin must be in `STOCKER_CORS_ALLOWED_ORIGINS` (dev default `http://localhost:5173`). Details: `../Stocker-Frontend/CLAUDE.md`.

## Stale docs

Trust code and the `AGENT.md` files over everything else. `README.md` and `docs/architecture/README.md` were rewritten for the submission; parts of `TASKS.md` (e.g. the "cross-cutting blockers" list) may still describe files that no longer exist.

## Price match for the frontend (`GET /api/pricing/match`)

The frontend adds a list item and calls `GET /api/pricing/match?term=&itemId=[&category=][&weightKg=|&volumeL=]` (JWT required). Pricing answers from the products its own **ingest** has scraped (no third-party provider): per chain the best-matching product (`matches`, at most one per chain, cheapest first) plus labelled `alternatives`; the matched products are stored under `itemId` so `POST /api/pricing/compare` prices them. Common NZ products (`services/pricing/src/main/resources/common-products.txt`) are answered from the normalised `normalized_products` index (`matchMethod: "canonical"`, with `unitPrice` / `packSize`); `weightKg` / `volumeL` are optional pack-size hints (e.g. a pantry product's selling weight/volume). Empty lists mean "nothing stored for this yet": the full-ingest-on-miss is OFF (`app.pricing.match.on-demand-ingest=false`) and the term is only logged in `unmatched_search_terms`. For common products pricing checks data age first and fetches just the stale chains' one category page (`app.pricing.on-demand.*`, `category-map.txt`; cooldown, hourly cap, 6 s deadline); `stale: true` in the response means old prices are shown while that refresh finishes - ask again shortly. Contract and details: `services/pricing/AGENT.md` (`MatchItem`) and `gateway/AGENT.md`.

Scope/limits: only categories that the crawl has stored exist (the crawl list is the catalog map `category-map.txt` plus the configured `app.ingest.*.category-urls`, about 150 pages per chain, and the crawl is off by default, so a fresh database has none); matching is alias/word overlap (`canonical` for common products, `lexical` fallback), not semantic; milk variants (trim/lite/A2/lactose-free) share one key; `productUrl` is always null (the scrapers do not capture product page URLs).
