package br.com.itau.challenge.balance.adapter.input.web.mapper

import br.com.itau.challenge.balance.adapter.input.web.dto.BalanceAmountResponse
import br.com.itau.challenge.balance.adapter.input.web.dto.BalanceResponse
import br.com.itau.challenge.balance.domain.model.Balance
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Renders a [Balance] for the API.
 *
 * **The amount is not converted.** It crosses as the `BigDecimal` the domain already normalised to
 * scale 2, and Jackson emits it as a JSON number with both decimals intact (FR-044). The text
 * rendering that used to happen here now belongs only to persistence, where `S` is what preserves
 * the scale that DynamoDB's `N` would strip.
 *
 * One conversion does happen here, and it is lossy on purpose: **microseconds become milliseconds,
 * by truncation.** Rounding `…589998` µs to `…590` ms would display an instant that never existed
 * and, at the boundary of a second, an instant in the future. Truncation always names a moment that
 * did occur.
 *
 * That precision loss is **display only**. The persisted value stays in microseconds and remains the
 * sole ordering criterion (FR-016, FR-019); nothing downstream reads this string back.
 */
@Component
class BalanceResponseMapper(
    @param:Value("\${balance.api.timezone}") private val timezone: String,
) {

    private val zone: ZoneId = ZoneId.of(timezone)

    fun toResponse(balance: Balance): BalanceResponse =
        BalanceResponse(
            id = balance.accountId.value,
            owner = balance.ownerId.value,
            balance =
                BalanceAmountResponse(
                    amount = balance.money.amount,
                    currency = balance.money.currency.value,
                ),
            updatedAt = renderInstant(balance.asOf.micros),
        )

    private fun renderInstant(micros: Long): String =
        Instant.EPOCH
            .plus(micros, ChronoUnit.MICROS)
            .atZone(zone)
            .truncatedTo(ChronoUnit.MILLIS)
            .format(FORMATTER)

    private companion object {
        /**
         * Exactly three fractional digits, always. `ISO_OFFSET_DATE_TIME` omits the fraction when it
         * is zero, which would make a balance recorded on a whole second render differently from
         * every other one — a difference a client would have to parse around.
         */
        val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    }
}
