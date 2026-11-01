package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpaceFolderQueryTest {
    private fun folder(id: String, parent: String = "", name: String = id) =
        FileStat(id = id, parentId = parent, name = name, kind = FileKind.FOLDER)

    private fun file(id: String, parent: String = "", bytes: Long = 1, name: String = id) =
        FileStat(id = id, parentId = parent, name = name, size = bytes.toString())

    @Test
    fun separatesPhysicalAndVaultedBytesAndSortsBySelectedSize() {
        val index = DriveTreeIndex()
        index.putFiles(listOf(folder("a"), folder("b"), file("physical", "a", 10), file("other", "b", 20)))
        index.putVaultEntries("a", listOf(VaultEntry("entry", "archived.mp4", 100, "ABC", addedAt = 0, duration = "3")))
        index.markComplete()

        val physical = spaceFolderSnapshot(index, "", includeVaulted = false, isBuilding = true)
        assertEquals(listOf("b", "a"), physical.items.map { it.id })
        assertEquals(30L, physical.physicalBytes)
        assertEquals(100L, physical.vaultedBytes)
        assertEquals(2L, physical.fileCount)
        assertTrue(physical.isTreeComplete)
        assertTrue(physical.isTreeBuilding)
        assertEquals(listOf("physical"), physical.items.last().previewChildren.map { it.id })

        val combined = spaceFolderSnapshot(index, "", includeVaulted = true, isBuilding = false)
        assertEquals(listOf("a", "b"), combined.items.map { it.id })
        assertEquals(3L, combined.fileCount)
        assertEquals(110L, combined.items.first().bytes(true))
        assertEquals(3_000L, combined.items.first().durationMillis)
        assertTrue(combined.items.first().previewChildren.first().isVaulted)
        val inside = spaceFolderSnapshot(index, "a", true, false)
        assertEquals(3_000L, inside.items.first().durationMillis)
        assertEquals(0L, inside.items.first().physicalBytes)
        assertEquals(100L, inside.items.first().vaultedBytes)
    }

    @Test
    fun excludesInternalTreesAndSidecarsButPreservesNestedUserNames() {
        val index = DriveTreeIndex()
        index.putFiles(listOf(
            folder("internal", name = ".piko"), folder("temp", name = "Piko-Temp"),
            folder("user"), folder("nested", "user", ".piko"),
            file("hidden", "internal", 500), file("temporary", "temp", 500),
            file("kept", "nested", 7),
            file("manifest", bytes = 500, name = ".piko-vault-v1-abcd.json"),
            file("recovery", bytes = 500, name = "归档来源-0123456789abcdef.magnet"),
        ))
        index.markComplete()
        val root = spaceFolderSnapshot(index, "", true, false)
        assertEquals(listOf("user"), root.items.map { it.id })
        assertEquals(7L, root.physicalBytes)
        assertEquals(listOf("nested"), root.items.single().previewChildren.map { it.id })
        assertTrue(spaceFolderSnapshot(index, "internal", true, false).items.isEmpty())
    }

    @Test
    fun breadcrumbsFollowLocalParentsAndStopAtCycles() {
        val index = DriveTreeIndex()
        index.putFiles(listOf(folder("a"), folder("b", "a"), folder("c", "b")))
        assertEquals(listOf("", "a", "b", "c"), spaceFolderSnapshot(index, "c", false, false).breadcrumbs.map { it.id })
        index.putFiles(listOf(folder("a", "c")))
        val cyclic = spaceFolderSnapshot(index, "c", false, false)
        assertEquals(listOf("", "a", "b", "c"), cyclic.breadcrumbs.map { it.id })
        assertFalse(cyclic.isTreeComplete)
        assertEquals(listOf(PikoDriveRepository.ROOT_BREADCRUMB), spaceFolderSnapshot(index, "missing", false, false).breadcrumbs)
    }

    @Test
    fun limitsPreviewsToLargest24FoldersAnd16DirectChildren() {
        val index = DriveTreeIndex()
        val files = buildList {
            add(file("large-root-file", bytes = 1_000_000))
            repeat(25) { n ->
                add(folder("folder-$n"))
                repeat(18) { child -> add(file("$n-$child", "folder-$n", (n + 1L) * (child + 1))) }
            }
        }
        index.putFiles(files.reversed())
        val snapshot = spaceFolderSnapshot(index, "", false, false)
        assertEquals(26, snapshot.items.size)
        assertEquals(24, snapshot.items.count { it.previewChildren.isNotEmpty() })
        assertTrue(snapshot.items.first().previewChildren.isEmpty())
        assertTrue(snapshot.items.last().previewChildren.isEmpty())
        val largest = snapshot.items.first { it.isFolder }
        assertEquals(16, largest.previewChildren.size)
        assertEquals("24-17", largest.previewChildren.first().id)
        assertEquals("24-2", largest.previewChildren.last().id)
    }

    @Test
    fun incompleteAndDirtyListingsAreNotReportedComplete() {
        val index = DriveTreeIndex()
        index.putFolderListing("", listOf(folder("a")))
        assertFalse(spaceFolderSnapshot(index, "", false, false).isTreeComplete)
        index.putFolderListing("a", emptyList())
        assertTrue(spaceFolderSnapshot(index, "", false, false).isTreeComplete)
        index.applyChange(DriveChange.ContentsChanged(setOf("a")))
        val dirty = spaceFolderSnapshot(index, "", false, false)
        assertFalse(dirty.isTreeComplete)
        assertFalse(dirty.items.single().isComplete)
    }

    @Test
    fun durationUsesCapturedContentMetadataAndTiesUseStableIds() {
        val index = DriveTreeIndex()
        index.putFiles(listOf(
            file("b", bytes = 10, name = "same.mp4").copy(hash = "ABC"),
            file("a", bytes = 10, name = "same.mp4").copy(hash = "DEF"),
            file("negative", bytes = -10),
        ))
        index.observeContentMetadata(ContentKey("ABC", 10), ContentMetadata(durationMillis = 9_000))
        val snapshot = spaceFolderSnapshot(index, "", false, false)
        assertEquals(listOf("a", "b", "negative"), snapshot.items.map { it.id })
        assertEquals(9_000L, snapshot.items[1].durationMillis)
        assertEquals(0L, snapshot.items.last().physicalBytes)
    }
}
