package br.com.itau.challenge.balance

import br.com.itau.challenge.balance.adapter.output.dynamodb.BalanceTableAttributes
import br.com.itau.challenge.balance.support.eventually
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.kafka.core.KafkaTemplate
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole path, once: shuffled ingestion through persistence to the REST answer.
 *
 * The stream deliberately mixes every case the specification distinguishes — out of order, a
 * duplicate, a declined transaction, a disabled account, and a message from another flow — for the
 * *same* account. Any one of them handled wrongly changes the answer, so a single assertion on the
 * final body covers all five rules at once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class EndToEndBalanceFlowIntegrationTest {

    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired private lateinit var dynamoDbClient: DynamoDbClient

    @Value("\${balance.kafka.topic}") private lateinit var topic: String

    @Value("\${dynamodb.table-name}") private lateinit var tableName: String

    @LocalServerPort private var port: Int = 0

    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()
    private val accountId = "acc-${UUID.randomUUID()}"

    @AfterEach
    fun cleanUp() {
        dynamoDbClient.deleteItem(
            DeleteItemRequest
                .builder()
                .tableName(tableName)
                .key(mapOf(BalanceTableAttributes.PK to AttributeValue.fromS("ACCOUNT#$accountId")))
                .build(),
        )
    }

    private fun event(
        micros: Long,
        amount: String,
        transactionId: String = "tx-$micros",
        transactionStatus: String = "APPROVED",
        accountStatus: String = "ENABLED",
    ) = """{"transaction": {"id": "$transactionId", "type": "CREDIT", "amount": 10.00, "currency": "BRL", """ +
        """"status": "$transactionStatus", "timestamp": $micros}, "account": {"id": "$accountId", """ +
        """"owner": "own-e2e", "created_at": 1634874339000000, "status": "$accountStatus", """ +
        """"balance": {"amount": $amount, "currency": "BRL"}}}"""

    private fun get(path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `a shuffled stream produces the newest eligible snapshot over REST`() {
        val winner = 1_751_749_453_433_589L

        listOf(
            event(1_000_000_000_000L, "100.00"),
            // Out of order: the newest arrives in the middle of the stream.
            event(winner, "183.12", transactionId = "tx-winner"),
            event(1_500_000_000_000L, "150.00"),
            // A duplicate of the winner: must be recognised, not reapplied.
            event(winner, "183.12", transactionId = "tx-winner"),
            // Newer than the winner, but declined: must never advance the marker.
            event(winner + 10_000_000, "999.99", transactionStatus = "DECLINED"),
            // Newer still, but the account is disabled: same treatment.
            event(winner + 20_000_000, "888.88", accountStatus = "DISABLED"),
        ).forEach { kafkaTemplate.send(topic, accountId, it) }

        // A message from another flow, for the same account, in the middle of the stream.
        kafkaTemplate.send(
            topic,
            accountId,
            """{"account": {"id": "$accountId", "owner": "own-e2e", "created_at": 1634874339000000, "status": "ENABLED"}}""",
        )

        eventually(Duration.ofSeconds(60)) {
            val response = get("/balances/$accountId")
            assertEquals(200, response.statusCode())

            val body = json.readValue(response.body(), Map::class.java)

            // 183.12 — not 999.99, not 888.88, not 150.00. Every rule in one number.
            assertEquals(setOf("id", "owner", "balance", "updated_at"), body.keys)
            assertEquals(accountId, body["id"])
            assertEquals("own-e2e", body["owner"])
            assertEquals("2025-07-05T18:04:13.433-03:00", body["updated_at"])
            // FR-043a: the winning transaction is still persisted and still logged, but the response
            // no longer carries it. Asserted as an absence so a re-added field fails here.
            assertTrue("lastTransactionId" !in body.keys, "lastTransactionId must not be exposed")

            val balance = body["balance"] as Map<*, *>
            assertEquals("BRL", balance["currency"])
            // Checked on the raw body: a parsed 183.12 and a parsed "183.12" are indistinguishable
            // once they are Java objects, and the scale is what makes the number form admissible.
            assertTrue(
                response.body().contains(""""amount":183.12"""),
                "the amount must cross as an unquoted JSON number with its scale: ${response.body()}",
            )
        }
    }

    @Test
    fun `an account the system has never seen is 404, never a zero balance`() {
        val response = get("/balances/acc-${UUID.randomUUID()}")

        assertEquals(404, response.statusCode())
        assertEquals("BALANCE_NOT_FOUND", (json.readValue(response.body(), Map::class.java))["code"])
    }

    @Test
    fun `a malformed account id is rejected without touching the datastore`() {
        val response = get("/balances/conta%2042")

        assertEquals(400, response.statusCode())
        assertEquals("INVALID_ACCOUNT_ID", (json.readValue(response.body(), Map::class.java))["code"])
    }
}
