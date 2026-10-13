package com.constructioncost.nativeapp.ui

import com.constructioncost.nativeapp.domain.InsightRow
import org.junit.Assert.assertEquals
import org.junit.Test

class InsightComparisonTest {
    @Test fun topFiveAndOtherKeepExactClassificationMembership() {
        val rows = listOf(
            InsightRow("small", "Small", "STAGE", 100, 1),
            InsightRow("sixth", "Sixth", "STAGE", 200, 1),
            InsightRow("first", "First", "STAGE", 700, 1),
            InsightRow(null, "Unclassified", "WORK", 50, 1),
            InsightRow("fourth", "Fourth", "STAGE", 400, 1),
            InsightRow("second", "Second", "STAGE", 600, 1),
            InsightRow("third", "Third", "STAGE", 500, 1),
            InsightRow("empty", "Empty", "STAGE", 0, 0)
        )
        val result = insightComparison(rows)
        assertEquals(listOf("first", "second", "third", "fourth", "sixth"), result.ranked.take(5).map { it.id })
        assertEquals(listOf("small", null), result.other.map { it.id })
        assertEquals(2550L, result.totalPaise)
        assertEquals(150L, result.otherPaise)
        assertEquals(50L, result.unclassifiedPaise)
        assertEquals(2500L, result.coveredPaise)
    }
}
