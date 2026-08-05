# application

Application / use-case layer: services, commands, queries, and application-level event handling.

Rules:
- Orchestrates domain objects; depends on `domain` and on ports defined in `infrastructure`.
- No framework-specific infrastructure here.
- Implement the real use cases here when implementing the service.
