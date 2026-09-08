package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidMoneyException
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An exact monetary amount with its currency.
 *
 * Two absences in this type are deliberate and are the whole point of it:
 *
 * 1. **There is no constructor taking `Double` or `Float`.** Constitution X forbids binary floating
 *    point for money anywhere — transport, domain, persistence and API. Not offering the overload
 *    makes the violation impossible to write rather than merely discouraged.
 * 2. **There is no `plus` or `minus`.** Constitution VIII forbids deriving a balance by arithmetic:
 *    the balance carried by an event is an authoritative snapshot, never a delta to apply. Without
 *    the operators, code that tries to accumulate transactions does not compile.
 *
 * The scale is normalised to exactly 2 using [RoundingMode.UNNECESSARY], which *throws* rather than
 * rounding when the incoming value carries more precision — Constitution X forbids implicit
 * rounding, so a three-decimal amount is a rejected input, not a silently truncated one.
 */
class Money private constructor(
    val amount: BigDecimal,
    val currency: CurrencyCode,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Money) return false
        return amount == other.amount && currency == other.currency
    }

    override fun hashCode(): Int = 31 * amount.hashCode() + currency.hashCode()

    override fun toString(): String = "${amount.toPlainString()} $currency"

    /** The amount as plain decimal text with exactly two places, e.g. `"150.00"` or `"-50.25"`. */
    fun toPlainString(): String = amount.toPlainString()

    companion object {
        const val SCALE: Int = 2

        fun of(
            amount: BigDecimal,
            currency: CurrencyCode,
        ): Money {
            val normalised =
                try {
                    amount.setScale(SCALE, RoundingMode.UNNECESSARY)
                } catch (e: ArithmeticException) {
                    throw InvalidMoneyException(
                        "monetary amount must have at most $SCALE decimal places, got ${amount.toPlainString()}",
                    )
                }
            return Money(normalised, currency)
        }
    }
}
