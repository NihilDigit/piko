package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptimisticWritesTest {
    @Test fun `rollback preserves later rename and independent fields`() {
        val writes = OptimisticWrites()
        val base = FileStat(id = "f", name = "original", parentId = "a", starred = true)
        val rename = writes.begin("account", DriveChange.Renamed("f", "first", "a"), mapOf("f" to base))
        writes.begin("account", DriveChange.Renamed("f", "second", "a"), mapOf("f" to base.copy(name = "first")))
        writes.begin("account", DriveChange.Starred(listOf("f"), false, setOf("a")), mapOf("f" to base))
        writes.finish(rename)
        val visible = writes.project("account", "a", listOf(base)).single()
        assertEquals("second", visible.name)
        assertFalse(visible.isStarred)
        assertEquals("original", writes.project("other", "a", listOf(base)).single().name)
    }

    @Test fun `trash stays hidden under a later metadata write and rollback restores latest name`() {
        val writes = OptimisticWrites()
        val base = FileStat(id = "f", name = "original", parentId = "a")
        val trash = writes.begin("account", DriveChange.Trashed(listOf("f"), setOf("a")), mapOf("f" to base))
        writes.begin("account", DriveChange.Renamed("f", "renamed", "a"), mapOf("f" to base))
        assertTrue(writes.project("account", "a", listOf(base)).isEmpty())
        assertEquals("renamed", writes.project("account", null, listOf(base), trash = true).single().name)
        writes.finish(trash)
        assertEquals("renamed", writes.project("account", "a", listOf(base)).single().name)
    }

    @Test fun `overlapping requests wait while independent files proceed`() = runBlocking {
        val writes = OptimisticWrites()
        val first = writes.begin("account", DriveChange.Renamed("a", "one", ""), emptyMap())
        val second = writes.begin("account", DriveChange.Renamed("a", "two", ""), emptyMap())
        val other = writes.begin("account", DriveChange.Renamed("b", "three", ""), emptyMap())
        val waiting = async { second.awaitTurn() }
        other.awaitTurn()
        assertFalse(waiting.isCompleted)
        writes.finish(first)
        waiting.await()
    }

    private fun folder(id: String, parent: String = "") = FileStat(id = id, parentId = parent, name = id, kind = FileKind.FOLDER)
    private val parents = mapOf("root" to "", "nested" to "root", "leaf" to "nested", "other" to "")
    private val leaf = FileStat(id = "leaf", parentId = "nested", name = "video.mp4", starred = true)

    @Test fun trashHidesDescendantsFromSearchAndStarredResultsUntilRollback() {
        val writes = OptimisticWrites()
        val trash = writes.begin("a", DriveChange.Trashed(listOf("root"), setOf("")), mapOf("root" to folder("root")))
        assertTrue(writes.project("a", null, listOf(leaf), parentOf = parents::get).isEmpty())
        assertTrue(writes.project("a", "nested", listOf(leaf), parentOf = parents::get).isEmpty())
        assertEquals(listOf(leaf), writes.project("b", null, listOf(leaf), parentOf = parents::get))
        writes.finish(trash)
        assertEquals(listOf(leaf), writes.project("a", null, listOf(leaf), parentOf = parents::get))
    }

    @Test fun movingAFolderOutPreservesItsDescendantsRegardlessOfWriteOrder() {
        for (trashFirst in listOf(false, true)) {
            val writes = OptimisticWrites()
            fun trash() { writes.begin("a", DriveChange.Trashed(listOf("root"), setOf("")), mapOf("root" to folder("root"))) }
            fun move() { writes.begin("a", DriveChange.Moved(listOf("nested"), setOf("root"), "other"), mapOf("nested" to folder("nested", "root"))) }
            if (trashFirst) { trash(); move() } else { move(); trash() }
            assertEquals(listOf(leaf), writes.project("a", null, listOf(leaf), parentOf = parents::get))
            val state = FolderIndex.State(parents.filterKeys { it != "leaf" }.mapValues { (id, parent) -> FolderIndex.Node(id, parent) })
            val projected = writes.projectFolders("a", state)
            assertEquals(setOf("nested", "other"), projected.nodes.keys)
            assertEquals("other", projected.nodes["nested"]?.parentId)
            assertEquals(setOf("root"), projected.gone)
        }
    }

    @Test fun movingIntoDeletedSubtreeHidesTheFileAndDeletionAlsoHidesTrashRows() {
        val writes = OptimisticWrites()
        writes.begin("a", DriveChange.Deleted(listOf("root"), setOf("")), mapOf("root" to folder("root")))
        val outside = leaf.copy(parentId = "other")
        writes.begin("a", DriveChange.Moved(listOf(leaf.id), setOf("other"), "nested"), mapOf(leaf.id to outside))
        assertTrue(writes.project("a", null, listOf(outside), parentOf = parents::get).isEmpty())
        assertTrue(writes.project("a", null, listOf(leaf.copy(trashed = true)), trash = true, parentOf = parents::get).isEmpty())
    }

    @Test fun restoreOfTheRootUnhidesTheSubtreeAndCyclesTerminate() {
        val writes = OptimisticWrites()
        writes.begin("a", DriveChange.Trashed(listOf("root"), setOf("")), mapOf("root" to folder("root")))
        writes.begin("a", DriveChange.Restored(listOf("root")), mapOf("root" to folder("root").copy(trashed = true)))
        assertEquals(listOf(leaf), writes.project("a", null, listOf(leaf), parentOf = parents::get))
        writes.begin("a", DriveChange.Trashed(listOf("elsewhere"), setOf("")), emptyMap())
        val cyclic = mapOf("nested" to "cycle", "cycle" to "nested")
        assertEquals(listOf(leaf), writes.project("a", null, listOf(leaf), parentOf = cyclic::get))
    }
}
