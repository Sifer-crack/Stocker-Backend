#!/usr/bin/env bash
set -euo pipefail

# Creates the Kafka topics from infra/kafka/topics.yml (kept in sync manually).
# Idempotent via --if-not-exists. Reserved topics (stocker.inventory.events.v1)
# are intentionally NOT created until the inventory service ships.

BOOTSTRAP=${KAFKA_BOOTSTRAP_SERVERS:-kafka:29092}

create() {
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists --replication-factor 1 \
    --topic "$1" --partitions "$2"
}

create stocker.household.events.v1 6
create stocker.catalog.events.v1 6
create stocker.promotions.events.v1 6
create stocker.shopping.events.v1 12
create stocker.pricing.events.v1 12

echo "Kafka topics ensured: household/catalog/promotions(6p), shopping/pricing(12p)"
