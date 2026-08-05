# infrastructure

Infrastructure adapters: messaging (Kafka), outbound notification channels, external clients.

Rules:
- Implements the ports used by the `application` layer.
- This service is stateless (no data store) — it consumes events and fans out notifications.
- Only stubs exist today; see `AGENT.md` in this module for the TODOs left for a future session.
