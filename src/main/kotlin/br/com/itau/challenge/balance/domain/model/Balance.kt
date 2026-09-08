package br.com.itau.challenge.balance.domain.model

/**
 * The current projected state of an account: what the service persists and exposes.
 *
 * There is exactly one per account (FR-029), and it always corresponds to the eligible event with
 * the highest [asOf] accepted so far (INV-001).
 *
 * There is deliberately **no constructor that accepts a previous balance**. Deriving a balance from
 * an earlier one is what Constitution VIII forbids, and the absence of such a constructor makes it
 * structurally impossible rather than merely against the rules.
 */
data class Balance(
    val accountId: AccountId,
    val ownerId: OwnerId,
    val money: Money,
    val asOf: EventTimestamp,
    val lastTransactionId: TransactionId,
)
