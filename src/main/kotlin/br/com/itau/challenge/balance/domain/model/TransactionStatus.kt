package br.com.itau.challenge.balance.domain.model

/**
 * Eligibility is a *positive equality* (FR-008): only `APPROVED` authorises a balance update.
 *
 * [from] therefore never throws. A missing status and an unknown status collapse into the same
 * negative value (FR-008a), which makes the event **ineligible** rather than the message invalid —
 * row 5 of the decision matrix, not row 6. That is the conservative reading under Constitution I,
 * and it stops a producer-side schema change (a new `PENDING`, say) from flooding the DLQ with
 * messages that only needed to be ignored.
 */
enum class TransactionStatus {
    APPROVED,
    NOT_APPROVED,
    ;

    companion object {
        const val APPROVED_VALUE: String = "APPROVED"

        fun from(raw: String?): TransactionStatus = if (raw == APPROVED_VALUE) APPROVED else NOT_APPROVED
    }
}
