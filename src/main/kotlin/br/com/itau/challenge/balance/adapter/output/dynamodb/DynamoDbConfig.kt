package br.com.itau.challenge.balance.adapter.output.dynamodb

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.DefaultRetryStrategy
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI
import java.time.Duration

@Configuration
class DynamoDbConfig {

    /**
     * The client deliberately performs **no retries of its own**.
     *
     * ADR-005 makes the Kafka container the single retry authority. Left at its default the SDK
     * retries three times, and with four container attempts on top the real budget becomes twelve —
     * at which point the recovery window of SC-008 stops being something anyone can calculate, and a
     * slow DynamoDB can hold a partition past `max.poll.interval.ms` and trigger a rebalance.
     * Bounding each call also matters: without `apiCallTimeout` a hung connection blocks the
     * consumer thread indefinitely, and no retry policy can rescue a call that never returns.
     */
    @Bean
    fun dynamoDbClient(
        @Value("\${dynamodb.endpoint}") endpoint: String,
        @Value("\${dynamodb.region}") region: String,
    ): DynamoDbClient =
        DynamoDbClient
            .builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")),
            ).overrideConfiguration(
                ClientOverrideConfiguration
                    .builder()
                    .apiCallAttemptTimeout(Duration.ofSeconds(1))
                    .apiCallTimeout(Duration.ofSeconds(2))
                    .retryStrategy(DefaultRetryStrategy.doNotRetry())
                    .build(),
            ).build()
}
