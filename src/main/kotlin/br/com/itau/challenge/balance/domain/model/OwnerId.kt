package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException

/**
 * The identifier of the account holder.
 *
 * **Structurally required** (FR-003a): in this problem domain every account belongs to a holder, so
 * an event without one describes an account that cannot exist and is a producer defect. Making the
 * type non-nullable everywhere it appears means the rule is enforced by the compiler rather than by
 * a check each boundary has to remember.
 */
data class OwnerId(val value: String) {

    init {
        if (value.isBlank()) {
            throw InvalidTransactionEventException("account.owner must not be blank")
        }
    }

    override fun toString(): String = value
}
