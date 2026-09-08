# Data Model: Balance Update API

**Date**: 2026-09-05
**Feature**: 001-balance-update-api

## Domain Entities

### AccountBalance (Aggregate Root)

Represents the current balance state of a bank account.

| Field | Type | Description |
|-------|------|-------------|
| `accountId` | String | Unique account identifier (alphanumeric with hyphens) |
| `currentSnapshot` | BalanceSnapshot? | Current balance snapshot (null = zero balance) |
| `createdAt` | Instant? | When first snapshot was accepted |
| `updatedAt` | Instant? | When current snapshot was accepted |

**Invariants**:
- `currentSnapshot` is always the snapshot with the highest timestamp
- `accountId` is immutable after creation

**Methods**:
- `shouldAccept(newSnapshot: BalanceSnapshot): Boolean` — Returns true if timestamp is strictly greater

---

## Value Objects

### BalanceSnapshot

Immutable snapshot of balance at a point in time.

| Field | Type | Description |
|-------|------|-------------|
| `transactionId` | String | Unique transaction identifier |
| `timestamp` | Long | Event timestamp (microseconds since epoch) |
| `amount` | Money | Balance amount with currency |

**Invariants**:
- `transactionId` must be non-blank
- `timestamp` must be positive
- `amount` must be valid Money instance

**Equality**: By value (data class)

---

### Money

Monetary amount with currency.

| Field | Type | Description |
|-------|------|-------------|
| `amount` | BigDecimal | Numeric amount (exactly 2 decimal places) |
| `currency` | String | ISO 4217 currency code (3 letters) |

**Invariants**:
- `amount` has exactly 2 decimal places
- `currency` is exactly 3 uppercase letters
- `amount` is not NaN or infinite

**Methods**:
- `format(): String` — Returns formatted string (e.g., "150.00")
- `isZero(): Boolean` — Returns true if amount is zero

**Equality**: By value (data class)

---

## DynamoDB Schema

### Table: AccountBalances

| Attribute | Type | Key | Description |
|-----------|------|-----|-------------|
| `accountId` | S | Partition Key | Account identifier |
| `amount` | S | - | Balance amount (string for BigDecimal precision) |
| `currency` | S | - | ISO currency code |
| `timestamp` | N | - | Last event timestamp (microseconds) |
| `transactionId` | S | - | Last applied transaction ID |
| `updatedAt` | S | - | ISO-8601 timestamp of last update |

**Item Example**:
```json
{
  "accountId": "acc-12345",
  "amount": "150.00",
  "currency": "BRL",
  "timestamp": 100,
  "transactionId": "tx-001",
  "updatedAt": "2026-09-05T10:30:00Z"
}
```

**Access Patterns**:

| Pattern | Operation | Key | Notes |
|---------|-----------|-----|-------|
| Get balance by account | GetItem | `accountId` | Strong consistency |
| Update balance (conditional) | PutItem | `accountId` | Condition: `timestamp < :new` |
| Initial write | PutItem | `accountId` | Condition: `attribute_not_exists(accountId)` |

**Conditional Write Expression**:
```
attribute_not_exists(accountId) OR timestamp < :newTimestamp
```

This ensures:
1. First event for account is always accepted
2. Subsequent events only accepted if timestamp is strictly greater
3. Atomic operation (no race conditions)

---

## Kafka Message Schema

### Topic: balance-updates

**Message Key**: `accountId` (ensures per-account ordering)

**Message Value**:
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

**Field Specifications**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `transaction.id` | String | Yes | Unique transaction identifier |
| `transaction.timestamp` | Long | Yes | Event timestamp (microseconds) |
| `account.id` | String | Yes | Account identifier |
| `account.balance.amount` | String | Yes | Balance amount (decimal string) |
| `account.balance.currency` | String | Yes | ISO 4217 currency code |

**Validation Rules**:
- All fields are required
- `transaction.id` must be non-blank
- `transaction.timestamp` must be positive
- `account.id` must be non-blank
- `account.balance.amount` must be valid decimal
- `account.balance.currency` must be 3-letter code

---

## REST API Response

### GET /api/v1/balances/{accountId}

**Success Response (200 OK)**:
```json
{
  "accountId": "acc-12345",
  "balance": "150.00",
  "currency": "BRL",
  "lastTransactionId": "tx-001",
  "timestamp": 100
}
```

**Zero Balance Response (200 OK)**:
```json
{
  "accountId": "acc-99999",
  "balance": "0.00",
  "currency": null,
  "lastTransactionId": null,
  "timestamp": null
}
```

**Field Specifications**:

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `accountId` | String | No | Account identifier |
| `balance` | String | No | Current balance (formatted decimal) |
| `currency` | String | Yes | ISO currency code (null for zero balance) |
| `lastTransactionId` | String | Yes | Last applied transaction ID |
| `timestamp` | Long | Yes | Last event timestamp |

---

## State Transitions

### AccountBalance State Machine

```
[Initial State: currentSnapshot = null]
           |
           | Event with timestamp T1
           ▼
[State: currentSnapshot = Snapshot(T1)]
           |
           | Event with timestamp T2
           | if T2 > T1:
           ▼
[State: currentSnapshot = Snapshot(T2)]
           |
           | Event with timestamp T3
           | if T3 <= T2:
           ✗ (ignored, state unchanged)
```

**Rules**:
1. Account starts with null snapshot (zero balance)
2. First event always accepted (creates account)
3. Subsequent event accepted only if timestamp > stored timestamp
4. Events with timestamp ≤ stored timestamp are ignored

---

## Data Mappings

### Domain → DynamoDB

| Domain Object | DynamoDB Item |
|---------------|---------------|
| `AccountBalance.accountId` | `accountId` (S) |
| `AccountBalance.currentSnapshot.amount.amount` | `amount` (S) |
| `AccountBalance.currentSnapshot.amount.currency` | `currency` (S) |
| `AccountBalance.currentSnapshot.timestamp` | `timestamp` (N) |
| `AccountBalance.currentSnapshot.transactionId` | `transactionId` (S) |
| `AccountBalance.updatedAt` | `updatedAt` (S) |

### Kafka → Domain

| Kafka Field | Domain Object |
|-------------|---------------|
| `transaction.id` | `BalanceSnapshot.transactionId` |
| `transaction.timestamp` | `BalanceSnapshot.timestamp` |
| `account.id` | `AccountBalance.accountId` |
| `account.balance.amount` | `Money.amount` (BigDecimal) |
| `account.balance.currency` | `Money.currency` |

### Domain → REST

| Domain Object | REST Field |
|---------------|------------|
| `AccountBalance.accountId` | `accountId` |
| `AccountBalance.currentSnapshot.amount.amount` | `balance` |
| `AccountBalance.currentSnapshot.amount.currency` | `currency` |
| `AccountBalance.currentSnapshot.transactionId` | `lastTransactionId` |
| `AccountBalance.currentSnapshot.timestamp` | `timestamp` |

---

## Constraints Summary

| Constraint | Enforcement |
|------------|-------------|
| Balance reflects highest timestamp | Conditional write |
| Amount has 2 decimal places | `Money` value object |
| Currency is 3-letter code | `Money` value object |
| Account ID format | Validation in adapter layer |
| Transaction ID uniqueness | Upstream guarantee |
| Ordering per account | Kafka message key |
