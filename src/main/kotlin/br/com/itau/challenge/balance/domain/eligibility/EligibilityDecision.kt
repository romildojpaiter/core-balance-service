package br.com.itau.challenge.balance.domain.eligibility

/**
 * The judgement of whether an event may compete to update the balance (FR-008, Constitution IX).
 *
 * [Ineligible] carries a *set* of reasons because an event can fail both criteria at once — a
 * `DECLINED` transaction on a `DISABLED` account reports both, and telemetry records both.
 */
sealed interface EligibilityDecision {

    data object Eligible : EligibilityDecision

    data class Ineligible(val reasons: Set<IneligibilityReason>) : EligibilityDecision {
        init {
            require(reasons.isNotEmpty()) { "an ineligible decision must carry at least one reason" }
        }
    }
}
