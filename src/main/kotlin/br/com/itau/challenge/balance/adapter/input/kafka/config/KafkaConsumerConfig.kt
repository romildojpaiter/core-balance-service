package br.com.itau.challenge.balance.adapter.input.kafka.config

import br.com.itau.challenge.balance.adapter.input.kafka.exception.UnprocessableEventException
import br.com.itau.challenge.balance.port.output.BalanceTelemetry
import br.com.itau.challenge.balance.port.output.TransientProcessingException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.MDC
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.BackOffExecution
import java.util.concurrent.ThreadLocalRandom

/**
 * The container's processing semantics: when an offset may be committed, how often a failure is
 * retried, and where a message goes when it cannot be processed at all.
 *
 * These three decisions live in one file because they are one policy. Splitting them is how systems
 * end up committing offsets the DLQ never accepted.
 *
 * Each piece is exposed as its own function rather than being buried inside the factory method. A
 * configuration whose parts cannot be inspected is a configuration that can only be verified in
 * production, and every rule here is the kind that looks right and behaves wrong.
 */
@Configuration
@EnableConfigurationProperties(BalanceKafkaProperties::class)
class KafkaConsumerConfig(
    private val properties: BalanceKafkaProperties,
) {

    /**
     * `AckMode.RECORD` with auto-commit off: the container commits **after** the listener returns
     * normally, and does not commit when it throws (FR-034, INV-007).
     *
     * `MANUAL_IMMEDIATE` was considered and rejected. It would require coordinating an
     * `Acknowledgment` by hand with the error handler's `ackAfterHandle`, producing two places that
     * decide whether an offset commits — more code and more risk for identical semantics.
     */
    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
        kafkaTemplate: KafkaTemplate<String, String>,
        telemetry: BalanceTelemetry,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConsumerFactory(consumerFactory)
        factory.setConcurrency(properties.concurrency)
        factory.containerProperties.ackMode = ContainerProperties.AckMode.RECORD
        factory.setCommonErrorHandler(errorHandler(kafkaTemplate, telemetry))
        return factory
    }

    /**
     * The single retry authority (ADR-005). The AWS SDK's own retries are switched off in
     * `DynamoDbConfig` so the budget declared here is the real one — with both active the effective
     * count would be twelve, and the recovery window of SC-008 would stop being calculable.
     */
    @Bean
    fun errorHandler(
        kafkaTemplate: KafkaTemplate<String, String>,
        telemetry: BalanceTelemetry,
    ): DefaultErrorHandler {
        val handler = DefaultErrorHandler(deadLetterRecoverer(kafkaTemplate, telemetry), retryBackOff())

        // Built from the constant below so the configured behaviour and the documented policy cannot
        // drift apart.
        handler.setClassifications(RETRY_CLASSIFICATIONS, RETRYABLE_BY_DEFAULT)

        handler.setRetryListeners(
            { record, _, attempt -> telemetry.anomalyDetected("RETRY_ATTEMPT_$attempt", contextOf(record)) },
        )
        return handler
    }

    /**
     * Exponential backoff with additive jitter.
     *
     * The jitter is not decoration. Without it the K consumers that hit the same DynamoDB throttle
     * all retry at the same instant, re-creating the burst that caused the throttle — which then
     * sustains itself. Spreading the retries breaks that loop.
     *
     * Written as a small [BackOff] rather than by configuring `ExponentialBackOff`, whose jitter
     * setter is not available across the Spring versions this project must build against (risk
     * R-04). Dropping the jitter instead was not an option.
     *
     * Total window is roughly 200 + 400 + 800 ms ≈ 1.4 s — two orders of magnitude under the default
     * `max.poll.interval.ms` of five minutes, so retrying can never starve the poll loop into a
     * rebalance.
     */
    @Bean
    fun retryBackOff(): BackOff =
        BackOff {
            object : BackOffExecution {
                private var attempts = 0
                private var interval = properties.retry.initialInterval.toMillis()

                override fun nextBackOff(): Long {
                    // maxAttempts counts the initial delivery, so the number of waits is one fewer.
                    if (++attempts >= properties.retry.maxAttempts) return BackOffExecution.STOP
                    val current = interval.coerceAtMost(properties.retry.maxInterval.toMillis())
                    interval = properties.retry.multiplier.multiply(interval.toBigDecimal()).toLong()
                    val jitterMillis = properties.retry.jitter.toMillis()
                    val offset = if (jitterMillis > 0) ThreadLocalRandom.current().nextLong(jitterMillis) else 0
                    return current + offset
                }
            }
        }

    /**
     * Partition `-1` lets the producer choose by key hash, so the DLQ keeps the per-account
     * grouping. The recoverer's default — reusing the source partition number — is rejected because
     * it couples the DLQ's partition count to the input topic's, and a DLQ with fewer partitions
     * would turn one bad message into a permanently stuck partition.
     */
    fun deadLetterDestination(): TopicPartition = TopicPartition(properties.dlqTopic, -1)

    /**
     * `INVALID_MESSAGE` when the payload itself was the problem, `PERMANENT_FAILURE` when processing
     * failed for any other reason that exhausted its retries. The cause chain is walked because the
     * container wraps the original exception before the recoverer sees it.
     */
    fun failureReasonOf(exception: Throwable?): String =
        if (unprocessableCauseOf(exception) != null) REASON_INVALID_MESSAGE else REASON_PERMANENT_FAILURE

    /**
     * What an operator reads first when triaging the DLQ.
     *
     * For an invalid payload it is the field that invalidated it; for anything else it falls back to
     * the exception's own message. "Listener method threw exception" — the wrapper's text — helps
     * nobody.
     */
    fun failureMessageOf(exception: Throwable?): String =
        unprocessableCauseOf(exception)?.reason
            ?: exception?.message
            ?: exception?.javaClass?.name
            ?: REASON_PERMANENT_FAILURE

    /** The class that actually failed, unwrapped from the container's `ListenerExecutionFailedException`. */
    fun failureClassOf(exception: Throwable?): String =
        (unprocessableCauseOf(exception) ?: exception)?.javaClass?.name ?: REASON_PERMANENT_FAILURE

    /** The original parse failure, if there was one, from anywhere in the wrapping chain. */
    fun unprocessableCauseOf(exception: Throwable?): UnprocessableEventException? =
        generateSequence(exception) { it.cause }
            .filterIsInstance<UnprocessableEventException>()
            .firstOrNull()

    /**
     * The destination for an invalid message and for exhausted retries — and for nothing else. A
     * message from another flow never arrives here, because FR-004a keeps it off the error path
     * entirely.
     */
    private fun deadLetterRecoverer(
        kafkaTemplate: KafkaTemplate<String, String>,
        telemetry: BalanceTelemetry,
    ): DeadLetterPublishingRecoverer {
        val recoverer =
            DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
                telemetry.anomalyDetected(ANOMALY_DLQ_PUBLISHED, contextOf(record))
                deadLetterDestination()
            }

        recoverer.setHeadersFunction { record, exception ->
            val headers: Headers = record.headers()
            headers.add(RecordHeader(FAILURE_REASON_HEADER, failureReasonOf(exception).toByteArray(Charsets.UTF_8)))
            MDC.get(MDC_CORRELATION_ID)?.let {
                headers.add(RecordHeader(CORRELATION_ID_HEADER, it.toByteArray(Charsets.UTF_8)))
            }
            headers
        }

        // The container wraps the listener's exception in a ListenerExecutionFailedException before
        // the recoverer sees it, so the default `kafka_dlt-exception-message` reads "Listener method
        // ... threw exception" — true, and useless to whoever is triaging the DLQ. FR-039 and
        // contracts/dlq-message.md require the header to name the field that invalidated the payload.
        //
        // This has to be done through `setExceptionHeadersCreator` rather than by appending in the
        // headers function: the recoverer writes its own `kafka_dlt-*` headers after that function
        // runs, so an appended value is discarded.
        recoverer.setExceptionHeadersCreator { headers, exception, _, headerNames ->
            val names = headerNames.exceptionInfo
            headers.add(RecordHeader(names.exceptionFqcn, failureClassOf(exception).toByteArray(Charsets.UTF_8)))
            headers.add(RecordHeader(names.exceptionMessage, failureMessageOf(exception).toByteArray(Charsets.UTF_8)))
            headers.add(
                RecordHeader(names.exceptionStacktrace, exception.stackTraceToString().toByteArray(Charsets.UTF_8)),
            )
        }

        // The published value stays the original payload, unserialized and unmodified, and the
        // original key is preserved — a replay must be byte-identical to what the producer sent.
        return recoverer
    }

    private fun contextOf(record: ConsumerRecord<*, *>) =
        BalanceTelemetry.EventContext(
            accountId = record.key()?.toString(),
            transactionId = null,
            topic = record.topic(),
            partition = record.partition(),
            offset = record.offset(),
        )

    companion object {
        const val FAILURE_REASON_HEADER = "x-failure-reason"
        const val EXCEPTION_MESSAGE_HEADER = "kafka_dlt-exception-message"
        const val CORRELATION_ID_HEADER = "x-correlation-id"
        const val REASON_INVALID_MESSAGE = "INVALID_MESSAGE"
        const val REASON_PERMANENT_FAILURE = "PERMANENT_FAILURE"
        const val ANOMALY_DLQ_PUBLISHED = "DLQ_PUBLISHED"

        private const val MDC_CORRELATION_ID = "correlationId"

        /**
         * Retrying an unprocessable payload can only reach the same conclusion four times while
         * delaying the whole partition; a transient failure is exactly what retrying is for.
         */
        val RETRY_CLASSIFICATIONS: Map<Class<out Throwable>, Boolean> =
            mapOf(
                UnprocessableEventException::class.java to false,
                TransientProcessingException::class.java to true,
            )

        /**
         * Anything unclassified is treated as permanent — the conservative reading, since an
         * unrecognised exception is most likely a deterministic bug that retrying cannot fix.
         */
        const val RETRYABLE_BY_DEFAULT = false
    }
}
