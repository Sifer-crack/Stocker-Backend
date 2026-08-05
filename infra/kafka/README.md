# infra/kafka/README.md
#
# Kafka topic registry (single source of truth): `topics.yml`.
#
# Local dev provisions a single-node KRaft broker via docker-compose
# (apache/kafka image). The `kafka-init` one-shot container runs
# `kafka-init.sh`, which mirrors `topics.yml` and is idempotent
# (`--if-not-exists`). Reserved topics (`enabled: false`) are never created.
#
# Consumer conventions (applied in every service):
#   - `enable-auto-commit: false` + manual (idempotent) handling
#   - at-least-once delivery: handle events idempotently
#   - consumer group id `stocker.<service>`
#
# To list current topics against a running broker:
#   docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
