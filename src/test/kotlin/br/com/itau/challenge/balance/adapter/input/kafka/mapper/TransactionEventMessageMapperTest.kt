package br.com.itau.challenge.balance.adapter.input.kafka.mapper

import br.com.itau.challenge.balance.adapter.input.kafka.exception.UnprocessableEventException
import br.com.itau.challenge.balance.domain.model.AccountStatus
import br.com.itau.challenge.balance.domain.model.TransactionStatus
import br.com.itau.challenge.balance.domain.model.TransactionType
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * T065: the challenge's own payload maps to the right domain object.
 */
class TransactionEventMessageMapperTest {

    private val mapper = TransactionEventMessageMapper(JsonMapper.builder().addModule(kotlinModule()).build())

    private val challengePayload =
        """
        {
          "transaction": {
            "id": "8e8ae808-b154-48b5-9f3e-553935cc4543",
            "type": "CREDIT",
            "amount": 97.07,
            "currency": "BRL",
            "status": "APPROVED",
            "timestamp": 1751641364589998
          },
          "account": {
            "id": "5b19c8b6-0cc4-4c72-a989-0c2ee15fa975",
            "owner": "315e3cfe-f4af-4cd2-b298-a449e614349a",
            "created_at": 1634874339000000,
            "status": "ENABLED",
            "balance": { "amount": 183.12, "currency": "BRL" }
          }
        }
        """.trimIndent()

    @Test
    fun `the challenge payload maps to the expected domain event`() {
        val interpreted = assertIs<MessageInterpretation.Interpreted>(mapper.interpret(challengePayload))
        val event = interpreted.event

        assertEquals("8e8ae808-b154-48b5-9f3e-553935cc4543", event.transaction.id.value)
        assertEquals(TransactionStatus.APPROVED, event.transaction.status)
        assertEquals(TransactionType.CREDIT, event.transaction.type)
        assertEquals(1_751_641_364_589_998L, event.transaction.timestamp.micros)
        assertEquals("5b19c8b6-0cc4-4c72-a989-0c2ee15fa975", event.account.id.value)
        assertEquals("315e3cfe-f4af-4cd2-b298-a449e614349a", event.account.ownerId.value)
        assertEquals(AccountStatus.ENABLED, event.account.status)
        assertEquals(BigDecimal("183.12"), event.account.balance.amount)
        assertEquals("BRL", event.account.balance.currency.value)
    }

    /**
     * FR-003a, deliberately adjacent to the happy path above.
     *
     * The payload below differs from it by a single field, and the difference sends the message to
     * the DLQ instead of the balance table. Read side by side, the rule is obvious; read apart, it
     * looks like an over-strict check someone could relax.
     */
    @Test
    fun `a payload without a holder is an invalid message and names the field`() {
        val withoutOwner = challengePayload.replace("\"owner\": \"315e3cfe-f4af-4cd2-b298-a449e614349a\",\n", "")

        val thrown = assertFailsWith<UnprocessableEventException> { mapper.interpret(withoutOwner) }

        assertEquals("'account.owner' is missing", thrown.reason)
        assertEquals(withoutOwner, thrown.payload, "the DLQ must carry the original payload")
    }

    @Test
    fun `a blank holder is rejected too, so the field cannot be satisfied with an empty string`() {
        val blankOwner = challengePayload.replace("315e3cfe-f4af-4cd2-b298-a449e614349a", "   ")

        val thrown = assertFailsWith<UnprocessableEventException> { mapper.interpret(blankOwner) }

        assertEquals("account.owner must not be blank", thrown.reason)
    }

    @Test
    fun `the holder is checked before the balance, so the DLQ names the first broken field`() {
        // The DLQ carries only the first failure. Checking in payload order is what stops an
        // operator from being sent to `account.balance` when the real defect is further up.
        val neitherOwnerNorBalance =
            challengePayload
                .replace("\"owner\": \"315e3cfe-f4af-4cd2-b298-a449e614349a\",\n", "")
                .replace("\"balance\": { \"amount\": 183.12, \"currency\": \"BRL\" }\n", "")
                .replace("\"status\": \"ENABLED\",\n", "\"status\": \"ENABLED\"\n")

        val thrown = assertFailsWith<UnprocessableEventException> { mapper.interpret(neitherOwnerNorBalance) }

        assertEquals("'account.owner' is missing", thrown.reason)
    }

    @Test
    fun `the amount never passes through a floating point type`() {
        // INV-006: 183.12 has no exact binary representation. Had Jackson bound it through double,
        // the value would arrive as 183.11999999999999 and the scale check would reject it.
        val interpreted = assertIs<MessageInterpretation.Interpreted>(mapper.interpret(challengePayload))

        assertEquals(BigDecimal("183.12"), interpreted.event.account.balance.amount)
        assertEquals(2, interpreted.event.account.balance.amount.scale())
    }

    @Test
    fun `the transaction amount is carried but never influences the balance`() {
        val interpreted = assertIs<MessageInterpretation.Interpreted>(mapper.interpret(challengePayload))

        assertNotNull(interpreted.event.transaction.amount)
        // 97.07 against a balance of 183.12: the snapshot wins, unchanged.
        assertEquals(BigDecimal("183.12"), interpreted.event.toBalance().money.amount)
    }

    @Test
    fun `unknown fields in the payload are tolerated`() {
        val withExtras = challengePayload.replace("\"type\": \"CREDIT\",", "\"type\": \"CREDIT\", \"channel\": \"ATM\",")

        assertIs<MessageInterpretation.Interpreted>(mapper.interpret(withExtras))
    }
}
