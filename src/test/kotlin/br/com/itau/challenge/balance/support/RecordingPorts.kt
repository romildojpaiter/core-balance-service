package br.com.itau.challenge.balance.support

import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.domain.model.Balance
import br.com.itau.challenge.balance.domain.outcome.ProcessingOutcome
import br.com.itau.challenge.balance.port.output.BalanceReader
import br.com.itau.challenge.balance.port.output.BalanceTelemetry
import br.com.itau.challenge.balance.port.output.BalanceWriteResult
import br.com.itau.challenge.balance.port.output.BalanceWriter
import java.time.Duration

/**
 * Hand-written test doubles for the output ports.
 *
 * They are preferred over a mocking framework here for one reason that matters to the assertions:
 * "the writer was never called" (INV-003) and "telemetry was called exactly once" (FR-054) are
 * statements about *counts*, and a recorded list makes the failure message show what actually
 * happened instead of a stubbing trace.
 */
class RecordingBalanceWriter(
    private val result: (Balance) -> BalanceWriteResult = { BalanceWriteResult.Applied },
) : BalanceWriter {
    val saved = mutableListOf<Balance>()

    override fun save(balance: Balance): BalanceWriteResult {
        saved += balance
        return result(balance)
    }
}

class ThrowingBalanceWriter(private val error: () -> Throwable) : BalanceWriter {
    override fun save(balance: Balance): BalanceWriteResult = throw error()
}

class RecordingBalanceReader(private val stored: Map<String, Balance> = emptyMap()) : BalanceReader {
    val queried = mutableListOf<AccountId>()

    override fun findByAccountId(accountId: AccountId): Balance? {
        queried += accountId
        return stored[accountId.value]
    }
}

class RecordingTelemetry : BalanceTelemetry {
    data class OutcomeCall(
        val outcome: ProcessingOutcome,
        val context: BalanceTelemetry.EventContext,
        val elapsed: Duration,
    )

    val received = mutableListOf<BalanceTelemetry.EventContext>()
    val outcomes = mutableListOf<OutcomeCall>()
    val unsupported = mutableListOf<Pair<String, BalanceTelemetry.EventContext>>()
    val persistenceFailures = mutableListOf<Triple<BalanceTelemetry.EventContext, Boolean, Throwable>>()
    val anomalies = mutableListOf<Pair<String, BalanceTelemetry.EventContext>>()

    override fun eventReceived(context: BalanceTelemetry.EventContext) {
        received += context
    }

    override fun outcomeRecorded(
        outcome: ProcessingOutcome,
        context: BalanceTelemetry.EventContext,
        elapsed: Duration,
    ) {
        outcomes += OutcomeCall(outcome, context, elapsed)
    }

    override fun unsupportedMessage(
        reason: String,
        context: BalanceTelemetry.EventContext,
    ) {
        unsupported += reason to context
    }

    override fun persistenceFailed(
        context: BalanceTelemetry.EventContext,
        transient: Boolean,
        cause: Throwable,
    ) {
        persistenceFailures += Triple(context, transient, cause)
    }

    override fun anomalyDetected(
        anomaly: String,
        context: BalanceTelemetry.EventContext,
    ) {
        anomalies += anomaly to context
    }
}
