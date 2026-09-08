package br.com.itau.challenge.balance.adapter.input.kafka.config

import br.com.itau.challenge.balance.adapter.input.kafka.exception.UnprocessableEventException
import br.com.itau.challenge.balance.port.output.TransientProcessingException
import br.com.itau.challenge.balance.support.RecordingTelemetry
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.util.backoff.BackOffExecution
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T070: the container's processing semantics, asserted without a broker.
 *
 * Every rule here is configuration, and configuration is exactly the kind of thing that looks right
 * and behaves wrong — a `<=` where a `<` was meant, a partition number that couples two topics, a
 * missing jitter that turns a throttle into a self-sustaining one. None of it fails a compiler.
 */
class KafkaConsumerConfigTest {

    private val properties =
        BalanceKafkaProperties(
            topic = "transactions-events",
            dlqTopic = "transactions-events.dlq",
            consumerGroupId = "core-banking-balance-consumer",
            concurrency = 3,
            retry =
                BalanceKafkaProperties.Retry(
                    maxAttempts = 4,
                    initialInterval = Duration.ofMillis(200),
                    multiplier = java.math.BigDecimal("2.0"),
                    maxInterval = Duration.ofSeconds(5),
                    jitter = Duration.ofMillis(100),
                ),
        )

    private val config = KafkaConsumerConfig(properties)
    private val telemetry = RecordingTelemetry()

    private fun template(): KafkaTemplate<String, String> = KafkaTemplate(DefaultKafkaProducerFactory(emptyMap()))

    @Test
    fun `the container commits per record, after the listener returns`() {
        val factory = config.kafkaListenerContainerFactory(DefaultKafkaConsumerFactory(emptyMap()), template(), telemetry)

        // AckMode.RECORD with auto-commit off: the offset advances only after a normal return, so an
        // exception leaves it uncommitted and the message is redelivered (FR-034, INV-007).
        assertEquals(ContainerProperties.AckMode.RECORD, factory.containerProperties.ackMode)
    }

    @Test
    fun `an invalid message is never retried and a transient failure always is`() {
        assertEquals(false, KafkaConsumerConfig.RETRY_CLASSIFICATIONS[UnprocessableEventException::class.java])
        assertEquals(true, KafkaConsumerConfig.RETRY_CLASSIFICATIONS[TransientProcessingException::class.java])
    }

    @Test
    fun `an unclassified exception is treated as permanent`() {
        assertFalse(
            KafkaConsumerConfig.RETRYABLE_BY_DEFAULT,
            "an unrecognised exception is most likely a deterministic bug; retrying only delays the partition",
        )
        // The handler is configured from these two constants and from nothing else, so the policy
        // asserted here cannot drift from the policy in force.
        config.errorHandler(template(), telemetry)
    }

    @Test
    fun `the backoff grows exponentially and stops at the attempt budget`() {
        val execution = config.retryBackOff().start()

        val waits = generateSequence { execution.nextBackOff() }.takeWhile { it != BackOffExecution.STOP }.toList()

        // 200 / 400 / 800 ms plus up to 100 ms of jitter each: a window near 1.4 s, two orders of
        // magnitude under max.poll.interval.ms, so retrying can never trigger a rebalance.
        assertEquals(3, waits.size, "maxAttempts = 4 means one delivery plus three redeliveries")
        assertTrue(waits[0] in 200..299, "first backoff was ${waits[0]}")
        assertTrue(waits[1] in 400..499, "second backoff was ${waits[1]}")
        assertTrue(waits[2] in 800..899, "third backoff was ${waits[2]}")
    }

    @Test
    fun `the backoff is capped so a long multiplier chain cannot exceed the maximum`() {
        val capped =
            KafkaConsumerConfig(
                properties.copy(
                    retry = properties.retry.copy(maxAttempts = 10, maxInterval = Duration.ofMillis(500), jitter = Duration.ZERO),
                ),
            )
        val execution = capped.retryBackOff().start()

        val waits = generateSequence { execution.nextBackOff() }.takeWhile { it != BackOffExecution.STOP }.toList()

        assertTrue(waits.all { it <= 500 }, "a wait exceeded maxInterval: $waits")
    }

    @Test
    fun `jitter actually varies between executions`() {
        val backOff = config.retryBackOff()

        // Without jitter, K consumers hitting the same throttle retry in lockstep and sustain it.
        val samples = (1..40).map { backOff.start().nextBackOff() }.toSet()

        assertTrue(samples.size > 1, "the backoff produced no variation: jitter is not being applied")
    }

    @Test
    fun `the dead letter destination lets the producer choose the partition by key`() {
        val destination = config.deadLetterDestination()

        assertEquals("transactions-events.dlq", destination.topic())
        // -1 makes the producer hash the key, so the DLQ keeps the per-account grouping and its
        // partition count stays independent of the input topic's.
        assertEquals(-1, destination.partition())
    }

    @Test
    fun `the failure reason distinguishes a bad payload from a failed process`() {
        val invalid = UnprocessableEventException("'transaction.id' is missing", "{}")

        assertEquals(KafkaConsumerConfig.REASON_INVALID_MESSAGE, config.failureReasonOf(invalid))
        // The container wraps the original exception before the recoverer sees it, so the cause
        // chain has to be walked rather than the top-level type inspected.
        assertEquals(
            KafkaConsumerConfig.REASON_INVALID_MESSAGE,
            config.failureReasonOf(IllegalStateException("listener failed", invalid)),
        )
        assertEquals(
            KafkaConsumerConfig.REASON_PERMANENT_FAILURE,
            config.failureReasonOf(TransientProcessingException("exhausted")),
        )
    }

    @Test
    fun `the field that invalidated the payload survives the container's wrapping`() {
        val invalid = UnprocessableEventException("'transaction.timestamp' is missing", "{}")
        val wrapped = IllegalStateException("Listener method threw exception", invalid)

        // The DLQ message is all an operator has when triaging, so it must name the field rather than
        // report that some listener failed (FR-039).
        assertEquals(invalid, config.unprocessableCauseOf(wrapped))
        assertEquals("'transaction.timestamp' is missing", config.failureMessageOf(wrapped))
        assertEquals(UnprocessableEventException::class.java.name, config.failureClassOf(wrapped))
        assertNull(config.unprocessableCauseOf(TransientProcessingException("exhausted")))
    }

    @Test
    fun `a failure that is not a bad payload still reports something usable`() {
        val transient = TransientProcessingException("dynamodb is throttling")

        assertEquals("dynamodb is throttling", config.failureMessageOf(transient))
        assertEquals(TransientProcessingException::class.java.name, config.failureClassOf(transient))
        // A message-less exception must not produce an empty header.
        assertEquals(IllegalStateException::class.java.name, config.failureMessageOf(IllegalStateException()))
        assertEquals(KafkaConsumerConfig.REASON_PERMANENT_FAILURE, config.failureMessageOf(null))
        assertEquals(KafkaConsumerConfig.REASON_PERMANENT_FAILURE, config.failureClassOf(null))
    }

    @Test
    fun `the dlq topic must differ from the input topic`() {
        // A DLQ pointing at the input topic turns one bad message into an infinite loop.
        val thrown =
            runCatching { properties.copy(dlqTopic = properties.topic) }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException, "expected the properties to reject a self-referential DLQ")
    }
}
