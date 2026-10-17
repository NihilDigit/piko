package dev.piko.shared.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportTreeTest {
    @Test
    fun manyLinksShareAFlatForestWithoutLosingOwnershipOrIndependentExpansion() {
        val forest = importSourceForest(listOf(
            ImportTreeSource("first", listOf("01.mkv")), ImportTreeSource("second", listOf("01.mkv")),
            ImportTreeSource("collection-a", listOf("合集/正片.mkv")), ImportTreeSource("collection-b", listOf("合集/正片.mkv")),
        ))
        assertEquals(listOf(0, 0), forest.take(2).map { it.node.depth })
        assertEquals(forest.size, forest.map { it.key }.toSet().size)
        val expanded = forest.filter { it.sourceKey == "collection-b" }.map { it.key }.toSet()
        val visible = visibleImportSources(forest, expanded, "")
        assertEquals(listOf("first", "second", "collection-a", "collection-b", "collection-b"), visible.map { it.sourceKey })
        val matches = visibleImportSources(forest, emptySet(), "正片")
        assertEquals(4, matches.size)
        assertEquals(setOf("collection-a", "collection-b"), matches.map { it.sourceKey }.toSet())
    }

    private val paths = listOf("合集/作品甲/正片/第一集.mkv", "合集/作品甲/字幕/第一集.ass", "合集/作品乙/广告网址.txt", "说明.txt")

    @Test
    fun completeNestedTreeKeepsOriginalNamesAndActualLeafIndices() {
        val nodes = importTree(paths)
        assertEquals(paths.indices.toSet(), nodes.filterNot { it.isFolder }.map { it.fileIndex }.toSet())
        assertEquals(setOf(0, 1, 2), nodes.single { it.key == "d:合集" }.indices)
        assertEquals(3, nodes.single { it.name == "第一集.mkv" }.depth)
    }

    @Test
    fun searchFindsUnexpandedDeepFilesAndKeepsTheirAncestors() {
        val nodes = importTree(paths)
        assertTrue(visibleImportTree(nodes, emptySet(), "").none { it.name == "第一集.mkv" })
        val result = visibleImportTree(nodes, emptySet(), "第一集.mkv")
        assertEquals(listOf("合集", "作品甲", "正片", "第一集.mkv"), result.map { it.name })
        assertEquals(nodes, visibleImportTree(nodes, nodes.filter { it.isFolder }.mapTo(HashSet()) { it.key }, ""))
    }
}
