# infrastructure

Infrastructure adapters: persistence (MongoDB), messaging (Kafka), publisher, external clients.

Rules:
- Implements the ports used by the `application` layer.
- MongoDB is schemaless (no Flyway here). Collection/index bootstrap TODOs belong in `persistence/`.
- The event publisher port lives in `publisher/` (`EventPublisher` interface + `KafkaEventPublisher` stub).
- Only stubs exist today; see `AGENT.md` in this module for the TODOs left for a future session.
