package br.com.itau.challenge.balance.support

import br.com.itau.challenge.balance.domain.model.Account
import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.domain.model.AccountStatus
import br.com.itau.challenge.balance.domain.model.Balance
import br.com.itau.challenge.balance.domain.model.CurrencyCode
import br.com.itau.challenge.balance.domain.model.EventTimestamp
import br.com.itau.challenge.balance.domain.model.Money
import br.com.itau.challenge.balance.domain.model.OwnerId
import br.com.itau.challenge.balance.domain.model.Transaction
import br.com.itau.challenge.balance.domain.model.TransactionEvent
import br.com.itau.challenge.balance.domain.model.TransactionId
import br.com.itau.challenge.balance.domain.model.TransactionStatus
import br.com.itau.challenge.balance.domain.model.TransactionType
import java.math.BigDecimal

/**
 * Builders for the challenge's canonical payload, so a test can state only the field it is about.
 *
 * Defaults are the eligible happy path (`APPROVED` + `ENABLED`); every negative case is one named
 * argument away, which keeps the interesting difference visible in the test body.
 */
object TestFixtures {

    const val ACCOUNT_ID = "5e4f2a3b-1c9d-4a7e-8f6b-2d3c4e5f6a7b"
    const val TRANSACTION_ID = "9f8e7d6c-5b4a-3928-1716-0f5e4d3c2b1a"
    const val OWNER_ID = "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e"

    /** `2025-07-05T18:04:13.433-03:00` rendered as microseconds since the epoch. */
    const val TIMESTAMP_MICROS = 1_751_749_453_433_589L

    val BRL = CurrencyCode("BRL")

    fun money(amount: String): Money = Money.of(BigDecimal(amount), BRL)

    fun account(
        id: String = ACCOUNT_ID,
        status: AccountStatus = AccountStatus.ENABLED,
        balance: String = "150.00",
        // Not nullable: FR-003a made a holder-less account unrepresentable in the domain, and a
        // fixture that could still build one would let a test assert state the spec forbids. The
        // absent-owner case now exists only as a raw JSON payload, at the boundary that rejects it.
        ownerId: String = OWNER_ID,
    ) = Account(
        id = AccountId(id),
        ownerId = OwnerId(ownerId),
        status = status,
        balance = money(balance),
    )

    fun transaction(
        id: String = TRANSACTION_ID,
        status: TransactionStatus = TransactionStatus.APPROVED,
        micros: Long = TIMESTAMP_MICROS,
        type: TransactionType = TransactionType.DEBIT,
        amount: String? = "30.00",
    ) = Transaction(
        id = TransactionId(id),
        status = status,
        timestamp = EventTimestamp(micros),
        type = type,
        amount = amount?.let { money(it) },
    )

    fun event(
        transaction: Transaction = transaction(),
        account: Account = account(),
    ) = TransactionEvent(transaction = transaction, account = account)

    fun balance(
        accountId: String = ACCOUNT_ID,
        amount: String = "150.00",
        micros: Long = TIMESTAMP_MICROS,
        transactionId: String = TRANSACTION_ID,
        ownerId: String = OWNER_ID,
    ) = Balance(
        accountId = AccountId(accountId),
        ownerId = OwnerId(ownerId),
        money = money(amount),
        asOf = EventTimestamp(micros),
        lastTransactionId = TransactionId(transactionId),
    )
}
