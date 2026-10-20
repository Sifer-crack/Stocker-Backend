# AGENT.md — catalog

## Implemented

- Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8082 (`SERVER_PORT`). Reached through the gateway as
  `/api/products/**` (`StripPrefix=1`).
- Endpoints (`api/rest/ProductController`, `api/rest/ProductGroupingController`):
  - `GET /products` — all products; `GET /products/{id}`; `GET /products/search?query=` — name search.
  - `POST /products?productName=&groceryType=&sellingWeightKg=&sellingVolumeL=` — create a product; `DELETE /products/{id}`.
  - `GET /products/grouping/preview`, `GET /products/grouping/preview/safe`, `GET /products/grouping/import-preview/` and
    `POST /products/grouping/import` — group the raw `items` rows into products (normalised name + grocery type + weight/volume),
    preview what an import would create, and import it (`ProductGroupingService`, `ProductImportService`).
  - `GET /healthz`.
- **Writes are blocked at the gateway.** `SecurityConfig` permits `GET /api/products/**` without a token and denies every
  other method (401 anonymous, 403 signed-in); create, delete and import are service-to-service or direct to port 8082 (bound to
  127.0.0.1 in compose). The frontend only reads.
- Postgres via `STOCKER_DB_URL` / `STOCKER_DB_USER` / `STOCKER_DB_PASSWORD` (no `application.yml` default; the `Dotenv` loader in
  `main()` copies them from the root `.env`). In the stack it is the shared Neon `neondb` (tables `products`, `items`, `product_item_mappings`).
- Flyway: only `V1__placeholder.sql` exists, and **Flyway is disabled in docker-compose** (`SPRING_FLYWAY_ENABLED=false`): the Neon
  tables are managed by hand, so the schema is not described by any migration here.
- Tests: `HealthzControllerTest` only (`@WebMvcTest`, no DB/broker needed). The product and grouping logic has no automated tests.

## Stubbed / TODO

- Kafka: `infrastructure/messaging/KafkaEventPublisher#publish` and `KafkaEventConsumer` are `// TODO` stubs; `application/CatalogEventService`
  does not publish when a product is added (topic `stocker.catalog.events.v1`).
- Real Flyway migrations for the `products` / `items` tables, and tests for `ProductService` and the grouping/import services.
- An admin role so catalog writes can be allowed through the gateway.

## Conventions

- Package root: `com.stocker.catalog`.
- Layers: `api` → `application` → `domain`, with ports implemented in `infrastructure`.
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
