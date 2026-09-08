package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.adapter.output.dynamodb.BalanceTableAttributes
import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.port.output.BalanceReader
import br.com.itau.challenge.balance.support.eventually
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.kafka.core.KafkaTemplate
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The production `@KafkaListener` consuming from a real broker and writing to a real table.
 *
 * Nothing here is stubbed on purpose: the unit tests already prove each piece in isolation, and the
 * only thing left to doubt is the wiring — that the listener is registered on the configured topic,
 * with the configured group, and that its output reaches the table the reader queries.
 */
@SpringBootTest
@ActiveProfiles("integration")
class TransactionEventConsumerIntegrationTest {

    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired private lateinit var balanceReader: BalanceReader

    @Autowired private lateinit var dynamoDbClient: DynamoDbClient

    @Value("\${balance.kafka.topic}") private lateinit var topic: String

    @Value("\${dynamodb.table-name}") private lateinit var tableName: String

    private val accounts = mutableListOf<String>()

    @AfterEach
    fun cleanUp() {
        accounts.forEach { id ->
            dynamoDbClient.deleteItem(
                DeleteItemRequest
                    .builder()
                    .tableName(tableName)
                    .key(mapOf(BalanceTableAttributes.PK to AttributeValue.fromS("ACCOUNT#$id")))
                    .build(),
            )
        }
        accounts.clear()
    }

    private fun newAccount(): String = "acc-${UUID.randomUUID()}".also { accounts += it }

    private fun event(
        accountId: String,
        micros: Long,
        amount: String,
        transactionStatus: String = "APPROVED",
        accountStatus: String = "ENABLED",
        transactionId: String = "tx-${UUID.randomUUID()}",
    ) = """{"transaction": {"id": "$transactionId", "type": "CREDIT", "amount": 10.00, "currency": "BRL", """ +
        """"status": "$transactionStatus", "timestamp": $micros}, "account": {"id": "$accountId", """ +
        """"owner": "own-1", "created_at": 1634874339000000, "status": "$accountStatus", """ +
        """"balance": {"amount": $amount, "currency": "BRL"}}}"""

    @Test
    fun `an eligible event published to the real topic reaches the real table`() {
        val accountId = newAccount()

        // Keyed by account id, exactly as the production producers do.
        kafkaTemplate.send(topic, accountId, event(accountId, 1_751_641_364_589_998L, "183.12"))

        eventually(Duration.ofSeconds(30)) {
            assertEquals(
                BigDecimal("183.12"),
                balanceReader.findByAccountId(AccountId(accountId))?.money?.amount,
            )
        }
    }

    @Test
    fun `an ineligible event is consumed and leaves no balance behind`() {
        val accountId = newAccount()

        kafkaTemplate.send(topic, accountId, event(accountId, 2_000_000L, "500.00", transactionStatus = "DECLINED"))
        // A second, eligible event for a different account acts as a fence: once it has landed, the
        // first message has certainly been consumed, so an absent balance means "ignored", not
        // "not yet processed".
        val fence = newAccount()
        kafkaTemplate.send(topic, fence, event(fence, 3_000_000L, "1.00"))

        eventually(Duration.ofSeconds(30)) {
            assertEquals(BigDecimal("1.00"), balanceReader.findByAccountId(AccountId(fence))?.money?.amount)
        }
        // FR-010: a declined transaction carries no authoritative balance, so nothing is written and
        // the freshness marker never advances.
        assertNull(balanceReader.findByAccountId(AccountId(accountId)))
    }

    @Test
    fun `out of order delivery still converges on the newest snapshot`() {
        val accountId = newAccount()

        kafkaTemplate.send(topic, accountId, event(accountId, 3_000_000L, "300.00"))
        kafkaTemplate.send(topic, accountId, event(accountId, 1_000_000L, "100.00"))
        kafkaTemplate.send(topic, accountId, event(accountId, 2_000_000L, "200.00"))

        eventually(Duration.ofSeconds(30)) {
            assertEquals(
                BigDecimal("300.00"),
                balanceReader.findByAccountId(AccountId(accountId))?.money?.amount,
            )
        }
        // Re-checked after settling: a late arrival must not be able to overwrite the newer state.
        Thread.sleep(1_000)
        assertEquals(BigDecimal("300.00"), balanceReader.findByAccountId(AccountId(accountId))?.money?.amount)
    }
}
