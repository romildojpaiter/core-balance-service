package br.com.itau.challenge.balance.domain.outcome

import br.com.itau.challenge.balance.domain.eligibility.IneligibilityReason
import br.com.itau.challenge.balance.domain.model.Balance

/**
 * The terminal business outcome of processing one event — rows 1 to 5 of the specification's
 * decision matrix.
 *
 * Rows 6 to 9 (invalid message, transient failure, permanent failure, DLQ failure) are **not**
 * modelled here: they are transport failures handled by the Kafka container, not business
 * decisions. Neither is row 5a (a message from another flow), which the input adapter discards
 * before the payload ever becomes a domain event.
 */
sealed interface ProcessingOutcome {

    data class Applied(val balance: Balance) : ProcessingOutcome

    data class IgnoredIneligible(val reasons: Set<IneligibilityReason>) : ProcessingOutcome

    data class Rejected(
        val reason: RejectionReason,
        val persisted: Balance?,
    ) : ProcessingOutcome
}
