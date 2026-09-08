package br.com.itau.challenge.balance.domain.model

/**
 * The transaction that originated the event.
 *
 * [amount] is **informative**. No code path combines it with a stored balance: the service is a
 * projection of an authoritative upstream, not a ledger, so the balance comes from
 * [Account.balance] alone (FR-014, Constitution VIII).
 */
data class Transaction(
    val id: TransactionId,
    val status: TransactionStatus,
    val timestamp: EventTimestamp,
    val type: TransactionType,
    val amount: Money?,
)
