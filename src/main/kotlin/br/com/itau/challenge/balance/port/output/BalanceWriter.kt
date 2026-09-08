package br.com.itau.challenge.balance.port.output

import br.com.itau.challenge.balance.domain.model.Balance

/**
 * Persists the current state of an account.
 *
 * **Contract**: the freshness condition (`persisted.asOf < incoming.asOf`, or no state at all) MUST
 * be evaluated atomically by the datastore, inside the same operation that writes (FR-021,
 * Constitution V). An implementation that reads, compares in memory and then writes does not
 * satisfy this contract, however careful the comparison — between the read and the write another
 * consumer can commit a newer state.
 */
fun interface BalanceWriter {
    fun save(balance: Balance): BalanceWriteResult
}
