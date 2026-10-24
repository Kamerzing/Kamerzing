package com.kamerzing.payment_core.ledger.domain

/** A positive RUB command amount; account balances and entry deltas are signed Longs. */
data class Money(val amountMinor: Long, val currency: String = "RUB") {
    init {
        require(amountMinor > 0) { "Amount must be positive" }
        require(currency == "RUB") { "Only RUB is supported" }
    }

    fun credit(balance: Long): Long = Math.addExact(balance, amountMinor)
    fun debit(balance: Long): Long = Math.subtractExact(balance, amountMinor)
}
