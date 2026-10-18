# AGENT.md — identity

## Implemented

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8081 (`SERVER_PORT`).
- Postgres via `STOCKER_DB_URL`/`STOCKER_DB_USER`/`STOCKER_DB_PASSWORD` (defaults: localhost:5432/identity, stocker/stocker).
- Flyway enabled locally; migrations `V1` placeholder, `V2__users.sql`, `V3__remove_username.sql`,
  `V4__oauth_identity.sql`. Docker Compose disables Flyway for identity because the Neon schema is
  managed directly — apply `V4` there manually.
- JWT auth (`infrastructure/security/`): HS256 access + refresh tokens via `JwtService`; refresh token
  is delivered as an httpOnly `refreshToken` cookie (`RefreshTokenCookieFactory`).
- REST (`api/rest/`): `POST /identity/register|login|refresh|logout`, `GET|PATCH /identity/me`,
  `GET|POST /identity/me/spending-report`, `GET /healthz`.
- GitHub sign-in (OAuth2 authorization-code, backend-brokered):
  - `OAuthClientConfig` builds the `github` `ClientRegistration` from `stocker.oauth.github.*`
    (empty registration when `STOCKER_GITHUB_CLIENT_ID` is unset, so the app still boots).
  - Login URL: `GET /identity/oauth2/authorization/github` (public via gateway:
    `GET /api/identity/oauth2/authorization/github`).
  - Callback: `GET /identity/login/oauth2/code/github` (registered with GitHub as
    `STOCKER_OAUTH_REDIRECT_URI`, default `http://localhost:8080/api/identity/login/oauth2/code/github`).
  - `GithubOAuth2UserService` reads `/user`, falling back to `/user/emails` for a verified primary
    email. `AuthService.provisionOAuthUser` links by `(provider, providerId)`, else auto-links an
    existing account by verified email, else creates a password-less user.
  - `OAuth2AuthenticationSuccessHandler` sets the refresh cookie and 302s to
    `STOCKER_OAUTH_SUCCESS_REDIRECT`; failure goes to `STOCKER_OAUTH_FAILURE_REDIRECT` with `?error=`.
  - The chain stays `STATELESS`: the authorization request is persisted in a signed cookie via
    `CookieOAuth2AuthorizationRequestRepository` (no server session).
- Auth rules in `infrastructure/security/SecurityConfig`; `/healthz`, actuator, the OAuth paths and
  the register/login/refresh/logout POSTs are public, everything else requires a JWT.

## Stubbed / TODO

- `infrastructure/config/KafkaProducerConfig` / `KafkaConsumerConfig` — wire `KafkaTemplate` and the
  household event handler (no business logic yet).

## Conventions

- Package root: `com.stocker.identity`.
- Layers: `api` → `application` → `domain`, with infrastructure in `infrastructure/`.
- Idempotent consumers / at-least-once delivery expected on all Kafka topics (see `infra/kafka/topics.yml`).
