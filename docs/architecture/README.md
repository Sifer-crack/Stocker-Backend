# docs/architecture/README.md
#
# Architecture documentation. Skeleton only.
#
# Planned artifacts (C4 model):
#   - system-context.md     C4 level 1: household app -> gateway -> services -> infra
#   - containers.md         C4 level 2: containers + Kafka topics + DBs
#   - components.md         C4 level 3: per-service internals (event-driven flows)
#
# TODO: render C4 diagrams (PlantUML / Mermaid) for each level.
#
# Quick mental model while docs are missing:
#
#   [Frontend] --HTTP--> [Gateway :8080] --gRPC--> [services]
#
#   identity :8081   (household/accounting)          -> stocker.household.events.v1
#   catalog  :8082   (item master data)              -> stocker.catalog.events.v1
#   shopping :8083   (lists/items, Postgres+outbox)  -> stocker.shopping.events.v1
#   pricing  :8084   (price quotes, MongoDB)         <- stocker.catalog/promotions
#                                                      -> stocker.pricing.events.v1
#   notifications :8085 (stateless consumer)
#   analytics :8086   (stateless consumer)
#
#   Kafka (KRaft single node) <-> all services
#   Postgres  identity/catalog/shopping   (Flyway-managed)
#   MongoDB   pricing
#
# Reserved (do NOT scaffold yet): inventory read-model service + stocker.inventory.events.v1.
