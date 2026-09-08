package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.adapter.input.web.dto.BalanceResponse
import br.com.itau.challenge.balance.adapter.input.web.mapper.BalanceResponseMapper
import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.port.input.GetBalanceUseCase
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * The one query this service exposes.
 *
 * Note the order of the first two statements: [AccountId] is constructed **before** the use case is
 * called, so a malformed identifier is rejected without the datastore ever being touched. FR-048
 * asks for that guarantee, and building the value object first gives it by construction rather than
 * by a validation step someone could later reorder.
 *
 * There is no validation, formatting or status decision in this class. Those live in the value
 * object, the mapper and the exception handler respectively — a controller that did any of them
 * would be a fourth place to look when the API misbehaves.
 */
@RestController
class BalanceController(
    private val getBalance: GetBalanceUseCase,
    private val responseMapper: BalanceResponseMapper,
) {

    @GetMapping("/balances/{accountId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getBalance(@PathVariable accountId: String,
    ): BalanceResponse = responseMapper.toResponse(getBalance.getBalance(AccountId(accountId)))
}
