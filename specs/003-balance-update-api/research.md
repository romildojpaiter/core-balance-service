# Research: Balance Update API

**Date**: 2026-09-05
**Feature**: 001-balance-update-api

## Research Questions

### 1. DynamoDB Conditional Write Semantics

**Question**: How do DynamoDB conditional writes behave under concurrent updates?

**Decision**: Use `PutItem` with `ConditionExpression` to ensure atomic check-and-update.

**Rationale**: DynamoDB conditional writes are serialized at the item level. When multiple writes target the same partition key, DynamoDB processes them sequentially, evaluating the condition atomically. This means:
- No race conditions between reads and writes
- No need for distributed locks or optimistic locking with retries
- The "highest timestamp wins" rule is enforced automatically

**Alternatives Considered**:
1. **Transactions**: Would require two operations (read + write), doubling cost
2. **Version attribute**: Adds complexity; timestamp already serves this purpose
3. **External lock service**: Introduces failure mode and latency

**Reference**: [DynamoDB Conditional Expressions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.ConditionExpressions.html)

---

### 2. Kafka Consumer Error Handling

**Question**: How should the consumer handle malformed or invalid messages?

**Decision**: Skip invalid messages with error logging; throw exception for transient errors.

**Rationale**:
- Malformed JSON indicates a producer bug; retrying won't fix it
- Validation errors are permanent; the message will never become valid
- Transient errors (network, throttling) should be retried by Kafka
- No DLQ configured to keep initial implementation simple

**Alternatives Considered**:
1. **DLQ for invalid messages**: Adds operational complexity; can add later if needed
2. **Block consumer on error**: Would halt all processing for one bad message
3. **Retry indefinitely**: Would consume resources on unfixable messages

---

### 3. BigDecimal Precision in DynamoDB

**Question**: How to store monetary amounts with exact precision in DynamoDB?

**Decision**: Store as String type in DynamoDB; serialize/deserialize via BigDecimal.

**Rationale**:
- DynamoDB Number type has 38 digits precision but loses trailing zeros
- Financial data requires exact representation (e.g., "100.00" not "100")
- String storage preserves exact decimal representation
- BigDecimal handles arithmetic and comparison correctly in application code

**Alternatives Considered**:
1. **Number type**: Loses trailing zeros (100.00 becomes 100)
2. **Integer representing cents**: Requires application-level conversion; error-prone
3. **Binary serialization**: Not human-readable in DynamoDB console

**Reference**: [DynamoDB Data Types](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html)

---

### 4. Message Key for Ordering

**Question**: What should be the Kafka message key for balance events?

**Decision**: Use `accountId` as the message key.

**Rationale**:
- Ensures all events for an account go to the same partition
- Guarantees ordering within an account (critical for timestamp rule)
- Producer sets key based on event's account ID
- Consumer doesn't need to know about key; ordering is automatic

**Alternatives Considered**:
1. **Random key**: Would distribute load but lose ordering guarantees
2. **Transaction ID**: Would distribute load but lose ordering guarantees
3. **Composite key**: Overkill; single account ID is sufficient

---

### 5. Strong vs Eventual Consistency for Reads

**Question**: Should balance queries use strong or eventual consistency?

**Decision**: Use strong consistency (`ConsistentRead = true`).

**Rationale**:
- Financial data must be accurate; stale reads are unacceptable
- After a write, the next read must see that write
- Strong consistency adds ~1-2ms latency (acceptable for <200ms SLA)
- Cost is 2x eventual consistency, but accuracy justifies it

**Alternatives Considered**:
1. **Eventual consistency**: Unacceptable for financial accuracy
2. **Cache with TTL**: Risk of stale data; adds complexity
3. **Read-after-write consistency**: Only works within same region/session; not general

**Reference**: [DynamoDB Read Consistency](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.ReadConsistency.html)

---

### 6. Idempotency Strategy

**Question**: How to ensure idempotent event processing without a deduplication table?

**Decision**: Rely on conditional write with timestamp check.

**Rationale**:
- Duplicate event has same transaction ID and timestamp
- If timestamp ≤ stored timestamp, write is rejected
- No separate storage needed for seen transaction IDs
- Simpler architecture, lower cost

**Trade-off**: If a duplicate event has a *different* timestamp (clock skew), it might be accepted. However, the spec states the upstream system guarantees unique transaction IDs, and timestamp comes from the same source.

**Alternatives Considered**:
1. **Idempotency table**: Store processed transaction IDs; requires second write per event
2. **Transaction with idempotency check**: Doubles write cost
3. **In-memory deduplication cache**: Lost on restart; not durable

---

### 7. Kafka Consumer Group Configuration

**Question**: What consumer group settings are appropriate for this workload?

**Decision**: Use Spring Kafka defaults with `auto-offset-reset: earliest`.

**Rationale**:
- `earliest`: Process all existing messages on first start (important for new deployments)
- Auto-commit after successful processing (at-least-once semantics)
- Single consumer group for the service (horizontal scaling via partitions)

**Key Settings**:
```yaml
spring.kafka.consumer:
  auto-offset-reset: earliest
  enable-auto-commit: true
  key-deserializer: StringDeserializer
  value-deserializer: StringDeserializer
```

**Alternatives Considered**:
1. **Manual acknowledgment**: More control, but adds complexity
2. **`latest` offset reset**: Would miss existing messages on first start

---

### 8. Testing Strategy for Conditional Writes

**Question**: How to test concurrent update scenarios?

**Decision**: Use integration tests with DynamoDB Local; simulate concurrency via threads.

**Rationale**:
- Unit tests can mock repository, but can't verify DynamoDB atomicity
- DynamoDB Local supports conditional writes
- Thread-based simulation can approximate concurrent writes
- Integration tests catch race conditions that mocks miss

**Test Scenarios**:
1. Two threads write different timestamps; verify higher wins
2. Two threads write same timestamp; verify exactly one succeeds
3. Sequential writes in wrong order; verify higher timestamp preserved

---

## Best Practices Applied

### Kotlin/Spring Boot

- **Value objects for domain concepts**: `Money`, `BalanceSnapshot`
- **Sealed classes for results**: `SaveResult.Accepted` / `SaveResult.Ignored`
- **Fun interfaces for use cases**: Single abstract method pattern
- **Data classes for DTOs**: Automatic equals/hashCode/copy

### DynamoDB

- **Single-table design**: One table for account balances
- **Conditional writes**: Atomic operations without transactions
- **Strong consistency**: For financial data accuracy
- **String for BigDecimal**: Preserve exact decimal representation

### Kafka

- **Account ID as key**: Per-account ordering
- **At-least-once**: Accept duplicates; handle via idempotency
- **Deserialization at adapter**: Keep domain isolated from JSON

### Testing

- **Hexagonal architecture test**: Konsist enforces dependency direction
- **MockMvc for REST**: Test HTTP layer in isolation
- **Integration tests for DynamoDB**: Validate conditional writes
- **90% coverage gate**: JaCoCo ensures test quality

---

## References

- [DynamoDB Conditional Expressions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.ConditionExpressions.html)
- [DynamoDB Read Consistency](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.ReadConsistency.html)
- [Spring Kafka Documentation](https://docs.spring.io/spring-kafka/reference/)
- [Kotlin BigDecimal Best Practices](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-big-decimal/)
