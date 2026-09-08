package br.com.itau.challenge.balance.domain.model

/**
 * Eligibility is a *positive equality* (FR-008): only `ENABLED` authorises a balance update.
 * See [TransactionStatus] for why [from] never throws (FR-008a).
 */
enum class AccountStatus {
    ENABLED,
    NOT_ENABLED,
    ;

    companion object {
        const val ENABLED_VALUE: String = "ENABLED"

        fun from(raw: String?): AccountStatus = if (raw == ENABLED_VALUE) ENABLED else NOT_ENABLED
    }
}
