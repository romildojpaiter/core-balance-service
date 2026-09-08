package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.eligibility.EligibilityDecision
import br.com.itau.challenge.balance.domain.eligibility.IneligibilityReason

/**
 * The ingestion aggregate root: a financial event carrying a transaction and the account state at
 * that transaction.
 *
 * This is the only place that judges eligibility. Constitution IX requires that judgement to live
 * in the domain, never in an adapter, so that it is executable in a plain unit test with no broker
 * or datastore running.
 */
data class TransactionEvent(
    val transaction: Transaction,
    val account: Account,
) {

    /**
     * Only `transaction.status = APPROVED` **and** `account.status = ENABLED` authorise a balance
     * update (FR-008). Anything else — including a missing or unrecognised status (FR-008a) — is
     * ineligible, and both failures are reported when both apply.
     */
    fun evaluateEligibility(): EligibilityDecision {
        val reasons = mutableSetOf<IneligibilityReason>()
        if (transaction.status != TransactionStatus.APPROVED) {
            reasons.add(IneligibilityReason.TRANSACTION_NOT_APPROVED)
        }
        if (account.status != AccountStatus.ENABLED) {
            reasons.add(IneligibilityReason.ACCOUNT_NOT_ENABLED)
        }
        return if (reasons.isEmpty()) {
            EligibilityDecision.Eligible
        } else {
            EligibilityDecision.Ineligible(reasons)
        }
    }

    /**
     * Produces the state this event would persist.
     *
     * The balance is copied **literally** from [Account.balance]; `transaction.amount` is never
     * applied to anything (FR-013, FR-014). Callers must check [evaluateEligibility] first — an
     * ineligible event carries no authoritative balance, and letting it through would advance the
     * freshness marker and suppress a later valid event (FR-010).
     */
    fun toBalance(): Balance {
        check(evaluateEligibility() is EligibilityDecision.Eligible) {
            "an ineligible event must never produce a balance"
        }
        return Balance(
            accountId = account.id,
            ownerId = account.ownerId,
            money = account.balance,
            asOf = transaction.timestamp,
            lastTransactionId = transaction.id,
        )
    }
}
