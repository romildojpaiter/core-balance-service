package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.support.TestFixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * T060: the authoritative snapshot is copied, never computed.
 */
class TransactionEventToBalanceTest {

    @Test
    fun `the balance is copied literally from the account, ignoring the transaction amount`() {
        // The fixture is deliberately arithmetic-tempting: balance 150.00 with amount 30.00. A
        // service that applied the delta would produce 120.00 or 180.00 (Constitution VIII, FR-014).
        val balance = TestFixtures.event().toBalance()

        assertEquals(TestFixtures.money("150.00"), balance.money)
    }

    @Test
    fun `the freshness marker is the transaction timestamp, not the time of processing`() {
        val balance = TestFixtures.event().toBalance()

        assertEquals(EventTimestamp(TestFixtures.TIMESTAMP_MICROS), balance.asOf)
        assertEquals(TransactionId(TestFixtures.TRANSACTION_ID), balance.lastTransactionId)
        assertEquals(AccountId(TestFixtures.ACCOUNT_ID), balance.accountId)
    }

    @Test
    fun `the holder is carried from the event into the balance`() {
        // FR-003a: there is no absent-owner case to test here any more. An event without a holder
        // never reaches the domain — the mapper rejects it (see TransactionEventMessageMapperTest),
        // and OwnerId being non-nullable means this test could not construct one if it wanted to.
        val balance = TestFixtures.event(account = TestFixtures.account(ownerId = "own-42")).toBalance()

        assertEquals(OwnerId("own-42"), balance.ownerId)
    }

    @Test
    fun `an ineligible event cannot produce a balance`() {
        val event = TestFixtures.event(transaction = TestFixtures.transaction(status = TransactionStatus.NOT_APPROVED))

        assertFailsWith<IllegalStateException> { event.toBalance() }
    }
}
