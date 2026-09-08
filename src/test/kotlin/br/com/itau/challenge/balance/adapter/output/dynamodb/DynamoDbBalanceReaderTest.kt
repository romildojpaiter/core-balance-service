package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.port.output.TransientProcessingException
import br.com.itau.challenge.balance.support.TestFixtures
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T074: the read is strongly consistent, keyed, and honest about a miss.
 */
class DynamoDbBalanceReaderTest {

    private val mapper = BalanceItemMapper()

    private class CapturingClient(
        private val behaviour: (GetItemRequest) -> GetItemResponse,
    ) : DynamoDbClient {
        var captured: GetItemRequest? = null

        override fun getItem(request: GetItemRequest): GetItemResponse {
            captured = request
            return behaviour(request)
        }

        override fun serviceName(): String = "dynamodb"

        override fun close() = Unit
    }

    @Test
    fun `the read is strongly consistent and keyed by the derived partition key`() {
        val stored = TestFixtures.balance()
        val client = CapturingClient { GetItemResponse.builder().item(mapper.toItem(stored)).build() }

        val found = DynamoDbBalanceReader(client, mapper, "AccountBalances").findByAccountId(AccountId(TestFixtures.ACCOUNT_ID))

        assertEquals(stored, found)
        val request = requireNotNull(client.captured)
        // SC-005: a query issued after a processed event must observe it. An eventually consistent
        // read can legitimately return the state that preceded an acknowledged write.
        assertTrue(request.consistentRead(), "the read must be strongly consistent")
        assertEquals(
            "ACCOUNT#${TestFixtures.ACCOUNT_ID}",
            request.key().getValue(BalanceTableAttributes.PK).s(),
        )
    }

    @Test
    fun `an unknown account reads as absent, not as an empty balance`() {
        // DynamoDB returns an empty map rather than null for a miss; conflating the two would let an
        // unknown account surface as 0.00 further up.
        val client = CapturingClient { GetItemResponse.builder().build() }

        assertNull(DynamoDbBalanceReader(client, mapper, "AccountBalances").findByAccountId(AccountId("unknown")))
    }

    @Test
    fun `a server failure is translated before it leaves the adapter`() {
        val client = CapturingClient { throw InternalServerErrorException.builder().message("boom").build() }

        assertFailsWith<TransientProcessingException> {
            DynamoDbBalanceReader(client, mapper, "AccountBalances").findByAccountId(AccountId(TestFixtures.ACCOUNT_ID))
        }
    }
}
