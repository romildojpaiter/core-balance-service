package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.adapter.input.web.mapper.BalanceResponseMapper
import br.com.itau.challenge.balance.domain.exception.BalanceNotFoundException
import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.domain.model.Balance
import br.com.itau.challenge.balance.port.input.GetBalanceUseCase
import br.com.itau.challenge.balance.support.TestFixtures
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import kotlin.test.assertTrue

/**
 * T076: the endpoint's observable contract, including the shapes it must never produce.
 */
class BalanceControllerTest {

    private class StubUseCase(
        private val behaviour: (AccountId) -> Balance,
    ) : GetBalanceUseCase {
        var called = false

        override fun getBalance(accountId: AccountId): Balance {
            called = true
            return behaviour(accountId)
        }
    }

    private fun mockMvc(useCase: GetBalanceUseCase): MockMvc {
        val builder =
            MockMvcBuilders.standaloneSetup(BalanceController(useCase, BalanceResponseMapper("America/Sao_Paulo")))
        builder.setControllerAdvice(BalanceExceptionHandler())
        builder.addFilters<StandaloneMockMvcBuilder>(CorrelationIdFilter())
        return builder.build()
    }

    @Test
    fun `a known account returns the exact contract body`() {
        val mvc = mockMvc(StubUseCase { TestFixtures.balance() })

        mvc
            .perform(get("/balances/${TestFixtures.ACCOUNT_ID}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(TestFixtures.ACCOUNT_ID))
            .andExpect(jsonPath("$.owner").value(TestFixtures.OWNER_ID))
            // `amount` is deliberately absent from this chain. `jsonPath` parses the body with
            // Jayway, which reads 150.00 into a double and hands back 150.0 — the scale is gone
            // before any assertion here can see it. Asserting through it would therefore accept a
            // Double-backed field just as happily as the BigDecimal one, which is precisely the
            // regression this file must catch. The scale is pinned on the raw body below instead.
            .andExpect(jsonPath("$.balance.currency").value("BRL"))
            .andExpect(jsonPath("$.updated_at").value("2025-07-05T18:04:13.433-03:00"))
            // FR-043a: still persisted, still logged, deliberately not exposed.
            .andExpect(jsonPath("$.lastTransactionId").doesNotExist())
            .andExpect(jsonPath("$.accountId").doesNotExist())
    }

    @Test
    fun `the amount is serialised as a JSON number with its scale intact`() {
        val mvc = mockMvc(StubUseCase { TestFixtures.balance() })

        val body =
            mvc
                .perform(get("/balances/${TestFixtures.ACCOUNT_ID}"))
                .andReturn()
                .response
                .contentAsString

        // FR-044, revised 2026-09-07. The number form is only admissible because the scale survives:
        // `150.00`, never `150`. Constitution X forbids floating-point *types* and requires an exact
        // decimal representation — a JSON number emitted from a BigDecimal breaks neither.
        assertTrue(body.contains("\"amount\":150.00"), "amount must be an unquoted 150.00; body was $body")
    }

    @Test
    fun `an unknown account is 404, never a zero balance`() {
        val mvc = mockMvc(StubUseCase { throw BalanceNotFoundException("not found") })

        mvc
            .perform(get("/balances/unknown-account"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("BALANCE_NOT_FOUND"))
    }

    @Test
    fun `a malformed account id is 400 and the use case is never called`() {
        val useCase = StubUseCase { TestFixtures.balance() }
        val mvc = mockMvc(useCase)

        mvc
            .perform(get("/balances/conta 42"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_ID"))

        // FR-048: the format is rejected before the datastore is touched. Constructing AccountId in
        // the controller before delegating is what makes this true by construction.
        assertTrue(!useCase.called, "the use case must not run for a malformed id")
    }

    @Test
    fun `an internal failure is 500 with a generic body`() {
        val mvc = mockMvc(StubUseCase { throw IllegalStateException("dynamodb table AccountBalances is missing") })

        val body =
            mvc
                .perform(get("/balances/${TestFixtures.ACCOUNT_ID}"))
                .andExpect(status().isInternalServerError)
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error"))
                .andReturn()
                .response
                .contentAsString

        // FR-049: no exception message, table name or endpoint crosses the boundary.
        assertTrue(!body.contains("AccountBalances"), "internal detail leaked: $body")
    }

    @Test
    fun `a generated correlation id is returned to the caller`() {
        val mvc = mockMvc(StubUseCase { TestFixtures.balance() })

        mvc
            .perform(get("/balances/${TestFixtures.ACCOUNT_ID}"))
            .andExpect(header().exists("X-Correlation-Id"))
    }

    @Test
    fun `a supplied correlation id is echoed unchanged`() {
        val mvc = mockMvc(StubUseCase { TestFixtures.balance() })

        mvc
            .perform(
                get("/balances/${TestFixtures.ACCOUNT_ID}")
                    .header("X-Correlation-Id", "trace-123"),
            ).andExpect(header().string("X-Correlation-Id", "trace-123"))
    }

    @Test
    fun `the response is json`() {
        val mvc = mockMvc(StubUseCase { TestFixtures.balance() })

        mvc
            .perform(get("/balances/${TestFixtures.ACCOUNT_ID}"))
            .andExpect(content().contentTypeCompatibleWith("application/json"))
    }
}
