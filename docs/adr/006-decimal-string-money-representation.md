# ADR-006: Money as Decimal Text at Every Boundary

**Status**: Accepted — **amended 2026-09-07** (see *Amendment* at the end; the REST boundary changed)

**Date**: 2026-09-06

**Context**: Core Banking — Balance Update

## Context

A monetary amount crosses three boundaries in this system: the Kafka payload, the DynamoDB item, and
the REST response. Each offers a numeric representation that silently loses information:

- **JSON numbers** are read as IEEE-754 doubles by most clients. `183.12` has no exact binary
  representation, and the error compounds invisibly.
- **DynamoDB's `N` type** is normalised: it strips trailing zeros, so `150.00` is stored and returned
  as `"150"`. The scale FR-044 requires is gone before anything can read it back.
- **`Double` anywhere in the JVM** reintroduces the same problem internally.

## Decision

Money is exact decimal in memory and **text** at every boundary.

| Boundary | Representation |
|---|---|
| Kafka payload → domain | `BigDecimal` declared on the DTO, so Jackson never routes the value through `double` |
| Domain | `Money` over `BigDecimal`, scale fixed at 2 |
| DynamoDB item | `balanceAmount` as **`S`**, via `toPlainString()` |
| REST response | `amount` as a **quoted string** matching `^-?\d+\.\d{2}$` |

`Money.of` normalises the scale with `RoundingMode.UNNECESSARY`, which **throws** when the incoming
value carries more precision. A three-decimal amount is a rejected input, not a silently truncated
one.

`Money` deliberately offers **no `Double` constructor** and **no `plus`/`minus`**. The first makes
the precision violation impossible to write; the second makes Constitution VIII structural — code
that tries to accumulate transactions into a balance does not compile.

One attribute stays numeric: `lastEventTimestamp` is `N`, because it is the left operand of the
freshness condition and DynamoDB compares `S` lexicographically — `"9"` would sort after `"10"`.

## Rationale

Implements **Constitution VIII (Authoritative Balance Snapshot)** and **X (Monetary Precision)**.

The prohibition is absolute — no `Double` or `Float` in production code at all, not merely "not for
money" — and `MonetaryPrecisionArchitectureTest` enforces it. An exception list would weaken a rule
whose entire value is that it has none; when the retry multiplier needed a fractional type, it became
a `BigDecimal` rather than an exception.

## Consequences

- The REST contract is textual money. Clients must parse it as a decimal, which is exactly the
  behaviour we want to force.
- The response diverges from the challenge's sample, which shows `"amount": 183.12` unquoted. The
  divergence is deliberate and documented in the spec: matching the sample would mean shipping the
  most common way an API corrupts money.
- Sorting or aggregating balances in DynamoDB is not possible on the stored text. Neither is a
  requirement of this service, which is a projection, not a ledger.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Store amounts in cents as `N` | Works, but pushes the scale into every reader's head and makes a currency with a different minor unit a migration. |
| `N` plus a separate scale attribute | Two attributes that must be updated together to express one value. |
| Unquoted JSON number in the response | Matches the sample and loses precision at the client. Rejected on the record. |

---

## Amendment — 2026-09-07: the REST boundary is a JSON number

The title still says *at every boundary*, and that stopped being true for one of the three. Rather
than rewrite the decision, this section records what changed and why, because the original reasoning
is what makes the remaining two boundaries comprehensible.

**What changed.** `amount` in the REST response is now a **JSON number** serialised from
`BigDecimal` — `183.12`, unquoted — with scale 2 preserved. FR-044 was revised accordingly
(spec.md, Clarifications 2026-09-07; research.md R19).

**What did not change.** Everything else in this ADR stands, unamended:

| Boundary | Representation | Status |
|---|---|---|
| Kafka payload → domain | `BigDecimal` on the DTO | unchanged |
| Domain | `Money` over `BigDecimal`, scale 2 | unchanged |
| DynamoDB item | `balanceAmount` as **`S`** | unchanged — `N` would still strip `150.00` to `150` |
| REST response | `amount` as an unquoted JSON number | **amended** |

**Which argument moved.** The original case against the number form was that *most clients read a
JSON number as a double*. That is true, and it was the wrong thing to optimise: it describes the
client's parser, not this service's output. Constitution X forbids floating-point **types** and
requires an exact decimal representation — neither clause is violated by a number emitted from a
`BigDecimal`. What the client does on deserialisation is outside the boundary this system controls.

**Verified, not assumed.** The decision turned on one empirical question: does Jackson preserve the
scale? Compiled against this project's own Jackson 3 jars, `BigDecimal("150.00")` serialises as
`150.00`, **not** `150`. Had it emitted `150`, the exact decimal representation would have been lost
inside our own boundary and the original decision would have stood.

**What now guards it.** `BalanceResponseContractTest` pins the raw body — an unquoted `150.00`, with
both decimals — and `MonetaryPrecisionArchitectureTest` still breaks the build on any `Double` or
`Float` in production code. One caveat worth recording: assertions written through Spring's
`jsonPath` cannot see this, because Jayway parses `150.00` into a double and returns `150.0`. The
scale must be asserted on the raw response text.

**Consequence, restated.** The third bullet under *Alternatives considered* — "Unquoted JSON number
in the response: rejected on the record" — is the alternative that was subsequently adopted. The
response now matches the challenge's sample exactly, and no concession to it remains outstanding.
