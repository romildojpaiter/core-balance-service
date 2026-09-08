package br.com.itau.challenge.balance.port.output

import br.com.itau.challenge.balance.domain.model.Balance

/**
 * The outcome of a conditional write, expressed without any datastore vocabulary.
 *
 * A **transient failure is deliberately not a variant here**: it is an exception, because it has to
 * propagate up to the Kafka container to trigger retry (FR-036). Modelling it as a value would let
 * the offset be committed for an event that was never applied, violating FR-034.
 */
sealed interface BalanceWriteResult {

    data object Applied : BalanceWriteResult

    data class Duplicate(val persisted: Balance) : BalanceWriteResult

    data class Stale(val persisted: Balance) : BalanceWriteResult

    data class TimestampTie(val persisted: Balance) : BalanceWriteResult
}
