package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.domain.model.Balance
import br.com.itau.challenge.balance.domain.model.CurrencyCode
import br.com.itau.challenge.balance.domain.model.EventTimestamp
import br.com.itau.challenge.balance.domain.model.Money
import br.com.itau.challenge.balance.domain.model.OwnerId
import br.com.itau.challenge.balance.domain.model.TransactionId
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

/**
 * Translates [Balance] to and from a DynamoDB item.
 *
 * Two type choices carry the whole correctness of the mapping and are not interchangeable:
 *
 * - **`balanceAmount` is stored as `S`, not `N`.** DynamoDB's `N` is a normalised numeric type: it
 *   strips trailing zeros, so `150.00` comes back as `"150"`. FR-044 requires exactly two decimal
 *   places in the API response, and Constitution X forbids reconstructing them by formatting a
 *   number whose scale was already lost. Decimal *text* survives the round trip untouched.
 * - **`lastEventTimestamp` is stored as `N`.** Here numeric comparison is mandatory: this attribute
 *   is the left operand of the conditional expression that decides freshness, and DynamoDB compares
 *   `S` lexicographically — `"9"` would sort after `"10"` and let a stale event overwrite a newer
 *   balance.
 */
@Component
class BalanceItemMapper(
    private val clock: Clock = Clock.systemUTC(),
) {

    fun toItem(balance: Balance): Map<String, AttributeValue> =
        buildMap {
            put(BalanceTableAttributes.PK, AttributeValue.fromS(BalanceTableAttributes.partitionKey(balance.accountId)))
            put(BalanceTableAttributes.ACCOUNT_ID, AttributeValue.fromS(balance.accountId.value))
            put(BalanceTableAttributes.OWNER_ID, AttributeValue.fromS(balance.ownerId.value))
            put(BalanceTableAttributes.BALANCE_AMOUNT, AttributeValue.fromS(balance.money.toPlainString()))
            put(BalanceTableAttributes.BALANCE_CURRENCY, AttributeValue.fromS(balance.money.currency.value))
            put(
                BalanceTableAttributes.LAST_EVENT_TIMESTAMP,
                AttributeValue.fromN(balance.asOf.micros.toString()),
            )
            put(BalanceTableAttributes.LAST_TRANSACTION_ID, AttributeValue.fromS(balance.lastTransactionId.value))
            // Application wall clock, written for operators only. No decision anywhere reads it —
            // freshness is decided by lastEventTimestamp, which comes from the event. Do not confuse
            // it with the REST response's `updated_at`, which is rendered from the event timestamp.
            put(BalanceTableAttributes.UPDATED_AT, AttributeValue.fromS(Instant.now(clock).toString()))
        }

    fun toBalance(item: Map<String, AttributeValue>): Balance =
        Balance(
            accountId = AccountId(required(item, BalanceTableAttributes.ACCOUNT_ID)),
            // Required like every other attribute: no path in this feature can write an item
            // without a holder (FR-003a), so one that lacks it is corrupt rather than legitimate.
            // Inventing an owner here would assert a fact the system does not know.
            ownerId = OwnerId(required(item, BalanceTableAttributes.OWNER_ID)),
            money =
                Money.of(
                    BigDecimal(required(item, BalanceTableAttributes.BALANCE_AMOUNT)),
                    CurrencyCode(required(item, BalanceTableAttributes.BALANCE_CURRENCY)),
                ),
            asOf = EventTimestamp(requiredNumber(item, BalanceTableAttributes.LAST_EVENT_TIMESTAMP)),
            lastTransactionId = TransactionId(required(item, BalanceTableAttributes.LAST_TRANSACTION_ID)),
        )

    private fun required(
        item: Map<String, AttributeValue>,
        name: String,
    ): String =
        item[name]?.s()
            ?: throw IllegalStateException("persisted balance item is missing the '$name' attribute")

    private fun requiredNumber(
        item: Map<String, AttributeValue>,
        name: String,
    ): Long =
        item[name]?.n()?.toLongOrNull()
            ?: throw IllegalStateException("persisted balance item is missing a numeric '$name' attribute")
}
