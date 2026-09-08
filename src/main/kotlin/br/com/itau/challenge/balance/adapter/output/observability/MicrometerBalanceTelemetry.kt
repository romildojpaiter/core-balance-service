package br.com.itau.challenge.balance.adapter.output.observability

import br.com.itau.challenge.balance.domain.outcome.ProcessingOutcome
import br.com.itau.challenge.balance.port.output.BalanceTelemetry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The only class that knows Micrometer exists, and the one place where the counter and the log line
 * for an outcome sit side by side.
 *
 * Keeping them together is not tidiness: it is how "every non-mutating outcome must be observable"
 * (FR-054) stays true. When the counter lives in one file and the log in another, one of them
 * eventually gets a new branch and the other does not, and the gap is invisible until an incident.
 *
 * **No metric is ever tagged with `accountId` or `transactionId`.** Both are unbounded, and an
 * unbounded tag in Prometheus is a cardinality explosion waiting for production traffic. FR-052 asks
 * for outcomes attributable to an account and a transaction — the structured logs do the
 * attributing, through the MDC; the metrics do the aggregating.
 */
@Component
class MicrometerBalanceTelemetry(
    private val registry: MeterRegistry,
) : BalanceTelemetry {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun eventReceived(context: BalanceTelemetry.EventContext) {
        registry.counter(EVENTS_RECEIVED).increment()
    }

    override fun outcomeRecorded(
        outcome: ProcessingOutcome,
        context: BalanceTelemetry.EventContext,
        elapsed: Duration,
    ) {
        val (name, reason) = describe(outcome)

        registry.counter(EVENTS_OUTCOME, TAG_OUTCOME, name, TAG_REASON, reason).increment()
        Timer.builder(EVENT_PROCESSING).tag(TAG_OUTCOME, name).register(registry).record(elapsed)

        // Logged at INFO even when nothing changed. A silent "do nothing" branch is indistinguishable
        // from the service being broken, which is the failure mode FR-054 exists to prevent.
        log.atInfo()
            .setMessage("transaction event processed")
            .addKeyValue("outcome", name)
            .addKeyValue("reason", reason)
            .addKeyValue("elapsedMillis", elapsed.toMillis())
            .log()
    }

    override fun unsupportedMessage(
        reason: String,
        context: BalanceTelemetry.EventContext,
    ) {
        registry.counter(EVENTS_UNSUPPORTED, TAG_REASON, reason).increment()
        log.atInfo()
            .setMessage("message from another flow discarded")
            .addKeyValue("reason", reason)
            .log()
    }

    override fun persistenceFailed(
        context: BalanceTelemetry.EventContext,
        transient: Boolean,
        cause: Throwable,
    ) {
        val type = if (transient) TYPE_TRANSIENT else TYPE_PERMANENT

        registry.counter(PERSISTENCE_FAILURES, TAG_TYPE, type).increment()
        // The exception class, not its message: the message can contain a request id or an endpoint,
        // and a metric tag is not the place for either.
        log.atWarn()
            .setMessage("persistence failed")
            .addKeyValue("type", type)
            .addKeyValue("exception", cause.javaClass.simpleName)
            .setCause(cause)
            .log()
    }

    override fun anomalyDetected(
        anomaly: String,
        context: BalanceTelemetry.EventContext,
    ) {
        registry.counter(counterNameFor(anomaly), TAG_ANOMALY, anomaly).increment()
        log.atWarn().setMessage("anomaly detected").addKeyValue("anomaly", anomaly).log()
    }

    /**
     * Anomalies that deserve their own series get one; the rest share a bounded catch-all.
     *
     * The names are a closed set defined in code, never derived from message content — deriving them
     * would let a producer create an unbounded number of metric series by sending unusual data.
     */
    private fun counterNameFor(anomaly: String): String =
        when {
            anomaly == ANOMALY_KEY_MISMATCH -> EVENTS_KEY_MISMATCH
            anomaly == ANOMALY_CURRENCY_CHANGED -> EVENTS_CURRENCY_CHANGED
            anomaly == ANOMALY_DLQ_PUBLISHED -> DLQ_PUBLISHED
            anomaly == ANOMALY_DLQ_FAILED -> DLQ_FAILURES
            anomaly.startsWith(ANOMALY_RETRY_PREFIX) -> RETRIES
            else -> ANOMALIES
        }

    private fun describe(outcome: ProcessingOutcome): Pair<String, String> =
        when (outcome) {
            is ProcessingOutcome.Applied -> "APPLIED" to REASON_NONE
            is ProcessingOutcome.IgnoredIneligible ->
                "IGNORED_INELIGIBLE" to outcome.reasons.map { it.name }.sorted().joinToString("+")
            is ProcessingOutcome.Rejected -> "REJECTED" to outcome.reason.name
        }

    companion object {
        const val EVENTS_RECEIVED = "balance.events.received"
        const val EVENTS_OUTCOME = "balance.events.outcome"
        const val EVENT_PROCESSING = "balance.event.processing"
        const val EVENTS_UNSUPPORTED = "balance.events.unsupported"
        const val PERSISTENCE_FAILURES = "balance.persistence.failures"
        const val RETRIES = "balance.retries"
        const val DLQ_PUBLISHED = "balance.dlq.published"
        const val DLQ_FAILURES = "balance.dlq.failures"
        const val EVENTS_KEY_MISMATCH = "balance.events.key.mismatch"
        const val EVENTS_CURRENCY_CHANGED = "balance.events.currency.changed"
        const val ANOMALIES = "balance.anomalies"

        const val ANOMALY_KEY_MISMATCH = "KEY_MISMATCH"
        const val ANOMALY_CURRENCY_CHANGED = "CURRENCY_CHANGED"
        const val ANOMALY_DLQ_PUBLISHED = "DLQ_PUBLISHED"
        const val ANOMALY_DLQ_FAILED = "DLQ_FAILED"
        const val ANOMALY_RETRY_PREFIX = "RETRY_ATTEMPT_"

        private const val TAG_OUTCOME = "outcome"
        private const val TAG_REASON = "reason"
        private const val TAG_TYPE = "type"
        private const val TAG_ANOMALY = "anomaly"
        private const val TYPE_TRANSIENT = "TRANSIENT"
        private const val TYPE_PERMANENT = "PERMANENT"
        private const val REASON_NONE = "NONE"
    }
}
