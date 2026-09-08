package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException

/**
 * The freshness marker of an account's persisted state: the `transaction.timestamp` of the event
 * that produced it, in **microseconds** since the Unix epoch (spec A-02, confirmed 2026-09-06).
 *
 * The unit lives here and nowhere else, so changing it would be a one-file change.
 *
 * [isNewerThan] exists for tests and telemetry. It is **not** how the service decides whether to
 * write: Constitution V requires that comparison to be evaluated inside the persistence operation,
 * atomically with the write, because an in-memory comparison leaves a window in which another
 * consumer can commit a newer state. See the conditional expression in the DynamoDB writer.
 */
data class EventTimestamp(val micros: Long) : Comparable<EventTimestamp> {

    init {
        if (micros <= 0) {
            throw InvalidTransactionEventException("transaction.timestamp must be a positive number of microseconds")
        }
    }

    fun isNewerThan(other: EventTimestamp): Boolean = micros > other.micros

    override fun compareTo(other: EventTimestamp): Int = micros.compareTo(other.micros)

    override fun toString(): String = micros.toString()
}
