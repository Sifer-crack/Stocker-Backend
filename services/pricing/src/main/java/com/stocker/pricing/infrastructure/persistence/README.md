# persistence

MongoDB adapters for this service.

- MongoDB is schemaless, so there are no Flyway migrations here (unlike the Postgres services).
- TODO: define collections, indexes, and any bootstrap documents when implementing the real model.
- Connection is configured via `spring.data.mongodb.uri` (env `STOCKER_MONGO_URI`).
