# AGENT.md — gateway

## Implemented (skeleton)

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8080 (`SERVER_PORT`).
- Spring Cloud Gateway **Server WebFlux** (`spring-cloud-starter-gateway-server-webflux`); routes under `spring.cloud.gateway.server.webflux.routes` (currently empty).
- `GET /healthz` via `api/rest/HealthzController` (WebFlux annotation controller).
- Spring gRPC **client** (`org.springframework.grpc:spring-grpc-client-spring-boot-starter` 1.0.3, BOM-managed) wired via `infrastructure/config/GrpcOutboundConfig`.
- `HealthzControllerTest` (`@WebFluxTest`, no broker needed).

## Stubbed / TODO

- Define real routes once services expose REST surfaces (in `application.yml`).
- Add proto definitions + spring-grpc protobuf build plugin, then create blocking/reactive stubs per service in `GrpcOutboundConfig`.
- `infrastructure/config/GatewayRoutesConfig` — document/configure route predicates and filters.

## Conventions

- Package root: `com.stocker.gateway`.
- Route config namespace for this stack is `spring.cloud.gateway.server.webflux.routes` (NOT the legacy `spring.cloud.gateway.routes`).
- gRPC channel targets: `spring.grpc.client.channels.<name>.address`; the default channel is `spring.grpc.client.default-channel.address`.
- Only `/healthz` (and actuator) may be exposed; keep the module structurally identical to the other services.
