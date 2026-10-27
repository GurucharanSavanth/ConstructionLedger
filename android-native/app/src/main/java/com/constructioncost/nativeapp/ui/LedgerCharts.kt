package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.constructioncost.nativeapp.domain.InsightRow
import com.constructioncost.nativeapp.domain.Money
import com.constructioncost.nativeapp.domain.MonthlyRow
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class InsightSegment(val name: String, val amountPaise: Long, val row: InsightRow?)

internal fun insightDonutSegments(comparison: InsightComparison): List<InsightSegment> =
    comparison.ranked.take(5).map { InsightSegment(it.name, it.amountPaise, it) } +
        if (comparison.other.isEmpty()) emptyList() else listOf(InsightSegment("Other", comparison.otherPaise, null))

internal fun cashChartMonths(rows: List<MonthlyRow>): List<MonthlyRow> = rows.sortedBy { it.month }.takeLast(12)

// Coordinates only use floating point; displayed values and grouping stay in integer paise.
internal fun cashChartY(amountPaise: Long, minimum: Long, maximum: Long): Float =
    if (minimum == maximum) 1f else
        ((maximum.toDouble() - amountPaise.toDouble()) / (maximum.toDouble() - minimum.toDouble())).toFloat().coerceIn(0f, 1f)

@Composable
internal fun InsightDonut(comparison: InsightComparison, select: (InsightRow) -> Unit) {
    val segments = insightDonutSegments(comparison)
    var showOther by remember(comparison.other) { mutableStateOf(false) }
    if (segments.isEmpty()) return
    val colors = WebColors.chart
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cost distribution",style=MaterialTheme.typography.titleLarge)
        Canvas(Modifier.size(208.dp).align(Alignment.CenterHorizontally).clearAndSetSemantics {}) {
            val width = 32.dp.toPx()
            val inset = width / 2
            val diameter = size.minDimension - width
            var start = -90f
            segments.forEachIndexed { index, segment ->
                val sweep = (segment.amountPaise.toDouble() / comparison.totalPaise.toDouble() * 360).toFloat()
                drawArc(colors[index], start, sweep, false, Offset(inset, inset), Size(diameter, diameter), style = Stroke(width))
                start += sweep
            }
        }
        Text("Total cost ${Money.format(comparison.totalPaise)}", style = MaterialTheme.typography.titleLarge)
        Text("${comparison.ranked.size} groups. Select a group to explore its costs.")
        segments.forEachIndexed { index, segment ->
            OutlinedCard(onClick = { segment.row?.let(select) ?: run { showOther = !showOther } }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp).background(colors[index], CircleShape))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(segment.name, style = MaterialTheme.typography.titleMedium)
                        Text("${insightSharePercent(segment.amountPaise, comparison.totalPaise)}% · ${Money.format(segment.amountPaise)}")
                        if (segment.row == null) Text("${if(showOther) "Hide" else "Show"} ${comparison.other.size} groups outside the top five")
                    }
                }
            }
            if(segment.row == null && showOther) comparison.other.forEach { row ->
                WebListRow(row.name, "${insightSharePercent(row.amountPaise, comparison.totalPaise)}%", Money.format(row.amountPaise)) { select(row) }
            }
        }
    }
}

@Composable
internal fun MonthlyCashChart(rows: List<MonthlyRow>) {
    val months = cashChartMonths(rows)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Payments over time",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            Text("Last 12 recorded months",Modifier.background(WebColors.soft).padding(6.dp),fontSize=9.sp,color=WebColors.muted)
        }
        if (months.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min=215.dp),contentAlignment=Alignment.Center) { EmptyPanel("Your cash flow starts here","Record a payment to see your monthly trend.") }
        } else {
            Text("Payments less reversals and refunds. Negative values mean net cash returned.")
            val minimum = min(0L, months.minOf { it.paidPaise })
            val maximum = max(0L, months.maxOf { it.paidPaise })
            val paidColor = MaterialTheme.colorScheme.primary
            val returnedColor = MaterialTheme.colorScheme.error
            val baselineColor = MaterialTheme.colorScheme.outline
            val monthFormat = DateTimeFormatter.ofPattern("MMM yyyy", LocalConfiguration.current.locales[0])
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                months.forEach { row ->
                    val label = YearMonth.parse(row.month).format(monthFormat)
                    Column(Modifier.width(112.dp).clearAndSetSemantics {
                        contentDescription = "$label, net cash ${Money.format(row.paidPaise)}${if (row.paidPaise < 0) ", cash returned" else ""}"
                    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Canvas(Modifier.fillMaxWidth().height(128.dp)) {
                            val baseline = cashChartY(0, minimum, maximum) * size.height
                            val end = cashChartY(row.paidPaise, minimum, maximum) * size.height
                            if (row.paidPaise != 0L) drawRect(
                                if (row.paidPaise < 0) returnedColor else paidColor,
                                Offset(size.width * .2f, min(baseline, end)),
                                Size(size.width * .6f, abs(baseline - end))
                            )
                            drawLine(baselineColor, Offset(0f, baseline.coerceAtMost(size.height - 1f)), Offset(size.width, baseline.coerceAtMost(size.height - 1f)))
                        }
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        Text(Money.format(row.paidPaise), style = MaterialTheme.typography.bodyLarge)
                        if (row.paidPaise < 0) Text("Net cash returned", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
