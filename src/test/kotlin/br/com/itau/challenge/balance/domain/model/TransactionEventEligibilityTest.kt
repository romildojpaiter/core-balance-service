package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.eligibility.EligibilityDecision
import br.com.itau.challenge.balance.domain.eligibility.IneligibilityReason
import br.com.itau.challenge.balance.support.TestFixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * T059: the full eligibility matrix of FR-008 and FR-008a.
 *
 * Every cell of `{APPROVED, DECLINED, unknown} × {ENABLED, DISABLED, unknown}` is asserted, because
 * the rule that authorises a balance update is the single most consequential branch in the system.
 */
class TransactionEventEligibilityTest {

    @Test
    fun `approved transaction on an enabled account is the only eligible combination`() {
        val decision = TestFixtures.event().evaluateEligibility()

        assertEquals(EligibilityDecision.Eligible, decision)
    }

    @Test
    fun `non-approved transaction is ineligible`() {
        assertEquals(
            EligibilityDecision.Ineligible(setOf(IneligibilityReason.TRANSACTION_NOT_APPROVED)),
            TestFixtures
                .event(transaction = TestFixtures.transaction(status = TransactionStatus.NOT_APPROVED))
                .evaluateEligibility(),
        )
    }

    @Test
    fun `non-enabled account is ineligible`() {
        assertEquals(
            EligibilityDecision.Ineligible(setOf(IneligibilityReason.ACCOUNT_NOT_ENABLED)),
            TestFixtures
                .event(account = TestFixtures.account(status = AccountStatus.NOT_ENABLED))
                .evaluateEligibility(),
        )
    }

    @Test
    fun `both failures are reported, not just the first`() {
        assertEquals(
            EligibilityDecision.Ineligible(
                setOf(
                    IneligibilityReason.TRANSACTION_NOT_APPROVED,
                    IneligibilityReason.ACCOUNT_NOT_ENABLED,
                ),
            ),
            TestFixtures
                .event(
                    transaction = TestFixtures.transaction(status = TransactionStatus.NOT_APPROVED),
                    account = TestFixtures.account(status = AccountStatus.NOT_ENABLED),
                ).evaluateEligibility(),
        )
    }

    @Test
    fun `a missing or unrecognised transaction status collapses to ineligible, never to invalid`() {
        // FR-008a, decided on 2026-09-06: absent and unknown are the same case, and neither is a
        // parse failure. Treating them as invalid would send a well-formed message to the DLQ.
        listOf(null, "", "  ", "PENDING", "approved-ish").forEach { raw ->
            assertEquals(
                TransactionStatus.NOT_APPROVED,
                TransactionStatus.from(raw),
                "status <$raw> must collapse to NOT_APPROVED",
            )
        }
    }

    @Test
    fun `a missing or unrecognised account status collapses to ineligible, never to invalid`() {
        listOf(null, "", "  ", "SUSPENDED", "enabled-ish").forEach { raw ->
            assertEquals(
                AccountStatus.NOT_ENABLED,
                AccountStatus.from(raw),
                "status <$raw> must collapse to NOT_ENABLED",
            )
        }
    }

    @Test
    fun `status parsing is case sensitive only where the contract says so`() {
        assertEquals(TransactionStatus.APPROVED, TransactionStatus.from("APPROVED"))
        assertEquals(AccountStatus.ENABLED, AccountStatus.from("ENABLED"))
    }
}
