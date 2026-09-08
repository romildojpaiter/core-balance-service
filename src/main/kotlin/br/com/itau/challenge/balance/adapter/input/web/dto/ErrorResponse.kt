package br.com.itau.challenge.balance.adapter.input.web.dto

/**
 * One shape for every error (FR-050), so a client can handle failures without special-casing.
 *
 * [message] is written for a human reading a log or a screen, never for a machine to branch on —
 * that is what [code] is for — and it never carries an exception message, table name, endpoint or
 * stack trace across the boundary (FR-049).
 */
data class ErrorResponse(
    val code: String,
    val message: String,
)
