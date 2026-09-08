package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.adapter.input.kafka.exception.UnprocessableEventException
import br.com.itau.challenge.balance.adapter.input.kafka.mapper.TransactionEventMessageMapper
import br.com.itau.challenge.balance.adapter.input.kafka.mapper.UnsupportedReason
import br.com.itau.challenge.balance.domain.model.TransactionEvent
import br.com.itau.challenge.balance.domain.outcome.ProcessingOutcome
import br.com.itau.challenge.balance.port.input.ProcessTransactionEventUseCase
import br.com.itau.challenge.balance.port.output.TransientProcessingException
import br.com.itau.challenge.balance.support.RecordingTelemetry
import br.com.itau.challenge.balance.support.TestFixtures
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T069: delegation, the FR-004a discard, MDC hygiene, and the absence of a second retry layer.
 */
class TransactionEventConsumerTest {

    private val telemetry = RecordingTelemetry()
    private val mapper = TransactionEventMessageMapper(JsonMapper.builder().addModule(kotlinModule()).build())

    private class RecordingUseCase(
        private val behaviour: (TransactionEvent) -> ProcessingOutcome = {
            ProcessingOutcome.Applied(TestFixtures.balance())
        },
    ) : ProcessTransactionEventUseCase {
        val processed = mutableListOf<TransactionEvent>()

        override fun process(event: TransactionEvent): ProcessingOutcome {
            processed += event
            return behaviour(event)
        }
    }

    private val validPayload =
        """{"transaction": {"id": "${TestFixtures.TRANSACTION_ID}", "status": "APPROVED", "timestamp": ${TestFixtures.TIMESTAMP_MICROS}}, """ +
            """"account": {"id": "${TestFixtures.ACCOUNT_ID}", "owner": "${TestFixtures.OWNER_ID}", """ +
            """"status": "ENABLED", "balance": {"amount": 150.00, "currency": "BRL"}}}"""

    private val accountEventPayload =
        """{"account": {"id": "${TestFixtures.ACCOUNT_ID}", "status": "ENABLED"}}"""

    private fun record(
        payload: String,
        key: String? = TestFixtures.ACCOUNT_ID,
    ) = ConsumerRecord("transactions-events", 2, 41L, key, payload)

    private fun consumer(useCase: ProcessTransactionEventUseCase) =
        TransactionEventConsumer(mapper, useCase, telemetry)

    @Test
    fun `a valid event is delegated to the use case`() {
        val useCase = RecordingUseCase()

        consumer(useCase).consume(record(validPayload))

        assertEquals(1, useCase.processed.size)
        assertEquals(TestFixtures.ACCOUNT_ID, useCase.processed.single().account.id.value)
        assertEquals(1, telemetry.received.size)
    }

    @Test
    fun `a message from another flow returns normally and never reaches the use case`() {
        val useCase = RecordingUseCase()

        // Returning normally is what authorises the container to commit the offset (FR-004a).
        consumer(useCase).consume(record(accountEventPayload))

        assertTrue(useCase.processed.isEmpty())
        assertEquals(
            listOf(UnsupportedReason.NO_TRANSACTION_BLOCK.name),
            telemetry.unsupported.map { it.first },
        )
    }

    @Test
    fun `the discard is never counted as an outcome`() {
        consumer(RecordingUseCase()).consume(record(accountEventPayload))

        // A discard is not a processing outcome: counting it as one would make the outcome metric
        // report account events as balance decisions.
        assertTrue(telemetry.outcomes.isEmpty())
    }

    @Test
    fun `an invalid payload propagates so the container can route it to the DLQ`() {
        val useCase = RecordingUseCase()

        assertFailsWith<UnprocessableEventException> { consumer(useCase).consume(record("{not json")) }
        assertTrue(useCase.processed.isEmpty())
    }

    @Test
    fun `a transient failure from the use case is not caught here`() {
        // Catching would create a second retry layer on top of the container's, which ADR-005
        // forbids: two independent policies multiply into a budget nobody can compute.
        val useCase = RecordingUseCase { throw TransientProcessingException("dynamodb is throttling") }

        assertFailsWith<TransientProcessingException> { consumer(useCase).consume(record(validPayload)) }
    }

    @Test
    fun `the MDC is cleared even when processing throws`() {
        val useCase = RecordingUseCase { throw TransientProcessingException("boom") }

        runCatching { consumer(useCase).consume(record(validPayload)) }

        // The container reuses threads. A leaked accountId attributes the next message to the wrong
        // account, which is worse than having no correlation at all.
        listOf("correlationId", "accountId", "transactionId", "kafkaTopic", "kafkaPartition", "kafkaOffset")
            .forEach { assertNull(MDC.get(it), "MDC key '$it' leaked") }
    }

    @Test
    fun `a key that disagrees with the payload is reported, and the payload wins`() {
        val useCase = RecordingUseCase()

        consumer(useCase).consume(record(validPayload, key = "a-different-account"))

        assertEquals(listOf("KEY_MISMATCH"), telemetry.anomalies.map { it.first })
        // Spec Edge Cases: the payload is the authority. The event still processes, under its own id.
        assertEquals(TestFixtures.ACCOUNT_ID, useCase.processed.single().account.id.value)
    }

    @Test
    fun `a matching key raises no anomaly`() {
        consumer(RecordingUseCase()).consume(record(validPayload))

        assertTrue(telemetry.anomalies.isEmpty())
    }
}
