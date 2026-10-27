package com.constructioncost.nativeapp

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.newId
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.CameraCaptures
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import com.constructioncost.nativeapp.ui.ledgerPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentNavigationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = (context.applicationContext as ConstructionApplication).repository
    private val projectKey = stringPreferencesKey("project")
    private var previousProject: String? = null
    private lateinit var attachment: AttachmentInfo
    private val store get() = AttachmentStore(context, repository)
    @get:Rule(order = 0) val fixture = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            previousProject = context.ledgerPreferences.data.first()[projectKey]
            repository.ensureDefaultProject()
            context.ledgerPreferences.edit { it[projectKey] = "default-project" }
            val id = newId()
            val file = DocumentPolicy.safeFile(store.root, "$id.png")
            val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            attachment = AttachmentInfo(id, "default-project", "Viewer fixture $id", "image/png", file.length(),
                DocumentPolicy.sha256(file), file.name, System.currentTimeMillis())
            repository.insertAttachment(attachment)
        }
        override fun after() = runBlocking<Unit> {
            if (::attachment.isInitialized) {
                repository.deleteAttachmentMetadata(attachment.id)
                store.fileFor(attachment).delete()
            }
            context.ledgerPreferences.edit { saved -> previousProject?.let { saved[projectKey] = it } ?: saved.remove(projectKey) }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun keptPhotoCanBeKeptThenImportedAfterRecreation() = runBlocking<Unit> {
        val captures = CameraCaptures(context)
        val photo = captures.create("photo-${java.util.UUID.randomUUID()}.jpg")
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        photo.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        image.recycle()
        val originalHash = DocumentPolicy.sha256(photo)
        val number = captures.list().indexOfFirst { it.name == photo.name } + 1
        var imported: AttachmentInfo? = null
        try {
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Files").performClick()
            compose.onNodeWithText("Kept camera photos").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Discard photo $number").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Discard photo $number").performScrollTo().performClick()
            compose.onNodeWithText("Cancel").performClick()
            org.junit.Assert.assertTrue(photo.isFile)
            org.junit.Assert.assertEquals(originalHash, DocumentPolicy.sha256(photo))
            compose.onNodeWithText("Kept camera photos").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Import photo $number into selected project").fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Import photo $number into selected project").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Import photo $number into selected project").performScrollTo().performClick()
            compose.waitUntil(30_000) { !photo.exists() }
            val saved = repository.attachments("default-project").first().single { it.name == photo.name }
            imported = saved
            org.junit.Assert.assertEquals(originalHash, saved.sha256)
            compose.onAllNodesWithText("Keep for later").fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
                compose.onNodeWithText("Keep for later").performClick()
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(photo.name, substring = false).fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(photo.name, substring = false).fetchSemanticsNodes().isNotEmpty() }
            org.junit.Assert.assertEquals(originalHash, DocumentPolicy.sha256(store.fileFor(saved)))
        } finally {
            imported?.let { repository.deleteAttachmentMetadata(it.id); store.fileFor(it).delete() }
            if (photo.exists()) captures.discard(photo)
        }
    }

    @Test fun viewerReturnsToDocumentDetailAfterToolbarBackBackDispatcherAndRecreation() {
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Files").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(attachment.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(attachment.name).performClick()
        val draft = listOf("Category" to "Custom category", "Status" to "Draft status", "Revision label" to "Draft revision",
            "Work" to "Draft work", "Place" to "Draft place", "Notes" to "Unsaved viewer notes")
        val modalList = hasScrollToNodeAction() and hasAnyAncestor(isDialog())
        fun documentControl(text: String): SemanticsNodeInteraction {
            compose.waitUntil(10_000) { compose.onAllNodes(modalList).fetchSemanticsNodes().size == 1 }
            compose.onNode(modalList).performScrollToNode(hasText(text))
            return compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))
        }
        documentControl("▸ Document details").performClick()
        draft.forEach { (label, value) -> compose.onNodeWithText(label).performScrollTo().performTextReplacement(value) }
        compose.onNodeWithText("▾ Document details").performScrollTo().performClick()
        fun assertDraft() {
            documentControl("▸ Document details").performClick()
            draft.forEach { (label, value) -> compose.onNodeWithText(label).performScrollTo().assertTextContains(value) }
            compose.onNodeWithText("▾ Document details").performScrollTo().performClick()
        }
        fun openViewer() {
            documentControl("View original").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(attachment.name).fetchSemanticsNodes().isNotEmpty() }
        }
        fun assertDocumentDetail() {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Document details").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Document details").assertExists()
        }
        openViewer()
        compose.onNodeWithText("Back").performClick()
        assertDocumentDetail()
        assertDraft()
        openViewer()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        assertDocumentDetail()
        assertDraft()
        openViewer()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(attachment.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Back").performClick()
        assertDocumentDetail()
        assertDraft()
        compose.onNode(modalList).performScrollToNode(hasText(attachment.name))
        compose.onNode(hasText(attachment.name, substring = false) and hasAnyAncestor(isDialog())).assertExists()
    }
}
