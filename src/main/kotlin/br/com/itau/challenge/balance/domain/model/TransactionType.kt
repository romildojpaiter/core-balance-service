package br.com.itau.challenge.balance.domain.model

/**
 * Informative only. The type never influences the persisted balance: a `DEBIT` carrying
 * `transaction.amount = 30.00` alongside `account.balance.amount = 70.00` persists `70.00`, the
 * snapshot, not the result of any subtraction (FR-014, Constitution VIII).
 */
enum class TransactionType {
    CREDIT,
    DEBIT,
    UNKNOWN,
    ;

    companion object {
        fun from(raw: String?): TransactionType =
            when (raw) {
                "CREDIT" -> CREDIT
                "DEBIT" -> DEBIT
                else -> UNKNOWN
            }
    }
}
