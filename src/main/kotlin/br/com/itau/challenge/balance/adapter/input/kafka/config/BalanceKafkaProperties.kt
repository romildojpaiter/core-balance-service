package br.com.itau.challenge.balance.adapter.input.kafka.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration

/**
 * The only place in the code that knows topic names, the consumer group and the retry policy.
 *
 * Binding them as typed properties rather than reading `@Value` at each use site means a missing or
 * malformed value fails at startup, where an operator sees it, instead of on the first message that
 * happens to need it.
 */
@ConfigurationProperties("balance.kafka")
data class BalanceKafkaProperties(
    val topic: String,
    val dlqTopic: String,
    val consumerGroupId: String,
    val concurrency: Int,
    val retry: Retry,
) {
    init {
        require(topic.isNotBlank()) { "balance.kafka.topic must not be blank" }
        require(dlqTopic.isNotBlank()) { "balance.kafka.dlq-topic must not be blank" }
        require(dlqTopic != topic) { "balance.kafka.dlq-topic must differ from the input topic" }
        require(consumerGroupId.isNotBlank()) { "balance.kafka.consumer-group-id must not be blank" }
        require(concurrency >= 1) { "balance.kafka.concurrency must be at least 1" }
    }

    data class Retry(
        val maxAttempts: Int,
        val initialInterval: Duration,
        // BigDecimal rather than Double: the codebase bans binary floating point outright
        // (Constitution X, enforced by MonetaryPrecisionArchitectureTest). An exception list would
        // weaken a rule whose whole value is that it has none — and the type costs nothing here.
        val multiplier: BigDecimal,
        val maxInterval: Duration,
        val jitter: Duration,
    ) {
        init {
            require(maxAttempts >= 1) { "balance.kafka.retry.max-attempts must be at least 1" }
            require(multiplier >= BigDecimal.ONE) { "balance.kafka.retry.multiplier must be at least 1" }
            require(!initialInterval.isNegative) { "balance.kafka.retry.initial-interval must not be negative" }
            require(maxInterval >= initialInterval) {
                "balance.kafka.retry.max-interval must not be smaller than the initial interval"
            }
            require(!jitter.isNegative) { "balance.kafka.retry.jitter must not be negative" }
        }
    }
}
