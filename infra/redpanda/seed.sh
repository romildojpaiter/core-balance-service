#!/bin/bash
set -euo pipefail

BROKERS="${REDPANDA_BROKERS:-redpanda:9092}"
TOPIC_NAME="${TRANSACTIONS_TOPIC:-transactions-events}"
DLQ_TOPIC_NAME="${TRANSACTIONS_DLQ_TOPIC:-transactions-events.dlq}"
PARTITIONS="${TRANSACTIONS_TOPIC_PARTITIONS:-3}"

echo "Waiting for Redpanda broker at ${BROKERS}..."
until rpk cluster info --brokers "${BROKERS}" >/dev/null 2>&1; do
  echo "  not ready yet, retrying in 2s..."
  sleep 2
done
echo "Redpanda broker is ready."

# config.sh disables auto-creation, so both topics must be created here: a produce against a missing
# topic fails loudly instead of silently creating a one-partition topic nobody intended.
create_topic() {
  local name=$1
  if rpk topic describe "${name}" --brokers "${BROKERS}" >/dev/null 2>&1; then
    echo "Topic '${name}' already exists, skipping creation."
  else
    echo "Creating topic '${name}' with ${PARTITIONS} partition(s)..."
    rpk topic create "${name}" --brokers "${BROKERS}" --partitions "${PARTITIONS}" --replicas 1
  fi
}

create_topic "${TOPIC_NAME}"
# The DLQ gets the same partition count so a replayed message can be keyed exactly like the original.
create_topic "${DLQ_TOPIC_NAME}"

# No messages are published here. Balances are built from events, and a seeded event would be an
# event no producer sent; use `make kafka-produce-transactions-events` to generate traffic.
echo "Ready. Topics '${TOPIC_NAME}' and '${DLQ_TOPIC_NAME}' are available."
