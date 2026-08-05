# infrastructure

Infrastructure adapters: persistence (JPA/Flyway), messaging (Kafka), outbox, external clients.

Rules:
- Implements the ports used by the `application` layer.
- The outbox publisher stub lives in `outbox/` and is driven by the `outbox` table
  created in `db/migration/V2__create_outbox.sql`.
- Only stubs exist today; see `AGENT.md` in this module for the TODOs left for a future session.
