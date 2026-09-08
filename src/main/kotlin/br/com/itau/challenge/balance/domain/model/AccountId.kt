package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidAccountIdException

/**
 * The identifier of an account. The accepted shape is a business invariant, not a transport concern
 * (spec A-05), which is why it is validated here: the web adapter can reject a malformed identifier
 * with `400` before ever touching the datastore (FR-048) simply by constructing this type.
 */
data class AccountId(val value: String) {

    init {
        if (!PATTERN.matches(value)) {
            throw InvalidAccountIdException("accountId must be non-empty and match [A-Za-z0-9-]")
        }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("^[A-Za-z0-9-]+$")
    }
}
