package com.constructioncost.nativeapp.ui

import com.constructioncost.nativeapp.domain.InsightRow
import com.constructioncost.nativeapp.domain.Money
import com.constructioncost.nativeapp.domain.MonthlyRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerChartsTest {
    @Test fun donutKeepsExactTopFiveAndOtherAmountsAndDrillTargets() {
        val rows = (1..7).map { InsightRow("id-$it", "Group $it", "STAGE", it * 101L, 1) }
        val comparison = insightComparison(rows)
        val segments = insightDonutSegments(comparison)
        assertEquals(6, segments.size)
        assertEquals(listOf("id-7", "id-6", "id-5", "id-4", "id-3"), segments.take(5).map { it.row?.id })
        assertEquals("Other", segments.last().name)
        assertEquals(303L, segments.last().amountPaise)
        assertNull(segments.last().row)
        assertEquals(comparison.totalPaise, Money.sum(segments.map { it.amountPaise }))
    }

    @Test fun unclassifiedSegmentKeepsItsRowInsteadOfOpeningOther() {
        val row = InsightRow(null, "Unclassified", "WORK", 123L, 1)
        val segments = insightDonutSegments(insightComparison(listOf(row)))
        assertEquals(1, segments.size)
        assertNotNull(segments.single().row)
        assertEquals(row, segments.single().row)
        assertTrue(insightDonutSegments(insightComparison(emptyList())).isEmpty())
    }

    @Test fun cashMonthsKeepOnlyLastTwelveRecordedMonthsIncludingZeroAndReturns() {
        val rows = (1..12).map { MonthlyRow("2024-${it.toString().padStart(2, '0')}", 0, it * 100L) } +
            listOf(MonthlyRow("2025-04", 0, -501), MonthlyRow("2025-08", 0, 0))
        val selected = cashChartMonths(rows.reversed())
        assertEquals(12, selected.size)
        assertEquals("2024-03", selected.first().month)
        assertEquals(listOf("2025-04", "2025-08"), selected.takeLast(2).map { it.month })
        assertEquals(listOf(-501L, 0L), selected.takeLast(2).map { it.paidPaise })
        assertTrue(cashChartMonths(emptyList()).isEmpty())
    }

    @Test fun cashPlotUsesSharedZeroBaselineForSignedNetCashWithoutOverflow() {
        assertEquals(0f, cashChartY(200, -100, 200), 0f)
        assertEquals(2f / 3f, cashChartY(0, -100, 200), .00001f)
        assertEquals(1f, cashChartY(-100, -100, 200), 0f)
        assertEquals(1f, cashChartY(0, 0, 0), 0f)
        assertEquals(0f, cashChartY(0, -100, 0), 0f)
        assertEquals(.5f, cashChartY(0, Long.MIN_VALUE, Long.MAX_VALUE), .00001f)
    }
}
