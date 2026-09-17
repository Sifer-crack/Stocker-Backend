# pricing

Owns: promotions and pricing.
Data store: PostgreSQL (`pricing` database, `price_records` table with JSONB). Flyway manages the schema.
Status: skeleton — boots, connects to Postgres/Kafka, exposes `GET /healthz`, plus a gRPC
`PriceRecordService` (`Fetch`/`Save` backed by a JPA repository and a fetcher service, and `Search`
which runs the web-crawl `fetch/` module end-to-end and persists live prices), plus a scheduled,
self-built `ingest/` module (New World / PAK'nSave / Woolworths NZ via Playwright) — default off
behind `app.ingest.enabled`, decoupled from `Search`. See `INGEST_MODULE.md`.

> TODO: the gRPC server is **not actually served** yet — the Spring gRPC server starter and
> `spring.grpc.server.port` are missing, and the controller is `@Controller` rather than `@GrpcService`.
> The event pipeline is also missing: no listener, no publisher
> (`application/PricingEventService`, `domain/port/EventPublisher`, `infrastructure/messaging/*`).
> This doc currently overstates what is implemented — see `TASKS.md` at the repo root.

## Confirmed event topics

| Topic | Direction | Key |
|---|---|---|
| `stocker.promotions.events.v1` | produces | `householdId` |
| `stocker.pricing.events.v1` | produces | `householdId` |
| `stocker.catalog.events.v1` | consumes (to confirm) | `householdId` or `itemId` |

## Structure

- `api/grpc/` — gRPC controller implementing `PriceRecordService` (`api/grpc/v1`, generated from proto)
- `api/rest/` — HTTP surface (only `/healthz` today)
- `model/` — `PriceRecord` JPA entity (Lombok)
- `repository/` — `PriceRecordRepository` (Spring Data JPA)
- `service/` — `PriceFetcherService`, `PriceSearchService`, `RawProductPriceRecordMapper`
- `fetch/` — web-crawl module (providers, config, models); see `FETCH_MODULE.md`
- `ingest/` — scheduled self-built supermarket scraping (Playwright); see `INGEST_MODULE.md`
- `infrastructure/config/` — Kafka wiring stubs

> TODO: structure no longer matches the repo convention (`api/rest`, `application`, `domain`,
> `infrastructure`) — migrate `model/`, `repository/`, `service/` per root `AGENT.md`. The
> `infrastructure/config` Kafka stubs and the `infrastructure/publisher` port were removed and their
> replacements under `infrastructure/messaging/` do not exist yet (see `TASKS.md`).

See `AGENT.md` for implementation TODOs.
