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
docker compose up postgres kafka kafka-init                        # infra only
docker compose up --build                                          # infra + all services
```

There is no linter/formatter configured.

Env / test wiring:
- Root `.env` (gitignored; template in `.env.example`) is loaded by `dotenv-java` (added to every subproject). `catalog` and `inventory` call `Dotenv` in `main()` and copy `STOCKER_DB_*` into system properties, and their `application.yml` has **no defaults** for the datasource — they won't start without those vars. Other services use `application.yml` defaults (`stocker`/`stocker`, `localhost`).
- `pricing`'s `test` task injects the root `.env` into the test JVM environment.
- Live/integration tests in `services/pricing/src/test/.../integration/` are skipped unless a gate env var is set (`@EnabledIfEnvironmentVariable`): `STOCKER_SPREAD_API_KEY` (Spread crawl), `STOCKER_INGEST_{NEWWORLD,PAKNSAVE,WOOLWORTHS}_LIVE_VERIFY`, `STOCKER_INGEST_MILK_LIVE_{VERIFY,PERSIST,TEST}`. The Playwright ones need `./gradlew :services:pricing:installPlaywrightBrowsers` (Firefox) first. Default runs never hit the network. `services/shopping`'s `ShoppingItemPostgresIT` needs a real Postgres and runs only with `STOCKER_SHOPPING_DB_IT=true` (default datasource localhost:5432/shopping, stocker/stocker, e.g. a throwaway `docker run postgres`).
- Unit tests use `@WebMvcTest` (services) / `@WebFluxTest` (gateway), with Boot 4 package names (`org.springframework.boot.webmvc.test.autoconfigure.*`).

## Architecture

```
Frontend --HTTP--> gateway :8080 --gRPC--> pricing :9094 (gRPC port; REST :8084 is /healthz only)
                          \--HTTP proxy--> identity :8081
```

Services: `identity` 8081, `catalog` 8082, `shopping` 8083, `pricing` 8084, `notifications` 8085, `analytics` 8086, `inventory` 8087. `identity`, `catalog`, `inventory`, `pricing` and `shopping` have real logic; `notifications` and `analytics` are still `/healthz` + stubbed Kafka handlers (keep them as stubs unless asked). Shopping's outbox publisher is still a stub.

**`services/inventory` is the pantry service** (`PantryItem`, calls `catalog` over REST via `CatalogClient`). It is *not* the reserved Inventory read-model service / `stocker.inventory.events.v1` that `AGENTS.md` forbids scaffolding.

### Gateway (WebFlux, `com.stocker.gateway`)
- Two ways to expose a service: (1) a route in `application.yml` under `spring.cloud.gateway.server.webflux.routes` (identity, catalog `/api/products/**`, inventory `/api/pantry-items/**`, shopping `/api/shopping/**`, all `StripPrefix=1`); (2) a local BFF controller that translates REST → gRPC (`api/rest/PricingController`: `GET /api/pricing/search`, `POST /api/pricing/compare`). BFF controllers need no route entry.
- Auth: gateway is an OAuth2 resource server validating **HS256 JWTs signed with the shared `STOCKER_JWT_SECRET`** (identity issues them; refresh token is an httpOnly cookie). Everything is authenticated except `/healthz`, actuator health/info, and identity register/login/refresh/logout. `JwtClaimForwardFilter` removes any client-supplied `X-User-Id` / `X-Household-Id` / `X-Roles` from every proxied request and sets them from the verified JWT; `Authorization` is left in place (identity validates it itself). `shopping` is the first service to consume `X-User-Id` (it trusts the header because it is only reachable through the gateway, so never expose it directly).
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
- Flyway is on for pricing/catalog/shopping/identity, but **identity in docker-compose runs against Neon with `SPRING_FLYWAY_ENABLED=false`** (tables managed there directly). Boot 4 needs the `spring-boot-flyway` module (`libs.spring.boot.flyway`) in addition to `flyway-database-postgresql`, or Flyway silently doesn't run.

## Stale docs

Trust code and the `AGENT.md` files over these: `README.md` still says pricing uses MongoDB, "no auth yet", and omits inventory; `docs/architecture/README.md` is a skeleton with the same Mongo claim; parts of `TASKS.md` (e.g. the "cross-cutting blockers" list) describe files that no longer exist. `AGENTS.md` refers to a root `AGENT.md` that doesn't exist and says shared pins live in the root `build.gradle` (they're in `gradle/libs.versions.toml`).

## Price match for the frontend (`GET /api/pricing/match`)

The frontend adds a list item and calls `GET /api/pricing/match?term=&itemId=[&category=]` (JWT required). Pricing answers from the products its own **ingest** has scraped (no third-party provider): per chain the best-matching product (`matches`, at most one per chain, cheapest first) plus labelled `alternatives`; the matched products are stored under `itemId` so `POST /api/pricing/compare` prices them. Empty lists mean "nothing found yet": an on-demand ingest run was started (rate-limited) and the frontend's existing polling asks again. Contract and details: `services/pricing/AGENT.md` (`MatchItem`) and `gateway/AGENT.md`.

Scope/limits: only categories with configured `app.ingest.*.category-urls` exist (milk today; Tim Tams/biscuits would need verified category URLs); matching is word overlap (`matchMethod: "lexical"`), not semantic; `productUrl` is always null (the scrapers do not capture product page URLs).
