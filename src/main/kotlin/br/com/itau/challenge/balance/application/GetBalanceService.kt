package br.com.itau.challenge.balance.application

import br.com.itau.challenge.balance.domain.exception.BalanceNotFoundException
import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.domain.model.Balance
import br.com.itau.challenge.balance.port.input.GetBalanceUseCase
import br.com.itau.challenge.balance.port.output.BalanceReader
import org.springframework.stereotype.Service

/**
 * Reads the current persisted state of one account.
 *
 * The absent account throws rather than returning a zero balance. Constitution I makes that
 * non-negotiable: `0.00` is a real, meaningful balance, and returning it for an account the system
 * has never seen would be indistinguishable from an account that genuinely holds nothing (FR-047,
 * FR-051). This path performs no write — a read must never create state.
 */
@Service
class GetBalanceService(
    private val balanceReader: BalanceReader,
) : GetBalanceUseCase {

    override fun getBalance(accountId: AccountId): Balance =
        balanceReader.findByAccountId(accountId)
            ?: throw BalanceNotFoundException("no balance is recorded for account ${accountId.value}")
}
