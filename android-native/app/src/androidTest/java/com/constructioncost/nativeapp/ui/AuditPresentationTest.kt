package com.constructioncost.nativeapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.constructioncost.nativeapp.domain.AuditInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuditPresentationTest {
    @get:Rule val compose=createComposeRule()
    private fun audit(before:String,after:String)=AuditInfo("a","expenses","e","EDIT",before,after,0)

    @Test fun ordinaryChangesAndNullSideRemainReadable() {
        val display=readableAuditChanges(audit("null", """{"amountPaise":12500,"rows":[{"name":"Cement"}],"sha256":"secret"}"""))
        assertFalse(display.truncated)
        assertEquals("₹125",display.changes.single { it.label=="Amount" }.after)
        assertEquals("Cement",display.changes.single { it.label.contains("name") }.after)
        assertTrue(display.changes.none { it.label.contains("sha256",ignoreCase=true) })
        val escaped=readableAuditChanges(audit("{}", """{"text":"a}\\\"b","items":[1,2]}"""))
        assertFalse(escaped.truncated)
    }

    @Test fun amplifiedPathsAndOversizedValuesShowTruncation() {
        val key="a".repeat(400)
        val input="{\"$key\":["+List(300){"0"}.joinToString(",")+"]}"
        assertTrue(readableAuditChanges(audit("{}",input)).truncated)
        assertTrue(readableAuditChanges(audit("{}","{\""+"b".repeat(513)+"\":0}")).truncated)
        assertTrue(readableAuditChanges(audit("{}","{\"x\":\""+"a".repeat(33_000)+"\"}")).truncated)
        compose.setContent { AuditChanges(audit("{}",input)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Some history is too large or complex to display.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Some history is too large or complex to display.").assertIsDisplayed()
    }

    @Test fun longNotesKeepOtherChangesVisible() {
        val note="a".repeat(17_000)
        val before="{\"note\":\"$note\",\"requestJson\":\"$note\",\"amountPaise\":10000}"
        val after="{\"note\":\"$note\",\"requestJson\":\"$note\",\"amountPaise\":12500}"
        val display=readableAuditChanges(audit(before,after))
        assertTrue(display.truncated)
        assertEquals("₹125",display.changes.single { it.label=="Amount" }.after)
    }

    @Test fun ambiguousNonstandardJsonCannotBypassDepthLimit() {
        val malicious="{\"x\":>\"h}\",\"y\":"+"[".repeat(5000)+"0"+"]".repeat(5000)+"}"
        val display=readableAuditChanges(audit("{}",malicious))
        assertTrue(display.truncated)
        assertTrue(display.changes.isEmpty())
    }

    @Test fun deepAndWideInputsAreBounded() {
        val deep="{\"x\":"+"[".repeat(100)+"0"+"]".repeat(100)+"}"
        val wide="{\"x\":["+List(2_000){"0"}.joinToString(",")+"]}"
        assertTrue(readableAuditChanges(audit(deep,"{}")).truncated)
        assertTrue(readableAuditChanges(audit("{}",wide)).truncated)
    }
}
