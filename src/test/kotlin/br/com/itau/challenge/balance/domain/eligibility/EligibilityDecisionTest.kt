package br.com.itau.challenge.balance.domain.eligibility

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EligibilityDecisionTest {

    @Test
    fun `an ineligible decision must name at least one reason`() {
        // A reasonless rejection is unexplainable in a log, and FR-054 requires every non-mutating
        // outcome to be observable — which means it has to say why.
        assertFailsWith<IllegalArgumentException> { EligibilityDecision.Ineligible(emptySet()) }
    }

    @Test
    fun `decisions compare by their reasons`() {
        assertEquals(
            EligibilityDecision.Ineligible(setOf(IneligibilityReason.ACCOUNT_NOT_ENABLED)),
            EligibilityDecision.Ineligible(setOf(IneligibilityReason.ACCOUNT_NOT_ENABLED)),
        )
    }
}
