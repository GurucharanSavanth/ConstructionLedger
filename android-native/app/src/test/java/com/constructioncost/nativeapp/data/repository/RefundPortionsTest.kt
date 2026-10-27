package com.constructioncost.nativeapp.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RefundPortionsTest {
    @Test fun residualsRemainBoundedAndDeterministic() {
        assertEquals(listOf(1L, 1L, 0L), refundPortions(2, listOf(1, 1, 1)))
        assertEquals(listOf(1L, 1L, 4L), refundPortions(6, listOf(1, 2, 7)))
        assertEquals(listOf(1L, 0L, 0L), refundPortions(1, listOf(1, 1, 1)))
        assertEquals(listOf(0L, 1L, 0L), refundPortions(1, listOf(0, 1, 0)))
        assertEquals(listOf(1L, 2L, 7L), refundPortions(10, listOf(1, 2, 7)))
        assertEquals(listOf(0L, 0L, 0L), refundPortions(0, listOf(1, 2, 7)))
        assertEquals(listOf(0L, 0L), refundPortions(0, listOf(0, 0)))
        assertEquals(emptyList<Long>(), refundPortions(0, emptyList()))
        val remaining = listOf(1L, 1L, 1L).zip(refundPortions(2, listOf(1, 1, 1))) { balance, part -> balance - part }
        assertEquals(listOf(0L, 0L, 1L), refundPortions(1, remaining))
    }

    @Test fun longBoundaryUsesExactProductsAndRejectsInvalidBalances() {
        assertEquals(listOf(Long.MAX_VALUE - 1, 0L), refundPortions(Long.MAX_VALUE - 1, listOf(Long.MAX_VALUE - 1, 1)))
        assertEquals(listOf(Long.MAX_VALUE - 1, 1L), refundPortions(Long.MAX_VALUE, listOf(Long.MAX_VALUE - 1, 1)))
        assertEquals(listOf(Long.MAX_VALUE), refundPortions(Long.MAX_VALUE, listOf(Long.MAX_VALUE)))
        assertThrows(ArithmeticException::class.java) { refundPortions(1, listOf(Long.MAX_VALUE, 1)) }
        assertThrows(IllegalArgumentException::class.java) { refundPortions(-1, listOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { refundPortions(1, listOf(-1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { refundPortions(4, listOf(1, 1, 1)) }
        assertThrows(IllegalArgumentException::class.java) { refundPortions(1, emptyList()) }
    }

    @Test fun seededRandomRefundsConserveFundsUntilEveryBalanceIsReturned() {
        val random = Random(20261024)
        repeat(2000) {
            var balances = List(random.nextInt(1, 51)) { random.nextLong(0, 100001) }
            var available = balances.sum()
            while (available > 0) {
                val requested = random.nextLong(1, available + 1)
                val parts = refundPortions(requested, balances)
                assertEquals(requested, parts.sum())
                assertEquals(parts, refundPortions(requested, balances))
                parts.forEachIndexed { index, part -> assertTrue(part in 0..balances[index]) }
                balances = balances.zip(parts) { balance, part -> balance - part }
                available -= requested
                assertEquals(available, balances.sum())
            }
            assertTrue(balances.all { it == 0L })
        }
    }
}
