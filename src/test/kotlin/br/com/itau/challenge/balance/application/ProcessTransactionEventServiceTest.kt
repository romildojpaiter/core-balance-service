package br.com.itau.challenge.balance.application

import br.com.itau.challenge.balance.domain.eligibility.IneligibilityReason
import br.com.itau.challenge.balance.domain.model.AccountStatus
import br.com.itau.challenge.balance.domain.model.TransactionStatus
import br.com.itau.challenge.balance.domain.outcome.ProcessingOutcome
import br.com.itau.challenge.balance.domain.outcome.RejectionReason
import br.com.itau.challenge.balance.port.output.BalanceWriteResult
import br.com.itau.challenge.balance.support.RecordingBalanceWriter
import br.com.itau.challenge.balance.support.RecordingTelemetry
import br.com.itau.challenge.balance.support.TestFixtures
import br.com.itau.challenge.balance.support.ThrowingBalanceWriter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T061 + T062: the ineligible short-circuit and the write-result translation.
 */
class ProcessTransactionEventServiceTest {

    private val telemetry = RecordingTelemetry()

    @Test
    fun `non-approved transaction is ignored and never reaches the writer`() {
        val writer = RecordingBalanceWriter()
        val service = ProcessTransactionEventService(writer, telemetry)

        val outcome =
            service.process(
                TestFixtures.event(transaction = TestFixtures.transaction(status = TransactionStatus.NOT_APPROVED)),
            )

        assertEquals(
            ProcessingOutcome.IgnoredIneligible(setOf(IneligibilityReason.TRANSACTION_NOT_APPROVED)),
            outcome,
        )
        // INV-003: if the writer ran, the freshness marker would advance and a later valid event
        // for the same account would be silently rejected as stale.
        assertTrue(writer.saved.isEmpty(), "the writer must not be called for an ineligible event")
    }

    @Test
    fun `non-enabled account is ignored and never reaches the writer`() {
        val writer = RecordingBalanceWriter()
        val service = ProcessTransactionEventService(writer, telemetry)

        val outcome =
            service.process(
                TestFixtures.event(account = TestFixtures.account(status = AccountStatus.NOT_ENABLED)),
            )

        assertEquals(
            ProcessingOutcome.IgnoredIneligible(setOf(IneligibilityReason.ACCOUNT_NOT_ENABLED)),
            outcome,
        )
        assertTrue(writer.saved.isEmpty())
    }

    @Test
    fun `both failures are reported together`() {
        val writer = RecordingBalanceWriter()
        val service = ProcessTransactionEventService(writer, telemetry)

        val outcome =
            service.process(
                TestFixtures.event(
                    transaction = TestFixtures.transaction(status = TransactionStatus.NOT_APPROVED),
                    account = TestFixtures.account(status = AccountStatus.NOT_ENABLED),
                ),
            )

        assertEquals(
            ProcessingOutcome.IgnoredIneligible(
                setOf(
                    IneligibilityReason.TRANSACTION_NOT_APPROVED,
                    IneligibilityReason.ACCOUNT_NOT_ENABLED,
                ),
            ),
            outcome,
        )
    }

    @Test
    fun `eligible event persists the snapshot carried by the account`() {
        val writer = RecordingBalanceWriter()
        val service = ProcessTransactionEventService(writer, telemetry)

        // amount is 30.00 and balance is 150.00: a service that applied the delta would produce
        // 120.00 or 180.00. FR-014 requires the snapshot to be copied, not computed.
        val outcome = service.process(TestFixtures.event())

        assertEquals(1, writer.saved.size)
        assertEquals(TestFixtures.money("150.00"), writer.saved.single().money)
        assertEquals(ProcessingOutcome.Applied(writer.saved.single()), outcome)
    }

    @Test
    fun `duplicate write result becomes a duplicate rejection`() {
        val persisted = TestFixtures.balance()
        val service =
            ProcessTransactionEventService(
                RecordingBalanceWriter { BalanceWriteResult.Duplicate(persisted) },
                telemetry,
            )

        assertEquals(
            ProcessingOutcome.Rejected(RejectionReason.DUPLICATE_EVENT, persisted),
            service.process(TestFixtures.event()),
        )
    }

    @Test
    fun `stale write result becomes a stale rejection`() {
        val persisted = TestFixtures.balance(micros = TestFixtures.TIMESTAMP_MICROS + 1_000)
        val service =
            ProcessTransactionEventService(
                RecordingBalanceWriter { BalanceWriteResult.Stale(persisted) },
                telemetry,
            )

        assertEquals(
            ProcessingOutcome.Rejected(RejectionReason.STALE_EVENT, persisted),
            service.process(TestFixtures.event()),
        )
    }

    @Test
    fun `timestamp tie write result becomes a tie rejection`() {
        val persisted = TestFixtures.balance(transactionId = "other-transaction")
        val service =
            ProcessTransactionEventService(
                RecordingBalanceWriter { BalanceWriteResult.TimestampTie(persisted) },
                telemetry,
            )

        assertEquals(
            ProcessingOutcome.Rejected(RejectionReason.TIMESTAMP_TIE_REJECTED, persisted),
            service.process(TestFixtures.event()),
        )
    }

    @Test
    fun `a transient writer failure propagates instead of being swallowed`() {
        val service =
            ProcessTransactionEventService(
                ThrowingBalanceWriter { IllegalStateException("dynamodb is throttling") },
                telemetry,
            )

        // FR-034/FR-036: the Kafka container is the single retry authority. Swallowing here would
        // let the offset commit and lose the event.
        assertFailsWith<IllegalStateException> { service.process(TestFixtures.event()) }
        assertTrue(telemetry.outcomes.isEmpty(), "no outcome exists yet when the write failed")
    }
}
