# Stocker Backend

Stocker is a household-inventory and grocery price-comparison app for New Zealand. A household records what is in its
pantry (with expiry dates and reminders), keeps a shopping list, and Stocker's price engine compares the list across
New World, PAK'nSAVE and Woolworths, including delivery and click-and-collect fees, so the user can see the cheapest
way to shop. This repository is the backend: a Java 25 / Spring Boot 4 microservice monorepo behind one API gateway,
with Kafka for events and Neon (serverless PostgreSQL) for storage. The React frontend lives in the sibling repository
`Stocker-Frontend`.

## Architecture

```
[Frontend] --HTTP/JSON--> [gateway :8080] --HTTP proxy--> identity, catalog, inventory, notifications, shopping
                                          \--gRPC--------> pricing :9094
 shopping --gRPC--> pricing        shopping --HTTP push--> gateway --SSE--> browser
 notifications --HTTP--> inventory (service-to-service)        all services <--> Kafka
```

| Service | Port | What it does | Storage |
|---|---|---|---|
| `gateway` | 8080 | Only entry point. Validates the JWT, forwards `X-User-Id`, routes to services, serves the pricing REST-to-gRPC endpoints and the shopping SSE stream | none |
| `identity` | 8081 | Register, login, refresh, `me`, grocery budget, spending report; issues JWTs | Neon `neondb` |
| `catalog` | 8082 | Product master data (read-only through the gateway) | Neon `neondb` |
| `shopping` | 8083 | Shopping-list items with price comparison, favorite stores | Neon `shopping` (own database) |
| `pricing` | 8084 (health), gRPC 9094 | Price engine: supermarket crawler, stored prices, product search, list comparison, fulfilment fees | Neon `neondb` |
| `notifications` | 8085 | Pantry expiry notifications (scans inventory, stores one notification per item and date) | Neon `notifications` (own database) |
| `analytics` | 8086 | Stub: `/healthz` and a Kafka consumer skeleton | none |
| `inventory` | 8087 | The household pantry (`PantryItem`), including free-text items | Neon `neondb` |

`identity`, `catalog`, `inventory` and `pricing` share the main Neon database `neondb` (different tables);
`shopping` and `notifications` each have their own Neon database. There is no local Postgres anywhere in the stack.
Kafka (single KRaft node) carries the events in `infra/kafka/topics.yml`. Details for each service are in its
`AGENT.md`; the architecture overview is in [`docs/architecture/README.md`](docs/architecture/README.md).

## Features of this sprint

- **Expiry notifications:** pantry items carry an expiration date and a "notify N days before" threshold; a scheduled scan
  in `notifications` creates one notification per item and date, which the user lists and acknowledges.
- **Favorite stores:** save a supermarket chain as a favorite, browse its stored products, add them to the shopping list.
- **Click-and-collect / delivery fees:** list comparison adds each chain's fee for the allowed methods and names the true cheapest
  option (`docs/features/fulfilment-costs.md`).
- **Price search v2:** one merged product with a price per chain, real alternatives, search-bar suggestions, a generic
  fallback for unknown brands and sizes, and a `status` / `refresh` field so the UI knows when a search is finished
  (`docs/features/price-search-v2.md`).
- **Free-text pantry items:** a pantry item can be added by name without a catalog product
  (`docs/features/inventory-custom-pantry-items.md`).
- **Crawler hardening:** a Playwright crawler for the three chains with a 20-40 s page pace, a per-chain circuit breaker,
  pagination of multi-page categories, a daily page cap, per-category saving and batched persistence
  (`docs/features/price-engine-reliability.md`).

## Run it

Prerequisites: Docker with Compose v2, bash 4+, curl 7.55+, python3, a Neon project, and about 8 GiB of free RAM
(the stack is capped at 8 GiB / 6 cores by `docker-compose.limits.yml`).

```bash
cp .env.example .env        # fill in the Neon URLs, DB user/password, STOCKER_JWT_SECRET and STOCKER_INTERNAL_TOKEN
scripts/manual-test/stack-up.sh    # preflight, build the 8 images one at a time, start, wait for every /healthz
scripts/manual-test/smoke.sh       # end-to-end check through the gateway
scripts/manual-test/stack-down.sh
```

- `STOCKER_INTERNAL_TOKEN` is required (shared by gateway and shopping); `.env.example` still describes it as optional.
- Create the Neon databases `shopping` and `notifications` first (same host and role as `neondb`, direct endpoint).
- On a machine with fewer than 6 cores, run with `STACK_LIMITS=off`.
- The supermarket crawl is **off by default**. Never turn it on without the owner's explicit approval: it makes live requests to
  supermarket websites. A crawl-only run (the three chains in parallel, all other services stopped) uses
  `docker-compose.crawl.yml`; see "Resource limits" and section 5 in [`docs/MANUAL_TESTING.md`](docs/MANUAL_TESTING.md).
- Host ports other than the gateway's 8080 are bound to 127.0.0.1 only.

Full runbook, acceptance checklists and troubleshooting: [`docs/MANUAL_TESTING.md`](docs/MANUAL_TESTING.md).

## Test it

Requires the JDK 25 toolchain.

```bash
./gradlew test                              # everything, offline (gated live/database tests are skipped)
./gradlew :services:pricing:test            # one module
./gradlew serviceTest                       # service-scenario layer only
./gradlew featureIntegrationTest            # one end-to-end test per feature
```

The test layers, tags and the environment variables that switch on the gated tests are in [`docs/testing.md`](docs/testing.md).

## Branches and pull requests

`main` -> `dev` -> one `feature/*` branch per story; features are merged into `dev` through pull requests that another team
member reviews and tests. Work for the final submission was integrated on `integration/sprint2-manual-test`. Which branch holds
what, and how to review each, is in [`docs/SUBMISSION.md`](docs/SUBMISSION.md).

## Known limitations

- `price_records` is append-only and grows on every crawl; there is no retention policy (Neon has a storage cap).
- Prices are per supermarket chain, not per store; the crawl uses whatever store the site picks by IP geolocation.
- Crawled prices never carry a promo flag (the crawler does not read promo badges); the flag exists but is always false for crawled rows.
- Coverage is partial: Woolworths pagination was verified live on one category only, and the first New World / PAK'nSAVE runs hit HTTP 429.
- The service-area region list (auckland, wellington, christchurch) is a placeholder; fees are estimates and do not model every surcharge.
- The pricing snapshot reloads every 15 s, which keeps the Neon compute awake.
- Supermarket sites' terms of use restrict automated access: the crawl is off by default, rate-limited and circuit-breaker protected,
  and must only be run with the owner's explicit approval.
- `analytics` and shopping's outbox publisher are stubs; notifications are poll-only (no push).

The complete list is in [`docs/SUBMISSION.md`](docs/SUBMISSION.md).

## Team and AI assistance

Stocker is a team project. Many commits in this repository carry a `Co-Authored-By: Claude Sonnet 5` or
`Claude Sonnet 5.5` trailer: an AI assistant (Claude Code) helped write code, tests and documentation. The team should check the
AUT policy on generative-AI use for this assessment and declare the assistance as it requires.

## Where to read more

- [`docs/MANUAL_TESTING.md`](docs/MANUAL_TESTING.md): run and test the whole stack by hand
- [`docs/testing.md`](docs/testing.md): test layers and commands
- [`docs/SUBMISSION.md`](docs/SUBMISSION.md): submission checklist, branch map, known limitations
- [`docs/features/`](docs/features/): one document per feature (favorite stores, fulfilment costs, price search v2, price engine
  reliability, coverage, free-text pantry items)
- [`docs/frontend/FRONTEND_PROMPT.md`](docs/frontend/FRONTEND_PROMPT.md): the API contract for the frontend
- `AGENTS.md`, `CLAUDE.md` and each service's `AGENT.md`: conventions and module details for contributors and AI agents

Secrets have no committed defaults: `STOCKER_JWT_SECRET` and `STOCKER_INTERNAL_TOKEN` must be set in `.env`
(copy `.env.example`; generate each with `openssl rand -hex 32`). Compose refuses to start without them.

## Repository layout

```
build.gradle, settings.gradle, gradle/   parent build, module registry, version catalog (libs.versions.toml)
gateway/                                  Spring Cloud Gateway (WebFlux) + gRPC client
services/<name>/                          identity catalog shopping pricing notifications analytics inventory
  src/main/java/com/stocker/<name>/       api, application, domain, infrastructure
  src/main/resources/db/migration/        Flyway migrations
infra/                                    Kafka topic registry, ingress, observability
docker-compose.yml                        stack; docker-compose.limits.yml caps it; docker-compose.crawl.yml is the crawl-only profile
scripts/manual-test/                      stack-up / smoke / stack-down / preflight / logs
docs/                                     manual testing, testing, features, frontend contract, architecture
```
