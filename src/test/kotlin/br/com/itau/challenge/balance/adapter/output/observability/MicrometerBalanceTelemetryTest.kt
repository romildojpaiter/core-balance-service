package br.com.itau.challenge.balance.adapter.output.observability

import br.com.itau.challenge.balance.domain.eligibility.IneligibilityReason
import br.com.itau.challenge.balance.domain.outcome.ProcessingOutcome
import br.com.itau.challenge.balance.domain.outcome.RejectionReason
import br.com.itau.challenge.balance.port.output.BalanceTelemetry
import br.com.itau.challenge.balance.support.TestFixtures
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T078: the metrics carry the right tags, and none of them carries an unbounded one.
 */
class MicrometerBalanceTelemetryTest {

    private val registry = SimpleMeterRegistry()
    private val telemetry = MicrometerBalanceTelemetry(registry)

    private val context =
        BalanceTelemetry.EventContext(
            accountId = TestFixtures.ACCOUNT_ID,
            transactionId = TestFixtures.TRANSACTION_ID,
            topic = "transactions-events",
            partition = 2,
            offset = 41L,
        )

    @Test
    fun `an applied outcome is counted and timed`() {
        telemetry.outcomeRecorded(
            ProcessingOutcome.Applied(TestFixtures.balance()),
            context,
            Duration.ofMillis(12),
        )

        assertEquals(
            1.0,
            registry.counter(MicrometerBalanceTelemetry.EVENTS_OUTCOME, "outcome", "APPLIED", "reason", "NONE").count(),
        )
        assertEquals(1L, registry.timer(MicrometerBalanceTelemetry.EVENT_PROCESSING, "outcome", "APPLIED").count())
    }

    @Test
    fun `every non mutating outcome is counted too`() {
        telemetry.outcomeRecorded(
            ProcessingOutcome.IgnoredIneligible(setOf(IneligibilityReason.TRANSACTION_NOT_APPROVED)),
            context,
            Duration.ofMillis(1),
        )
        telemetry.outcomeRecorded(
            ProcessingOutcome.Rejected(RejectionReason.DUPLICATE_EVENT, TestFixtures.balance()),
            context,
            Duration.ofMillis(1),
        )

        // FR-054: a silent "do nothing" branch is indistinguishable from the service being broken.
        assertEquals(
            1.0,
            registry
                .counter(
                    MicrometerBalanceTelemetry.EVENTS_OUTCOME,
                    "outcome",
                    "IGNORED_INELIGIBLE",
                    "reason",
                    "TRANSACTION_NOT_APPROVED",
                ).count(),
        )
        assertEquals(
            1.0,
            registry
                .counter(MicrometerBalanceTelemetry.EVENTS_OUTCOME, "outcome", "REJECTED", "reason", "DUPLICATE_EVENT")
                .count(),
        )
    }

    @Test
    fun `both ineligibility reasons appear in a stable order`() {
        telemetry.outcomeRecorded(
            ProcessingOutcome.IgnoredIneligible(
                setOf(IneligibilityReason.TRANSACTION_NOT_APPROVED, IneligibilityReason.ACCOUNT_NOT_ENABLED),
            ),
            context,
            Duration.ZERO,
        )

        // Sorted, so set iteration order cannot silently create two series for one situation.
        assertEquals(
            1.0,
            registry
                .counter(
                    MicrometerBalanceTelemetry.EVENTS_OUTCOME,
                    "outcome",
                    "IGNORED_INELIGIBLE",
                    "reason",
                    "ACCOUNT_NOT_ENABLED+TRANSACTION_NOT_APPROVED",
                ).count(),
        )
    }

    @Test
    fun `a discarded message from another flow is counted separately from outcomes`() {
        telemetry.unsupportedMessage("NO_TRANSACTION_BLOCK", context)

        assertEquals(
            1.0,
            registry.counter(MicrometerBalanceTelemetry.EVENTS_UNSUPPORTED, "reason", "NO_TRANSACTION_BLOCK").count(),
        )
        assertTrue(
            registry.find(MicrometerBalanceTelemetry.EVENTS_OUTCOME).counters().isEmpty(),
            "a discard is not a processing outcome",
        )
    }

    @Test
    fun `a transient persistence failure is distinguishable from a permanent one`() {
        telemetry.persistenceFailed(context, transient = true, cause = IllegalStateException("throttled"))
        telemetry.persistenceFailed(context, transient = false, cause = IllegalStateException("bad request"))

        assertEquals(1.0, registry.counter(MicrometerBalanceTelemetry.PERSISTENCE_FAILURES, "type", "TRANSIENT").count())
        assertEquals(1.0, registry.counter(MicrometerBalanceTelemetry.PERSISTENCE_FAILURES, "type", "PERMANENT").count())
    }

    @Test
    fun `known anomalies get their own series and retries collapse into one`() {
        telemetry.anomalyDetected(MicrometerBalanceTelemetry.ANOMALY_KEY_MISMATCH, context)
        telemetry.anomalyDetected(MicrometerBalanceTelemetry.ANOMALY_DLQ_PUBLISHED, context)
        telemetry.anomalyDetected("RETRY_ATTEMPT_1", context)
        telemetry.anomalyDetected("RETRY_ATTEMPT_2", context)

        assertEquals(1.0, registry.find(MicrometerBalanceTelemetry.EVENTS_KEY_MISMATCH).counter()?.count())
        assertEquals(1.0, registry.find(MicrometerBalanceTelemetry.DLQ_PUBLISHED).counter()?.count())
        assertEquals(2, registry.find(MicrometerBalanceTelemetry.RETRIES).counters().size)
    }

    @Test
    fun `no metric is ever tagged with an account or transaction id`() {
        telemetry.eventReceived(context)
        telemetry.outcomeRecorded(ProcessingOutcome.Applied(TestFixtures.balance()), context, Duration.ofMillis(3))
        telemetry.unsupportedMessage("NO_TRANSACTION_BLOCK", context)
        telemetry.persistenceFailed(context, transient = true, cause = RuntimeException("boom"))
        telemetry.anomalyDetected(MicrometerBalanceTelemetry.ANOMALY_KEY_MISMATCH, context)

        val tagValues = registry.meters.flatMap { it.id.tags }.map { it.value }

        // Both identifiers are unbounded. An unbounded Prometheus tag is a cardinality explosion
        // waiting for production traffic — attribution belongs in the logs, via the MDC.
        assertTrue(
            tagValues.none { it == TestFixtures.ACCOUNT_ID || it == TestFixtures.TRANSACTION_ID },
            "a high-cardinality identifier reached a metric tag: $tagValues",
        )
    }

    @Test
    fun `received events are counted`() {
        telemetry.eventReceived(context)
        telemetry.eventReceived(context)

        assertEquals(2.0, registry.counter(MicrometerBalanceTelemetry.EVENTS_RECEIVED).count())
    }
}
