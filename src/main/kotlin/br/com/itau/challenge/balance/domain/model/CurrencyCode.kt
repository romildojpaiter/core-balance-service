package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidCurrencyCodeException

/**
 * An ISO 4217 currency code. Currency is preserved exactly as delivered and is never inferred,
 * defaulted or converted (FR-015, FR-045).
 */
data class CurrencyCode(val value: String) {

    init {
        if (!PATTERN.matches(value)) {
            throw InvalidCurrencyCodeException("currency must be a 3-letter uppercase ISO 4217 code")
        }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("^[A-Z]{3}$")
    }
}
