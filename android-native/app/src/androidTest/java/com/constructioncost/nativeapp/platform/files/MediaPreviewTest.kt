package com.constructioncost.nativeapp.platform.files

import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import com.constructioncost.nativeapp.MainActivity
import com.constructioncost.nativeapp.ConstructionApplication
import com.constructioncost.nativeapp.ui.LedgerViewModel
import com.constructioncost.nativeapp.ui.ledgerPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.newId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaPreviewTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var repository: LedgerRepository
    private lateinit var file: File
    @get:Rule(order = 0) val cleanup = object : ExternalResource() {
        override fun after() {
            if (::file.isInitialized) file.delete()
            if (::repository.isInitialized) repository.close()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    private fun fixture(asset: String?, mime: String): Pair<AttachmentInfo, AttachmentStore> {
        repository = LedgerRepository.inMemory(instrumentation.targetContext)
        val store = AttachmentStore(instrumentation.targetContext, repository)
        val id = newId()
        file = DocumentPolicy.safeFile(store.root, "$id.bin")
        if (asset == null) file.writeText("This is not playable media.")
        else instrumentation.context.assets.open(asset).use { input -> file.outputStream().use(input::copyTo) }
        return AttachmentInfo(id, "media-test", "Media playback fixture", mime, file.length(),
            DocumentPolicy.sha256(file), file.name, System.currentTimeMillis()) to store
    }

    private fun findPlayer(view: View): VideoView? = when (view) {
        is VideoView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findPlayer(view.getChildAt(it)) }
        else -> null
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun playback(asset: String, mime: String) {
        val (info, store) = fixture(asset, mime)
        val showing = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (showing.value) DocumentPreviewScreen(info, store, { showing.value = false }, {})
                else Text("Viewer closed")
            }
        }
        lateinit var player: VideoView
        compose.waitUntil(10_000) {
            onMain { findPlayer(compose.activity.window.decorView)?.also { player = it } != null }
        }
        compose.waitUntil(15_000) { onMain { player.duration >= 19_000 } }
        assertFalse("Opening the preview must not autoplay", onMain { player.isPlaying })

        compose.onNodeWithText("Play").performClick()
        compose.waitUntil(10_000) { onMain { player.isPlaying && player.currentPosition > 1_000 } }
        compose.onNodeWithText("Pause").performClick()
        val pausedAt = onMain { player.currentPosition }
        assertFalse(onMain { player.isPlaying })
        Thread.sleep(350)
        assertTrue("Paused position must stay stable", onMain { kotlin.math.abs(player.currentPosition - pausedAt) < 200 })

        compose.onNodeWithText("Restart").performClick()
        compose.waitUntil(5_000) { onMain { player.currentPosition < 500 } }
        assertFalse("Restart seeks without resuming paused playback", onMain { player.isPlaying })
        compose.onNodeWithText("Play").performClick()
        compose.waitUntil(5_000) { onMain { player.isPlaying && player.currentPosition > 500 } }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse("Playback must stop when the activity goes to the background", onMain { player.isPlaying })
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(15_000) { onMain { player.duration >= 19_000 } }
        assertFalse("Returning to the app must not autoplay", onMain { player.isPlaying })
        compose.onNodeWithText("Play").performClick()
        compose.waitUntil(5_000) { onMain { player.isPlaying && player.currentPosition > 500 } }
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Viewer closed").assertExists()
        assertFalse("Closing the preview must release playback", onMain { player.isPlaying })
        assertEquals(-1, onMain { player.duration })
    }

    @Test fun videoPreparesPlaysPausesRestartsAndReleasesAcrossLifecycle() = playback("media-playback.mp4", "video/mp4")

    @Test fun audioPreparesPlaysPausesRestartsAndReleasesAcrossLifecycle() = playback("media-playback.m4a", "audio/mp4")

    @Test fun invalidMediaShowsErrorAndKeepsExternalFallbackAvailable() {
        val (info, store) = fixture(null, "video/mp4")
        var externalCalls = 0
        compose.setContent { MaterialTheme { DocumentPreviewScreen(info, store, {}, { externalCalls++ }) } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("This media cannot be played. Try opening it with another installed app.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Open with").performClick()
        assertEquals(1, externalCalls)
    }
}


@RunWith(AndroidJUnit4::class)
class MediaNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = (context.applicationContext as ConstructionApplication).repository
    private val store get() = AttachmentStore(context, repository)
    private val projectKey = stringPreferencesKey("project")
    private var previousProject: String? = null
    private lateinit var projectId: String
    private lateinit var attachment: AttachmentInfo
    @get:Rule(order = 0) val fixture = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            previousProject = context.ledgerPreferences.data.first()[projectKey]
            repository.ensureDefaultProject()
            val projects = repository.projects.first()
            projectId = projects.firstOrNull { !it.archived }?.id ?: projects.first().id
            context.ledgerPreferences.edit { it[projectKey] = projectId }
            val file = DocumentPolicy.safeFile(store.root, "${newId()}.bin")
            instrumentation.context.assets.open("media-playback.mp4").use { input -> file.outputStream().use(input::copyTo) }
            attachment = AttachmentInfo(newId(), projectId, "Media navigation fixture", "video/mp4", file.length(),
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

    private fun findPlayer(view: View): VideoView? = when (view) {
        is VideoView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findPlayer(view.getChildAt(it)) }
        else -> null
    }
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    @Test fun actualDocumentNavigationPreparesAndPlaysVideoThenReleasesOnBack() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty() }
        val model = ViewModelProvider(compose.activity)[LedgerViewModel::class.java]
        compose.activityRule.scenario.onActivity { model.selectProject(projectId) }
        compose.waitUntil(5_000) { model.state.value.projectId == projectId }
        assertTrue(runBlocking { repository.attachments(projectId).first().any { it.id == attachment.id } })
        compose.onNodeWithText("Files").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Saved documents"))
        compose.onNodeWithText(attachment.name).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("View original").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("View original").performScrollTo().performClick()
        lateinit var player: VideoView
        compose.waitUntil(10_000) { onMain { findPlayer(compose.activity.window.decorView)?.also { player = it } != null } }
        compose.waitUntil(15_000) { onMain { player.duration >= 19_000 } }
        compose.onNodeWithText("Play").performClick()
        compose.waitUntil(10_000) { onMain { player.isPlaying && player.currentPosition > 1_000 } }
        compose.onNodeWithText("Pause").performClick()
        assertFalse(onMain { player.isPlaying })
        compose.onNodeWithText("Back").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Document details").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Document details").assertExists()
        assertEquals(-1, onMain { player.duration })
    }
}
