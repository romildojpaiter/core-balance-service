package br.com.itau.challenge.balance.port.output

import br.com.itau.challenge.balance.domain.outcome.ProcessingOutcome
import java.time.Duration

/**
 * Everything the service must make observable, as a single reviewable contract.
 *
 * Every correctness rule in this system has a "do nothing" branch — ignore, duplicate, stale, tie,
 * unsupported. Without telemetry those branches are indistinguishable from the service being
 * broken, which is why FR-054 requires them to be silent in effect but never in telemetry.
 *
 * Keeping this behind a port has two concrete payoffs: the application layer stays testable with no
 * `MeterRegistry`, and the full list of observable outcomes can be reviewed against FR-052 by
 * reading one file instead of hunting for calls.
 */
interface BalanceTelemetry {

    fun eventReceived(context: EventContext)

    fun outcomeRecorded(
        outcome: ProcessingOutcome,
        context: EventContext,
        elapsed: Duration,
    )

    /** Row 5a: a message from another flow, discarded observably and never sent to the DLQ (FR-004a). */
    fun unsupportedMessage(
        reason: String,
        context: EventContext,
    )

    fun persistenceFailed(
        context: EventContext,
        transient: Boolean,
        cause: Throwable,
    )

    fun anomalyDetected(
        anomaly: String,
        context: EventContext,
    )

    /**
     * Correlation data for one processed message.
     *
     * These identifiers belong in **logs** (via MDC), never as metric tags: `accountId` and
     * `transactionId` are unbounded, and high-cardinality tags are a production incident waiting to
     * happen. FR-052 asks for outcomes attributable to an account and a transaction — the structured
     * logs do the attributing, the metrics do the aggregating.
     */
    data class EventContext(
        val accountId: String?,
        val transactionId: String?,
        val topic: String?,
        val partition: Int?,
        val offset: Long?,
    ) {
        companion object {
            val EMPTY = EventContext(null, null, null, null, null)
        }
    }
}
