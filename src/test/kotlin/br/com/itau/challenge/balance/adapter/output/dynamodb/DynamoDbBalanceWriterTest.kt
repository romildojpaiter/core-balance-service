package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.Balance
import br.com.itau.challenge.balance.port.output.BalanceWriteResult
import br.com.itau.challenge.balance.port.output.TransientProcessingException
import br.com.itau.challenge.balance.support.RecordingTelemetry
import br.com.itau.challenge.balance.support.TestFixtures
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T071 + T072 + T073: the exact conditional expression, the rejection taxonomy, and the error
 * translation.
 *
 * Asserting the expression *text* is unusual and deliberate. It is the only place in the system
 * where correctness lives in a string interpreted by another process — no compiler checks it, and a
 * silent edit from `<` to `<=` would let two events bearing the same instant overwrite each other
 * with no test failing anywhere else.
 */
class DynamoDbBalanceWriterTest {

    private val mapper = BalanceItemMapper()
    private val telemetry = RecordingTelemetry()

    private fun writerOver(client: DynamoDbClient) =
        DynamoDbBalanceWriter(client, mapper, telemetry, "AccountBalances")

    private class CapturingClient(
        private val behaviour: (PutItemRequest) -> PutItemResponse,
    ) : DynamoDbClient {
        var captured: PutItemRequest? = null

        override fun putItem(request: PutItemRequest): PutItemResponse {
            captured = request
            return behaviour(request)
        }

        override fun serviceName(): String = "dynamodb"

        override fun close() = Unit
    }

    @Test
    fun `the conditional expression compares strictly greater and aliases both names`() {
        val client = CapturingClient { PutItemResponse.builder().build() }

        writerOver(client).save(TestFixtures.balance())

        val request = requireNotNull(client.captured)
        assertEquals(
            "attribute_not_exists(#pk) OR #lastEventTimestamp < :incomingTimestamp",
            request.conditionExpression(),
        )
        assertEquals(
            mapOf("#pk" to "pk", "#lastEventTimestamp" to "lastEventTimestamp"),
            request.expressionAttributeNames(),
        )
        // `timestamp` is a DynamoDB reserved word; without the alias the expression fails at runtime,
        // never at compile time.
        assertEquals(
            TestFixtures.TIMESTAMP_MICROS.toString(),
            request.expressionAttributeValues().getValue(":incomingTimestamp").n(),
        )
    }

    @Test
    fun `the rejected item is requested back so the rejection can be named`() {
        val client = CapturingClient { PutItemResponse.builder().build() }

        writerOver(client).save(TestFixtures.balance())

        assertEquals(
            ReturnValuesOnConditionCheckFailure.ALL_OLD,
            requireNotNull(client.captured).returnValuesOnConditionCheckFailure(),
        )
    }

    @Test
    fun `no read precedes the write`() {
        // FR-028: a GetItem before the PutItem would reintroduce the race the condition eliminates.
        // This client throws on every other operation, so any read would fail the test.
        val client = CapturingClient { PutItemResponse.builder().build() }

        assertEquals(BalanceWriteResult.Applied, writerOver(client).save(TestFixtures.balance()))
    }

    @Test
    fun `a redelivery of the same transaction is a duplicate, even with a tied timestamp`() {
        val persisted = TestFixtures.balance()
        val result = writerOver(rejectingWith(persisted)).save(TestFixtures.balance())

        // Identity is checked before the timestamp precisely for this case: a redelivery necessarily
        // ties, and reporting it as a tie would hide idempotency behind a conflict-sounding name.
        assertEquals(BalanceWriteResult.Duplicate(persisted), result)
    }

    @Test
    fun `a different transaction at the same instant is a timestamp tie`() {
        val persisted = TestFixtures.balance(transactionId = "a-different-transaction")
        val result = writerOver(rejectingWith(persisted)).save(TestFixtures.balance())

        assertEquals(BalanceWriteResult.TimestampTie(persisted), result)
    }

    @Test
    fun `an older event against a newer persisted state is stale`() {
        val persisted =
            TestFixtures.balance(
                transactionId = "a-later-transaction",
                micros = TestFixtures.TIMESTAMP_MICROS + 1_000,
            )
        val result = writerOver(rejectingWith(persisted)).save(TestFixtures.balance())

        assertEquals(BalanceWriteResult.Stale(persisted), result)
    }

    @Test
    fun `an unavailable ALL_OLD item degrades granularity, never correctness`() {
        val client =
            CapturingClient {
                throw ConditionalCheckFailedException.builder().message("rejected").build()
            }

        val result = writerOver(client).save(TestFixtures.balance())

        // The write still did not happen — which is the guarantee. Only the label is coarser.
        assertTrue(result is BalanceWriteResult.Stale)
    }

    @Test
    fun `throughput and server failures become transient, and the aws type never escapes`() {
        val failures =
            listOf(
                ProvisionedThroughputExceededException.builder().message("throttled").build(),
                RequestLimitExceededException.builder().message("limit").build(),
                InternalServerErrorException.builder().message("boom").build(),
            )

        failures.forEach { failure ->
            val client = CapturingClient { throw failure }

            val thrown =
                assertFailsWith<TransientProcessingException> { writerOver(client).save(TestFixtures.balance()) }

            // Constitution III: the input adapter classifies retryability on this type and must never
            // need to import software.amazon to do it.
            assertEquals(failure, thrown.cause)
        }

        assertEquals(failures.size, telemetry.persistenceFailures.size)
        assertTrue(telemetry.persistenceFailures.all { it.second }, "all three are transient")
    }

    private fun rejectingWith(persisted: Balance) =
        CapturingClient {
            throw ConditionalCheckFailedException
                .builder()
                .message("rejected")
                .item(mapper.toItem(persisted))
                .build()
        }
}
