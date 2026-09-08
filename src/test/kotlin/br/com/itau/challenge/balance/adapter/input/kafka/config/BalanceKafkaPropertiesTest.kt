package br.com.itau.challenge.balance.adapter.input.kafka.config

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The properties validate at construction, so a misconfiguration fails at startup where an operator
 * sees it — not on the first message that happens to need the value.
 */
class BalanceKafkaPropertiesTest {

    private fun properties(
        topic: String = "transactions-events",
        dlqTopic: String = "transactions-events.dlq",
        consumerGroupId: String = "core-banking-balance-consumer",
        concurrency: Int = 3,
        retry: BalanceKafkaProperties.Retry = retry(),
    ) = BalanceKafkaProperties(topic, dlqTopic, consumerGroupId, concurrency, retry)

    private fun retry(
        maxAttempts: Int = 4,
        initialInterval: Duration = Duration.ofMillis(200),
        multiplier: BigDecimal = BigDecimal("2.0"),
        maxInterval: Duration = Duration.ofSeconds(5),
        jitter: Duration = Duration.ofMillis(100),
    ) = BalanceKafkaProperties.Retry(maxAttempts, initialInterval, multiplier, maxInterval, jitter)

    @Test
    fun `the defaults used in production are valid`() {
        assertEquals("transactions-events", properties().topic)
    }

    @Test
    fun `a blank topic, dlq topic or group id is rejected`() {
        assertFailsWith<IllegalArgumentException> { properties(topic = " ") }
        assertFailsWith<IllegalArgumentException> { properties(dlqTopic = " ") }
        assertFailsWith<IllegalArgumentException> { properties(consumerGroupId = " ") }
    }

    @Test
    fun `a dlq pointing at the input topic is rejected`() {
        // It would turn one bad message into an infinite loop: the DLQ publish re-enters the very
        // listener that failed on it.
        assertFailsWith<IllegalArgumentException> { properties(dlqTopic = "transactions-events") }
    }

    @Test
    fun `concurrency below one is rejected`() {
        assertFailsWith<IllegalArgumentException> { properties(concurrency = 0) }
    }

    @Test
    fun `a retry policy that could never make progress is rejected`() {
        assertFailsWith<IllegalArgumentException> { retry(maxAttempts = 0) }
        // A multiplier below 1 would shrink the backoff on each attempt, retrying hardest exactly
        // when the dependency is least able to answer.
        assertFailsWith<IllegalArgumentException> { retry(multiplier = BigDecimal("0.5")) }
        assertFailsWith<IllegalArgumentException> { retry(initialInterval = Duration.ofMillis(-1)) }
        assertFailsWith<IllegalArgumentException> { retry(jitter = Duration.ofMillis(-1)) }
        assertFailsWith<IllegalArgumentException> { retry(maxInterval = Duration.ofMillis(100)) }
    }
}
