package br.com.itau.challenge.balance.domain.outcome

/**
 * Why the datastore refused to apply a snapshot. All three are **successful, non-mutating**
 * outcomes that authorise acknowledging the message (FR-022) — they are not errors and never reach
 * the DLQ.
 */
enum class RejectionReason {
    /** The incoming `transaction.id` equals the one that produced the current state (FR-023). */
    DUPLICATE_EVENT,

    /** A lower timestamp from a different transaction (FR-023). */
    STALE_EVENT,

    /** An equal timestamp from a different transaction (FR-023, spec A-03). */
    TIMESTAMP_TIE_REJECTED,
}
