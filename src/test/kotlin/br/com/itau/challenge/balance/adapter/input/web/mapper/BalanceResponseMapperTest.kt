package br.com.itau.challenge.balance.adapter.input.web.mapper

import br.com.itau.challenge.balance.support.TestFixtures
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * T077: the two lossy conversions, pinned.
 */
class BalanceResponseMapperTest {

    private val mapper = BalanceResponseMapper("America/Sao_Paulo")

    @Test
    fun `the amount crosses as an exact decimal, not as text`() {
        val response = mapper.toResponse(TestFixtures.balance(amount = "150.00"))

        assertEquals(BigDecimal("150.00"), response.balance.amount)
        assertEquals("BRL", response.balance.currency)
    }

    @Test
    fun `the scale survives the mapping, so 150 never becomes 150 point 0`() {
        // The scale is the whole reason a JSON number is admissible here (FR-044, R19). Lose it and
        // the response stops carrying an exact decimal, which is what Constitution X requires.
        assertEquals(2, mapper.toResponse(TestFixtures.balance(amount = "150.00")).balance.amount.scale())
        assertEquals(2, mapper.toResponse(TestFixtures.balance(amount = "0.00")).balance.amount.scale())
    }

    @Test
    fun `a whole amount still carries two places`() {
        assertEquals(BigDecimal("0.00"), mapper.toResponse(TestFixtures.balance(amount = "0.00")).balance.amount)
    }

    @Test
    fun `a negative amount keeps its sign`() {
        assertEquals(BigDecimal("-50.25"), mapper.toResponse(TestFixtures.balance(amount = "-50.25")).balance.amount)
    }

    @Test
    fun `microseconds render as local time with three fractional digits`() {
        val response = mapper.toResponse(TestFixtures.balance(micros = TestFixtures.TIMESTAMP_MICROS))

        assertEquals("2025-07-05T18:04:13.433-03:00", response.updatedAt)
    }

    @Test
    fun `sub millisecond precision is truncated, never rounded`() {
        // …589998 µs. Rounding would print .590, an instant that never occurred — and at the edge of
        // a second, an instant in the future.
        val response = mapper.toResponse(TestFixtures.balance(micros = 1_751_641_364_589_998L))

        assertEquals("2025-07-04T12:02:44.589-03:00", response.updatedAt)
    }

    @Test
    fun `a whole second still carries three fractional digits`() {
        // ISO_OFFSET_DATE_TIME would drop the fraction here, making one balance render differently
        // from every other and forcing clients to parse two shapes.
        val response = mapper.toResponse(TestFixtures.balance(micros = 1_751_641_364_000_000L))

        assertEquals("2025-07-04T12:02:44.000-03:00", response.updatedAt)
    }

    @Test
    fun `the rendering timezone is configuration, not a constant`() {
        val utc = BalanceResponseMapper("UTC").toResponse(TestFixtures.balance())

        assertEquals("2025-07-05T21:04:13.433Z", utc.updatedAt)
    }

    @Test
    fun `the account and its holder are what identify the balance`() {
        // FR-043a: the transaction that produced it is no longer exposed. It stays in the table and
        // in the structured logs, which is where tracing "which event produced this" now lives.
        val response = mapper.toResponse(TestFixtures.balance())

        assertEquals(TestFixtures.ACCOUNT_ID, response.id)
        assertEquals(TestFixtures.OWNER_ID, response.owner)
    }
}
