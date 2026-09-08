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
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The most important test in the suite: it is the only direct evidence that the system is safe under
 * concurrency without a lock anywhere in it.
 *
 * The `CountDownLatch` is not ceremony. A test that starts N threads and lets them run usually
 * executes them almost serially — each thread finishes its round trip before the next is scheduled —
 * and then passes while proving nothing (risk R-08). Holding every thread at a barrier and releasing
 * them together is what forces the overlap the test claims to exercise.
 *
 * The scenario repeats [REPETITIONS] times for the same reason: a concurrency test that passes once
 * has demonstrated one scheduling, not a guarantee.
 */
class ConditionalWriteConcurrencyIntegrationTest {

    private val client: DynamoDbClient =
        DynamoDbClient
            .builder()
            .endpointOverride(URI.create(System.getenv("DYNAMODB_ENDPOINT") ?: "http://localhost:8000"))
            .region(Region.of("us-east-1"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
            .build()

    private val tableName = System.getenv("BALANCE_TABLE_NAME") ?: "AccountBalances"
    private val itemMapper = BalanceItemMapper()
    private val writer = DynamoDbBalanceWriter(client, itemMapper, RecordingTelemetry(), tableName)
    private val reader = DynamoDbBalanceReader(client, itemMapper, tableName)

    private val accounts = mutableListOf<String>()

    @BeforeEach
    fun reset() {
        accounts.clear()
    }

    @AfterEach
    fun cleanUp() {
        accounts.forEach { id ->
            client.deleteItem(
                DeleteItemRequest
                    .builder()
                    .tableName(tableName)
                    .key(mapOf(BalanceTableAttributes.PK to AttributeValue.fromS("ACCOUNT#$id")))
                    .build(),
            )
        }
    }

    private fun newAccount(): String = "acc-${UUID.randomUUID()}".also { accounts += it }

    @Test
    fun `concurrent writers converge on the newest snapshot, every time`() {
        repeat(REPETITIONS) { round ->
            val accountId = newAccount()
            val timestamps = (1..WRITERS).map { it * 1_000L }.shuffled()

            val applied = AtomicInteger()
            val rejected = AtomicInteger()
            val ready = CountDownLatch(WRITERS)
            val go = CountDownLatch(1)
            val done = CountDownLatch(WRITERS)
            val pool = Executors.newFixedThreadPool(WRITERS)

            timestamps.forEach { micros ->
                pool.submit {
                    ready.countDown()
                    go.await()
                    try {
                        when (
                            writer.save(
                                TestFixtures.balance(
                                    accountId = accountId,
                                    amount = "%.2f".format(micros / 1_000.0),
                                    micros = micros,
                                    transactionId = "tx-$micros",
                                ),
                            )
                        ) {
                            is BalanceWriteResult.Applied -> applied.incrementAndGet()
                            else -> rejected.incrementAndGet()
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }

            assertTrue(ready.await(30, TimeUnit.SECONDS), "workers never reached the barrier")
            go.countDown()
            assertTrue(done.await(60, TimeUnit.SECONDS), "workers did not finish in round $round")
            pool.shutdown()

            val persisted = reader.findByAccountId(AccountId(accountId))

            // The newest snapshot wins regardless of arrival order — the whole guarantee, in one line.
            assertEquals(
                (WRITERS * 1_000).toLong(),
                persisted?.asOf?.micros,
                "round $round did not converge on the newest event",
            )
            // Full accounting: nothing may vanish. A write that neither applied nor was rejected is a
            // lost update, which is exactly the failure this design exists to make impossible.
            assertEquals(
                WRITERS,
                applied.get() + rejected.get(),
                "round $round lost a write: ${applied.get()} applied, ${rejected.get()} rejected",
            )
            assertTrue(applied.get() >= 1, "at least the winner must have applied")
        }
    }

    @Test
    fun `strictly out of order arrivals never move the marker backwards`() {
        val accountId = newAccount()
        writer.save(TestFixtures.balance(accountId = accountId, micros = 300, transactionId = "tx-300"))

        listOf(100L, 200L, 299L).forEach { micros ->
            val result =
                writer.save(TestFixtures.balance(accountId = accountId, micros = micros, transactionId = "tx-$micros"))

            assertIs<BalanceWriteResult.Stale>(result, "an event at $micros should have been rejected")
        }

        assertEquals(300L, reader.findByAccountId(AccountId(accountId))?.asOf?.micros)
    }

    @Test
    fun `the same event delivered many times applies exactly once`() {
        val accountId = newAccount()
        val event = TestFixtures.balance(accountId = accountId, micros = 5_000, transactionId = "tx-once")

        val applied = AtomicInteger()
        val duplicates = AtomicInteger()
        val ready = CountDownLatch(DUPLICATE_WRITERS)
        val go = CountDownLatch(1)
        val done = CountDownLatch(DUPLICATE_WRITERS)
        val pool = Executors.newFixedThreadPool(DUPLICATE_WRITERS)

        repeat(DUPLICATE_WRITERS) {
            pool.submit {
                ready.countDown()
                go.await()
                try {
                    when (writer.save(event)) {
                        is BalanceWriteResult.Applied -> applied.incrementAndGet()
                        is BalanceWriteResult.Duplicate -> duplicates.incrementAndGet()
                        else -> Unit
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS))
        go.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS))
        pool.shutdown()

        // INV-005: redelivery is a terminal success, not a conflict. Exactly one write happens, and
        // the state is identical to a single delivery.
        assertEquals(1, applied.get(), "exactly one writer must apply the event")
        assertEquals(DUPLICATE_WRITERS - 1, duplicates.get())
        assertEquals(5_000L, reader.findByAccountId(AccountId(accountId))?.asOf?.micros)
        assertEquals("tx-once", reader.findByAccountId(AccountId(accountId))?.lastTransactionId?.value)
    }

    private companion object {
        const val WRITERS = 32
        const val DUPLICATE_WRITERS = 16
        const val REPETITIONS = 20
    }
}
