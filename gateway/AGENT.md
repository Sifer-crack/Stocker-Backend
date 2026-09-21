# AGENT.md — gateway

## Implemented (skeleton)

- Boots a Spring Boot 4.0.7 app (Java 25, Gradle Groovy DSL) on port 8080 (`SERVER_PORT`).
- Spring Cloud Gateway **Server WebFlux** (`spring-cloud-starter-gateway-server-webflux`); routes under `spring.cloud.gateway.server.webflux.routes` (identity, catalog, inventory and shopping; see `application.yml`).
- `GET /healthz` via `api/rest/HealthzController` (WebFlux annotation controller).
- Spring gRPC **client** (`org.springframework.grpc:spring-grpc-client-spring-boot-starter` 1.0.3, BOM-managed) wired via `infrastructure/config/GrpcOutboundConfig`.
- `pricing` gRPC channel (`spring.grpc.client.channels.pricing`, default `dns:///localhost:9094`) with a real `PriceRecordServiceGrpc.PriceRecordServiceBlockingStub` bean. `src/main/proto/price_record.proto` is a duplicate of pricing's copy (protobuf/grpc-java codegen plugin added to `build.gradle`) — there's no shared-proto mechanism in this repo, so keep the two files byte-for-byte in sync when the contract changes.
- `api/rest/PricingController` — `GET /api/pricing/search` (`term`, `itemId`, optional `storeUrls`, `category`) translates to the gRPC `Search` RPC and maps the response to JSON, sorted by price ascending. This is a locally-implemented BFF endpoint, not a proxied route — it needs no `GatewayRoutesConfig` entry. The blocking stub call is offloaded via `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` since this module runs on WebFlux/Netty.
- `api/rest/PricingController#compare` — `POST /api/pricing/compare` (JSON body: `items`, `region`,
  optional `selectedChainId`) translates to the gRPC `CompareShoppingList` RPC. On the RPC's
  `Status.INVALID_ARGUMENT` ("outside service area"), `onErrorResume` maps it to a
  `ResponseStatusException(422)`; a controller-scoped `@ExceptionHandler(ResponseStatusException.class)`
  puts the message into the JSON body as `{"error": "..."}` — WebFlux's default error body omits
  `getReason()` unless `server.error.include-message` is set globally, which this deliberately avoids
  changing repo-wide.
- **Shopping route + comparison push.** Route `shopping` (`/api/shopping/**` -> `http://shopping:8083`, `StripPrefix=1`). Comparison results
  reach the browser over `GET /api/updates/shopping` (`api/rest/ShoppingUpdatesController`): a per-user SSE stream, JWT-authenticated
  (the user is the token subject), event name `shopping-item-updated`, data `{ "source": "sync"|"backfill", "item": {full item state} }`
  (the owner id is routing data and is not sent). An immediate SSE-comment heartbeat opens the stream, then one every 25s. The stream
  is deliberately outside `/api/shopping/**` (which is proxied). Shopping feeds it via `POST /internal/shopping/updates`
  (`X-Internal-Token` must equal `stocker.internal.token` / `STOCKER_INTERNAL_TOKEN`; closed if unset; no user JWT; blocked at the
  ingress in `infra/ingress/nginx.conf`). `application/ShoppingUpdateHub` is the in-memory fan-out (best effort, single gateway instance).
  A browser `EventSource` cannot send an `Authorization` header, so the frontend must read the stream with a fetch-based SSE client.
- `api/rest/PricingController#match` - `GET /api/pricing/match?term&itemId[&category]` -> gRPC `MatchItem`; JSON `{matchMethod, matches[], alternatives[]}` (`matches`: at most one per chain, cheapest first, `matchType: "exact"`; `alternatives`: `matchType: "alternative"`). Empty lists = nothing found yet (not an error). `INVALID_ARGUMENT` -> 422 `{error}`.
- `JwtClaimForwardFilter` (global filter, runs for EVERY proxied request, must not depend on the path - `StripPrefix` has already rewritten it by then): removes any client-supplied `X-User-Id` / `X-Household-Id` / `X-Roles`, then sets them from the verified JWT. `Authorization` is deliberately left in place (identity validates it itself). It previously checked `path.startsWith("/api/")` after StripPrefix, so it never ran and identity headers were spoofable; `JwtClaimForwardFilterTest` guards this with a real upstream.
- `ShoppingUpdatesControllerTest` (`@SpringBootTest`, real signed JWTs) covers token rejection, per-user isolation and the pushed payload.
- `HealthzControllerTest` (`@WebFluxTest`, no broker needed); `PricingControllerCompareTest`
  (`@WebFluxTest` + `@MockitoBean` stub) covers the success shape and the 422 error-mapping path.

## Stubbed / TODO

- Define real routes once other services (identity, catalog, ...) expose REST surfaces (in `application.yml`) — `PricingController` above is the only real endpoint so far.
- `identity` gRPC channel exists in `application.yml` but has no stub bean yet (identity has no gRPC API to generate from).

## Conventions

- Package root: `com.stocker.gateway`.
- Route config namespace for this stack is `spring.cloud.gateway.server.webflux.routes` (NOT the legacy `spring.cloud.gateway.routes`).
- gRPC channel targets: `spring.grpc.client.channels.<name>.address`; the default channel is `spring.grpc.client.default-channel.address`.
- Only `/healthz` (and actuator) may be exposed; keep the module structurally identical to the other services.
