package com.constructioncost.nativeapp.domain

import java.time.LocalDate

internal fun reminderEligible(schedule: InstalmentRow, today: LocalDate): Boolean {
    require(schedule.reminderDays in 0..365) { "Reminder days must be between 0 and 365" }
    return schedule.remind && !schedule.cancelled && schedule.outstandingPaise > 0 &&
        !LocalDate.parse(schedule.dueDate).isAfter(today.plusDays(schedule.reminderDays.toLong()))
}
