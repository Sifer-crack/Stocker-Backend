# AGENT.md — gateway

## Implemented (skeleton)

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8080 (`SERVER_PORT`).
- Spring Cloud Gateway **Server WebFlux** (`spring-cloud-starter-gateway-server-webflux`); routes under `spring.cloud.gateway.server.webflux.routes` (currently empty).
- `GET /healthz` via `api/rest/HealthzController` (WebFlux annotation controller).
- Spring gRPC **client** (`org.springframework.grpc:spring-grpc-client-spring-boot-starter` 1.0.3, BOM-managed) wired via `infrastructure/config/GrpcOutboundConfig`.
- `pricing` gRPC channel (`spring.grpc.client.channels.pricing`, default `dns:///localhost:9094`) with a real `PriceRecordServiceGrpc.PriceRecordServiceBlockingStub` bean. `src/main/proto/price_record.proto` is a duplicate of pricing's copy (protobuf/grpc-java codegen plugin added to `build.gradle`) — there's no shared-proto mechanism in this repo, so keep the two files byte-for-byte in sync when the contract changes.
- `api/rest/PricingController` — `GET /api/pricing/search` (`term`, `itemId`, optional `storeUrls`, `category`) translates to the gRPC `Search` RPC and maps the response to JSON, sorted by price ascending. This is a locally-implemented BFF endpoint, not a proxied route — it needs no `GatewayRoutesConfig` entry. The blocking stub call is offloaded via `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` since this module runs on WebFlux/Netty.
- `HealthzControllerTest` (`@WebFluxTest`, no broker needed).

## Stubbed / TODO

- Define real routes once other services (identity, catalog, ...) expose REST surfaces (in `application.yml`) — `PricingController` above is the only real endpoint so far.
- `identity` gRPC channel exists in `application.yml` but has no stub bean yet (identity has no gRPC API to generate from).

## Conventions

- Package root: `com.stocker.gateway`.
- Route config namespace for this stack is `spring.cloud.gateway.server.webflux.routes` (NOT the legacy `spring.cloud.gateway.routes`).
- gRPC channel targets: `spring.grpc.client.channels.<name>.address`; the default channel is `spring.grpc.client.default-channel.address`.
- Only `/healthz` (and actuator) may be exposed; keep the module structurally identical to the other services.
