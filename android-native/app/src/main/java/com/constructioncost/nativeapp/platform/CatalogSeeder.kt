package com.constructioncost.nativeapp.platform

import android.content.Context
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ClassificationInfo
import org.json.JSONObject

class CatalogSeeder(private val context: Context, private val repository: LedgerRepository) {
    suspend fun seed(projectId: String) {
        val source = context.assets.open("default_catalog.json").bufferedReader().use { JSONObject(it.readText()) }
        require(source.getInt("version") == 1) { "Unsupported default catalog version" }
        val nodes = mutableListOf<ClassificationInfo>()
        val work = source.getJSONArray("workStages")
        for (index in 0 until work.length()) {
            nodes += ClassificationInfo("$projectId:work:${index + 1}", projectId, work.getString(index), "STAGE", sortOrder = index)
        }
        val cost = source.getJSONArray("costCatalog")
        for (index in 0 until cost.length()) {
            val entry = cost.getJSONObject(index)
            val kind = when (entry.getString("type")) {
                "head" -> "COST_HEAD"
                "subhead" -> "COST_SUBHEAD"
                "detail" -> "COST_DETAIL"
                else -> error("Invalid default cost node")
            }
            val parent = if (entry.isNull("parentId")) null else entry.getString("parentId").takeIf { it.isNotBlank() }
            nodes += ClassificationInfo(
                id = "$projectId:${entry.getString("id")}", projectId = projectId,
                name = entry.getString("label"), kind = kind,
                parentId = parent?.let { "$projectId:$it" }, sortOrder = entry.getInt("order"),
                archived = !entry.getBoolean("active")
            )
        }
        listOf("Hall", "Parking", "Bathroom", "Kitchen", "Bedroom", "Staircase", "Balcony", "Terrace").forEachIndexed { index, name ->
            nodes += ClassificationInfo("$projectId:place:${index + 1}", projectId, name, "PLACE", sortOrder = index)
        }
        repository.seedClassifications(projectId, nodes)
    }
}
