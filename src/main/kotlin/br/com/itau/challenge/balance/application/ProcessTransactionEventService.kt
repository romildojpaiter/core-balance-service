package br.com.itau.challenge.balance.application

import br.com.itau.challenge.balance.domain.eligibility.EligibilityDecision
import br.com.itau.challenge.balance.domain.model.TransactionEvent
import br.com.itau.challenge.balance.domain.outcome.ProcessingOutcome
import br.com.itau.challenge.balance.domain.outcome.RejectionReason
import br.com.itau.challenge.balance.port.input.ProcessTransactionEventUseCase
import br.com.itau.challenge.balance.port.output.BalanceTelemetry
import br.com.itau.challenge.balance.port.output.BalanceWriteResult
import br.com.itau.challenge.balance.port.output.BalanceWriter
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Sequences ingestion: eligibility, then the conditional write, then the outcome.
 *
 * Note what this service does *not* do. It does not decide eligibility — that is the domain
 * ([TransactionEvent.evaluateEligibility]). It does not decide ordering — that is the datastore's
 * conditional expression. Its job is to sequence and translate, which is why it holds no state and
 * needs no lock.
 */
@Service
class ProcessTransactionEventService(
    private val balanceWriter: BalanceWriter,
    private val telemetry: BalanceTelemetry,
) : ProcessTransactionEventUseCase {

    override fun process(event: TransactionEvent): ProcessingOutcome {
        val startedAt = System.nanoTime()
        val outcome = decide(event)
        // Recorded here, and only here. The consumer knows the transport; this layer knows the
        // outcome, so counting it here is what makes FR-054 exhaustiveness testable with a mock
        // instead of a running broker. Recording in both places would double every counter.
        telemetry.outcomeRecorded(outcome, contextOf(event), Duration.ofNanos(System.nanoTime() - startedAt))
        return outcome
    }

    private fun decide(event: TransactionEvent): ProcessingOutcome {
        val decision = event.evaluateEligibility()
        if (decision is EligibilityDecision.Ineligible) {
            // The writer is deliberately not called. That is precisely how the freshness marker is
            // kept from advancing (FR-010, INV-003): a declined transaction carries no authoritative
            // balance, and advancing the marker would suppress a later valid event.
            return ProcessingOutcome.IgnoredIneligible(decision.reasons)
        }

        val balance = event.toBalance()
        // A transient failure from the writer propagates on purpose: the Kafka container is the
        // single retry authority (ADR-005) and must see the exception to retry without committing.
        // No telemetry is emitted for it here — the outcome is not yet known, and the adapter that
        // raised it already reports it through persistenceFailed.
        return when (val result = balanceWriter.save(balance)) {
            is BalanceWriteResult.Applied -> ProcessingOutcome.Applied(balance)
            is BalanceWriteResult.Duplicate ->
                ProcessingOutcome.Rejected(RejectionReason.DUPLICATE_EVENT, result.persisted)
            is BalanceWriteResult.Stale ->
                ProcessingOutcome.Rejected(RejectionReason.STALE_EVENT, result.persisted)
            is BalanceWriteResult.TimestampTie ->
                ProcessingOutcome.Rejected(RejectionReason.TIMESTAMP_TIE_REJECTED, result.persisted)
        }
    }

    /**
     * Correlation identifiers only. `topic`, `partition` and `offset` stay null: this layer does not
     * know Kafka exists (Constitution III), and the consumer fills them for its own calls.
     */
    private fun contextOf(event: TransactionEvent) =
        BalanceTelemetry.EventContext(
            accountId = event.account.id.value,
            transactionId = event.transaction.id.value,
            topic = null,
            partition = null,
            offset = null,
        )
}
