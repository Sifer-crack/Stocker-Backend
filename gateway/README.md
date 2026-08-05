# gateway

Owns: the single entry point / BFF. REST (HTTP) in from clients, gRPC out to services.
Status: **skeleton only** — boots (WebFlux), exposes `GET /healthz`. No routes, no gRPC stubs yet.

## Design

- Inbound: REST via Spring Cloud Gateway (routes under `spring.cloud.gateway.server.webflux.routes`).
- Outbound: gRPC to services via Spring gRPC client (`infrastructure/config/GrpcOutboundConfig`).
- No database; stateless.

## Structure

- `api/` — HTTP surface (only `/healthz` today)
- `application/` — use cases (empty, TODO)
- `domain/` — entities/aggregates (empty, TODO)
- `infrastructure/config/` — `GatewayRoutesConfig` + `GrpcOutboundConfig` stubs

See `AGENT.md` for implementation TODOs.
