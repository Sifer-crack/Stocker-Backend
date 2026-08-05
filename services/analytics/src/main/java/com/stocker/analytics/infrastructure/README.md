# infrastructure

Infrastructure adapters: messaging (Kafka), event sink / analytics store (optional), external clients.

Rules:
- Implements the ports used by the `application` layer.
- The analytics store is OPTIONAL and unconfirmed — no store dependency is wired yet.
  If/when confirmed, add the adapter here (e.g. MongoDB, ClickHouse, or object storage).
- Only stubs exist today; see `AGENT.md` in this module for the TODOs left for a future session.
