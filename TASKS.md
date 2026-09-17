# TASKS.md

Outstanding work for the Stocker Backend, grouped by area.

Last updated: 13/09/2026

## Price engine (`services/pricing`)

TODOs are also inlined in the source: `build.gradle`, `application.yml`,
`PricingApplication.java`, `api/grpc/PriceRecordGrpcController.java`, `README.md`, `AGENT.md`.

- [ ] **gRPC server is not actually served**
  `build.gradle` declares only the low-level `io.grpc` libs (netty-shaded / protobuf / stub), so
  `PriceRecordServiceGrpc.PriceRecordServiceImplBase` is never bound to a listener. There is no
  Spring gRPC server starter and no `spring.grpc.server.port` in `application.yml`.
  `api/grpc/PriceRecordGrpcController` is `@Controller`; Spring gRPC registers `@GrpcService` beans.
  `README.md`/`AGENT.md` claim a working gRPC server on 8084 — that is currently false.
  Adding the starter requires approval (root `AGENT.md` pins the dependency set).

- [ ] **No event wiring**
  `application.yml` configures consumer topic `stocker.catalog.events.v1` and producer topics
  `stocker.pricing.events.v1` / `stocker.promotions.events.v1`, but there is no `@KafkaListener` and
  no publisher class. The working tree deleted `infrastructure/publisher/EventPublisher` +
  `KafkaEventPublisher` and the Kafka configs without replacing them (unlike identity/catalog/shopping).
  Missing files to create: `domain/port/EventPublisher.java`,
  `infrastructure/messaging/KafkaEventPublisher.java`, `infrastructure/messaging/KafkaEventConsumer.java`,
  `application/PricingEventService.java`. Topic names must match `infra/kafka/topics.yml`.

- [ ] **Save-path bugs (runtime failures) in `PriceRecordGrpcController.toEntity`**
  - `raw_attributes` is never copied from the request (proto field is a JSON string) → `null` inserted
    into a `NOT NULL` column.
  - `captured_at` may be the zero proto timestamp → mapped to `null` → `NOT NULL` violation.
  - `channel` has a DB CHECK (`pickup`/`click_and_collect`) but no validation.
  - `price_amount` has no negative guard against the DB CHECK `>= 0`.

- [ ] **Structure does not follow the convention**
  The module uses legacy `model/`, `repository/`, `service/`; other services use `api/rest`,
  `application`, `domain`, `infrastructure`. Root `AGENT.md` requires the layout to be identical, and
  the in-progress hexagonal refactor skipped pricing.

- [ ] **Tests missing**
  Only `GET /healthz` is covered. Add gRPC and `PriceFetcherService` tests; declare an in-process gRPC
  test dependency.

- [ ] **Docs drift**
  `services/pricing/README.md` and `AGENT.md` describe a live gRPC server and a retained
  `EventPublisher` port that no longer exist in code. Reconcile once the above are done.

- [ ] **Gateway cannot reach the price engine**
  `GrpcOutboundConfig` is still a TODO and `gateway/application.yml` defines no `pricing` channel
  (only `identity`, pointing at a placeholder `localhost:9090`). Needs a pricing gRPC channel + stubs.

## Cross-cutting blockers (outside pricing)

These make `./gradlew build` fail but are unrelated to the price engine. `:services:pricing:build`
passes in isolation.

- [ ] `services/identity/.../application/HouseholdEventService.java:3` imports a missing
  `com.stocker.identity.domain.port.EventConsumer`.
- [ ] `services/analytics/.../test/.../HealthzControllerTest.java:12` references the deleted
  `HealthzController`.
- [ ] The uncommitted hexagonal refactor is inconsistent (pricing not migrated; docs out of sync).
