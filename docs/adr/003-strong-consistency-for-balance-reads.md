# ADR-003: Strong Consistency for Balance Reads

**Status**: Accepted

**Date**: 2026-09-05

**Context**: Balance Update API

## Context

The balance query endpoint must return the most current balance for an account. After a balance update event is processed and persisted to DynamoDB, subsequent queries must reflect that update.

DynamoDB offers two read consistency models:
1. **Eventual consistency**: Reads may return stale data (up to ~1 second delay)
2. **Strong consistency**: Reads always return the most recent write

For financial data, accuracy is non-negotiable. A customer querying their balance after a transaction must see the updated value.

## Decision

Use **strong consistency** (`ConsistentRead = true`) for all balance query operations.

## Rationale

### Financial Data Accuracy

Consider this scenario:
1. Event processed at T=0: Balance updated from 100.00 to 150.00
2. Customer queries balance at T=0.5 seconds

With eventual consistency:
- Query might return 100.00 (stale)
- Customer sees incorrect balance
- Could lead to overdraft or incorrect financial decisions

With strong consistency:
- Query always returns 150.00
- Customer sees accurate balance
- Financial integrity maintained

### Cost vs. Accuracy Trade-off

| Consistency | Read Cost | Latency | Accuracy |
|-------------|-----------|---------|----------|
| Eventual | 1 RCU | Lower | May be stale |
| Strong | 2 RCU | Higher | Always accurate |

**Decision**: The 2x read cost is justified for financial data. Accuracy cannot be compromised for cost savings.

### Latency Impact

Strong consistency reads typically add 1-2ms latency compared to eventual consistency. With a <200ms SLA for balance queries, this overhead is acceptable.

**Typical query latency**:
- Network: 1-2ms
- DynamoDB strong read: 3-5ms
- Application processing: 1-2ms
- Total: ~10ms (well under 200ms SLA)

## Consequences

### Positive

- ✅ Guarantees most recent write is visible
- ✅ Meets financial data accuracy requirements
- ✅ Prevents customer confusion from stale balances
- ✅ Eliminates race conditions between writes and reads

### Negative

- ⚠️ **Higher read cost**: 2x RCU consumption vs eventual consistency
- ⚠️ **Slightly higher latency**: 1-2ms additional per read
- ⚠️ **Capacity planning**: Must provision for 2x read units

### Mitigations

- Use on-demand billing mode (default) to avoid capacity planning complexity
- Monitor read throttling; adjust if needed
- Cost is predictable: 1M strong reads ≈ $0.50 (us-east-1)

## Implementation

### DynamoDB GetItem Request

```kotlin
val request = GetItemRequest.builder()
    .tableName(tableName)
    .key(mapOf("accountId" to AttributeValue.builder().s(accountId).build()))
    .consistentRead(true)  // Strong consistency
    .build()

val response = dynamoDbClient.getItem(request)
```

### When to Use Each Consistency

| Operation | Consistency | Reason |
|-----------|-------------|--------|
| Balance query (customer) | Strong | Financial accuracy |
| Balance query (admin/analytics) | Eventual (optional) | Staleness acceptable |
| Health check | Eventual | Not critical |
| Write | Strong (default) | Conditional write requires it |

### Configuration

No application-level configuration needed—`consistentRead(true)` is set in the code.

For future flexibility, consider adding a query parameter for internal tools:

```kotlin
fun findByAccountId(accountId: String, consistentRead: Boolean = true): AccountBalance?
```

## Monitoring

Track these metrics:
- `balance.query.latency` — Should remain under 200ms
- `dynamodb.read.throttled` — Indicates capacity issues
- `balance.query.count{consistency=strong}` — Volume of strong reads

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Eventual consistency** | Unacceptable for financial data; stale reads violate business requirements |
| **Cache layer (Redis)** | Adds complexity, stale data risk, and additional infrastructure dependency |
| **Read-after-write consistency** | Only works within same region/session; not applicable for arbitrary client queries |
| **Hybrid approach (eventual with TTL)** | Stale data during TTL window; defeats the purpose of accuracy |

## References

- [DynamoDB Read Consistency](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.ReadConsistency.html)
- [Choosing the Right Consistency Model](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/best-practices.html#best-practices-consistency)

---

## Update — 2026-09-06

Realised as `GetItem` with `consistentRead = true` in `DynamoDbBalanceReader` — never `Query`, never
`Scan`, and with no cache in front of it. A cache would be an eventually consistent read with a
longer and less predictable staleness window, which is the thing this ADR exists to rule out.

Verified end to end by `DynamoDbBalanceIntegrationTest` ("a consistent read returns what was just
written") and by `EndToEndBalanceFlowIntegrationTest`, where the REST answer must reflect an event
published moments earlier (SC-005).

An account with no recorded state reads as **absent**, not as `0.00`. DynamoDB returns an empty map
rather than null for a miss, and conflating the two would let an unknown account surface as a zero
balance — a financial claim the system cannot make (Constitution I, FR-047).
