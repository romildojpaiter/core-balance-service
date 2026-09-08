package br.com.itau.challenge.balance.port.input

import br.com.itau.challenge.balance.domain.model.TransactionEvent
import br.com.itau.challenge.balance.domain.outcome.ProcessingOutcome

/**
 * Ingestion contract. Nothing about transport — payload, headers, partition, offset — appears in
 * the signature: translating those into a [TransactionEvent] is the input adapter's job.
 */
fun interface ProcessTransactionEventUseCase {
    fun process(event: TransactionEvent): ProcessingOutcome
}
