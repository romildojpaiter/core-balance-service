package br.com.itau.challenge.balance.domain.eligibility

/** Why an event may not update a balance. Recorded in telemetry for every discard (FR-011). */
enum class IneligibilityReason {
    TRANSACTION_NOT_APPROVED,
    ACCOUNT_NOT_ENABLED,
}
