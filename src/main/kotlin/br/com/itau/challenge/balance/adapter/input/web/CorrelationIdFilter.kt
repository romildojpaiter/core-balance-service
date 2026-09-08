package br.com.itau.challenge.balance.adapter.input.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Ties a query to the structured logs it produces.
 *
 * The identifier is echoed back on the response so that a caller reporting "my request failed" can
 * name the exact request, rather than a time window an operator has to guess at.
 *
 * The MDC is cleared in `finally` without exception. Servlet containers pool threads, and a leaked
 * `accountId` attributes the next request to the wrong account — a wrong answer, which is worse
 * than no answer.
 */
@Component
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId =
            request.getHeader(CORRELATION_ID_HEADER)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()

        MDC.put(MDC_CORRELATION_ID, correlationId)
        accountIdOf(request)?.let { MDC.put(MDC_ACCOUNT_ID, it) }
        response.setHeader(CORRELATION_ID_HEADER, correlationId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_CORRELATION_ID)
            MDC.remove(MDC_ACCOUNT_ID)
        }
    }

    /**
     * Read from the path rather than from a resolved `@PathVariable`: the filter runs before the
     * handler is chosen, and a request that never reaches a controller is exactly the one whose logs
     * most need the account it was asking about.
     */
    private fun accountIdOf(request: HttpServletRequest): String? =
        request.requestURI
            .substringAfter(BALANCES_PATH, missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.substringBefore('/')

    private companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-Id"
        const val MDC_CORRELATION_ID = "correlationId"
        const val MDC_ACCOUNT_ID = "accountId"
        const val BALANCES_PATH = "/balances/"
    }
}
