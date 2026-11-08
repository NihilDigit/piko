package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptimisticSubtreeTest {
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
