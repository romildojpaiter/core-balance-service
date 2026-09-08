package br.com.itau.challenge.balance.adapter.input.kafka.mapper

import br.com.itau.challenge.balance.domain.eligibility.EligibilityDecision
import br.com.itau.challenge.balance.domain.model.AccountStatus
import br.com.itau.challenge.balance.domain.model.TransactionStatus
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * T067: a missing status makes the event ineligible, never the message invalid (FR-008a).
 *
 * The distinction has an operational consequence that is easy to underrate: were it invalid, the
 * day a producer introduces a new status value the DLQ would fill with well-formed messages that
 * only ever needed to be ignored.
 */
class MissingStatusTest {

    private val mapper = TransactionEventMessageMapper(JsonMapper.builder().addModule(kotlinModule()).build())

    private fun payload(
        transactionStatus: String?,
        accountStatus: String?,
    ): String {
        val txStatus = transactionStatus?.let { """"status": "$it", """ } ?: ""
        val accStatus = accountStatus?.let { """"status": "$it", """ } ?: ""
        return """{"transaction": {${txStatus}"id": "tx-1", "timestamp": 1751641364589998}, """ +
            """"account": {${accStatus}"id": "acc-1", "owner": "own-1", """ +
            """"balance": {"amount": 183.12, "currency": "BRL"}}}"""
    }

    @Test
    fun `an absent transaction status parses and is ineligible`() {
        val interpreted = assertIs<MessageInterpretation.Interpreted>(mapper.interpret(payload(null, "ENABLED")))

        assertEquals(TransactionStatus.NOT_APPROVED, interpreted.event.transaction.status)
        assertIs<EligibilityDecision.Ineligible>(interpreted.event.evaluateEligibility())
    }

    @Test
    fun `an unrecognised transaction status parses and is ineligible`() {
        val interpreted = assertIs<MessageInterpretation.Interpreted>(mapper.interpret(payload("PENDING", "ENABLED")))

        assertEquals(TransactionStatus.NOT_APPROVED, interpreted.event.transaction.status)
    }

    @Test
    fun `an absent account status parses and is ineligible`() {
        val interpreted = assertIs<MessageInterpretation.Interpreted>(mapper.interpret(payload("APPROVED", null)))

        assertEquals(AccountStatus.NOT_ENABLED, interpreted.event.account.status)
        assertIs<EligibilityDecision.Ineligible>(interpreted.event.evaluateEligibility())
    }

    @Test
    fun `an unrecognised account status parses and is ineligible`() {
        val interpreted = assertIs<MessageInterpretation.Interpreted>(mapper.interpret(payload("APPROVED", "SUSPENDED")))

        assertEquals(AccountStatus.NOT_ENABLED, interpreted.event.account.status)
    }
}
