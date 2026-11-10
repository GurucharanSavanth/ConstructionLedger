package com.constructioncost.nativeapp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreResultsTest {
    @Test fun searchAndSortKeepMatchingAmountsAndStableNames() {
        val rows = listOf("Steel frame" to 200L, "Frame concrete" to 100L, "Steel mesh" to 200L)
        fun results(query: String, sort: String) = exploreResults(rows, query, sort, { it.first }, { it.second })
        assertEquals(listOf(rows[1], rows[0]), results("FRAME", "lowest"))
        assertEquals(listOf(rows[0]), results("frame steel", "highest"))
        assertEquals(listOf(rows[1], rows[0], rows[2]), results("", "name"))
        assertEquals(emptyList<Pair<String, Long>>(), results("missing", "highest"))
    }
}
