package com.constructioncost.nativeapp.ui

import com.constructioncost.nativeapp.domain.PaymentRow
import org.junit.Assert.assertEquals
import org.junit.Test

class AdvanceBalancesTest {
    @Test fun returnsAffectOnlyTheirOriginalUnallocatedBalance() {
        val payments = listOf(
            PaymentRow("first", "project", 10_000, "2026-10-22", allocatedPaise = 4_000),
            PaymentRow("second", "project", 5_000, "2026-10-22", allocatedPaise = 1_000),
            PaymentRow("refund", "project", 2_000, "2026-10-22", kind = "REFUND", originalPaymentId = "first", allocatedPaise = 500),
            PaymentRow("reversal", "project", 1_000, "2026-10-22", kind = "REVERSAL", originalPaymentId = "first", allocatedPaise = 1_000),
            PaymentRow("other-refund", "project", 500, "2026-10-22", kind = "REFUND", originalPaymentId = "second")
        )
        assertEquals(mapOf("first" to 4_500L, "second" to 3_500L), advanceBalances(payments))
        assertEquals(emptyMap<String, Long>(), advanceBalances(emptyList()))
    }
}
