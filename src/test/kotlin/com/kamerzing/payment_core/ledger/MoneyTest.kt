package com.kamerzing.payment_core.ledger

import com.kamerzing.payment_core.ledger.domain.Money
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun positiveRubleAmountsOnly() {
        assertFailsWith<IllegalArgumentException> { Money(0) }
        assertFailsWith<IllegalArgumentException> { Money(-1) }
        assertFailsWith<IllegalArgumentException> { Money(1, "USD") }
        assertEquals(Long.MAX_VALUE, Money(Long.MAX_VALUE).amountMinor)
    }

    @Test
    fun arithmeticNeverWraps() {
        assertEquals(150L, Money(50).credit(100))
        assertEquals(-50L, Money(50).debit(0))
        assertFailsWith<ArithmeticException> { Money(1).credit(Long.MAX_VALUE) }
        assertFailsWith<ArithmeticException> { Money(1).debit(Long.MIN_VALUE) }
    }
}
