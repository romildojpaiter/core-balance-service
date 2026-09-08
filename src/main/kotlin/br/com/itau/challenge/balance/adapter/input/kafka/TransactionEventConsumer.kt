package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.adapter.input.kafka.mapper.MessageInterpretation
import br.com.itau.challenge.balance.adapter.input.kafka.mapper.TransactionEventMessageMapper
import br.com.itau.challenge.balance.port.input.ProcessTransactionEventUseCase
import br.com.itau.challenge.balance.port.output.BalanceTelemetry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The ingestion entry point.
 *
 * The payload arrives as a `String` rather than a bound object on purpose: keeping the original
 * bytes reachable inside the listener is what lets the DLQ carry the untouched payload (FR-039).
 * Deserializing in the container would mean a malformed message never reaches this method, and the
 * DLQ would receive a deserialization wrapper instead of what the producer actually sent.
 *
 * This method **never catches an exception to handle it**. Anything thrown propagates to the
 * container, which owns retry and the DLQ. Catching here would create a second retry layer, which
 * ADR-005 forbids — two independent policies multiply into an attempt budget nobody can compute.
 */
@Component
class TransactionEventConsumer(
    private val mapper: TransactionEventMessageMapper,
    private val useCase: ProcessTransactionEventUseCase,
    private val telemetry: BalanceTelemetry,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${balance.kafka.topic}"],
        groupId = "\${balance.kafka.consumer-group-id}",
        concurrency = "\${balance.kafka.concurrency}",
    )
    // The key type is nullable because a producer may legitimately publish without one. Nothing
    // depends on the key for correctness — `account.id` in the payload is the authority.
    fun consume(record: ConsumerRecord<String?, String>) {
        val correlationId = correlationIdOf(record)
        MDC.put(MDC_CORRELATION_ID, correlationId)
        MDC.put(MDC_TOPIC, record.topic())
        MDC.put(MDC_PARTITION, record.partition().toString())
        MDC.put(MDC_OFFSET, record.offset().toString())

        try {
            telemetry.eventReceived(contextOf(record))

            when (val interpretation = mapper.interpret(record.value())) {
                is MessageInterpretation.Unsupported -> {
                    // Returning normally is the whole mechanism of FR-004a: it is what authorises the
                    // container to commit the offset. Throwing would route a perfectly fine account
                    // event to the DLQ (matrix row 5a).
                    telemetry.unsupportedMessage(interpretation.reason.name, contextOf(record))
                    log.debug("discarded a message from another flow: {}", interpretation.reason)
                }

                is MessageInterpretation.Interpreted -> {
                    val event = interpretation.event
                    MDC.put(MDC_ACCOUNT_ID, event.account.id.value)
                    MDC.put(MDC_TRANSACTION_ID, event.transaction.id.value)
                    reportKeyMismatch(record, event.account.id.value)
                    // The outcome and its latency are recorded by the service, not here. Recording in
                    // both places would count every outcome twice.
                    useCase.process(event)
                }
            }
        } finally {
            // Always cleared. The listener container reuses threads across messages, and MDC leaking
            // from one message to the next attributes an event to the wrong account — worse than
            // having no correlation at all, because it is wrong rather than merely absent.
            MDC.remove(MDC_CORRELATION_ID)
            MDC.remove(MDC_TOPIC)
            MDC.remove(MDC_PARTITION)
            MDC.remove(MDC_OFFSET)
            MDC.remove(MDC_ACCOUNT_ID)
            MDC.remove(MDC_TRANSACTION_ID)
        }
    }

    /**
     * The payload is the authority when the message key disagrees with `account.id` (spec Edge
     * Cases). The mismatch is still counted: it means the producer's partitioning no longer matches
     * the data, which silently costs the per-account ordering the key was supposed to buy.
     */
    private fun reportKeyMismatch(
        record: ConsumerRecord<String?, String>,
        accountId: String,
    ) {
        val key = record.key()
        if (key != null && key != accountId) {
            telemetry.anomalyDetected(ANOMALY_KEY_MISMATCH, contextOf(record, accountId))
        }
    }

    private fun correlationIdOf(record: ConsumerRecord<String?, String>): String =
        record
            .headers()
            .lastHeader(CORRELATION_ID_HEADER)
            ?.value()
            ?.toString(Charsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

    private fun contextOf(
        record: ConsumerRecord<String?, String>,
        accountId: String? = null,
    ) = BalanceTelemetry.EventContext(
        accountId = accountId ?: record.key(),
        transactionId = MDC.get(MDC_TRANSACTION_ID),
        topic = record.topic(),
        partition = record.partition(),
        offset = record.offset(),
    )

    private companion object {
        const val CORRELATION_ID_HEADER = "x-correlation-id"
        const val ANOMALY_KEY_MISMATCH = "KEY_MISMATCH"

        const val MDC_CORRELATION_ID = "correlationId"
        const val MDC_ACCOUNT_ID = "accountId"
        const val MDC_TRANSACTION_ID = "transactionId"
        const val MDC_TOPIC = "kafkaTopic"
        const val MDC_PARTITION = "kafkaPartition"
        const val MDC_OFFSET = "kafkaOffset"
    }
}
