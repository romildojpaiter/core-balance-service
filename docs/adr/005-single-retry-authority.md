# ADR-005: A Single Retry Authority

**Status**: Accepted

**Date**: 2026-09-06

**Context**: Core Banking — Balance Update

## Context

Three layers in this stack retry by default or by temptation:

1. the AWS SDK, which retries three times out of the box;
2. the Spring Kafka listener container, via its error handler;
3. application code, whenever someone wraps a call in a `try`/retry loop.

Left alone, they compose multiplicatively. Four container attempts over three SDK attempts is twelve
real calls, and the recovery window that SC-008 specifies stops being something anyone can compute.
Worse, a slow dependency can then hold a partition past `max.poll.interval.ms`, and the consumer is
evicted from the group for a failure that retrying was supposed to absorb.

## Decision

Exactly one component retries: the **Kafka listener container's `DefaultErrorHandler`**.

- The AWS SDK client is built with `DefaultRetryStrategy.doNotRetry()`, plus
  `apiCallAttemptTimeout = 1s` and `apiCallTimeout = 2s`.
- The consumer does **not** catch exceptions to handle them; anything thrown propagates to the
  container.
- The application service does not catch transient failures either — it lets them through so the
  container can see them.

Policy: 4 attempts, 200 ms initial interval, ×2, capped at 5 s, plus up to 100 ms of jitter. Total
window ≈ 1.4 s — two orders of magnitude under the default `max.poll.interval.ms` of five minutes.

Classification is explicit and closed:

| Exception | Retryable |
|---|---|
| `TransientProcessingException` | yes |
| `UnprocessableEventException` | no |
| anything else | no |

## Rationale

Implements **Constitution XI (Resilience)** and **XII (Kafka Processing Semantics)**.

Retrying is safe here without any further reasoning, because each attempt re-sends the **same
conditional `PutItem`**. If a previous attempt actually wrote and only the response was lost, the new
attempt fails the condition with `persisted.lastTransactionId == incoming.transactionId` and is
classified as a duplicate — a terminal success. No sequence of retries can apply an event twice.

Treating an unclassified exception as permanent is the conservative reading: an unrecognised
exception is most likely a deterministic bug, and retrying it four times only delays the partition to
reach the same failure.

The jitter is not decoration. Without it, the K consumers that hit the same DynamoDB throttle retry
at the same instant, re-creating the burst that caused the throttle — which then sustains itself.

## Consequences

- The attempt budget is readable in one place and asserted in `KafkaConsumerConfigTest`.
- A new caller of `BalanceWriter` inherits no retry; it must arrange its own, deliberately.
- `TransientProcessingException` lives in `port.output`, not in the Kafka adapter: the DynamoDB
  writer raises it and the Kafka handler classifies on it, and an output adapter must not import an
  input adapter.

## Alternatives considered

| Alternative | Why not |
|---|---|
| SDK retries plus container retries | Multiplicative budget; SC-008 becomes uncomputable and rebalances become likely. |
| `RetryTemplate` around the writer | A second authority in a third place, with its own budget to keep in sync. |
| No retry, DLQ everything | Turns a two-second DynamoDB blip into a manual replay operation. |
