package br.com.itau.challenge.balance.adapter.input.kafka.mapper

import br.com.itau.challenge.balance.adapter.input.kafka.dto.TransactionEventMessage
import br.com.itau.challenge.balance.adapter.input.kafka.exception.UnprocessableEventException
import br.com.itau.challenge.balance.domain.exception.InvalidAccountIdException
import br.com.itau.challenge.balance.domain.exception.InvalidCurrencyCodeException
import br.com.itau.challenge.balance.domain.exception.InvalidMoneyException
import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.domain.model.Account
import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.domain.model.AccountStatus
import br.com.itau.challenge.balance.domain.model.CurrencyCode
import br.com.itau.challenge.balance.domain.model.EventTimestamp
import br.com.itau.challenge.balance.domain.model.Money
import br.com.itau.challenge.balance.domain.model.OwnerId
import br.com.itau.challenge.balance.domain.model.Transaction
import br.com.itau.challenge.balance.domain.model.TransactionEvent
import br.com.itau.challenge.balance.domain.model.TransactionId
import br.com.itau.challenge.balance.domain.model.TransactionStatus
import br.com.itau.challenge.balance.domain.model.TransactionType
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

/**
 * Turns a raw payload into one of three destinations, and the ordering of the checks is the design.
 *
 * The mapper decides two boundaries that nothing downstream can revisit:
 *
 * 1. **Another flow versus invalid.** A message with no `transaction` block at all is an account
 *    event — a different flow, discarded observably (FR-004a). A message whose `transaction` block
 *    is present but empty or null is a defect and goes to the DLQ. The two payloads differ by a few
 *    characters and have opposite destinations, which is why the check is made against the parsed
 *    JSON tree rather than the bound DTO: after binding, `{"account":{}}` and
 *    `{"transaction":null,"account":{}}` are indistinguishable.
 * 2. **Invalid versus ineligible.** A missing required field is invalid. A missing or unrecognised
 *    *status* is not: it makes the event ineligible (FR-008a). Conflating them would send
 *    well-formed messages to the DLQ every time a producer added a new status value.
 */
@Component
class TransactionEventMessageMapper(
    private val objectMapper: ObjectMapper,
) {

    fun interpret(payload: String): MessageInterpretation {
        val tree = parseTree(payload)

        // Checked on the tree, before binding, because binding erases the difference between an
        // absent key and an explicit null.
        if (!tree.has(TRANSACTION_FIELD)) {
            return MessageInterpretation.Unsupported(UnsupportedReason.NO_TRANSACTION_BLOCK)
        }

        val message = bind(tree, payload)
        val transaction =
            message.transaction
                ?: throw UnprocessableEventException(
                    "'transaction' is present but null; an account event omits the field entirely",
                    payload,
                )

        // Fields are checked in payload order — transaction before account, and each block's own
        // fields before the next block's. The DLQ carries only the *first* failure, so the order
        // decides which field an operator is told about; reporting a missing `account.balance` for a
        // payload whose transaction block is also empty sends them to the wrong place first.
        val transactionId = required(transaction.id, "transaction.id", payload)
        val transactionTimestamp = required(transaction.timestamp, "transaction.timestamp", payload)

        val account =
            message.account
                ?: throw UnprocessableEventException("'account' is missing", payload)
        val accountId = required(account.id, "account.id", payload)
        // FR-003a: a missing holder is an invalid message, not an ineligible one. Checked here, in
        // payload order, so the DLQ names `account.owner` rather than whatever field comes later.
        val accountOwner = required(account.owner, "account.owner", payload)
        val accountBalance =
            account.balance
                ?: throw UnprocessableEventException("'account.balance' is missing", payload)

        return try {
            MessageInterpretation.Interpreted(
                TransactionEvent(
                    transaction =
                        Transaction(
                            id = TransactionId(transactionId),
                            // Never fails: absent and unknown both collapse to the negative value,
                            // which makes the event ineligible rather than the message invalid.
                            status = TransactionStatus.from(transaction.status),
                            timestamp = EventTimestamp(transactionTimestamp),
                            type = TransactionType.from(transaction.type),
                            amount = optionalMoney(transaction.amount, transaction.currency),
                        ),
                    account =
                        Account(
                            id = AccountId(accountId),
                            // A blank owner is rejected too: OwnerId's own invariant throws
                            // InvalidTransactionEventException, which the catch below converts.
                            ownerId = OwnerId(accountOwner),
                            status = AccountStatus.from(account.status),
                            balance =
                                Money.of(
                                    required(accountBalance.amount, "account.balance.amount", payload),
                                    CurrencyCode(
                                        required(accountBalance.currency, "account.balance.currency", payload),
                                    ),
                                ),
                        ),
                ),
            )
        } catch (e: InvalidMoneyException) {
            throw UnprocessableEventException(reasonOf(e), payload, e)
        } catch (e: InvalidCurrencyCodeException) {
            throw UnprocessableEventException(reasonOf(e), payload, e)
        } catch (e: InvalidAccountIdException) {
            throw UnprocessableEventException(reasonOf(e), payload, e)
        } catch (e: InvalidTransactionEventException) {
            throw UnprocessableEventException(reasonOf(e), payload, e)
        }
    }

    private fun parseTree(payload: String): JsonNode =
        try {
            objectMapper.readTree(payload)
        } catch (e: JacksonException) {
            throw UnprocessableEventException("payload is not valid JSON", payload, e)
        }

    private fun bind(
        tree: JsonNode,
        payload: String,
    ): TransactionEventMessage =
        try {
            objectMapper.treeToValue(tree, TransactionEventMessage::class.java)
        } catch (e: JacksonException) {
            // Reached when a field carries the wrong JSON type — a textual timestamp, say. The DTO
            // itself requires nothing, so this is genuinely a shape problem, not a missing value.
            throw UnprocessableEventException("payload does not match the transaction event shape", payload, e)
        }

    /**
     * The transaction's own amount is informative and never reaches a balance (FR-014), so an
     * absent one is not a defect. It is still validated when present: a value the system cannot
     * represent exactly would be a lie in the logs.
     */
    private fun optionalMoney(
        amount: BigDecimal?,
        currency: String?,
    ): Money? {
        if (amount == null || currency == null) return null
        return Money.of(amount, CurrencyCode(currency))
    }

    private fun <T : Any> required(
        value: T?,
        field: String,
        payload: String,
    ): T = value ?: throw UnprocessableEventException("'$field' is missing", payload)

    private fun reasonOf(e: RuntimeException): String = e.message ?: e.javaClass.simpleName

    private companion object {
        const val TRANSACTION_FIELD = "transaction"
    }
}
