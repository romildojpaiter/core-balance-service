package br.com.itau.challenge.balance.adapter.input.kafka.mapper

/**
 * Why a well-formed message belongs to a different flow (FR-004a).
 *
 * This is not an error taxonomy. Every value here describes a message that is *fine* — it simply is
 * not a transaction event, and processing it was never expected.
 */
enum class UnsupportedReason {
    /**
     * The `transaction` block is absent in its entirety — the `{"account": {...}}` shape produced by
     * `make kafka-produce-accounts-events`. A `transaction` block that is present but empty or null
     * is a different case: that one is a defect and goes to the DLQ.
     */
    NO_TRANSACTION_BLOCK,
}
