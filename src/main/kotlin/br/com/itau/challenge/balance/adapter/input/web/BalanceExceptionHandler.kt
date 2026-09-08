package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.adapter.input.web.dto.ErrorResponse
import br.com.itau.challenge.balance.domain.exception.BalanceNotFoundException
import br.com.itau.challenge.balance.domain.exception.InvalidAccountIdException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Translates domain failures into HTTP, and stops everything else at the boundary.
 *
 * The `404` matters more than it looks: an account with no recorded state is **not** an account
 * holding `0.00`. Returning zero would assert a financial fact the system does not know
 * (Constitution I, FR-047) — and an account that has only ever received ineligible events lands
 * here, correctly.
 */
@RestControllerAdvice
class BalanceExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(InvalidAccountIdException::class)
    fun handleInvalidAccountId(e: InvalidAccountIdException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(CODE_INVALID_ACCOUNT_ID, "accountId must be non-empty and match [A-Za-z0-9-]"))

    @ExceptionHandler(BalanceNotFoundException::class)
    fun handleBalanceNotFound(e: BalanceNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(CODE_BALANCE_NOT_FOUND, "No balance found for the given account"))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        // Logged in full on the server, reported as a fixed string to the client. FR-049: no
        // exception message, table name, endpoint or stack trace crosses the boundary — those are
        // the details an attacker uses to map the system, and the caller can do nothing with them.
        log.error("unexpected failure while serving a balance query", e)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(CODE_INTERNAL_ERROR, "Unexpected error"))
    }

    private companion object {
        const val CODE_INVALID_ACCOUNT_ID = "INVALID_ACCOUNT_ID"
        const val CODE_BALANCE_NOT_FOUND = "BALANCE_NOT_FOUND"
        const val CODE_INTERNAL_ERROR = "INTERNAL_ERROR"
    }
}
