package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileTag
import io.github.nihildigit.pikpak.TaskPhase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveTreeDeltaTest {
    @Test fun absentTombstonesUseBatchedParentIndexLookups() = fixture { factory ->
        val database = factory.open("delta-account")
        var deletionBatches = 0
        val checked = object : PikoTreeDatabase by database {
            override fun execute(sql: String, parameters: List<Any?>) {
                if (sql.startsWith("WITH RECURSIVE removed") && sql.contains("INSERT OR REPLACE INTO tree_deleted")) {
                    deletionBatches++
                    val plan = mutableListOf<String>()
                    database.query("EXPLAIN QUERY PLAN $sql", parameters).use { cursor ->
                        while (cursor.next()) plan += cursor.string(3).orEmpty()
                    }
                    assertTrue(plan.any { it.contains("SEARCH n") && it.contains("parent=?") }, plan.toString())
                }
                database.execute(sql, parameters)
            }
        }
        val index = DriveTreeIndex(database = checked, ownerAccount = "delta-account")
        try {
            seed(index, List(1024) { file("kept-$it") })
            index.beginDelta()
            repeat(4) { page ->
                index.appendDelta(List(256) { folder("absent-${page * 256 + it}").copy(trashed = true) })
            }
            index.commitDelta("after-tombstones")
            assertEquals(4, deletionBatches)
            assertEquals(1024, index.children("").files.size)
            assertEquals("after-tombstones", index.syncCursor)
        } finally { index.close() }
    }

    private fun fixture(block: (DesktopPikoTreeDatabaseFactory) -> Unit) {
        val directory = Files.createTempDirectory("piko-tree-delta").toFile()
        try { block(DesktopPikoTreeDatabaseFactory(directory)) } finally { directory.deleteRecursively() }
    }

    private fun open(factory: DesktopPikoTreeDatabaseFactory) =
        DriveTreeIndex(database = factory.open("delta-account"), ownerAccount = "delta-account")

    private fun folder(id: String, parent: String = "") =
        FileStat(id = id, parentId = parent, name = id, kind = FileKind.FOLDER)

    private fun file(id: String, parent: String = "") =
        FileStat(id = id, parentId = parent, name = "$id.mp4", kind = FileKind.FILE, size = "100", phase = TaskPhase.COMPLETE)

    private fun seed(index: DriveTreeIndex, rows: List<FileStat>) {
        val generation = index.beginCandidate()
        index.appendCandidate(generation, rows)
        index.commitCandidate(generation, null, syncCursor = "initial-server-cursor")
        assertTrue(index.isComplete)
        assertEquals("initial-server-cursor", index.syncCursor)
    }

    @Test fun stagedPagesStayInvisibleAndAbortPreservesTheCheckpoint() = fixture { factory ->
        val index = open(factory)
        try {
            val original = file("existing")
            seed(index, listOf(original))
            val revision = index.flow.value
            index.beginDelta()
            index.appendDelta(listOf(original.copy(name = "renamed.mp4"), file("new")))
            index.appendDelta(listOf(original.copy(trashed = true)))
            assertEquals(original, index.file(original.id))
            assertNull(index.file("new"))
            assertEquals(revision, index.flow.value)
            assertEquals("initial-server-cursor", index.syncCursor)
            index.abortDelta()
            assertEquals(original, index.file(original.id))
            assertNull(index.file("new"))
            index.beginDelta()
            index.commitDelta("empty-success-cursor")
            assertEquals(original, index.file(original.id))
            assertNull(index.file("new"), "Aborted pages must not leak into the next delta")
            assertEquals("empty-success-cursor", index.syncCursor)
        } finally { index.close() }
    }

    @Test fun committedDeltaUpdatesRowsFolderSubtreesAndBothMoveParents() = fixture { factory ->
        val index = open(factory)
        try {
            val moved = file("moving", "source")
            val deletedFolder = folder("removed", "source")
            seed(index, listOf(folder("source"), folder("target"), moved, deletedFolder,
                folder("nested", deletedFolder.id), file("descendant", "nested"), file("z-escaped", deletedFolder.id), file("trashed", "target")))
            index.beginDelta()
            index.appendDelta(listOf(moved.copy(name = "first-name.mp4"), file("created", "target")))
            index.appendDelta(listOf(moved.copy(parentId = "target", name = "final-name.mp4", tags = listOf(FileTag(name = FileTag.STAR))),
                deletedFolder.copy(trashed = true), file("z-escaped", "target"), file("trashed", "target").copy(trashed = true)))
            val affected = index.commitDelta("next-server-cursor")
            assertTrue(affected.containsAll(setOf("source", "target")))
            val result = assertNotNull(index.file(moved.id))
            assertEquals("target", result.parentId)
            assertEquals("final-name.mp4", result.name)
            assertTrue(result.isStarred)
            assertNotNull(index.file("created"))
            assertNull(index.file(deletedFolder.id))
            assertNull(index.file("nested"))
            assertNull(index.file("descendant"))
            assertNull(index.file("trashed"))
            assertFalse(index.flow.value.entries.containsKey("nested"))
            assertEquals(setOf("moving", "created", "z-escaped"), index.children("target").files.map { it.id }.toSet())
            assertEquals(300L, index.sortContext().subtreeStats.getValue("").physical.bytes)
            assertEquals("next-server-cursor", index.syncCursor)
            assertTrue(index.isComplete)
        } finally { index.close() }
    }

    @Test fun newManifestWinnerInvalidatesVirtualRowsUntilItsContentsAreLoaded() = fixture { factory ->
        val index = open(factory)
        try {
            val oldManifest = file("old-manifest", "folder").copy(name = ".piko-vault-v1-0001.json")
            seed(index, listOf(folder("folder"), oldManifest))
            val entry = VaultEntry("old-entry", "archived.mp4", 100, "GCID", addedAt = 1)
            index.putVaultEntries("folder", listOf(entry))
            assertNotNull(index.file(entry.virtualId))
            index.beginDelta()
            index.appendDelta(listOf(file("new-manifest", "folder").copy(name = ".piko-vault-v2-0001.json")))
            index.commitDelta("new-manifest-cursor")
            assertNull(index.file(entry.virtualId))
            assertNull(index.vaultEntries("folder"), "The new manifest contents remain unresolved, not known-empty")
            assertEquals("new-manifest", index.flow.value.manifestsByFolder["folder"]?.id)
            assertNotNull(index.file(oldManifest.id), "Invalidation must follow the winner even before old-manifest cleanup")
        } finally { index.close() }
    }

    @Test fun interruptedOptimisticVaultRenameIsInvalidatedOnReopen() = fixture { factory ->
        var index = open(factory)
        try {
            val manifest = file("manifest", "folder").copy(name = ".piko-vault-v1-0001.json")
            seed(index, listOf(folder("folder"), manifest, file("physical", "folder")))
            val original = VaultEntry("entry", "original.mp4", 100, "GCID", addedAt = 1)
            index.putVaultEntries("folder", listOf(original))
            val projected = original.copy(name = "optimistic.mp4")
            index.putVaultEntries("folder", listOf(projected), optimistic = true)
            assertEquals("optimistic.mp4", index.file(projected.virtualId)?.name)
            assertEquals(listOf(projected), index.vaultEntries("folder"))
            index.close()
            index = open(factory)
            assertNull(index.file(original.virtualId))
            assertNull(index.file(projected.virtualId))
            assertNull(index.vaultEntries("folder"), "Interrupted projection must be reloadable from the physical manifest")
            assertNotNull(index.file(manifest.id))
            assertNotNull(index.file("physical"))
            assertEquals(manifest.id, index.flow.value.manifestsByFolder["folder"]?.id)
            assertEquals(0L, index.sortContext().subtreeStats.getValue("").vaulted.bytes)
        } finally { index.close() }
    }

    @Test fun confirmedVaultRenameClearsTheInterruptionMarkerAndSurvivesReopen() = fixture { factory ->
        var index = open(factory)
        try {
            val manifest = file("manifest", "folder").copy(name = ".piko-vault-v1-0001.json")
            seed(index, listOf(folder("folder"), manifest))
            val original = VaultEntry("entry", "original.mp4", 100, "GCID", addedAt = 1)
            index.putVaultEntries("folder", listOf(original))
            val renamed = original.copy(name = "confirmed.mp4")
            index.putVaultEntries("folder", listOf(renamed), optimistic = true)
            index.putVaultEntries("folder", listOf(renamed), optimistic = false)
            index.close()
            index = open(factory)
            assertEquals("confirmed.mp4", index.file(renamed.virtualId)?.name)
            assertEquals(listOf(renamed), index.vaultEntries("folder"))
            assertNotNull(index.file(manifest.id))
        } finally { index.close() }
    }

    @Test fun rawStarredFieldSurvivesDeltaPersistence() = fixture { factory ->
        var index = open(factory)
        try {
            seed(index, listOf(file("starred")))
            index.beginDelta()
            index.appendDelta(listOf(file("starred").copy(starred = true)))
            index.commitDelta("starred-cursor")
            assertTrue(assertNotNull(index.file("starred")).isStarred)
            index.close()
            index = open(factory)
            assertTrue(assertNotNull(index.file("starred")).isStarred)
        } finally { index.close() }
    }

    @Test fun fullScanAndDeltaCursorsSurviveReopenButUncommittedPagesDoNot() = fixture { factory ->
        var index = open(factory)
        try {
            seed(index, listOf(file("original")))
            index.close()
            index = open(factory)
            assertEquals("initial-server-cursor", index.syncCursor)
            index.beginDelta()
            index.appendDelta(listOf(file("committed")))
            index.commitDelta("committed-server-cursor")
            index.beginDelta()
            index.appendDelta(listOf(file("uncommitted"), file("original").copy(trashed = true)))
            index.close()
            index = open(factory)
            assertEquals("committed-server-cursor", index.syncCursor)
            assertNotNull(index.file("committed"))
            assertNotNull(index.file("original"))
            assertNull(index.file("uncommitted"))
            index.beginDelta()
            index.commitDelta("later-cursor")
            assertNull(index.file("uncommitted"))
        } finally { index.close() }
    }
}
