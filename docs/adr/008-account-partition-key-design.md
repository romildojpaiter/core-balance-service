# ADR-008: `ACCOUNT#{accountId}` as the Partition Key

**Status**: Accepted

**Date**: 2026-09-06

**Context**: Core Banking — Balance Update

## Context

The table holds exactly one item per account: the current balance snapshot. Access is always by a
known account id, from two places — the conditional write and the strongly consistent read.

## Decision

Single-attribute primary key, no sort key:

```
pk = "ACCOUNT#" + accountId
```

Item attributes: `pk`, `accountId`, `ownerId` (omitted when absent), `balanceAmount` (`S`),
`balanceCurrency` (`S`), `lastEventTimestamp` (`N`), `lastTransactionId` (`S`), `updatedAt` (`S`).

The prefix and the key derivation live in `BalanceTableAttributes` and nowhere else. **The domain
never sees a partition key.**

## Rationale

Implements **Constitution III (Hexagonal Architecture)** and **XV (Simplicity)**.

The `ACCOUNT#` prefix carries no cost and buys room: a single-table design that later needs to hold
another entity type can do so without a migration of existing keys. Naming what a key refers to also
makes a scan output readable during an incident, which the bare id does not.

Omitting the sort key is deliberate. There is exactly one item per account; a sort key would create
the possibility of several, which is a shape the correctness argument does not allow — the
conditional write reasons about *the* item for an account.

`updatedAt` is the application's wall clock, written for operators and **read by no decision**.
Freshness comes from `lastEventTimestamp`, which comes from the event. Conflating the two would make
ordering depend on when a machine happened to process a message.

## Consequences

- Reads and writes are single-item operations: predictable cost, single-digit millisecond latency.
- An account with disproportionate volume is a hot partition (risk R-10). Monitored via per-partition
  lag; no structural mitigation in this version.
- Querying "all accounts of an owner" would need a GSI. Not a requirement, and not added
  speculatively.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Bare `accountId` as `pk` | Works, but forecloses single-table reuse and reads worse in a scan. |
| `pk = OWNER#{ownerId}`, `sk = ACCOUNT#{accountId}` | Optimises a query nobody asked for, and makes the per-account condition span an item collection. |
| Append-only event items with a sort key | That is a ledger. This service is a projection of an upstream source of truth; Constitution VIII and XV both rule it out. |
