# ADR-001: Conditional Write for Idempotency and Ordering

**Status**: **Superseded by [ADR-004](./004-conditional-put-with-return-values-on-failure.md)**

> **Do not implement the expression below.** It does not run. `timestamp` is a DynamoDB reserved word
> and fails with a `ValidationException` unless aliased through `ExpressionAttributeNames`, and
> `attribute_not_exists` is applied here to `accountId`, a non-key attribute, which conflates "no
> item" with "item missing a field". ADR-004 keeps this decision's reasoning — which is sound — and
> replaces the mechanism, adding `ReturnValuesOnConditionCheckFailure = ALL_OLD` so a rejection can be
> named as duplicate, stale or tie without a second read that would race.
>
> This ADR is kept rather than deleted because the *reasoning* it records is the reasoning the system
> still runs on.

**Date**: 2026-09-05

**Context**: Balance Update API

## Context

The system must handle:
- Out-of-order events arriving via Kafka
- Duplicate message delivery (at-least-once semantics)
- Concurrent updates to the same account from multiple consumers

The core business rule is that the balance must always reflect the snapshot with the **highest timestamp**. This rule must be enforced atomically—no race condition should allow a lower timestamp to overwrite a higher one.

## Decision

Use DynamoDB **conditional writes** with the expression:

```
attribute_not_exists(accountId) OR timestamp < :newTimestamp
```

The `PutItem` operation will succeed only if:
1. The account doesn't exist yet (first event), OR
2. The new event's timestamp is **strictly greater** than the stored timestamp

## Rationale

### Why Conditional Writes Work

1. **Atomicity**: DynamoDB evaluates the condition and performs the write as a single atomic operation. No other write can interleave.

2. **No Locks Required**: Unlike pessimistic locking, conditional writes don't hold locks between reads and writes. This eliminates deadlock risk and improves throughput.

3. **Built-in Retry Logic**: When a conditional check fails, the operation returns `ConditionalCheckFailedException`. The application can interpret this as "event was out-of-order or duplicate" and handle gracefully.

4. **Cost Effective**: Single write operation (no read-before-write transaction needed).

### Why Not Alternatives

| Alternative | Why Rejected |
|-------------|--------------|
| **Optimistic locking with version number** | Requires additional attribute; timestamp already serves as version |
| **Distributed lock (e.g., Redis)** | Adds infrastructure dependency, introduces lock holder crash scenario, increases latency |
| **Transaction with read + write** | Doubles write cost (2 operations instead of 1), adds complexity |
| **Idempotency table** | Requires second write per event, doubles storage and cost |

## Consequences

### Positive

- ✅ Atomic check-and-update without distributed locks
- ✅ Idempotency without separate deduplication table
- ✅ Out-of-order events handled implicitly (rejected by condition)
- ✅ Concurrent updates serialized correctly (higher timestamp wins)
- ✅ Lower cost than transaction-based approaches

### Negative

- ⚠️ Requires strong consistency reads for queries (to see latest write)
- ⚠️ `ConditionalCheckFailedException` is a normal outcome (not an error) for out-of-order events
- ⚠️ No visibility into *why* the write failed (timestamp comparison not returned)

### Mitigations

- Log `ConditionalCheckFailedException` at WARN level with context (accountId, timestamp) for observability
- Use strong consistency reads by default for balance queries
- Consider adding CloudWatch metric for conditional write failures to monitor out-of-order event rate

## Implementation

### DynamoDB PutItem Request

```kotlin
val request = PutItemRequest.builder()
    .tableName(tableName)
    .item(itemMap)
    .conditionExpression("attribute_not_exists(accountId) OR timestamp < :newTimestamp")
    .expressionAttributeValues(mapOf(":newTimestamp" to AttributeValue.builder().n(newTimestamp.toString()).build()))
    .build()
```

### Error Handling

```kotlin
try {
    dynamoDbClient.putItem(request)
    return SaveResult.Accepted
} catch (e: ConditionalCheckFailedException) {
    logger.warn("Event rejected - timestamp not greater than stored value. accountId={}, timestamp={}", accountId, timestamp)
    return SaveResult.Ignored
}
```

## Monitoring

Track these metrics to validate the approach:
- `balance.repository.write.accepted` — Count of successful writes
- `balance.repository.write.ignored` — Count of conditional check failures (expected for out-of-order)
- Ratio of ignored/accepted — Indicates out-of-order event rate (should be low in normal operation)

## References

- [DynamoDB Conditional Expressions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.ConditionExpressions.html)
- [Working with Items and Attributes](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/WorkingWithItems.html)
