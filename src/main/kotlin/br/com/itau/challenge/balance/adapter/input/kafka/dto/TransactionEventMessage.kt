package br.com.itau.challenge.balance.adapter.input.kafka.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * The wire shape, and nothing more. These types validate nothing.
 *
 * **Every field is nullable on purpose.** A DTO with non-null fields would let Jackson fail the
 * deserialization, and all the mapper could then report is "the payload did not fit" — whereas
 * FR-039 requires the DLQ to carry a message naming the field that was missing. Deferring every
 * check to the mapper is what makes that message possible.
 *
 * `amount` is declared as [BigDecimal] rather than a number type: Jackson binds JSON numbers
 * directly to `BigDecimal` when the target type says so, and never routes the value through
 * `double`. That is where INV-006 is actually enforced — by the time a `Double` exists, the
 * precision is already gone.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TransactionEventMessage(
    // Nullable so that FR-004a can tell an absent block from an incomplete one. The two payloads
    // look almost identical and have opposite destinations: discard versus DLQ.
    val transaction: TransactionMessage? = null,
    val account: AccountMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TransactionMessage(
    val id: String? = null,
    val type: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val status: String? = null,
    val timestamp: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountMessage(
    val id: String? = null,
    val owner: String? = null,
    @param:JsonProperty("created_at") val createdAt: Long? = null,
    val status: String? = null,
    val balance: BalanceMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BalanceMessage(
    val amount: BigDecimal? = null,
    val currency: String? = null,
)
