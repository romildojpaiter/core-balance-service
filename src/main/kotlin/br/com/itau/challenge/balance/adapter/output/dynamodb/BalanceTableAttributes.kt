package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.AccountId

/**
 * The single place that knows the physical shape of the `AccountBalances` item.
 *
 * The domain never sees a partition key: `ACCOUNT#{accountId}` is a storage decision, and confining
 * it here means changing the key layout touches one file (ADR-008).
 */
object BalanceTableAttributes {

    const val PK = "pk"
    const val ACCOUNT_ID = "accountId"
    const val OWNER_ID = "ownerId"
    const val BALANCE_AMOUNT = "balanceAmount"
    const val BALANCE_CURRENCY = "balanceCurrency"
    const val LAST_EVENT_TIMESTAMP = "lastEventTimestamp"
    const val LAST_TRANSACTION_ID = "lastTransactionId"
    const val UPDATED_AT = "updatedAt"

    private const val ACCOUNT_PREFIX = "ACCOUNT#"

    fun partitionKey(accountId: AccountId): String = ACCOUNT_PREFIX + accountId.value
}
