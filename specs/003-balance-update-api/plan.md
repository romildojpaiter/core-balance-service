# Implementation Plan: Balance Update API

**Branch**: `001-balance-update-api` | **Date**: 2026-09-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-balance-update-api/spec.md`

## Summary

Implement a balance update API that consumes snapshot events from Kafka and exposes a REST endpoint for balance queries. The core challenge is handling out-of-order events and concurrent updates using the "highest timestamp wins" rule with atomic conditional writes in DynamoDB.

**Technical approach**: Leverage DynamoDB conditional writes to ensure only events with strictly greater timestamps update the balance, providing atomicity and idempotency without distributed locks or transactions.

## Technical Context

**Language/Version**: Kotlin 2.3.21, Java 21

**Primary Dependencies**: Spring Boot 4.1.0, Spring Kafka, AWS SDK DynamoDB v2, Jackson 3

**Storage**: Amazon DynamoDB (local for development)

**Testing**: JUnit 5, MockMvc, Konsist (architecture tests), JaCoCo (90% minimum coverage)

**Target Platform**: Linux server (containerized)

**Project Type**: Web service with Kafka consumer

**Performance Goals**: <200ms query latency, 1000+ events/second throughput

**Constraints**: Strong consistency for balance reads, at-least-once Kafka delivery

**Scale/Scope**: Single-region, single-table design, account-level ordering

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| **1. Integridade Financeira** | ✅ PASS | Balance updates use atomic conditional writes; precision maintained with BigDecimal; audit logging for all processed events |
| **2. Determinismo das Regras de Negócio** | ✅ PASS | "Highest timestamp wins" rule is a pure function; no hidden side effects; domain logic isolated from infrastructure |
| **3. Atomicidade** | ✅ PASS | DynamoDB conditional writes provide atomicity; no partial updates possible; idempotency via timestamp comparison |
| **4. Idempotência de Mensagens** | ✅ PASS | Events with timestamp ≤ stored timestamp are ignored; transaction.id logged for deduplication verification |
| **5. Segregação de Responsabilidades** | ✅ PASS | Hexagonal architecture enforced by Konsist test; domain has zero external dependencies |

**Post-design re-check**: Completed after Phase 1 — no violations introduced.

## Project Structure

### Documentation (this feature)

```text
specs/001-balance-update-api/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── kafka-event-schema.json
│   └── rest-api.yaml
└── tasks.md             # Phase 2 output (NOT created yet)
```

### Source Code (repository root)

```text
src/main/kotlin/br/com/itau/challenge/
├── Application.kt                          # Existing
└── balance/
    ├── domain/
    │   ├── model/
    │   │   ├── AccountBalance.kt           # NEW: Balance aggregate root
    │   │   ├── BalanceSnapshot.kt          # NEW: Value object for snapshot data
    │   │   └── Money.kt                    # NEW: Value object for amount + currency
    │   └── exception/
    │       ├── InvalidBalanceEventException.kt  # NEW
    │       └── InvalidAccountIdException.kt      # NEW
    ├── port/
    │   ├── input/
    │   │   ├── ProcessBalanceEventUseCase.kt    # NEW: Kafka event processing
    │   │   └── GetAccountBalanceUseCase.kt      # NEW: REST query
    │   └── output/
    │       ├── BalanceRepository.kt              # NEW: Persist balance snapshots
    │       └── BalanceProvider.kt                # NEW: Query current balance
    ├── application/
    │   ├── BalanceEventProcessor.kt             # NEW: Implements ProcessBalanceEventUseCase
    │   └── AccountBalanceQueryService.kt        # NEW: Implements GetAccountBalanceUseCase
    └── adapter/
        ├── input/
        │   ├── web/
        │   │   ├── BalanceController.kt         # NEW: REST endpoint
        │   │   └── dto/
        │   │       └── BalanceResponse.kt       # NEW: API response DTO
        │   └── kafka/
        │       ├── BalanceEventConsumer.kt      # NEW: Kafka listener
        │       └── dto/
        │           └── BalanceEventMessage.kt   # NEW: Event deserialization DTO
        └── output/
            └── dynamodb/
                ├── DynamoDbBalanceRepository.kt # NEW: Write with conditional check
                └── DynamoDbBalanceProvider.kt   # NEW: Read with strong consistency

src/test/kotlin/br/com/itau/challenge/balance/
├── domain/model/
│   ├── AccountBalanceTest.kt                   # NEW
│   ├── BalanceSnapshotTest.kt                  # NEW
│   └── MoneyTest.kt                            # NEW
├── application/
│   ├── BalanceEventProcessorTest.kt            # NEW
│   └── AccountBalanceQueryServiceTest.kt       # NEW
├── adapter/input/web/
│   └── BalanceControllerTest.kt                # NEW (MockMvc)
├── adapter/input/kafka/
│   └── BalanceEventConsumerTest.kt             # NEW
└── adapter/output/dynamodb/
    ├── DynamoDbBalanceRepositoryTest.kt        # NEW
    └── DynamoDbBalanceProviderTest.kt          # NEW

src/integrationTest/kotlin/br/com/itau/challenge/balance/
├── BalanceEventConsumerIntegrationTest.kt      # NEW: End-to-end Kafka flow
└── DynamoDbBalanceIntegrationTest.kt           # NEW: Conditional write validation
```

**Structure Decision**: Follows existing hexagonal pattern in `balance/` package. New domain models and ports added alongside existing greeting feature. Adapters isolated by technology (web, kafka, dynamodb).

---

## Architecture

### Components

```
┌─────────────────────────────────────────────────────────────────┐
│                         ADAPTERS (Input)                        │
├──────────────────────────┬──────────────────────────────────────┤
│   BalanceController      │   BalanceEventConsumer               │
│   (REST: GET /balance)   │   (Kafka: balance-updates topic)     │
└──────────┬───────────────┴───────────────┬──────────────────────┘
           │                               │
           ▼                               ▼
┌──────────────────────────────────────────────────────────────────┐
│                          APPLICATION                             │
├─────────────────────────────┬────────────────────────────────────┤
│  AccountBalanceQueryService │  BalanceEventProcessor             │
│  (query current balance)    │  (process snapshot events)         │
└──────────────┬──────────────┴──────────────┬─────────────────────┘
               │                             │
               ▼                             ▼
┌──────────────────────────────────────────────────────────────────┐
│                            PORTS                                 │
├─────────────────────────────┬────────────────────────────────────┤
│  GetAccountBalanceUseCase   │  ProcessBalanceEventUseCase        │
│  (input port - query)       │  (input port - command)            │
├─────────────────────────────┴────────────────────────────────────┤
│  BalanceProvider             │  BalanceRepository                 │
│  (output port - read)        │  (output port - write)             │
└──────────────────────────────┴────────────────────────────────────┘
               │                             │
               ▼                             ▼
┌──────────────────────────────────────────────────────────────────┐
│                       ADAPTERS (Output)                          │
├─────────────────────────────┬────────────────────────────────────┤
│  DynamoDbBalanceProvider    │  DynamoDbBalanceRepository         │
│  (strong consistency read)  │  (conditional write)               │
└─────────────────────────────┴────────────────────────────────────┘
               │                             │
               └──────────────┬──────────────┘
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│                          DOMAIN                                  │
├──────────────────────────────────────────────────────────────────┤
│  AccountBalance (aggregate)  │  BalanceSnapshot (value object)   │
│  Money (value object)        │  Domain exceptions                │
└──────────────────────────────────────────────────────────────────┘
```

### Responsibilities

| Layer | Responsibility | Allowed Dependencies |
|-------|----------------|---------------------|
| **Domain** | Business rules, invariants, value objects | None (pure Kotlin) |
| **Port** | Contracts defining boundaries | Domain only |
| **Application** | Use case orchestration, business flow | Domain + Port |
| **Adapter** | Technology-specific implementations | Port + Domain + Frameworks |

### Boundaries

- **Domain boundary**: No imports from Spring, AWS SDK, Kafka, or any external framework
- **Application boundary**: Never imports adapters directly; depends only on port interfaces
- **Port boundary**: Defines the contract; implementations live in adapters
- **Adapter boundary**: Implements ports; handles serialization, network, persistence details

---

## Domain Model

### Entities

**AccountBalance** (Aggregate Root)
- Represents the current balance state of a bank account
- Enforces the invariant that balance always reflects the highest-timestamp snapshot

### Value Objects

**BalanceSnapshot**
- Immutable snapshot of balance at a point in time
- Fields: `transactionId: String`, `timestamp: Long`, `amount: Money`
- Equality by value (data class)

**Money**
- Monetary amount with currency
- Fields: `amount: BigDecimal`, `currency: String`
- Preserves 2 decimal places precision
- Invariant: amount cannot be null, currency must be 3-letter ISO code

### Aggregates

**AccountBalance** (root)
- `accountId: String` — unique identifier
- `currentSnapshot: BalanceSnapshot?` — null means zero balance (account not yet created)
- `createdAt: Instant?` — when first snapshot was accepted
- `updatedAt: Instant?` — when current snapshot was accepted

### Invariants

| ID | Invariant | Enforcement Point |
|----|-----------|-------------------|
| INV-001 | Balance always reflects highest timestamp | Domain validation in `AccountBalance.shouldAccept()` |
| INV-002 | No event with timestamp ≤ stored timestamp alters state | Conditional write in repository |
| INV-003 | Duplicate events (same transactionId) don't change state | Implicit via timestamp rule |
| INV-004 | Amount precision is exactly 2 decimal places | Money value object validation |
| INV-005 | Currency is consistent per account | Validated at application layer (optional: warn on mismatch) |

### Domain Services

**None required** — The "highest timestamp wins" rule is simple enough to be encapsulated in the `AccountBalance` aggregate method `shouldAccept(newSnapshot)`.

### Domain Events

**None required** — This version is a read-only projection. No downstream events are emitted.

---

## Application Layer

### Use Cases

#### ProcessBalanceEventUseCase (Command)

**Input**: `BalanceEvent` containing transaction ID, account ID, timestamp, amount, currency

**Output**: `ProcessingResult` (ACCEPTED or IGNORED)

**Flow**:
1. Validate event fields (non-empty account ID, valid timestamp, valid amount, currency present)
2. Create `BalanceSnapshot` from event
3. Delegate to `BalanceRepository.saveIfNewer(accountId, snapshot)`
4. Return result for logging/metrics

**Error Handling**:
- Invalid event → log error, return REJECTED (no exception thrown)
- Repository failure → throw exception (Kafka will retry)

#### GetAccountBalanceUseCase (Query)

**Input**: `accountId: String`

**Output**: `AccountBalance` (may have null snapshot for zero balance)

**Flow**:
1. Validate account ID (non-blank)
2. Query `BalanceProvider.findByAccountId(accountId)`
3. Return result (empty balance if not found)

**Error Handling**:
- Invalid account ID → throw `InvalidAccountIdException`
- Provider failure → throw exception (propagate to HTTP layer)

### Commands

```kotlin
data class ProcessBalanceEventCommand(
    val transactionId: String,
    val accountId: String,
    val timestamp: Long,
    val amount: BigDecimal,
    val currency: String
)
```

### Queries

```kotlin
data class GetAccountBalanceQuery(
    val accountId: String
)
```

### Input Ports

```kotlin
fun interface ProcessBalanceEventUseCase {
    fun process(command: ProcessBalanceEventCommand): ProcessingResult
}

fun interface GetAccountBalanceUseCase {
    fun getBalance(query: GetAccountBalanceQuery): AccountBalance
}
```

### Output Ports

```kotlin
interface BalanceRepository {
    fun saveIfNewer(accountId: String, snapshot: BalanceSnapshot): SaveResult
}

interface BalanceProvider {
    fun findByAccountId(accountId: String): AccountBalance?
}

sealed class SaveResult {
    object Accepted : SaveResult()
    object Ignored : SaveResult()
}
```

---

## Adapters

### REST Adapter

**BalanceController**
- `GET /api/v1/balances/{accountId}`
- Returns `BalanceResponse(accountId, balance, currency, lastTransactionId, timestamp)`
- HTTP 200 for all cases (including zero balance)
- HTTP 400 for invalid account ID format
- Uses `MockMvc` for testing

**BalanceResponse DTO**
```json
{
  "accountId": "acc-12345",
  "balance": "150.00",
  "currency": "BRL",
  "lastTransactionId": "tx-001",
  "timestamp": 100
}
```

### Kafka Adapter

**BalanceEventConsumer**
- Listens to topic `${balance-updates.topic-name}` (default: `balance-updates`)
- Deserializes JSON to `BalanceEventMessage`
- Calls `ProcessBalanceEventUseCase.process()`
- Logs result (ACCEPTED/IGNORED/REJECTED)
- No manual acknowledgment (auto-commit after successful processing)
- **Message key**: `accountId` — ensures ordering per account within a partition

**BalanceEventMessage DTO**
```json
{
  "transaction": {
    "id": "tx-001",
    "timestamp": 100
  },
  "account": {
    "id": "acc-12345",
    "balance": {
      "amount": "150.00",
      "currency": "BRL"
    }
  }
}
```

**Error Handling**:
- JSON parse error → log error, skip message (no retry for malformed data)
- Validation error → log error, skip message
- Repository error → throw exception (Kafka retries)

### DynamoDB Adapter

**DynamoDbBalanceRepository** (write)

Table: `${BALANCE_TABLE_NAME}` (default: `AccountBalances`)

| Attribute | Type | Key | Description |
|-----------|------|-----|-------------|
| `accountId` | S | Partition Key | Account identifier |
| `timestamp` | N | Sort Key (GSI) | Event timestamp (for queries) |
| `amount` | S | - | Balance amount (string for BigDecimal precision) |
| `currency` | S | - | ISO currency code |
| `transactionId` | S | - | Last applied transaction ID |
| `updatedAt` | S | - | ISO-8601 timestamp of last update |

**Conditional Write Logic**:
```kotlin
PutItem with condition: "attribute_not_exists(accountId) OR timestamp < :newTimestamp"
```

This atomically ensures:
- First event for account is always accepted
- Subsequent events only accepted if timestamp is strictly greater
- Concurrent writes are serialized; higher timestamp wins

**DynamoDbBalanceProvider** (read)

- Uses `GetItem` with `ConsistentRead = true` for strong consistency
- Returns null if item doesn't exist (zero balance)
- Maps DynamoDB item to `AccountBalance` domain object

### Serialization

- **JSON**: Jackson 3 `ObjectMapper` (auto-configured by Spring Boot)
- **BigDecimal**: Stored as string in DynamoDB to preserve precision
- **Timestamps**: Microseconds since epoch (Long) — matches spec format

### Configuration

New properties in `application.yaml`:
```yaml
balance-updates:
  topic-name: ${BALANCE_UPDATES_TOPIC:balance-updates}

dynamodb:
  balance-table-name: ${BALANCE_TABLE_NAME:AccountBalances}
```

Environment variables:
- `BALANCE_UPDATES_TOPIC`: Kafka topic for balance events
- `BALANCE_TABLE_NAME`: DynamoDB table for account balances

---

## Kafka Considerations

### At-Least-Once Delivery

Spring Kafka provides at-least-once by default with auto-commit. Our conditional write logic ensures idempotency even if the same message is delivered multiple times.

### Idempotency

**Strategy**: Conditional write with timestamp check

An event is idempotent if:
- It has the same transaction ID as already stored (ignored by timestamp rule)
- It has a timestamp ≤ stored timestamp (rejected by condition)

No separate deduplication table needed.

### Retries

**Consumer retries**: Spring Kafka auto-retries on exceptions (configurable retry count)
**Our approach**: Throw exception for transient errors (DynamoDB throttling, network issues)
**Poison pill**: Messages that fail validation are logged and skipped (no retry, no DLQ in this version)

### Duplicate Events

Handled implicitly by the conditional write:
- Same timestamp → rejected (timestamp ≤ stored)
- Same transaction ID → rejected if timestamp matches

### Out-of-Order Events

Handled by the "strictly greater" condition:
- Event with timestamp 150 arrives after 200 → rejected
- Event with timestamp 250 arrives after 200 → accepted

### Message Key Strategy

**Key**: `accountId` (from event)

**Rationale**: Ensures all events for the same account go to the same partition, maintaining order within that account.

**Impact**: If a single account has high throughput, it's limited to one partition's capacity. For typical banking workloads, this is acceptable.

### Event Versioning

Current version: v1 (schema defined in contracts)

Future versions: Add version field to message, create separate deserializer if breaking changes needed.

### Invalid Message Handling

| Invalid Type | Action | Logging |
|--------------|--------|---------|
| Malformed JSON | Skip, log error | ERROR with raw payload |
| Missing required field | Skip, log error | ERROR with field name |
| Invalid amount (negative/NaN) | Skip, log error | ERROR with value |
| Missing currency | Skip, log error | ERROR with transaction ID |

**No DLQ configured** — invalid messages are dropped. Consider adding DLQ topic if operational experience shows frequent issues.

---

## Persistence Strategy

### Partition Key Design

**Primary table**: `AccountBalances`
- Partition key: `accountId` (String)
- No sort key needed for single-item-per-account pattern

**Access pattern**: One item per account, fast point lookup

### Access Patterns

| Pattern | Operation | Consistency | Notes |
|---------|-----------|-------------|-------|
| Query balance by account | GetItem | Strong | Primary use case |
| Update balance (conditional) | PutItem (conditional) | Strong | Atomic check-and-update |
| Scan all balances (admin) | Scan | Eventual | Optional, not in spec |

### Conditional Writes

**Expression**: `attribute_not_exists(accountId) OR timestamp < :newTimestamp`

**Why this works**:
- `attribute_not_exists(accountId)` → true for new accounts (accept first event)
- `timestamp < :newTimestamp` → true only if new timestamp is strictly greater
- DynamoDB evaluates atomically, preventing race conditions

### Optimistic Concurrency

No explicit version number needed — the timestamp itself acts as the version.

The conditional write is the optimistic concurrency mechanism:
- No locks held
- No transaction coordinator
- DynamoDB handles the race condition

### Consistency Requirements

| Operation | Consistency | Rationale |
|-----------|-------------|-----------|
| Read balance | Strong | Financial data must be accurate |
| Write balance | Strong (default) | Conditional write requires consistency |

**Note**: Strong reads cost 2x eventual consistency in provisioned capacity mode, but accuracy is non-negotiable for financial data.

### Idempotency Records

Not needed — the conditional write logic provides idempotency.

### Atomicity

Single-item operations (GetItem, PutItem) are atomic by default in DynamoDB.

No multi-item transactions needed for this use case.

### Transactions

**Not required** because:
- Only one item is modified per event
- No cross-account operations
- No read-modify-write race condition (handled by conditional write)

---

## Test Strategy

### Unit Tests

| Layer | Test File | Focus | Tools |
|-------|-----------|-------|-------|
| Domain | `AccountBalanceTest.kt` | Invariants, shouldAccept logic | JUnit 5 |
| Domain | `MoneyTest.kt` | Precision, validation | JUnit 5 |
| Application | `BalanceEventProcessorTest.kt` | Use case flow, error handling | JUnit 5, Mockito |
| Application | `AccountBalanceQueryServiceTest.kt` | Query logic, validation | JUnit 5, Mockito |
| Adapter | `BalanceControllerTest.kt` | HTTP layer, status codes | MockMvc |
| Adapter | `BalanceEventConsumerTest.kt` | Deserialization, delegation | JUnit 5, Mockito |
| Adapter | `DynamoDbBalanceRepositoryTest.kt` | Conditional write logic | JUnit 5, DynamoDB Local |
| Adapter | `DynamoDbBalanceProviderTest.kt` | Read logic, mapping | JUnit 5, DynamoDB Local |

### Domain Tests

Focus on business rules:
- `AccountBalance.shouldAccept()` returns true only for strictly greater timestamps
- `Money` validates currency format and amount precision
- `BalanceSnapshot` equality by value

### Application Tests

Focus on use case orchestration:
- `ProcessBalanceEventUseCase` validates input, calls repository, returns result
- `GetAccountBalanceUseCase` validates account ID, calls provider
- Error handling: invalid input vs. repository failure

### Adapter Tests

**REST** (`BalanceControllerTest.kt`):
- Use `MockMvc` with `@WebMvcTest`
- Mock `GetAccountBalanceUseCase`
- Test scenarios: valid account, zero balance, invalid account ID

**Kafka** (`BalanceEventConsumerTest.kt`):
- Mock `ProcessBalanceEventUseCase`
- Test valid message, malformed JSON, validation failure
- Verify logging calls

**DynamoDB** (repository/provider tests):
- Use embedded DynamoDB Local
- Test conditional write accepts/rejects correctly
- Test strong consistency read

### Integration Tests

| Test File | Scope | Infrastructure |
|-----------|-------|----------------|
| `BalanceEventConsumerIntegrationTest.kt` | End-to-end Kafka flow | Redpanda + DynamoDB |
| `DynamoDbBalanceIntegrationTest.kt` | Conditional write correctness | DynamoDB Local |

**Test scenarios**:
1. Publish event with timestamp 100 → balance updated to 100
2. Publish event with timestamp 200 → balance updated to 200
3. Publish event with timestamp 150 → balance remains 200 (out-of-order rejected)
4. Publish duplicate event → balance unchanged

### Contract Tests

Not required — no external service contracts in this version.

### Architecture Tests

**HexagonalArchitectureTest** (update existing):
- Add `balance.domain..` to domain layer check
- Add `balance.port..` to port layer check
- Add `balance.application..` to application layer check
- Add `balance.adapter..` to adapter layer check
- Verify no Spring imports in `balance.domain..`

### MockMvc for REST

Used for `BalanceControllerTest`:
```kotlin
@WebMvcTest(BalanceController::class)
class BalanceControllerTest {
    @MockitoBean
    private lateinit var getAccountBalanceUseCase: GetAccountBalanceUseCase

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `returns balance for valid account`() {
        // Given
        whenever(getAccountBalanceUseCase.getBalance(any()))
            .thenReturn(AccountBalance(...))

        // When/Then
        mockMvc.perform(get("/api/v1/balances/{accountId}", "acc-12345"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accountId").value("acc-12345"))
    }
}
```

### JaCoCo Coverage Gate

**Minimum**: 90% instruction coverage (already configured in `build.gradle.kts`)

**Exclusions**: `Application.kt` (framework bootstrap)

---

## Observability

### Structured Logs

**Format**: JSON (configured via Spring Boot's structured logging)

**Fields**:
- `timestamp`: ISO-8601
- `level`: INFO/WARN/ERROR
- `logger`: Class name
- `message`: Human-readable message
- `correlationId`: Request/message ID (from MDC)
- `accountId`: Account being processed (when applicable)
- `transactionId`: Transaction ID (when applicable)

**Log levels**:
- INFO: Event accepted, balance queried
- WARN: Event ignored (out-of-order or duplicate)
- ERROR: Validation failure, repository error

### Correlation ID

**HTTP**: Extract from `X-Correlation-ID` header, or generate UUID if absent
**Kafka**: Use `transactionId` as correlation ID for event processing
**Propagation**: Store in MDC (Mapped Diagnostic Context) for all log statements

### Metrics

**Custom metrics** (via Micrometer):

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `balance.events.received` | Counter | `result=accepted\|ignored\|rejected` | Events processed |
| `balance.query.count` | Counter | `status=success\|not_found` | Balance queries |
| `balance.query.latency` | Timer | - | Query response time |
| `balance.repository.write.latency` | Timer | `result=accepted\|ignored` | DynamoDB write time |

**Kafka metrics** (Spring Kafka built-in):
- Consumer lag
- Consumption rate
- Error rate

### Tracing

**Tool**: OpenTelemetry (if configured) or Spring Cloud Sleuth

**Spans**:
- `kafka.consumer.balance-updates` — Kafka message processing
- `dynamodb.put.AccountBalances` — DynamoDB write
- `dynamodb.get.AccountBalances` — DynamoDB read
- `http GET /api/v1/balances/{accountId}` — HTTP request

### Error Metrics

**Counters**:
- `balance.errors.validation` — Invalid event format
- `balance.errors.repository` — DynamoDB failures
- `balance.errors.kafka` — Kafka consumer errors

### Processing Metrics

See `balance.events.received` above.

### Kafka Metrics

Spring Kafka exposes Kafka client metrics via Micrometer:
- `kafka.consumer.fetch.rate`
- `kafka.consumer.records.consumed`
- `kafka.consumer lag`

---

## Security

### Validations

**Input validation** (at adapter layer):

| Field | Validation | Error |
|-------|------------|-------|
| `accountId` | Non-blank, alphanumeric with hyphens | 400 Bad Request / log error |
| `transactionId` | Non-blank | Log error, skip message |
| `timestamp` | Positive long | Log error, skip message |
| `amount` | Valid decimal, 2 places max | Log error, skip message |
| `currency` | 3-letter ISO code, non-blank | Log error, skip message |

**Domain validation**:
- `Money` validates amount precision and currency format
- `AccountBalance` validates snapshot consistency

### Authentication/Authorization

**Not implemented in this version** (per spec assumption).

Future: Add API key or JWT validation on REST endpoint.

### Replay Protection

**Implicit via timestamp rule**: A replayed message has the same transaction ID and timestamp, which will be rejected because timestamp ≤ stored timestamp.

### Payload Alteration Protection

**Not implemented** (no digital signature).

Future: Add HMAC signature validation if upstream system provides it.

### Data Exposure Protection

- No PII in balance data (only account ID and amount)
- Logs never include customer names or personal details
- Error messages don't expose internal state (e.g., "account not found" vs. "account X with balance Y")

---

## Infrastructure

### Docker

**No changes to Dockerfile** — existing runtime image is sufficient.

### Docker Compose

**New service**: None (use existing DynamoDB and Redpanda)

**New environment variables** (in `docker-compose.yml`):
```yaml
app:
  environment:
    BALANCE_UPDATES_TOPIC: balance-updates
    BALANCE_TABLE_NAME: AccountBalances
```

### Redpanda

**New topic**: `balance-updates`

**Configuration**: Add to `infra/redpanda/seed.sh`:
```bash
rpk topic create balance-updates --brokers "${BROKERS}" --partitions 3 --replicas 1
```

**Seed data**: Create `infra/redpanda/balance-updates-seed.jsonl` with sample events for testing.

### DynamoDB

**New table**: `AccountBalances`

**Schema**:
```json
{
  "TableName": "AccountBalances",
  "AttributeDefinitions": [
    {"AttributeName": "accountId", "AttributeType": "S"}
  ],
  "KeySchema": [
    {"AttributeName": "accountId", "KeyType": "HASH"}
  ],
  "BillingMode": "PAY_PER_REQUEST"
}
```

**Seed script**: Update `infra/dynamodb/seed.sh` to create `AccountBalances` table.

### Local Configuration

**application.yaml additions**:
```yaml
balance-updates:
  topic-name: ${BALANCE_UPDATES_TOPIC:balance-updates}

dynamodb:
  balance-table-name: ${BALANCE_TABLE_NAME:AccountBalances}
```

### Integration Tests

**Update Makefile**:
- `make kafka-seed` should also create `balance-updates` topic
- `make db-seed` should also create `AccountBalances` table

**Test data**: Provide seed events in `infra/redpanda/balance-updates-seed.jsonl` for manual testing.

---

## Specification → Architecture → Technical Design Verification

| Requirement | Technical Solution | Status |
|-------------|-------------------|--------|
| **FR-001**: Consume events from Kafka | `BalanceEventConsumer` adapter with `@KafkaListener` | ✅ Defined |
| **FR-002**: Validate event fields | Validation in consumer, domain model invariants | ✅ Defined |
| **FR-003**: Reject invalid events | Skip with error logging, no retry | ✅ Defined |
| **FR-004**: Persist balance state | `DynamoDbBalanceRepository` with conditional write | ✅ Defined |
| **FR-005**: Accept only strictly greater timestamps | Conditional write expression | ✅ Defined |
| **FR-006**: Ignore events with timestamp ≤ stored | Implicit in conditional write | ✅ Defined |
| **FR-007**: Atomic concurrent updates | DynamoDB conditional write (atomic) | ✅ Defined |
| **FR-008**: Ignore duplicate events | Implicit via timestamp rule | ✅ Defined |
| **FR-009**: Expose REST endpoint | `BalanceController` with GET endpoint | ✅ Defined |
| **FR-010**: Return most current balance | Strong consistency read from DynamoDB | ✅ Defined |
| **FR-011**: Return zero for missing accounts | Provider returns null → service maps to zero balance | ✅ Defined |
| **FR-012**: Error for invalid account ID | Validation in use case, 400 response | ✅ Defined |
| **FR-013**: Preserve 2 decimal precision | `Money` value object with `BigDecimal` | ✅ Defined |
| **FR-014**: Persist balance metadata | All fields in DynamoDB item | ✅ Defined |
| **FR-015**: Audit log for processed events | Structured logging with transaction ID | ✅ Defined |
| **FR-016**: Atomic balance updates | DynamoDB single-item atomicity | ✅ Defined |
| **INV-001**: Balance = highest timestamp | Enforced by conditional write | ✅ Defined |
| **INV-002**: No ≤ timestamp alters state | Conditional write condition | ✅ Defined |
| **INV-003**: Duplicates don't change state | Timestamp rule handles this | ✅ Defined |
| **INV-004**: Amount has 2 decimal places | Money value object validation | ✅ Defined |
| **INV-005**: Currency consistency per account | Validated at application layer | ✅ Defined |
| **SC-001**: <200ms query latency | Strong consistency read, single-item op | ✅ Feasible |
| **SC-002**: 1000 events/second | DynamoDB on-demand, partition by account | ✅ Feasible |
| **SC-003**: 100% audit logging | Structured logging for all events | ✅ Defined |
| **SC-004**: 0% corruption on out-of-order | Conditional write guarantees this | ✅ Defined |
| **SC-005**: Duplicates don't alter balance | Timestamp rule guarantees this | ✅ Defined |
| **SC-006**: Concurrent updates produce correct balance | Conditional write serialization | ✅ Defined |
| **SC-007**: Availability during consumer failure | Read path independent of Kafka | ✅ Defined |

**Result**: All requirements have technical solutions defined.

---

## Architecture Decision Records (ADRs)

### ADR-001: Conditional Write for Idempotency and Ordering

**Status**: Accepted

**Context**:
The system must handle out-of-order events, duplicates, and concurrent updates while ensuring the balance always reflects the highest-timestamp snapshot.

**Decision**:
Use DynamoDB conditional writes with the expression:
```
attribute_not_exists(accountId) OR timestamp < :newTimestamp
```

**Consequences**:
- ✅ Atomic check-and-update without distributed locks
- ✅ Idempotency without separate deduplication table
- ✅ Out-of-order events handled implicitly
- ✅ Concurrent updates serialized correctly
- ⚠️ Requires strong consistency reads for correctness
- ⚠️ Conditional write failures are expected (not errors) for out-of-order events

**Alternatives Considered**:
1. **Optimistic locking with version number**: Requires additional attribute, more complex
2. **Distributed lock**: Adds latency and failure mode (lock holder crash)
3. **Transaction with idempotency table**: More expensive (2x DynamoDB writes)

### ADR-002: Account ID as Kafka Message Key

**Status**: Accepted

**Context**:
Events for the same account must be processed in order to ensure the highest-timestamp rule works correctly.

**Decision**:
Use `accountId` as the Kafka message key.

**Consequences**:
- ✅ All events for an account go to the same partition
- ✅ Ordering preserved within account
- ⚠️ Hot partitions possible if some accounts have very high throughput
- ⚠️ Maximum throughput per account limited to single partition capacity

**Alternatives Considered**:
1. **Random key**: Would lose ordering guarantees
2. **Composite key (accountId + timestamp)**: Would also lose ordering

### ADR-003: Strong Consistency for Balance Reads

**Status**: Accepted

**Context**:
Balance queries must return the most up-to-date information for financial accuracy.

**Decision**:
Use `ConsistentRead = true` for all `GetItem` operations on the `AccountBalances` table.

**Consequences**:
- ✅ Guarantees most recent write is visible
- ✅ Meets financial data accuracy requirements
- ⚠️ Higher read capacity consumption (2x eventual consistency)
- ⚠️ Slightly higher latency (single-digit ms)

**Alternatives Considered**:
1. **Eventual consistency**: Unacceptable for financial data
2. **Cache layer**: Adds complexity and stale data risk

---

## Next Steps

This plan is complete. The next step is to run `/speckit-tasks` to generate `tasks.md` with implementation tasks.
