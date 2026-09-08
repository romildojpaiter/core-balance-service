package br.com.itau.challenge.balance.adapter.input.kafka.mapper

import br.com.itau.challenge.balance.adapter.input.kafka.exception.UnprocessableEventException
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * T068: the FR-004a boundary.
 *
 * This is the sharpest boundary in the system and the easiest to erase by accident. The two
 * payloads below differ by nineteen characters and go to opposite destinations: one is discarded
 * and its offset committed, the other lands in the DLQ. Any refactor that reads the bound DTO
 * instead of the JSON tree will make them identical and silently break FR-004a.
 */
class UnsupportedMessageTest {

    private val mapper = TransactionEventMessageMapper(JsonMapper.builder().addModule(kotlinModule()).build())

    /** Exactly what `make kafka-produce-accounts-events` publishes: no `transaction`, no `balance`. */
    private val accountEvent =
        """
        {"account": {"id": "5b19c8b6-0cc4-4c72-a989-0c2ee15fa975", "owner": "315e3cfe-f4af-4cd2-b298-a449e614349a", "created_at": 1634874339000000, "status": "ENABLED"}}
        """.trimIndent()

    /**
     * The same account block, but complete. Used for the DLQ cases so that the only thing under test
     * is the `transaction` block — otherwise a missing balance would mask the distinction.
     */
    private val completeAccount =
        """"account": {"id": "5b19c8b6-0cc4-4c72-a989-0c2ee15fa975", "owner": "315e3cfe-f4af-4cd2-b298-a449e614349a", "status": "ENABLED", "balance": {"amount": 183.12, "currency": "BRL"}}"""

    @Test
    fun `a message with no transaction block at all is another flow, not a defect`() {
        // Exactly what `make kafka-produce-accounts-events` publishes.
        val interpretation = mapper.interpret(accountEvent)

        val unsupported = assertIs<MessageInterpretation.Unsupported>(interpretation)
        assertEquals(UnsupportedReason.NO_TRANSACTION_BLOCK, unsupported.reason)
    }

    @Test
    fun `a null transaction block is a defect and goes to the DLQ`() {
        // Present-but-null. After binding to the DTO this is indistinguishable from the absent case
        // above, which is exactly why the mapper decides on the JSON tree.
        val payload = """{"transaction": null, $completeAccount}"""

        val thrown = assertFailsWith<UnprocessableEventException> { mapper.interpret(payload) }

        assertEquals(payload, thrown.payload, "the DLQ must carry the original payload")
    }

    @Test
    fun `an empty transaction block is a defect and names the missing field`() {
        val payload = """{"transaction": {}, $completeAccount}"""

        val thrown = assertFailsWith<UnprocessableEventException> { mapper.interpret(payload) }

        assertEquals("'transaction.id' is missing", thrown.reason)
    }

    @Test
    fun `discarding never throws, so the offset can commit`() {
        // FR-004a hinges on this: any exception leaving the listener is caught by the container's
        // error handler and routed to the DLQ, which is the outcome this rule exists to prevent.
        mapper.interpret(accountEvent)
    }
}
