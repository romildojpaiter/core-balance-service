package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.OwnerId
import br.com.itau.challenge.balance.support.TestFixtures
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T075: the item round trip, and the two type choices that make it correct.
 */
class BalanceItemMapperTest {

    private val mapper = BalanceItemMapper()

    @Test
    fun `round trip preserves the value and its scale`() {
        val balance = TestFixtures.balance(amount = "150.00")

        assertEquals(balance, mapper.toBalance(mapper.toItem(balance)))
        assertEquals(BigDecimal("150.00"), mapper.toBalance(mapper.toItem(balance)).money.amount)
    }

    @Test
    fun `the partition key is derived, never stored in the domain`() {
        val item = mapper.toItem(TestFixtures.balance())

        assertEquals("ACCOUNT#${TestFixtures.ACCOUNT_ID}", item.getValue(BalanceTableAttributes.PK).s())
    }

    @Test
    fun `the amount is stored as a string so trailing zeros survive`() {
        val item = mapper.toItem(TestFixtures.balance(amount = "150.00"))
        val amount = item.getValue(BalanceTableAttributes.BALANCE_AMOUNT)

        // DynamoDB's N type is normalised: 150.00 would come back as "150" and FR-044's two decimal
        // places would have to be re-invented by formatting, which Constitution X forbids.
        assertNull(amount.n(), "balanceAmount must not use the numeric type")
        assertEquals("150.00", amount.s())
    }

    @Test
    fun `the freshness marker is stored as a number so comparison is numeric`() {
        val item = mapper.toItem(TestFixtures.balance(micros = 9L))
        val marker = item.getValue(BalanceTableAttributes.LAST_EVENT_TIMESTAMP)

        // This attribute is the left operand of the conditional expression. Stored as S, DynamoDB
        // would compare it lexicographically and "9" would sort after "10".
        assertNull(marker.s(), "lastEventTimestamp must not use the string type")
        assertEquals("9", marker.n())
    }

    @Test
    fun `the owner is always written`() {
        // FR-003a: the attribute stopped being conditional. No path in this feature can persist a
        // balance without a holder, so writing it unconditionally is not a defensive default — it is
        // the only shape an item can have.
        val item = mapper.toItem(TestFixtures.balance(ownerId = "own-42"))

        assertEquals("own-42", item.getValue(BalanceTableAttributes.OWNER_ID).s())
        assertEquals(OwnerId("own-42"), mapper.toBalance(item).ownerId)
    }

    @Test
    fun `an item without an owner is corrupt, not a legitimate balance`() {
        // Reading such an item means something outside this feature wrote it. Inventing a holder
        // would assert a fact the system does not know (Constitution I), so it fails loudly instead.
        val item = mapper.toItem(TestFixtures.balance()) - BalanceTableAttributes.OWNER_ID

        val thrown = assertFailsWith<IllegalStateException> { mapper.toBalance(item) }

        assertTrue(thrown.message!!.contains(BalanceTableAttributes.OWNER_ID))
    }

    @Test
    fun `updatedAt is written for operators and read by nothing`() {
        val item = mapper.toItem(TestFixtures.balance())

        assertTrue(item.containsKey(BalanceTableAttributes.UPDATED_AT))
        // Freshness comes from the event, never from this wall clock: the round trip must not depend
        // on it, and the domain type has no field that could carry it.
        assertEquals(TestFixtures.balance(), mapper.toBalance(item))
    }

    @Test
    fun `a negative balance survives the round trip`() {
        val overdrawn = TestFixtures.balance(amount = "-50.25")

        assertEquals(overdrawn, mapper.toBalance(mapper.toItem(overdrawn)))
    }
}
