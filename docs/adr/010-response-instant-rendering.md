# ADR-010: Rendering `updated_at` in the Response

**Status**: Accepted

**Date**: 2026-09-06 — from the clarification session of the same date

**Context**: Core Banking — Balance Query

## Context

The persisted freshness marker is `transaction.timestamp` in **microseconds** since the Unix epoch.
The challenge's sample response shows a very different shape:

```json
"updated_at": "2025-07-05T18:04:13.433-03:00"
```

Three decisions were open: the field's name, its timezone, and what to do with the precision that
does not fit.

## Decision

The response field is named **`updated_at`** and is rendered as ISO-8601 with a **local offset** and
**exactly three** fractional digits, obtained by **truncation**:

```
Instant.EPOCH.plus(micros, MICROS)
    .atZone(ZoneId.of(balance.api.timezone))   // default America/Sao_Paulo
    .truncatedTo(ChronoUnit.MILLIS)
    .format("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
```

- **Truncation, never rounding.** Rounding `…589998` µs to `…590` ms would display an instant that
  never occurred and, at the edge of a second, an instant in the future.
- **Always three digits.** `ISO_OFFSET_DATE_TIME` omits the fraction when it is zero, which would
  make a balance recorded on a whole second render differently from every other one and force clients
  to parse two shapes.
- **The timezone is configuration**, not a constant: `balance.api.timezone`.

The precision loss is **display only**. The persisted value stays in microseconds and remains the
sole ordering criterion; nothing reads this string back.

## Rationale

Realises FR-046 as clarified on 2026-09-06. The field name is the one place where the challenge's
sample was allowed to override the specification's own contract — the response is what the evaluation
reads, and `updated_at` is what it shows. `balance.amount` was **not** conceded in the same way: text
versus number is a correctness question (see [ADR-006](./006-decimal-string-money-representation.md)),
whereas a field name is a naming question.

The result is one snake_case field among camelCase siblings. The inconsistency is accepted knowingly
and confined to a single annotation on the response DTO; neither the domain nor the persisted item
knows about it.

`America/Sao_Paulo` renders as `-03:00` today because Brazil abolished daylight saving in 2019. If it
returns, this zone will alternate with `-02:00` — which is why the zone is configurable and the
consequence is written down here rather than discovered later.

## Consequences

- `BalanceResponseMapperTest` pins the truncation with `…589998` µs → `.589`, and the whole-second
  case with `.000`.
- `BalanceResponseContractTest` reads `balances-api.yaml` and fails if the code and the published
  contract drift apart — this contract has already changed twice.
- A client needing microsecond precision cannot get it from this endpoint. No requirement asks for
  it, and exposing it would contradict the sample.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Raw microseconds as a number | Matches persistence, matches nothing a caller expects, and reintroduces a large JSON number. |
| UTC with `Z` | Unambiguous, but does not match the sample the evaluation compares against. |
| Rounding to the nearest millisecond | Can display a moment that never happened, and at a second boundary a moment in the future. |
| Fixed `-03:00` offset in code | Correct today, wrong the moment the policy changes, and untestable as configuration. |
