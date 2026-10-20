# Testing

Every feature ships with three layers of tests, written before the feature code. `./gradlew test` runs all
of them offline (no network, no database, no Kafka), so it works the same on any machine.

| Layer | What it covers | Naming / tag | Style |
|---|---|---|---|
| Unit | One function or class, every major function | `*Test` | Plain JUnit 5 + Mockito, fixed `Clock`; `@WebMvcTest` for controllers |
| Service | 1-3 realistic scenarios of how a feature is used, with real services and in-memory fakes for ports | `*ScenarioTest`, `@Tag("service")` | No Spring context |
| Feature integration | One per feature: the whole path across modules, with only the network edge stubbed | `*IntegrationTest`, `@Tag("feature-integration")` | JDK `HttpServer` stands in for the other service |
| Database (gated) | Real Postgres + Flyway (a throwaway test fixture; the running system uses Neon) | `*IT` | `@EnabledIfEnvironmentVariable`, skipped by default |

## Commands

```bash
./gradlew test                                   # everything, offline
./gradlew :services:notifications:test           # one module
./gradlew serviceTest                            # only the service-scenario layer
./gradlew featureIntegrationTest                 # only the feature-integration layer
STOCKER_NOTIFICATIONS_DB_IT=true ./gradlew :services:notifications:test --tests '*IT'   # needs a Postgres test fixture
```

## Conventions

- Time-dependent code takes a `java.time.Clock`; tests use `Clock.fixed(...)`.
- Boot 4 test packages: `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`,
  `org.springframework.test.context.bean.override.mockito.MockitoBean`.
- Tests never call a supermarket. Live tests stay behind their env-var gates.
- Name tests after the acceptance criterion they prove (the Trello acceptance tests are the source of truth).

## Lessons learned

### Feature 1: expiry notifications

- **Check the baseline first.** `origin/dev` had two red tests unrelated to the feature (inventory's health
  controller in the wrong package, a stale pricing test). They were fixed here exactly as on `feature/price-engine`
  (commit `503ff8a`) so the two branches merge without a conflict.
- **Share fakes between layers.** One in-memory repository per module (`InMemory*Repository`) serves the service
  scenarios and the feature-integration tests; only the network edge differs.
- **A JDK `HttpServer` is enough as a stub** for the other service (`InventoryStub`): no WireMock dependency, and
  the real HTTP client and JSON contract are exercised. The same literal JSON is asserted on both sides.
- **A shared database needs its own Flyway history table and a baseline.** The Neon schema is shared and
  inventory's table was made by hand, so `PantryItemPostgresIT` simulates that exact case in a throwaway schema.
- **Service-to-service endpoints go under `/internal`.** The gateway only routes `/api/**`, so they are not
  reachable from a browser. The expiring-items list holds every user's data.
- **Database tests use a throwaway Postgres as a test fixture** (the running system itself always uses Neon, one database per service). Never point them at the shared Neon `neondb`:
  `docker --context default run --rm -p 5544:5432 -e POSTGRES_USER=stocker -e POSTGRES_PASSWORD=stocker -e POSTGRES_DB=notifications postgres:17`
  (use `--context default` when Docker Desktop is not running), then
  `STOCKER_NOTIFICATIONS_DB_IT=true STOCKER_DB_URL=jdbc:postgresql://localhost:5544/notifications ./gradlew :services:notifications:test --tests '*PostgresIT'`.
- **Final proof is a real run:** both jars started against the throwaway Postgres and every acceptance scenario
  driven with curl (add item, scan, list, acknowledge, rescan).

### Feature 2: favorite stores

- **Check what is actually committed.** The first exploration read an uncommitted working tree
  (`NormalizedProduct`, on-demand refresh) that does not exist at the committed `feature/price-engine` HEAD. The
  feature was rebuilt on the committed `price_records` data instead. Always `git worktree add` the real base and run
  its tests first.
- **New proto files, not edits to old ones.** The store catalogue is its own `chain_catalog.proto` (pricing and a
  copy in the gateway), a new gateway controller and a new gRPC controller. Nothing existing was touched, so the
  change cannot collide with in-flight edits to `price_record.proto`, `PricingController` or the gRPC controller
  constructor (which every existing gRPC test calls).
- **A UNIQUE constraint treats NULLs as distinct.** "Whole chain" favorites have a NULL `store_id`, so uniqueness is
  an index on `COALESCE(store_id, '')`, and the Postgres test proves the database rejects a duplicate.
- **Spring replaces lists, it does not merge them.** Overriding one gateway route from an environment variable
  broke the whole list. A test-only `--spring.config.additional-location` file with the full route list works.
- **Harness gotchas** (cost time, not code): `pkill -f` matches your own shell command; `docker exec` needs `-i`
  to receive piped SQL; check seed data landed (`select count(*)`) before blaming the code.
- **Real run through the real gateway** (JWT minted with the dev secret) for pricing + shopping + gateway on a
  throwaway Postgres fixture with fake rows and `STOCKER_INGEST_ENABLED=false`: it can never reach a supermarket.
  Runtime config itself is Neon-only: each service has its own Neon database (`STOCKER_SHOPPING_DB_URL`, ...).
