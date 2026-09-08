package br.com.itau.challenge.balance.adapter.input.kafka.exception

/**
 * A payload that cannot be interpreted, no matter how many times it is retried.
 *
 * Retrying would waste the budget and delay the partition to reach the same conclusion, so the
 * error handler routes this straight to the DLQ (FR-038, matrix row 6). The [reason] becomes the
 * `kafka_dlt-exception-message` header, which is the only thing an operator has to work with when
 * triaging the DLQ — so it must name the offending field, not merely say the payload was bad.
 */
class UnprocessableEventException(
    val reason: String,
    val payload: String,
    cause: Throwable? = null,
) : RuntimeException(reason, cause)
