package br.com.itau.challenge.balance.adapter.input.kafka.mapper

import br.com.itau.challenge.balance.domain.model.TransactionEvent

/**
 * What a message turned out to be.
 *
 * The existence of this type is the whole mechanism behind FR-004a. Discarding a message from
 * another flow is a **terminal success**, so it cannot be signalled by throwing: any exception
 * leaving the listener is caught by the `DefaultErrorHandler`, which would route it to the DLQ —
 * precisely the outcome FR-004a rules out. A sealed return type keeps the discard off the error
 * path entirely.
 *
 * Note what is *not* a variant here: an invalid message. That remains an
 * `UnprocessableEventException`, thrown, because it genuinely belongs on the error path. The two
 * are different destinations and must stay different mechanisms.
 *
 * Both types live in the adapter, not the domain: deciding "what kind of message is this" is a
 * transport concern, not a banking rule.
 */
sealed interface MessageInterpretation {

    data class Interpreted(val event: TransactionEvent) : MessageInterpretation

    data class Unsupported(val reason: UnsupportedReason) : MessageInterpretation
}
