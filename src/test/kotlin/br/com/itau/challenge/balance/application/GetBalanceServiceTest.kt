package br.com.itau.challenge.balance.application

import br.com.itau.challenge.balance.domain.exception.BalanceNotFoundException
import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.support.RecordingBalanceReader
import br.com.itau.challenge.balance.support.TestFixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * T064: an unknown account must never be reported as holding `0.00`.
 */
class GetBalanceServiceTest {

    @Test
    fun `returns the persisted balance when the account is known`() {
        val stored = TestFixtures.balance()
        val reader = RecordingBalanceReader(mapOf(TestFixtures.ACCOUNT_ID to stored))

        assertEquals(stored, GetBalanceService(reader).getBalance(AccountId(TestFixtures.ACCOUNT_ID)))
        assertEquals(listOf(AccountId(TestFixtures.ACCOUNT_ID)), reader.queried)
    }

    @Test
    fun `throws for an unknown account instead of inventing a zero balance`() {
        val reader = RecordingBalanceReader()

        // FR-047/FR-051: 0.00 is a real balance. Returning it for an account the system has never
        // seen would be indistinguishable from an account that genuinely holds nothing.
        assertFailsWith<BalanceNotFoundException> {
            GetBalanceService(reader).getBalance(AccountId("unknown-account"))
        }
    }
}
