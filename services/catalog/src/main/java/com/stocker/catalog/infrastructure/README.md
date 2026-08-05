# infrastructure

Infrastructure adapters: persistence (JPA/Flyway), messaging (Kafka), outbox/publisher, external clients.

Rules:
- Implements the ports used by the `application` layer.
- Only stubs exist today; see `AGENT.md` in this module for the TODOs left for a future session.
