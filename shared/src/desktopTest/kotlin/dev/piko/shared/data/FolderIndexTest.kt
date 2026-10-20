package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FolderIndexTest {
    @Test
    fun unchangedUpdatesKeepThePublishedStateAndNodes() {
        val index = FolderIndex()
        val folder = FileStat(id = "child", name = "Child", kind = FileKind.FOLDER)
        assertTrue(index.listed("parent", listOf(folder)))
        val before = index.flow.value
        assertFalse(index.listed("parent", listOf(folder)))
        assertFalse(index.listed("parent", listOf(folder), complete = false))
        assertFalse(index.learned("child", "Child", "parent", true, false))
        assertFalse(index.renamed("child", "Child", always = true))
        assertFalse(index.moved(listOf("child"), "parent"))
        assertFalse(index.removed(listOf("unknown"), emptySet()))
        assertFalse(index.seed(mapOf("child" to "Old"), mapOf("child" to "elsewhere")))
        assertSame(before, index.flow.value)
        assertSame(before.nodes, index.flow.value.nodes)
    }

    @Test
    fun authoritativeListingReplacesProvisionalNodesOnlyOnce() {
        val index = FolderIndex()
        assertTrue(index.seed(mapOf("child" to "Child"), mapOf("child" to "parent")))
        val seeded = index.flow.value
        assertFalse(index.seed(mapOf("child" to "Child"), mapOf("child" to "parent")))
        assertSame(seeded, index.flow.value)
        assertTrue(index.learned("child", "Child", "parent", true, false))
        assertFalse(index.node("child")!!.provisional)
        assertFalse(index.learned("child", "Child", "parent", true, false))
    }
}
