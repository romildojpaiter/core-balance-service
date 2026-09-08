# ADR-004: Conditional PutItem with ReturnValuesOnConditionCheckFailure

**Status**: Accepted — supersedes [ADR-001](./001-conditional-write-for-idempotency.md)

**Date**: 2026-09-06

**Context**: Core Banking — Balance Update

## Context

ADR-001 established the right idea — one conditional write decides ordering, idempotency and
concurrency at once — but the expression it published does not run:

```
attribute_not_exists(accountId) OR timestamp < :newTimestamp
```

Two defects, both of which surface only at runtime:

1. **`timestamp` is a DynamoDB reserved word.** Used unaliased in a condition expression, the request
   is rejected with a `ValidationException`. No compiler, linter or unit test over the Kotlin source
   catches this, because the expression is a string interpreted by another process.
2. **`attribute_not_exists` was applied to a non-key attribute.** `accountId` is a regular attribute
   of the item, not its key. Testing a non-key attribute conflates "there is no item" with "the item
   exists but lacks this field" — a distinction that matters the moment the item shape changes.

A third gap was behavioural rather than broken: when the condition rejects a write, the caller learns
only that it was rejected. Distinguishing a duplicate from a stale event from a timestamp tie
required a second `GetItem`, which reintroduces the very race the condition eliminates.

## Decision

The write is a single `PutItem` carrying:

```
ConditionExpression:        attribute_not_exists(#pk) OR #lastEventTimestamp < :incomingTimestamp
ExpressionAttributeNames:   #pk -> pk, #lastEventTimestamp -> lastEventTimestamp
ExpressionAttributeValues:  :incomingTimestamp -> N(<micros>)
ReturnValuesOnConditionCheckFailure: ALL_OLD
```

- `attribute_not_exists` is applied to **`pk`**, the partition key — the only attribute guaranteed to
  exist on every item.
- Both names are aliased, including `pk`, which is not reserved. The alias costs nothing and removes
  a class of failure that only appears in production.
- The comparison is **strictly** `<`, implementing decision A-03: equal timestamps reject, and the
  first writer to arrive wins.
- `ALL_OLD` returns the item that caused the rejection, so the outcome can be named without a second
  call.

Classification of a rejection, in this order:

| Check | Result |
|---|---|
| `persisted.lastTransactionId == incoming.transactionId` | `Duplicate` |
| `persisted.lastEventTimestamp == incoming.timestamp` | `TimestampTie` |
| otherwise | `Stale` |

The order is load-bearing. A redelivery of the same event necessarily ties on timestamp, so checking
identity first is what makes idempotency report as idempotency rather than as a conflict.

## Rationale

Implements **Constitution IV (Idempotency)**, **V (Out-of-Order Protection)** and **VI (Concurrency
Safety)**. The condition is evaluated by DynamoDB inside the same atomic operation that writes, so
there is no window between deciding and writing — which is precisely why no lock of any kind appears
anywhere in the codebase.

The classification is **telemetry, not correctness**: all three branches decline to write, and the
persisted balance is identical whichever label is chosen. If `ALL_OLD` is unavailable in some
environment, the result collapses to `Stale` and the system loses granularity in its metrics, not
safety.

## Consequences

- No read precedes the write (FR-028): one round trip per event, and no read-then-write race.
- Rejections are cheap and expected. Under keyed partitioning they are rare; without it they are more
  frequent and correctness is unchanged.
- The condition expression is a string, so it is asserted **verbatim** in
  `DynamoDbBalanceWriterTest`. A silent edit from `<` to `<=` would let two events bearing the same
  instant overwrite each other, and no other test in the suite would notice.

## Alternatives considered

| Alternative | Why not |
|---|---|
| `UpdateItem` with the same condition | Equivalent guarantee, but the item is a full snapshot every time — `PutItem` says that plainly, `UpdateItem` suggests a partial mutation that never happens here. |
| `TransactWriteItems` (read + write) | Doubles cost and latency for the same guarantee a single-item condition already gives. |
| Diagnostic `GetItem` after a rejection | Reintroduces the race the condition eliminates, and can read a state written by yet another consumer in between. |
| Distributed lock | Adds infrastructure, latency, and the problem of a holder that dies mid-hold — to solve what the condition solves for free. Forbidden by Constitution XV. |
