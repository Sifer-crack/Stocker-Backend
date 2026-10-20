# AGENT.md — identity

## Implemented

- Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8081 (`SERVER_PORT`). Reached through the gateway as
  `/api/identity/**` (`StripPrefix=1`, so the service itself serves `/identity/**`).
- Endpoints (`api/rest/AuthController`, `api/rest/UserController`):
  - `POST /identity/register` — body `{email, password (8-100), firstName?, lastName?}`; 201 with the user (`id`, `email`,
    `firstName`, `lastName`, `groceryBudget`); no token is returned. Duplicate email: 409 `{"error":"email already registered"}`.
  - `POST /identity/login` — 200 `{accessToken, tokenType:"Bearer", expiresIn}` plus an httpOnly `refreshToken` cookie
    (`SameSite=Lax`, `Path=/`, `Secure` from `STOCKER_AUTH_COOKIE_SECURE`). Wrong credentials: 401 `{"error":"invalid credentials"}`.
  - `POST /identity/refresh` — reads the `refreshToken` cookie, returns a new access token and rotates the cookie.
  - `POST /identity/logout` — 204, clears the cookie.
  - `GET /identity/me`, `PATCH /identity/me` (`{groceryBudget}`), `GET` / `POST /identity/me/spending-report` (JSON document stored per user).
- Tokens (`infrastructure/security/JwtService`): HS256, secret `STOCKER_JWT_SECRET` (at least 32 bytes), issuer `stocker-identity`,
  subject = user id, claims `email` and `type` (`access` or `refresh`). Lifetimes: access 900 s (`STOCKER_JWT_ACCESS_TTL`),
  refresh 604800 s = 7 days (`STOCKER_JWT_REFRESH_TTL`). The gateway accepts only `type=access` as a Bearer token; the refresh
  token is only valid on `/identity/refresh`. The client refreshes on a 401 (the access token lives 15 min).
- Postgres via `STOCKER_DB_URL` / `STOCKER_DB_USER` / `STOCKER_DB_PASSWORD` (the `application.yml` fallback is
  `localhost:5432/identity`, `stocker`/`stocker`; in the stack it is the shared Neon `neondb`).
- Flyway migrations: `V1__placeholder.sql`, `V2__users.sql` (`users` table), `V3__remove_username.sql`. **Flyway is disabled in
  docker-compose** (`SPRING_FLYWAY_ENABLED=false`): the Neon `users` table is managed by hand. V1-V3 do not create the
  `grocery_budget` and `spending_report` columns that the entity and `me` endpoints use, so a database created only from these
  migrations would not match (`ddl-auto: validate`); the Neon table is assumed to have them (not checked from this repository).
- Tests: `AuthControllerTest`, `UserControllerTest`, `HealthzControllerTest` (`@WebMvcTest`), `JwtServiceTest`. No database or broker needed.

## Stubbed / TODO

- Kafka: `infrastructure/messaging/KafkaEventPublisher#publish`, `KafkaEventConsumer` and `application/HouseholdEventService#handleEvent`
  are `// TODO` stubs (topic `stocker.household.events.v1`); nothing is published yet.
- No roles or households yet (the gateway forwards `X-Household-Id` / `X-Roles` only if a token carries them; this service issues neither).
- A Flyway migration for the budget and spending-report columns.

## Conventions

- Package root: `com.stocker.identity`.
- Layers: `api` → `application` → `domain`, with ports implemented in `infrastructure`.
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
- The service trusts nothing from the browser: CORS is enforced only at the gateway, which strips the browser's CORS request headers.
