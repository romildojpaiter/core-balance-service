# ADR-002: Account ID as Kafka Message Key

**Status**: Accepted

**Date**: 2026-09-05

**Context**: Balance Update API

## Context

The balance update system consumes events from a Kafka topic. The core business rule requires that for any given account, the balance must reflect the snapshot with the highest timestamp.

When multiple events for the same account arrive at different times (due to network latency, retries, or producer batching), they may be processed out of order. The conditional write (ADR-001) handles this at the persistence layer, but we also need to consider ordering at the Kafka layer.

## Decision

Use `accountId` as the **Kafka message key** for all balance update events.

## Rationale

### Kafka Partitioning Guarantee

Kafka guarantees that all messages with the same key are sent to the same partition and processed in order by a single consumer within a consumer group.

This means:
1. All events for account "acc-12345" go to partition N
2. Consumer reads them sequentially: event 1, then event 2, then event 3
3. Even if event 3 has a lower timestamp than event 2, the conditional write will reject it

### Why Order Matters

Without per-account ordering:
- Events A (timestamp=100) and B (timestamp=200) for account X could go to different partitions
- Partition 1 processes A → balance = 100
- Partition 2 processes B → balance = 200 (correct)
- If partition 2 is slower, and partition 1 reprocesses A → conditional write rejects it (correct)
- However, if A arrives after B in the same partition, the conditional write still rejects it correctly

So why use the key if conditional write handles it?

**Answer**: The key reduces the probability of out-of-order processing, making the system more predictable and reducing conditional write failures. It also ensures that the consumer can process events for different accounts in parallel (different partitions) while maintaining order within each account.

### Load Distribution

With N partitions and accounts distributed uniformly:
- Partition count = 3 (configurable)
- Accounts distributed across partitions via key hash
- Enables parallel processing for different accounts
- Single account throughput limited to one partition's capacity

## Consequences

### Positive

- ✅ All events for an account processed in order (within partition)
- ✅ Enables parallel processing across accounts (different partitions)
- ✅ Reduces conditional write failures (fewer out-of-order scenarios)
- ✅ Simple key assignment (no complex key generation logic)

### Negative

- ⚠️ **Hot partition risk**: If one account has very high throughput, that partition becomes a bottleneck
- ⚠️ **Single account throughput limit**: Cannot exceed one partition's capacity
- ⚠️ **Partition count fixed**: Changing partition count requires topic recreation or careful migration

### Mitigations

- Monitor partition lag per partition to detect hot spots
- If a specific account dominates traffic, consider dedicated topic or partition
- For typical banking workloads, per-account throughput is bounded (customers can only transact so fast)

## Implementation

### Producer Side

When publishing balance events to Kafka, set the key to `accountId`:

```kotlin
val record = ProducerRecord(
    "balance-updates",
    event.account.id,  // key = accountId
    objectMapper.writeValueAsString(event)
)
producer.send(record)
```

### Consumer Side

Spring Kafka automatically deserializes the key. The consumer doesn't need to use the key explicitly—the ordering is handled by Kafka's partition assignment.

```kotlin
@KafkaListener(topics = ["\${balance-updates.topic-name}"])
fun consume(
    payload: String,
    @Header(KafkaHeaders.RECEIVED_KEY) key: String  // optional, for logging
) {
    val event = objectMapper.readValue(payload, BalanceEventMessage::class.java)
    processBalanceEventUseCase.process(event.toCommand())
}
```

## Capacity Planning

### Partition Sizing

For a balance update service:
- Typical event size: ~200 bytes
- Target throughput: 1000 events/second
- Consumer processing time: ~5ms per event

With 3 partitions:
- Per-partition throughput: ~333 events/second
- Per-partition capacity: 200 events/second (conservative)
- Headroom: ~40%

**Recommendation**: Start with 3 partitions; monitor lag; scale up if needed.

### Account Distribution

With uniform distribution:
- 1,000,000 accounts
- 3 partitions
- ~333,000 accounts per partition

If one account has 100 events/second:
- That partition's throughput = baseline + 100
- Other partitions unaffected

## Monitoring

Track these metrics:
- `kafka.consumer.lag.per.partition` — Detect hot partitions
- `balance.events.received` per `accountId` (top 10) — Identify high-throughput accounts
- Partition size over time — Detect imbalance

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Random key** | Would distribute load evenly but lose ordering guarantees |
| **Transaction ID as key** | Would distribute load but lose ordering guarantees |
| **Composite key (accountId:timestamp)** | Overkill; accountId alone is sufficient |
| **No key (null)** | Kafka would round-robin, losing all ordering |

## References

- [Kafka Semantics](https://kafka.apache.org/documentation/#semantics)
- [Partitioning Strategies](https://developer.confluent.io/courses/apache-kafka/partitions/)

---

## Update — 2026-09-06

Two clarifications, added while implementing this feature.

**The key is a contention optimisation, never a correctness argument.** With `accountId` as the key,
events for one account land on one partition and arrive in order, so the conditional write in
[ADR-004](./004-conditional-put-with-return-values-on-failure.md) rarely has to reject anything.
Without the key, it rejects more often and the persisted balance is *identical*. Nothing in this
system may be justified by "the key guarantees ordering" — a rebalance, a retry or a DLQ replay
reorders regardless, which is exactly what Constitution VII says.

**Where the key applies.** The system produces to only one topic, the DLQ, and there the
`DeadLetterPublishingRecoverer` resolves the destination to `TopicPartition(dlqTopic, -1)`. The `-1`
lets the producer choose by key hash, so the DLQ preserves the per-account grouping and its partition
count stays independent of the input topic's. The recoverer's default — reusing the source partition
number — is rejected because a DLQ with fewer partitions would turn one bad message into a stuck
partition.

Where the system *consumes*, the key is used for nothing but telemetry. If the message key disagrees
with `account.id` in the payload, **the payload wins** and the divergence is counted as
`balance.events.key.mismatch`.

`infra/redpanda/produce-transactions-events.sh` publishes keyed by `account.id`; it previously
published without a key.
