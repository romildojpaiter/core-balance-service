package br.com.itau.challenge.balance.port.output

import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.domain.model.Balance

/**
 * Reads the current state of an account.
 *
 * **Contract**: the read MUST NOT return state older than a completed write for that account
 * (FR-033) — i.e. strongly consistent (ADR-003).
 */
fun interface BalanceReader {
    fun findByAccountId(accountId: AccountId): Balance?
}
