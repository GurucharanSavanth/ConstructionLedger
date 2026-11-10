package com.constructioncost.nativeapp.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ClassificationValidationTest {
    private fun node(id: String, parent: String?, project: String = "one", kind: String = "PLACE") = JSONObject().apply {
        put("id", id); put("projectId", project); put("name", id); put("kind", kind)
        put("parentId", parent ?: JSONObject.NULL); put("sortOrder", 0); put("archived", false); put("nature", "")
    }

    @Test fun longReversedChainAndSharedParentsRemainValid() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val file = File.createTempFile("classification-chain-", ".json", context.cacheDir)
        try {
            repository.saveProject(ProjectInfo("one", "House"))
            val records = repository.exportRecords()
            val nodes = JSONArray()
            for (i in 2999 downTo 0) nodes.put(node("place-$i", if (i == 0) null else "place-${i - 1}"))
            nodes.put(node("branch", "place-1500"))
            records.put("classifications", nodes)
            file.writeText(records.toString())
            val result = repository.inspectRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
            assertEquals(3001, result.counts["classifications"])
        } finally { repository.close(); file.delete() }
    }

    @Test fun invalidParentsAndCyclesAreRejectedWithoutChangingExistingRecords() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val file = File.createTempFile("classification-invalid-", ".json", context.cacheDir)
        try {
            repository.saveProject(ProjectInfo("one", "House"))
            repository.saveProject(ProjectInfo("two", "Other house"))
            val original = repository.exportRecords().toString()
            val cases = listOf(
                JSONArray().put(node("self", "self")) to "Classification cycle",
                JSONArray().put(node("a", "b")).put(node("b", "a")) to "Classification cycle",
                JSONArray().put(node("a", "b")).put(node("b", null, project = "two")) to "Invalid classification",
                JSONArray().put(node("a", "missing")) to "Foreign key validation failed",
                JSONArray().put(node("a", "b")).put(node("b", null, kind = "STAGE")) to "Invalid classification parent type",
                JSONArray().put(node("a", null, kind = "INVALID")) to "Invalid classification"
            )
            for ((nodes, message) in cases) {
                val records = JSONObject(original).put("classifications", nodes)
                file.writeText(records.toString())
                val failure = runCatching { repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION) }.exceptionOrNull()
                assertNotNull("Expected invalid graph to fail: $nodes", failure)
                assertTrue("Unexpected failure for $nodes: $failure", failure?.message?.contains(message) == true)
                assertEquals(original, repository.exportRecords().toString())
            }
        } finally { repository.close(); file.delete() }
    }
}
