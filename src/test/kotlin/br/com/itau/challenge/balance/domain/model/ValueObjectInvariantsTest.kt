package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidAccountIdException
import br.com.itau.challenge.balance.domain.exception.InvalidCurrencyCodeException
import br.com.itau.challenge.balance.domain.exception.InvalidMoneyException
import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The guard clauses, exercised.
 *
 * A value object whose invariant is never tested is a value object whose invariant might not hold:
 * the whole reason these types exist is that an invalid value cannot be constructed, and that claim
 * is only worth as much as the evidence for it.
 */
class ValueObjectInvariantsTest {

    @Test
    fun `an account id must be non-empty and use only the allowed characters`() {
        listOf("", "  ", "conta 42", "acc/1", "acc_1", "café").forEach { candidate ->
            assertFailsWith<InvalidAccountIdException>("'$candidate' should be rejected") { AccountId(candidate) }
        }
        listOf("acc-1", "5b19c8b6-0cc4-4c72-a989-0c2ee15fa975", "ACC123").forEach { AccountId(it) }
    }

    @Test
    fun `a transaction id and an owner id must not be blank`() {
        assertFailsWith<InvalidTransactionEventException> { TransactionId("") }
        assertFailsWith<InvalidTransactionEventException> { TransactionId("   ") }
        assertFailsWith<InvalidTransactionEventException> { OwnerId("") }
        assertFailsWith<InvalidTransactionEventException> { OwnerId("   ") }
    }

    @Test
    fun `identifiers render as their bare value`() {
        assertEquals("acc-1", AccountId("acc-1").toString())
        assertEquals("tx-1", TransactionId("tx-1").toString())
        assertEquals("own-1", OwnerId("own-1").toString())
        assertEquals("BRL", CurrencyCode("BRL").toString())
    }

    @Test
    fun `a currency code must be exactly three uppercase letters`() {
        listOf("brl", "BR", "BRLL", "BR1", "", "R$").forEach { candidate ->
            assertFailsWith<InvalidCurrencyCodeException>("'$candidate' should be rejected") { CurrencyCode(candidate) }
        }
        listOf("BRL", "USD", "JPY").forEach { CurrencyCode(it) }
    }

    @Test
    fun `money normalises to two decimal places without inventing precision`() {
        val brl = CurrencyCode("BRL")

        assertEquals(BigDecimal("150.00"), Money.of(BigDecimal("150"), brl).amount)
        assertEquals(BigDecimal("150.00"), Money.of(BigDecimal("150.0"), brl).amount)
        assertEquals(BigDecimal("-50.25"), Money.of(BigDecimal("-50.25"), brl).amount)
    }

    @Test
    fun `money refuses to round away precision it was given`() {
        // Constitution X: truncating 183.125 would quietly change a customer's money, so the value is
        // rejected rather than adjusted.
        val thrown = assertFailsWith<InvalidMoneyException> { Money.of(BigDecimal("183.125"), CurrencyCode("BRL")) }

        assertTrue(thrown.message!!.contains("183.125"), "the message should name the offending value")
    }

    @Test
    fun `money compares by value and currency together`() {
        val hundredReais = Money.of(BigDecimal("100.00"), CurrencyCode("BRL"))
        val hundredDollars = Money.of(BigDecimal("100.00"), CurrencyCode("USD"))

        assertEquals(hundredReais, Money.of(BigDecimal("100.0"), CurrencyCode("BRL")))
        assertEquals(hundredReais.hashCode(), Money.of(BigDecimal("100"), CurrencyCode("BRL")).hashCode())
        // The same number in a different currency is a different amount of money, never an equal one.
        assertNotEquals(hundredReais, hundredDollars)
        assertNotEquals<Any?>(hundredReais, "100.00 BRL")
        assertTrue(hundredReais == hundredReais)
    }

    @Test
    fun `money renders with its currency and as plain text`() {
        val money = Money.of(BigDecimal("150"), CurrencyCode("BRL"))

        assertEquals("150.00 BRL", money.toString())
        assertEquals("150.00", money.toPlainString())
    }

    @Test
    fun `an event timestamp must be a positive number of microseconds`() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { candidate ->
            assertFailsWith<InvalidTransactionEventException>("$candidate should be rejected") { EventTimestamp(candidate) }
        }
    }

    @Test
    fun `event timestamps order numerically`() {
        val earlier = EventTimestamp(1_000)
        val later = EventTimestamp(2_000)

        assertTrue(later.isNewerThan(earlier))
        assertTrue(!earlier.isNewerThan(later))
        // A tie is not "newer": the strict comparison here mirrors the strict `<` in the conditional
        // write, where an equal timestamp rejects (decision A-03).
        assertTrue(!earlier.isNewerThan(EventTimestamp(1_000)))
        assertTrue(earlier < later)
        assertEquals(0, earlier.compareTo(EventTimestamp(1_000)))
        assertEquals("1000", earlier.toString())
    }

    @Test
    fun `a transaction type is informative and never fails to parse`() {
        assertEquals(TransactionType.CREDIT, TransactionType.from("CREDIT"))
        assertEquals(TransactionType.DEBIT, TransactionType.from("DEBIT"))
        assertEquals(TransactionType.UNKNOWN, TransactionType.from(null))
        assertEquals(TransactionType.UNKNOWN, TransactionType.from("TRANSFER"))
    }
}
