package br.com.itau.challenge.balance.adapter.input.web.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * The wire shape of a balance query: **exactly four keys, and no others** (FR-043).
 *
 * The contract declares `additionalProperties: false`, so an extra key is not harmless noise — it
 * makes a strict client reject the whole response. `lastTransactionId` is therefore absent
 * (FR-043a); it stays persisted, because it is the operand that tells a redelivery apart from a
 * conflict in the conditional write, and it stays in the structured logs.
 *
 * **`amount` is a `BigDecimal`, serialised as a JSON number** (FR-044, revised 2026-09-07). The
 * earlier choice was text, on the argument that a client reads a JSON number as a double — true, but
 * it describes the client's parser, not this service's output. Verified: Jackson emits
 * `BigDecimal("150.00")` as `150.00`, not `150`, so the scale survives and the exact decimal
 * representation Constitution X requires reaches the last boundary this system controls intact.
 * What the client does when deserialising is its own choice.
 *
 * The condition that is not negotiable: `amount` comes from a `BigDecimal`, never a `Double`. That
 * does not rest on discipline — `MonetaryPrecisionArchitectureTest` breaks the build if any
 * `Double`/`Float` appears in production code.
 *
 * `owner` is always present and never null: `account.owner` is structurally required at ingestion
 * (FR-003a), so no balance without a holder can exist to be rendered.
 *
 * `updated_at` deliberately keeps the snake_case of the challenge's sample response next to
 * camelCase siblings. The inconsistency is accepted knowingly: matching the sample is worth more
 * than internal style, and the deviation is confined to this one annotation — neither the domain nor
 * the persisted item knows about it.
 */
data class BalanceResponse(
    val id: String,
    val owner: String,
    val balance: BalanceAmountResponse,
    @get:JsonProperty("updated_at") val updatedAt: String,
)

data class BalanceAmountResponse(
    val amount: BigDecimal,
    val currency: String,
)
