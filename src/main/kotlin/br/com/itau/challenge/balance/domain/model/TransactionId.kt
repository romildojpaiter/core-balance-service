package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException

/**
 * The identifier of a transaction. It is the basis of duplicate detection (FR-023): an incoming
 * event whose id equals the id that produced the current state is a duplicate, not a stale event.
 */
data class TransactionId(val value: String) {

    init {
        if (value.isBlank()) {
            throw InvalidTransactionEventException("transaction.id must not be blank")
        }
    }

    override fun toString(): String = value
}
