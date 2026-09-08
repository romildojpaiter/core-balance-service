package br.com.itau.challenge.balance.port.input

import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.domain.model.Balance

/**
 * Balance query contract. No HTTP status appears here: an account with no current state is signalled
 * with `BalanceNotFoundException`, which the web adapter maps to `404` (FR-047).
 */
fun interface GetBalanceUseCase {
    fun getBalance(accountId: AccountId): Balance
}
