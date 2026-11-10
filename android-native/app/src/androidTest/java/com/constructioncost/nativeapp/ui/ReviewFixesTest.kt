package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.InsightRow
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReviewFixesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun otherGroupsExpandAndOpenTheSelectedGroup() {
        val rows = (1..7).map { InsightRow("group-$it", "Group $it", "SUPPLIER", (8-it)*100L, 1) }
        var selected: InsightRow? = null
        compose.setContent { WebTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { InsightDonut(insightComparison(rows)) { selected = it } }
        } }
        compose.onNodeWithText("Group 6").assertDoesNotExist()
        compose.onNodeWithText("Show 2 groups outside the top five").performScrollTo().performClick()
        compose.onNodeWithText("Group 6").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("group-6", selected?.id) }
        compose.onNodeWithText("Hide 2 groups outside the top five").performScrollTo().performClick()
        compose.onNodeWithText("Group 6").assertDoesNotExist()
    }

    @Test fun compactDateHintMatchesAcceptedTypedFormat() {
        val value = mutableStateOf("")
        compose.setContent { WebTheme { DateField("From", value.value, { value.value = it }, compact = true) } }
        compose.onNodeWithContentDescription("yyyy-MM-dd").performTextInput("2026-10-22")
        compose.runOnIdle { assertEquals(LocalDate.of(2026,10,22), LocalDate.parse(value.value)) }
    }

    @Test fun documentActivityAndCalendarUseTheSameLocalDay() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("date-project", "House"))
            val day = LocalDate.of(2026,10,22)
            listOf(LocalTime.of(0,30), LocalTime.of(23,30)).forEachIndexed { index, time ->
                repository.insertAttachment(AttachmentInfo("document-$index", "date-project", "Drawing $index", "text/plain", 1,
                    "a".repeat(64), "document-$index.bin", day.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
            }
            val events = repository.activity("date-project", fromDate=day.toString(), toDate=day.toString(), recordType="ATTACHMENT").first()
            assertEquals(2, events.size)
            assertEquals(setOf(day.toString()), events.map { it.date }.toSet())
            assertEquals(2, repository.calendarDayCounts("date-project", day.toString(), day.toString()).first().single().eventCount)
            assertEquals(0, repository.activity("date-project", fromDate=day.minusDays(1).toString(), toDate=day.minusDays(1).toString(), recordType="ATTACHMENT").first().size)
        } finally { repository.close() }
    }
}
