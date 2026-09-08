package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.port.output.BalanceWriteResult
import br.com.itau.challenge.balance.support.RecordingTelemetry
import br.com.itau.challenge.balance.support.TestFixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest
import java.math.BigDecimal
import java.net.URI
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The ordering and idempotency rules, exercised against a real DynamoDB.
 *
 * Constitution XIV requires these to be proven at the persistence level rather than in memory,
 * because that is where they actually live: the guarantee is a property of the conditional
 * expression as DynamoDB evaluates it, and a mocked client can only prove that we send the string we
 * think we send.
 */
class DynamoDbBalanceIntegrationTest {

    private val client: DynamoDbClient =
        DynamoDbClient
            .builder()
            .endpointOverride(URI.create(System.getenv("DYNAMODB_ENDPOINT") ?: "http://localhost:8000"))
            .region(Region.of("us-east-1"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
            .build()

    private val tableName = System.getenv("BALANCE_TABLE_NAME") ?: "AccountBalances"
    private val itemMapper = BalanceItemMapper()
    private val telemetry = RecordingTelemetry()
    private val writer = DynamoDbBalanceWriter(client, itemMapper, telemetry, tableName)
    private val reader = DynamoDbBalanceReader(client, itemMapper, tableName)

    private lateinit var accountId: String

    @BeforeEach
    fun freshAccount() {
        // A unique account per test: the table is shared with every other integration test, and
        // leaking state between them would produce failures that depend on execution order.
        accountId = "acc-${UUID.randomUUID()}"
    }

    @AfterEach
    fun cleanUp() {
        client.deleteItem(
            DeleteItemRequest
                .builder()
                .tableName(tableName)
                .key(mapOf(BalanceTableAttributes.PK to AttributeValue.fromS("ACCOUNT#$accountId")))
                .build(),
        )
    }

    private fun balance(
        micros: Long,
        transactionId: String = "tx-$micros",
        amount: String = "150.00",
    ) = TestFixtures.balance(accountId = accountId, amount = amount, micros = micros, transactionId = transactionId)

    @Test
    fun `the first event for an account is applied`() {
        assertEquals(BalanceWriteResult.Applied, writer.save(balance(1_000)))
        assertEquals(BigDecimal("150.00"), reader.findByAccountId(AccountId(accountId))?.money?.amount)
    }

    @Test
    fun `a newer event replaces the persisted state`() {
        writer.save(balance(1_000, amount = "150.00"))

        assertEquals(BalanceWriteResult.Applied, writer.save(balance(2_000, amount = "275.50")))
        assertEquals(BigDecimal("275.50"), reader.findByAccountId(AccountId(accountId))?.money?.amount)
    }

    @Test
    fun `an older event is rejected as stale and changes nothing`() {
        writer.save(balance(2_000, amount = "275.50"))

        val result = writer.save(balance(1_000, amount = "150.00"))

        val stale = assertIs<BalanceWriteResult.Stale>(result)
        // ALL_OLD gives the rejection its name without a second round trip that would race.
        assertEquals(2_000L, stale.persisted.asOf.micros)
        assertEquals(BigDecimal("275.50"), reader.findByAccountId(AccountId(accountId))?.money?.amount)
    }

    @Test
    fun `a different transaction at the same instant is rejected as a tie`() {
        writer.save(balance(2_000, transactionId = "tx-first", amount = "275.50"))

        val result = writer.save(balance(2_000, transactionId = "tx-second", amount = "999.99"))

        assertIs<BalanceWriteResult.TimestampTie>(result)
        // Decision A-03: strictly greater, so the first writer wins and no tiebreak is invented.
        assertEquals(BigDecimal("275.50"), reader.findByAccountId(AccountId(accountId))?.money?.amount)
    }

    @Test
    fun `redelivering the same event is a duplicate, not a tie`() {
        val event = balance(2_000, transactionId = "tx-same")
        writer.save(event)

        val result = writer.save(event)

        val duplicate = assertIs<BalanceWriteResult.Duplicate>(result)
        assertEquals("tx-same", duplicate.persisted.lastTransactionId.value)
    }

    @Test
    fun `a consistent read returns what was just written`() {
        // SC-005: an eventually consistent read could legitimately return the previous state here.
        writer.save(balance(3_000, amount = "42.00"))

        assertEquals(BigDecimal("42.00"), reader.findByAccountId(AccountId(accountId))?.money?.amount)
    }

    @Test
    fun `an unknown account reads as absent`() {
        assertNull(reader.findByAccountId(AccountId("acc-${UUID.randomUUID()}")))
    }

    @Test
    fun `the decimal scale survives the round trip through the datastore`() {
        writer.save(balance(1_000, amount = "150.00"))

        val persisted = reader.findByAccountId(AccountId(accountId))

        // Stored as N, DynamoDB would return "150" here and FR-044's two places would be gone.
        assertEquals("150.00", persisted?.money?.toPlainString())
    }

    @Test
    fun `a negative balance survives the round trip`() {
        writer.save(balance(1_000, amount = "-50.25"))

        assertEquals("-50.25", reader.findByAccountId(AccountId(accountId))?.money?.toPlainString())
    }
}
