package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.adapter.input.web.mapper.BalanceResponseMapper
import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.domain.model.Balance
import br.com.itau.challenge.balance.port.input.GetBalanceUseCase
import br.com.itau.challenge.balance.support.TestFixtures
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import tools.jackson.databind.json.JsonMapper
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T086: the response is checked against the published contract, not against a copy of it.
 *
 * The REST contract changed twice during this feature — the field was renamed and its format
 * respecified. Each time, the risk was that the code and `balances-api.yaml` drifted apart with
 * every test still green, because the tests asserted what the code already did. This one reads the
 * contract file and fails if the two stop agreeing.
 */
class BalanceResponseContractTest {

    private val contract = File("specs/001-core-banking-balance/contracts/balances-api.yaml").readText()
    private val json = JsonMapper.builder().build()

    private fun mockMvc(balance: Balance): MockMvc {
        val useCase = GetBalanceUseCase { _: AccountId -> balance }
        val builder =
            MockMvcBuilders.standaloneSetup(
                BalanceController(useCase, BalanceResponseMapper("America/Sao_Paulo")),
            )
        builder.addFilters<StandaloneMockMvcBuilder>(CorrelationIdFilter())
        return builder.build()
    }

    private fun rawBodyFor(balance: Balance): String =
        mockMvc(balance)
            .perform(get("/balances/${balance.accountId.value}"))
            .andReturn()
            .response
            .contentAsString

    private fun bodyFor(balance: Balance): Map<*, *> = json.readValue(rawBodyFor(balance), Map::class.java)

    @Test
    fun `the contract file is present and describes this endpoint`() {
        // Guards against the test passing vacuously if the contract is moved or renamed.
        assertTrue(contract.contains("/balances/{accountId}"), "the contract does not describe the endpoint")
    }

    @Test
    fun `the response carries exactly the required properties and no others`() {
        val body = bodyFor(TestFixtures.balance())

        // `additionalProperties: false` in the schema. An extra field is a contract break even when
        // nothing consumes it, because a strict client will reject the whole response.
        assertEquals(
            setOf("id", "owner", "balance", "updated_at"),
            body.keys,
        )
        assertEquals(setOf("amount", "currency"), (body["balance"] as Map<*, *>).keys)
    }

    @Test
    fun `the fields dropped from the contract are absent, not merely undocumented`() {
        // FR-043a. `lastTransactionId` is still persisted and still logged; it is only the response
        // that stopped carrying it. `accountId` was renamed to `id`, not duplicated.
        val body = bodyFor(TestFixtures.balance())

        assertTrue("lastTransactionId" !in body.keys, "lastTransactionId must not be exposed")
        assertTrue("accountId" !in body.keys, "accountId was renamed to id")
    }

    @Test
    fun `every required property named in the contract is present in the response`() {
        val required =
            Regex("""required: \[(id[^]]*)]""")
                .find(contract)
                ?.groupValues
                ?.get(1)
                ?.split(",")
                ?.map { it.trim() }
                ?: error("could not read the required list from the contract")

        assertEquals(required.toSet(), bodyFor(TestFixtures.balance()).keys)
    }

    @Test
    fun `the amount is a JSON number, not a quoted string`() {
        // Checked on the raw body: once parsed, `183.12` and `"183.12"` are equally readable and the
        // distinction the contract makes (`type: number`) would be invisible to the assertion.
        assertTrue(contract.contains("type: number"), "the contract no longer declares amount as a number")

        listOf("150.00", "-50.25", "0.00").forEach { amount ->
            val raw = rawBodyFor(TestFixtures.balance(amount = amount))

            assertTrue(
                raw.contains(""""amount":$amount"""),
                "expected an unquoted \"amount\":$amount in the body, got: $raw",
            )
        }
    }

    @Test
    fun `the scale survives serialisation, so 150 point 00 never arrives as 150`() {
        // This is the single property that separates a JSON number from a Constitution X violation.
        // It is also the one that would break in silence if BigDecimal were ever swapped for Double:
        // every other assertion in this file would still pass.
        val raw = rawBodyFor(TestFixtures.balance(amount = "150.00"))

        assertTrue(raw.contains(""""amount":150.00"""), "the two decimal places were lost: $raw")
        assertTrue(!raw.contains(""""amount":150,"""), "the amount was emitted without its scale: $raw")
    }

    @Test
    fun `the currency is a three letter uppercase code`() {
        val balance = bodyFor(TestFixtures.balance())["balance"] as Map<*, *>

        assertTrue(Regex("""^[A-Z]{3}$""").matches(balance["currency"] as String))
    }

    @Test
    fun `updated_at matches the example the contract publishes`() {
        val body = bodyFor(TestFixtures.balance())

        // The contract's own example, byte for byte.
        assertTrue(contract.contains("\"2025-07-05T18:04:13.433-03:00\""), "the contract example changed")
        assertEquals("2025-07-05T18:04:13.433-03:00", body["updated_at"])
    }
}
