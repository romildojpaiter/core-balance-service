package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.adapter.input.kafka.config.KafkaConsumerConfig
import br.com.itau.challenge.balance.adapter.output.dynamodb.BalanceTableAttributes
import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.port.output.BalanceReader
import br.com.itau.challenge.balance.support.eventually
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What reaches the DLQ, and — more importantly — what does not.
 *
 * A dead-letter queue is only useful while it means "something is broken". The moment it also
 * collects messages the consumer simply was not meant to handle, every alert on it becomes noise and
 * the real defects stop being noticed. FR-004a exists for that reason, and the second test here is
 * the one that proves it.
 */
@SpringBootTest
@ActiveProfiles("integration")
class TransactionEventDlqIntegrationTest {

    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired private lateinit var balanceReader: BalanceReader

    @Autowired private lateinit var dynamoDbClient: DynamoDbClient

    @Value("\${balance.kafka.topic}") private lateinit var topic: String

    @Value("\${balance.kafka.dlq-topic}") private lateinit var dlqTopic: String

    @Value("\${spring.kafka.bootstrap-servers}") private lateinit var bootstrapServers: String

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

    private fun eligibleEvent(
        accountId: String,
        amount: String,
    ) = """{"transaction": {"id": "tx-${UUID.randomUUID()}", "status": "APPROVED", "timestamp": 9000000}, """ +
        """"account": {"id": "$accountId", "owner": "own-dlq", "status": "ENABLED", """ +
        """"balance": {"amount": $amount, "currency": "BRL"}}}"""

    /** Reads the DLQ from the beginning; the topic is low volume, so a full scan is cheap and exact. */
    private fun readDlq(timeout: Duration = Duration.ofSeconds(15)): List<ConsumerRecord<String, String>> {
        val consumer =
            KafkaConsumer(
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "dlq-assertions-${UUID.randomUUID()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ),
                StringDeserializer(),
                StringDeserializer(),
            )

        return consumer.use {
            it.subscribe(listOf(dlqTopic))
            val deadline = System.nanoTime() + timeout.toNanos()
            val collected = mutableListOf<ConsumerRecord<String, String>>()
            while (System.nanoTime() < deadline) {
                it.poll(Duration.ofMillis(500)).forEach(collected::add)
            }
            collected
        }
    }

    @Test
    fun `an invalid message lands in the DLQ with its original payload and a reason`() {
        val marker = UUID.randomUUID().toString()
        val accountId = newAccount()
        val malformed = """{"transaction": {"id": "$marker", "status": "APPROVED"}, "account": {"id": "$accountId"}}"""

        kafkaTemplate.send(topic, accountId, malformed)

        eventually(Duration.ofSeconds(45)) {
            val record = readDlq().firstOrNull { it.value().contains(marker) }

            assertNotNull(record, "the invalid message never reached the DLQ")
            // FR-039: the payload is republished untouched, so a replay is byte-identical.
            assertEquals(malformed, record.value())
            assertEquals(accountId, record.key(), "the original key must be preserved")

            val reason = record.headers().lastHeader(KafkaConsumerConfig.FAILURE_REASON_HEADER)
            assertNotNull(reason, "the failure reason header is missing")
            assertEquals(
                KafkaConsumerConfig.REASON_INVALID_MESSAGE,
                reason.value().toString(Charsets.UTF_8),
            )
            assertNotNull(
                record.headers().lastHeader("kafka_dlt-original-topic"),
                "the origin headers the recoverer adds are missing",
            )
            val message = record.headers().lastHeader("kafka_dlt-exception-message")
            assertNotNull(message)
            // The DLQ message is all an operator has when triaging; it must name the field.
            assertTrue(
                message.value().toString(Charsets.UTF_8).contains("transaction.timestamp"),
                "the reason should name the missing field",
            )
        }

        assertNull(balanceReader.findByAccountId(AccountId(accountId)), "an invalid message must change no balance")
    }

    @Test
    fun `an event without a holder is invalid, reaches the DLQ and leaves the balance untouched`() {
        // FR-003a, and the whole cost of it in one test: the balance block is perfectly valid, the
        // amount is real, and the account still ends up with no balance at all. That is deliberate
        // under Constitution I — when in doubt, do not mutate — and the DLQ is where the cost is
        // visible instead of silent.
        val marker = UUID.randomUUID().toString()
        val accountId = newAccount()
        val withoutOwner =
            """{"transaction": {"id": "$marker", "status": "APPROVED", "timestamp": 9000000}, """ +
                """"account": {"id": "$accountId", "status": "ENABLED", """ +
                """"balance": {"amount": 123.45, "currency": "BRL"}}}"""

        kafkaTemplate.send(topic, accountId, withoutOwner)

        eventually(Duration.ofSeconds(45)) {
            val record = readDlq().firstOrNull { it.value().contains(marker) }

            assertNotNull(record, "the holder-less event never reached the DLQ")
            assertEquals(withoutOwner, record.value())

            val reason = record.headers().lastHeader(KafkaConsumerConfig.FAILURE_REASON_HEADER)
            assertNotNull(reason)
            assertEquals(KafkaConsumerConfig.REASON_INVALID_MESSAGE, reason.value().toString(Charsets.UTF_8))

            val message = record.headers().lastHeader("kafka_dlt-exception-message")
            assertNotNull(message)
            assertTrue(
                message.value().toString(Charsets.UTF_8).contains("account.owner"),
                "the DLQ message must name account.owner so an operator knows what the producer omitted",
            )
        }

        // The assertion that makes this test worth having. Without it, it only proves the DLQ
        // received something — not that a valid-looking balance was correctly refused.
        assertNull(
            balanceReader.findByAccountId(AccountId(accountId)),
            "an event without a holder must not update the balance, however valid its amount",
        )
    }

    @Test
    fun `a message from another flow never reaches the DLQ and does not block the next one`() {
        val marker = UUID.randomUUID().toString()
        val otherFlowAccount = newAccount()
        val accountEvent =
            """{"account": {"id": "$otherFlowAccount", "owner": "$marker", "created_at": 1634874339000000, "status": "ENABLED"}}"""

        kafkaTemplate.send(topic, otherFlowAccount, accountEvent)

        // Published after the discard, on the same topic: if the discard had thrown, the offset would
        // not have advanced and this event would be stuck behind it.
        val nextAccount = newAccount()
        kafkaTemplate.send(topic, nextAccount, eligibleEvent(nextAccount, "77.77"))

        eventually(Duration.ofSeconds(45)) {
            assertEquals(BigDecimal("77.77"), balanceReader.findByAccountId(AccountId(nextAccount))?.money?.amount)
        }

        // FR-004a, matrix row 5a. This is what keeps the DLQ a signal of defects rather than a
        // dumping ground for everything the consumer did not recognise.
        assertTrue(
            readDlq(Duration.ofSeconds(8)).none { it.value().contains(marker) },
            "an account event reached the DLQ; FR-004a is broken",
        )
        assertNull(balanceReader.findByAccountId(AccountId(otherFlowAccount)))
    }
}
