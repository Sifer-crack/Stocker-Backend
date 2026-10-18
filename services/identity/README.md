# identity

Owns: authentication, users, and households.
Data store: PostgreSQL (`identity` database), schema managed by Flyway.
Status: auth implemented — email/password + GitHub OAuth2 sign-in issuing the service's own JWTs.

## Authentication

- `POST /identity/register`, `POST /identity/login` — email/password; login returns an access token
  and sets an httpOnly `refreshToken` cookie.
- `POST /identity/refresh`, `POST /identity/logout` — rotate/clear the refresh cookie.
- `GET|PATCH /identity/me`, `GET|POST /identity/me/spending-report` — require a bearer access token.
- GitHub sign-in: `GET /identity/oauth2/authorization/github` starts the flow; GitHub redirects to
  `GET /identity/login/oauth2/code/github`. On success the app sets the refresh cookie and redirects
  to `STOCKER_OAUTH_SUCCESS_REDIRECT`.

Via the gateway these are exposed at `/api/identity/**` (e.g. the login link is
`http://localhost:8080/api/identity/oauth2/authorization/github`).

### Configuration

| Env var | Purpose |
|---|---|
| `STOCKER_GITHUB_CLIENT_ID` / `STOCKER_GITHUB_CLIENT_SECRET` | GitHub OAuth App credentials. Unset = OAuth disabled. |
| `STOCKER_OAUTH_REDIRECT_URI` | Callback registered with GitHub (gateway URL). |
| `STOCKER_OAUTH_SUCCESS_REDIRECT` / `STOCKER_OAUTH_FAILURE_REDIRECT` | Frontend redirect after callback. |
| `STOCKER_JWT_SECRET` | HS256 signing key (≥32 bytes), shared with the gateway. |

GitHub OAuth App callback URL must be
`http://localhost:8080/api/identity/login/oauth2/code/github` (dev).

## Confirmed event topics

| Topic | Direction | Key |
|---|---|---|
| `stocker.household.events.v1` | produces | `householdId` |

Consumes are unconfirmed for now (a stub consumer exists on the household topic).

## Structure

- `api/rest/` — HTTP surface (`AuthController`, `UserController`, `HealthzController`)
- `application/` — use cases (`AuthService`)
- `domain/` — entities (`User`)
- `infrastructure/` — persistence, security (JWT + OAuth2), Kafka wiring

See `AGENT.md` for details and TODOs.
