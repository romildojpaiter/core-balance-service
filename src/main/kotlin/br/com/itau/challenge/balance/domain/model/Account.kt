package br.com.itau.challenge.balance.domain.model

/**
 * The state of the account **at the instant of the event**, as delivered by the producer.
 *
 * This is not the persisted state — that is [Balance]. This is a block of the incoming event, and
 * [balance] is the authoritative snapshot it carries (Constitution VIII).
 */
data class Account(
    val id: AccountId,
    val ownerId: OwnerId,
    val status: AccountStatus,
    val balance: Money,
)
