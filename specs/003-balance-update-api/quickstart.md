# Quickstart: Balance Update API

**Date**: 2026-09-05
**Feature**: 001-balance-update-api

This guide provides runnable validation scenarios to prove the feature works end-to-end.

## Prerequisites

- Docker with Docker Compose
- `make` command (available on macOS/Linux by default)
- `curl` or HTTP client for API testing

## Setup

### 1. Start Infrastructure

```bash
# Start DynamoDB and Redpanda (if not already running)
make db-up
make kafka-up

# Wait for infrastructure to be ready (check logs)
make logs
```

### 2. Start Application

```bash
# Option 1: Run via Docker Compose (includes all infrastructure)
make up

# Option 2: Run via IDE for development/debugging
# Infrastructure must be running (step 1)
# Run Application.kt from IDE or:
./gradlew bootRun
```

### 3. Verify Startup

```bash
# Check application health
curl http://localhost:8080/actuator/health

# Check DynamoDB table exists (via admin console)
open http://localhost:8001

# Check Kafka topic exists (via Redpanda console)
open http://localhost:8081
```

---

## Validation Scenarios

### Scenario 1: Query Zero Balance for New Account

**Given**: An account with no prior events
**When**: Query balance via REST API
**Then**: Returns zero balance

```bash
curl -s http://localhost:8080/api/v1/balances/acc-new-account | jq
```

**Expected Response**:
```json
{
  "accountId": "acc-new-account",
  "balance": "0.00",
  "currency": null,
  "lastTransactionId": null,
  "timestamp": null
}
```

---

### Scenario 2: Process First Balance Event

**Given**: An account with no prior events
**When**: Publish a balance event to Kafka
**Then**: Balance is updated and queryable

**Publish Event** (via Redpanda Console or rpk):

```bash
# Option 1: Via rpk
docker compose run --rm --entrypoint rpk redpanda-seed topic produce balance-updates --brokers redpanda:9092 <<EOF
{"transaction":{"id":"tx-001","timestamp":100},"account":{"id":"acc-12345","balance":{"amount":"100.00","currency":"BRL"}}}
EOF

# Option 2: Via Redpanda Console (http://localhost:8081)
# Topic: balance-updates
# Key: acc-12345
# Value: {"transaction":{"id":"tx-001","timestamp":100},"account":{"id":"acc-12345","balance":{"amount":"100.00","currency":"BRL"}}}
```

**Verify Balance**:
```bash
curl -s http://localhost:8080/api/v1/balances/acc-12345 | jq
```

**Expected Response**:
```json
{
  "accountId": "acc-12345",
  "balance": "100.00",
  "currency": "BRL",
  "lastTransactionId": "tx-001",
  "timestamp": 100
}
```

---

### Scenario 3: Process Higher Timestamp Event

**Given**: Account has balance with timestamp=100
**When**: Publish event with timestamp=200
**Then**: Balance is updated to new amount

```bash
docker compose run --rm --entrypoint rpk redpanda-seed topic produce balance-updates --brokers redpanda:9092 <<EOF
{"transaction":{"id":"tx-002","timestamp":200},"account":{"id":"acc-12345","balance":{"amount":"150.00","currency":"BRL"}}}
EOF
```

**Verify Balance**:
```bash
curl -s http://localhost:8080/api/v1/balances/acc-12345 | jq
```

**Expected Response**:
```json
{
  "accountId": "acc-12345",
  "balance": "150.00",
  "currency": "BRL",
  "lastTransactionId": "tx-002",
  "timestamp": 200
}
```

---

### Scenario 4: Reject Out-of-Order Event

**Given**: Account has balance with timestamp=200
**When**: Publish event with timestamp=150 (lower than stored)
**Then**: Balance remains unchanged

```bash
docker compose run --rm --entrypoint rpk redpanda-seed topic produce balance-updates --brokers redpanda:9092 <<EOF
{"transaction":{"id":"tx-003","timestamp":150},"account":{"id":"acc-12345","balance":{"amount":"120.00","currency":"BRL"}}}
EOF
```

**Verify Balance** (should still be 150.00):
```bash
curl -s http://localhost:8080/api/v1/balances/acc-12345 | jq
```

**Expected Response**:
```json
{
  "accountId": "acc-12345",
  "balance": "150.00",
  "currency": "BRL",
  "lastTransactionId": "tx-002",
  "timestamp": 200
}
```

---

### Scenario 5: Handle Duplicate Event

**Given**: Event with transaction.id="tx-002" and timestamp=200 already processed
**When**: Publish the same event again
**Then**: Balance remains unchanged

```bash
docker compose run --rm --entrypoint rpk redpanda-seed topic produce balance-updates --brokers redpanda:9092 <<EOF
{"transaction":{"id":"tx-002","timestamp":200},"account":{"id":"acc-12345","balance":{"amount":"150.00","currency":"BRL"}}}
EOF
```

**Verify Balance** (unchanged):
```bash
curl -s http://localhost:8080/api/v1/balances/acc-12345 | jq
```

---

### Scenario 6: Handle Invalid Event

**Given**: Any account
**When**: Publish event with missing required field
**Then**: Event is rejected, no balance change, error logged

```bash
docker compose run --rm --entrypoint rpk redpanda-seed topic produce balance-updates --brokers redpanda:9092 <<EOF
{"transaction":{"id":"tx-004"},"account":{"id":"acc-12345","balance":{"amount":"200.00","currency":"BRL"}}}
EOF
```

**Expected Behavior**:
- Application logs an error (check with `make logs`)
- No balance change

---

## Automated Test Commands

### Run Unit Tests

```bash
make test
```

**Expected**: All tests pass, JaCoCo reports ≥90% coverage.

### Run Integration Tests

```bash
make integration-test
```

**Expected**: All integration tests pass against live DynamoDB and Redpanda.

---

## Verification Checklist

After completing all scenarios, verify:

- [ ] Zero balance returns for non-existent accounts
- [ ] First event creates account with correct balance
- [ ] Higher timestamp events update balance
- [ ] Lower/equal timestamp events are ignored
- [ ] Duplicate events are idempotent
- [ ] Invalid events are rejected with error logging
- [ ] Unit tests pass with ≥90% coverage
- [ ] Integration tests pass against live infrastructure

---

## Troubleshooting

### Application Won't Start

**Symptom**: Connection refused to DynamoDB or Kafka

**Solution**: Ensure infrastructure is running:
```bash
make db-up    # Starts DynamoDB
make kafka-up # Starts Redpanda
```

### Events Not Consumed

**Symptom**: Published events don't update balance

**Solution**:
1. Check application logs: `make logs`
2. Verify topic name matches: `BALANCE_UPDATES_TOPIC=balance-updates`
3. Check consumer group: `KAFKA_CONSUMER_GROUP_ID=balance-consumer`

### Conditional Write Failures

**Symptom**: DynamoDB ConditionalCheckFailedException in logs

**Expected**: This is normal for out-of-order/duplicate events. The exception indicates the write was correctly rejected.

---

## Related Documentation

- [Data Model](./data-model.md) — Entity and value object definitions
- [Contracts](./contracts/) — API and Kafka message schemas
- [Plan](./plan.md) — Full technical design
