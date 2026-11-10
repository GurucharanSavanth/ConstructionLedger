package com.constructioncost.nativeapp.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MoneyTest {
    @Test fun oversizedNumericInputsFailBeforeDecimalAllocation() {
        assertThrows(IllegalArgumentException::class.java) { Money.decimal("0." + "1".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { Money.decimal("1" + ",000".repeat(1000)) }
        assertEquals(Long.MAX_VALUE, Money.parse("92233720368547758.07"))
    }

    @Test fun referenceAmountsAndTax() {
        assertEquals(1850000L, Money.parse("18,500"))
        assertEquals(123456789L, Money.parse("12,34,567.89"))
        assertEquals(123456789L, Money.parse("1,234,567.89"))
        assertEquals(250000L, Money.calculate("10", "250").totalPaise)
        assertEquals(31350L, Money.calculate("2.5", "125.40").totalPaise)
        assertEquals(900000L, Money.calculate("1", "10000", discountPercent = "10").totalPaise)
        assertEquals(1112000L, Money.calculate("1", "10000", discountPercent = "10", taxPercent = "18", transport = "500").totalPaise)
        assertEquals(1850000L, Money.parse("१८५००"))
    }
    @Test fun decimalZeroCanAccompanyPercentageCharges() {
        assertEquals(9_000L, Money.calculate("1", "100", discountAmount = "0.00", discountPercent = "10").totalPaise)
        assertEquals(11_800L, Money.calculate("1", "100", taxAmount = "0.00", taxPercent = "18").totalPaise)
    }
    @Test fun settlementReferenceFixtures() {
        assertEquals(600000L, Settlement.outstanding(1000000, listOf(400000)))
        assertEquals(0L, Settlement.outstanding(1000000, listOf(400000, 600000)))
        assertEquals(1000000L, Settlement.outstanding(1000000, listOf(400000, -400000)))
        assertEquals(0L, Money.sum(emptyList()))
        assertEquals(600000L, Settlement.available(1000000, listOf(400000)))
    }
    @Test fun allocationsNeverDrift() {
        assertEquals(listOf(33L, 33L, 35L), Money.proportional(101, listOf(1, 1, 1)))
        assertEquals(listOf(0L, 101L, 0L), Money.proportional(101, listOf(0, 1, 0)))
        assertEquals(listOf(34L, 34L, 33L), Money.divide(101, 3))
        repeat(50) { assertEquals(31350L, Money.calculate("2.5", Money.input(12540)).totalPaise) }
    }
    @Test fun halfUpAfterDiscountAndTax() {
        assertEquals(2L, Money.calculate("0.5", "0.03").totalPaise)
        assertEquals(1L, Money.calculate("0.5", "0.03", discountPercent = "10").totalPaise)
        assertEquals(2L, Money.calculate("0.5", "0.03", discountPercent = "10", taxPercent = "18").totalPaise)
        assertEquals(10000L, Money.calculate("100", "100", quantityUnit = "sqft", rateUnit = "sq100").totalPaise)
    }
    @Test fun finalTotalRoundsOnceWhenDisplayedComponentsRoundIndependently() {
        val calculation = Money.calculate("0.5", "0.01", taxPercent = "100")
        assertEquals(1L, calculation.basePaise)
        assertEquals(1L, calculation.taxPaise)
        assertEquals(1L, calculation.totalPaise)
    }
    @Test fun anchoredMonthEndDates() {
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), Money.scheduleDates(LocalDate.parse("2026-01-31"), 3, true).map { it.toString() })
    }
    @Test fun invalidInputsAndOverflowFail() {
        for (text in listOf("", "-1", "NaN", "1e3", "1.001", "1,2", "1234,567", "92233720368547758.08")) {
            assertThrows(RuntimeException::class.java) { Money.parse(text) }
        }
        assertThrows(ArithmeticException::class.java) { Money.sum(listOf(Long.MAX_VALUE, 1)) }
        assertThrows(IllegalArgumentException::class.java) { Settlement.outstanding(100, listOf(101)) }
        assertThrows(IllegalArgumentException::class.java) { Settlement.available(100, listOf(101)) }
        assertThrows(IllegalArgumentException::class.java) { Money.calculate("1", "100", discountPercent = "101") }
        assertThrows(IllegalArgumentException::class.java) { Money.calculate("1", "100", discountAmount = "5", discountPercent = "5") }
    }
}
