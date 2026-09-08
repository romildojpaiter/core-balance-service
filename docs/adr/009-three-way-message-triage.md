# ADR-009: Three-Way Message Triage

**Status**: Accepted

**Date**: 2026-09-06 — from the clarification session of the same date

**Context**: Core Banking — Balance Update

## Context

The `transactions-events` topic carries messages the consumer was never meant to process. The local
tooling makes this concrete: `make kafka-produce-accounts-events` publishes `{"account": {...}}`
payloads with no `transaction` block at all.

Under a two-way model — valid or invalid — every one of those lands in the DLQ. That destroys the
DLQ's only useful property. A dead-letter queue is worth having while it means "something is
broken"; once it also collects messages the consumer simply was not meant to handle, every alert on
it becomes noise and real defects stop being noticed.

The naive fix — signalling the discard by throwing a dedicated exception — does not work. Any
exception leaving the listener is caught by the `DefaultErrorHandler`, which routes it to the DLQ.
The mechanism itself has to change.

## Decision

`TransactionEventMessageMapper.interpret` returns a sealed `MessageInterpretation`:

| Case | Return / throw | Destination |
|---|---|---|
| `transaction` block absent entirely | `Unsupported(NO_TRANSACTION_BLOCK)` | discarded, counted, offset committed |
| Well-formed transaction event | `Interpreted(event)` | processed |
| `transaction` present but null, empty, or missing a required field | throws `UnprocessableEventException` | DLQ, no retry |

An invalid message is deliberately **not** a variant of the sealed type. It belongs on the error
path, and keeping the two mechanisms distinct is what keeps the two destinations distinct.

The check is made against the **parsed JSON tree**, before binding to the DTO. After binding,
`{"account":{}}` and `{"transaction":null,"account":{}}` are indistinguishable — every DTO field is
nullable — and the two have opposite destinations.

A missing or unknown *status* is a third thing again: neither invalid nor unsupported, but
**ineligible** (FR-008a). `TransactionStatus.from` and `AccountStatus.from` never throw.

## Rationale

Implements **Constitution XII (Kafka Processing Semantics)** and **XIII (Observability)**, and
realises FR-004a and row 5a of the decision matrix.

Returning normally is what authorises the container to commit the offset. The discard is a
**terminal success**, so it must not travel the error path — and the sealed return type is what keeps
it off.

The discard is never silent: it increments `balance.events.unsupported` tagged by reason and is
logged at INFO with the correlation identifiers (INV-008).

Collapsing an unknown status into "ineligible" rather than "invalid" has a concrete operational
payoff: the day a producer introduces a new status value, the DLQ does not fill with well-formed
messages that only ever needed to be ignored.

## Consequences

- The boundary between "another flow" and "a defect" is nineteen characters wide. `UnsupportedMessageTest`
  pins both sides of it with near-identical payloads, and the mapper must keep reading the tree — any
  refactor to decide on the bound DTO silently breaks FR-004a.
- The DLQ stays a signal. `TransactionEventDlqIntegrationTest` asserts the negative case explicitly:
  an account event must **not** appear there.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Everything unrecognised to the DLQ | Turns the DLQ into noise and hides real defects. |
| A dedicated topic per message type | Requires changing the producer, which is outside this system's control. |
| Silent `return` with no telemetry | Violates INV-008: a discarded message with no record is indistinguishable from a lost one. |
| A discard signalled by exception | The error handler would route it to the DLQ — the exact outcome being avoided. |
