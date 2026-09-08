#!/bin/bash
set -euo pipefail

TOPIC="${1:?Usage: produce-transactions-events.sh <topic> [count]}"
COUNT="${2:-100}"
BROKERS="${REDPANDA_BROKERS:-redpanda:9092}"
WINDOW_10MIN_US=$((10 * 60 * 1000000))
WINDOW_10YEARS_US=$((10 * 365 * 24 * 60 * 60 * 1000000))
NOW_US=$(date +%s%6N)

random_choice() {
  if (( RANDOM % 2 == 0 )); then echo "$1"; else echo "$2"; fi
}

random_amount() {
  awk -v min="$1" -v max="$2" -v seed="$RANDOM$RANDOM" 'BEGIN { srand(seed); printf "%.2f", min + rand() * (max - min) }'
}

random_offset_us() {
  local window=$1
  local wide=$(( RANDOM + (RANDOM << 15) + (RANDOM << 30) + (RANDOM << 45) ))
  echo $(( wide % window ))
}

echo "Producing ${COUNT} transaction event(s) to topic '${TOPIC}' (brokers: ${BROKERS})..."

for ((i = 1; i <= COUNT; i++)); do
  tx_timestamp=$((NOW_US - $(random_offset_us "$WINDOW_10MIN_US")))
  account_created_at=$((NOW_US - $(random_offset_us "$WINDOW_10YEARS_US")))

  account_id="$(cat /proc/sys/kernel/random/uuid)"

  # Keyed by account.id (Constitution VII): events for one account land on one partition and are
  # therefore consumed in order. This is a contention optimisation, never the ordering guarantee —
  # that lives in the DynamoDB conditional write, which is indifferent to arrival order.
  printf '%s\t{"transaction": {"id": "%s", "type": "%s", "amount": %s, "currency": "BRL", "status": "%s", "timestamp": %s}, "account": {"id": "%s", "owner": "%s", "created_at": %s, "status": "ENABLED", "balance": {"amount": %s, "currency": "BRL"}}}\n' \
    "$account_id" \
    "$(cat /proc/sys/kernel/random/uuid)" \
    "$(random_choice CREDIT DEBIT)" \
    "$(random_amount 0.01 10000)" \
    "$(random_choice APPROVED DECLINED)" \
    "$tx_timestamp" \
    "$account_id" \
    "$(cat /proc/sys/kernel/random/uuid)" \
    "$account_created_at" \
    "$(random_amount 0.00 20000)"
done | rpk topic produce "${TOPIC}" --brokers "${BROKERS}" -f '%k\t%v\n'

echo "Done. Published ${COUNT} event(s) to '${TOPIC}'."
