# ADR-007: One Deployable, With the Split Designed but Not Taken

**Status**: Accepted

**Date**: 2026-09-06

**Context**: Core Banking — Balance Update

## Context

The service does two things with very different load profiles: it consumes a stream of transaction
events, and it answers balance queries. That asymmetry is the standard argument for splitting them
into two deployables — independent scaling, and isolation so an ingestion backlog cannot degrade
query latency.

At current scope neither pressure exists. Splitting now would buy two build pipelines, two
deployments, two sets of dashboards and a shared library, in exchange for a scaling need nobody has
measured.

## Decision

Ship **one** deployable containing both adapters. Design so the split stays cheap:

- Ingestion and query share **no code path** — they meet only at the DynamoDB table.
- `TransactionEventConsumer` depends on `ProcessTransactionEventUseCase`; `BalanceController` depends
  on `GetBalanceUseCase`. Neither knows the other exists.
- The consumer's registration is the only thing that would need to become conditional.

When the split is warranted, it is a Spring profile on the listener plus a second deployment
descriptor. No domain, port or application code changes.

## Rationale

Implements **Constitution XV (Simplicity)**. The principle is not "never split" — it is that
structure is added when a requirement demands it, not in anticipation of one.

Keeping the two paths independent is what makes the deferral honest. A monolith whose halves have
grown into each other cannot be split later at any reasonable cost, and "we'll split it when we need
to" becomes a sentence nobody can act on.

## Consequences

- One image, one compose service, one health endpoint.
- A consumer restart briefly interrupts query serving. Acceptable at this scope; it is the first
  symptom that would justify the split.
- Both workloads scale together. The useful ceiling is the topic's partition count.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Two services now | Real operational cost today for a scaling need not yet observed. |
| Modular monolith with enforced module boundaries | The hexagonal packages plus Konsist already give the boundary; a module system would add build complexity for the same guarantee. |
