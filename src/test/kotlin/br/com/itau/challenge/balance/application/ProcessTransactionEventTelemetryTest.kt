package br.com.itau.challenge.balance.application

import br.com.itau.challenge.balance.domain.model.TransactionStatus
import br.com.itau.challenge.balance.domain.outcome.ProcessingOutcome
import br.com.itau.challenge.balance.port.output.BalanceWriteResult
import br.com.itau.challenge.balance.support.RecordingBalanceWriter
import br.com.itau.challenge.balance.support.RecordingTelemetry
import br.com.itau.challenge.balance.support.TestFixtures
import br.com.itau.challenge.balance.port.output.BalanceWriter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T063: turns FR-054 ("every non-mutating outcome must be observable") from an aspiration into a
 * check.
 *
 * The test enumerates the [ProcessingOutcome] subclasses reflectively rather than listing them, so
 * adding a variant without telemetry breaks the build instead of quietly shipping a blind spot.
 */
class ProcessTransactionEventTelemetryTest {

    private fun scenarios(): Map<Class<out ProcessingOutcome>, BalanceWriter> =
        mapOf(
            ProcessingOutcome.Applied::class.java to RecordingBalanceWriter(),
            ProcessingOutcome.IgnoredIneligible::class.java to RecordingBalanceWriter(),
            ProcessingOutcome.Rejected::class.java to
                RecordingBalanceWriter { BalanceWriteResult.Duplicate(TestFixtures.balance()) },
        )

    @Test
    fun `every processing outcome variant is covered by a scenario`() {
        val declared =
            ProcessingOutcome::class.sealedSubclasses
                .map { it.java }
                .toSet()

        assertEquals(
            declared,
            scenarios().keys,
            "a ProcessingOutcome variant exists with no telemetry scenario — add one here and in the service",
        )
    }

    @Test
    fun `each outcome variant records telemetry exactly once`() {
        scenarios().forEach { (variant, writer) ->
            val telemetry = RecordingTelemetry()
            val service = ProcessTransactionEventService(writer, telemetry)

            val event =
                if (variant == ProcessingOutcome.IgnoredIneligible::class.java) {
                    TestFixtures.event(transaction = TestFixtures.transaction(status = TransactionStatus.NOT_APPROVED))
                } else {
                    TestFixtures.event()
                }

            val outcome = service.process(event)

            assertEquals(variant, outcome.javaClass, "scenario for $variant produced the wrong outcome")
            assertEquals(1, telemetry.outcomes.size, "$variant must record telemetry exactly once")
            assertEquals(outcome, telemetry.outcomes.single().outcome)
        }
    }

    @Test
    fun `telemetry context carries correlation ids and no transport fields`() {
        val telemetry = RecordingTelemetry()
        ProcessTransactionEventService(RecordingBalanceWriter(), telemetry).process(TestFixtures.event())

        val context = telemetry.outcomes.single().context
        assertEquals(TestFixtures.ACCOUNT_ID, context.accountId)
        assertEquals(TestFixtures.TRANSACTION_ID, context.transactionId)
        // Constitution III: the application layer does not know that Kafka exists. The consumer
        // fills these for its own calls.
        assertTrue(context.topic == null && context.partition == null && context.offset == null)
    }
}
