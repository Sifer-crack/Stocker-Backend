# gateway

Owns: the single entry point / BFF. REST (HTTP) in from clients, gRPC out to services.
Status: boots (WebFlux), exposes `GET /healthz`, `GET /api/pricing/search` (real, backed by
pricing's gRPC `Search` RPC), and `POST /api/pricing/compare` (real, backed by pricing's gRPC
`CompareShoppingList` RPC — a shopping list's total cost per supermarket chain, the recommended
cheapest chain, and a savings amount per chain). No proxied routes yet — every other service is
still skeleton.

## Design

- Inbound: REST via Spring Cloud Gateway (routes under `spring.cloud.gateway.server.webflux.routes`
  for proxying) **or** a locally-implemented BFF controller like `PricingController` that
  translates REST directly to a gRPC call — the latter needs no route entry.
- Outbound: gRPC to services via Spring gRPC client (`infrastructure/config/GrpcOutboundConfig`).
- No database; stateless.

## Structure

- `api/rest/` — `HealthzController`, `PricingController` (REST → gRPC translation)
- `application/` — use cases (empty, TODO)
- `domain/` — entities/aggregates (empty, TODO)
- `infrastructure/config/` — `GatewayRoutesConfig` (proxied-route config, currently empty) +
  `GrpcOutboundConfig` (gRPC channel/stub beans)
- `src/main/proto/` — `price_record.proto`, duplicated from pricing (no shared-proto mechanism yet)

See `AGENT.md` for implementation TODOs.
