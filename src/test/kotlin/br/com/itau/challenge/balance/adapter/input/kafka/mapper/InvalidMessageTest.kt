package br.com.itau.challenge.balance.adapter.input.kafka.mapper

import br.com.itau.challenge.balance.adapter.input.kafka.exception.UnprocessableEventException
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T066: every way a message can be invalid, and the field each one names.
 *
 * The DLQ message is the only thing an operator has when triaging, so "the payload was bad" is not
 * an acceptable reason — each case must name what was wrong.
 */
class InvalidMessageTest {

    private val mapper = TransactionEventMessageMapper(JsonMapper.builder().addModule(kotlinModule()).build())

    private fun payload(
        transaction: String = """"id": "tx-1", "status": "APPROVED", "timestamp": 1751641364589998""",
        balance: String = """"amount": 183.12, "currency": "BRL"""",
        accountId: String = "5b19c8b6-0cc4-4c72-a989-0c2ee15fa975",
        owner: String = "315e3cfe-f4af-4cd2-b298-a449e614349a",
    ) = """{"transaction": {$transaction}, "account": {"id": "$accountId", "owner": "$owner", """ +
        """"status": "ENABLED", "balance": {$balance}}}"""

    @Test
    fun `malformed json is invalid`() {
        val thrown = assertFailsWith<UnprocessableEventException> { mapper.interpret("{not json") }

        assertEquals("payload is not valid JSON", thrown.reason)
    }

    @Test
    fun `each missing required field is named`() {
        val cases =
            mapOf(
                payload(transaction = """"status": "APPROVED", "timestamp": 1751641364589998""") to
                    "'transaction.id' is missing",
                payload(transaction = """"id": "tx-1", "status": "APPROVED"""") to
                    "'transaction.timestamp' is missing",
                payload(balance = """"currency": "BRL"""") to "'account.balance.amount' is missing",
                payload(balance = """"amount": 183.12""") to "'account.balance.currency' is missing",
            )

        cases.forEach { (json, expected) ->
            assertEquals(expected, assertFailsWith<UnprocessableEventException> { mapper.interpret(json) }.reason)
        }
    }

    @Test
    fun `a missing account id is named`() {
        val json = """{"transaction": {"id": "tx-1", "timestamp": 1}, "account": {"balance": {"amount": 1.00, "currency": "BRL"}}}"""

        assertEquals("'account.id' is missing", assertFailsWith<UnprocessableEventException> { mapper.interpret(json) }.reason)
    }

    @Test
    fun `a missing account block is named`() {
        val json = """{"transaction": {"id": "tx-1", "timestamp": 1}}"""

        assertEquals("'account' is missing", assertFailsWith<UnprocessableEventException> { mapper.interpret(json) }.reason)
    }

    @Test
    fun `an amount with three decimal places is invalid, never silently rounded`() {
        // Constitution X forbids implicit rounding: 183.125 is a value this system cannot represent
        // exactly, and truncating it would quietly change a customer's money.
        val thrown = assertFailsWith<UnprocessableEventException> {
            mapper.interpret(payload(balance = """"amount": 183.125, "currency": "BRL""""))
        }

        assertTrue(thrown.reason.contains("decimal places"), "reason should explain the precision problem: ${thrown.reason}")
    }

    @Test
    fun `an invalid currency code is invalid`() {
        assertFailsWith<UnprocessableEventException> {
            mapper.interpret(payload(balance = """"amount": 183.12, "currency": "brl""""))
        }
    }

    @Test
    fun `an account id outside the allowed format is invalid`() {
        assertFailsWith<UnprocessableEventException> { mapper.interpret(payload(accountId = "conta 42")) }
    }

    @Test
    fun `a non numeric timestamp is invalid`() {
        val json = payload(transaction = """"id": "tx-1", "timestamp": "yesterday"""")

        assertFailsWith<UnprocessableEventException> { mapper.interpret(json) }
    }

    @Test
    fun `a non positive timestamp is invalid`() {
        val json = payload(transaction = """"id": "tx-1", "timestamp": 0""")

        assertFailsWith<UnprocessableEventException> { mapper.interpret(json) }
    }

    @Test
    fun `the original payload travels with the exception`() {
        val json = payload(balance = """"currency": "BRL"""")

        // FR-039: the DLQ carries what the producer sent, byte for byte.
        assertEquals(json, assertFailsWith<UnprocessableEventException> { mapper.interpret(json) }.payload)
    }
}
