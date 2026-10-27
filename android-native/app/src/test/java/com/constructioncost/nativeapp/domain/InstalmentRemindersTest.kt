package com.constructioncost.nativeapp.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class InstalmentRemindersTest {
    @Test fun remindersRespectLeadDaysUnpaidBalanceAndCancellation() {
        val today = LocalDate.of(2026, 10, 13)
        val plan = InstalmentRow("plan", "project", "Concrete", 2500000, "2026-10-20", remind = true, reminderDays = 7)
        assertTrue(reminderEligible(plan, today))
        assertFalse(reminderEligible(plan.copy(reminderDays = 3), today))
        assertTrue(reminderEligible(plan.copy(dueDate = "2026-10-13", reminderDays = 0), today))
        assertFalse(reminderEligible(plan.copy(dueDate = "2026-10-14", reminderDays = 0), today))
        assertTrue(reminderEligible(plan.copy(dueDate = "2026-10-12", reminderDays = 0), today))
        assertFalse(reminderEligible(plan.copy(remind = false), today))
        assertFalse(reminderEligible(plan.copy(cancelled = true), today))
        assertFalse(reminderEligible(plan.copy(paidPaise = 2500000, outstandingPaise = 0), today))
    }
}
