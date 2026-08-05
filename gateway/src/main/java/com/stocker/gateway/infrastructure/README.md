# infrastructure

Infrastructure adapters: gateway routing, outbound gRPC channels, external clients.

Rules:
- Implements the ports used by the `application` layer.
- `config/GatewayRoutesConfig` documents the HTTP route story; real routes live in
  `spring.cloud.gateway.server.webflux.routes` in application.yml.
- `config/GrpcOutboundConfig` is the stub for outbound gRPC (Spring gRPC client).
- Only stubs exist today; see `AGENT.md` in this module for the TODOs left for a future session.
