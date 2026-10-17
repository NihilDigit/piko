package dev.piko.ui

import dev.piko.ui.screens.instant.*
import io.github.nihildigit.pikpak.ResolvedFile
import kotlin.test.*

class ImportResultProjectionTest {
    @Test
    fun singleVideosStayAtRootAndEqualOutputNamesKeepTheirSourceIdentity() {
        val file = ResolvedFile("视频.mkv", 100, "HASH")
        val entries = importResultEntries(listOf(
            ImportProjectionSource("one", listOf(7 to file)), ImportProjectionSource("two", listOf(3 to file)),
        ))
        assertEquals(2, entries.size)
        assertTrue(entries.none { it.file.isFolder })
        assertTrue(entries.all { it.file.parentId.isEmpty() })
        assertEquals(2, entries.map { it.file.id }.toSet().size)
        assertEquals(listOf(7, 3), entries.map { it.sourceIndex })
        assertEquals(entries.first().file.id,
            importResultEntries(listOf(ImportProjectionSource("one", listOf(7 to file)))).single().file.id)
    }

    @Test
    fun equalFolderPathsFromDifferentLinksRemainDistinctSavedFolders() {
        val file = ResolvedFile("合集/目录/视频.mkv", 100, "HASH")
        val entries = importResultEntries(listOf(
            ImportProjectionSource("one", listOf(0 to file)), ImportProjectionSource("two", listOf(0 to file)),
        ))
        assertEquals(6, entries.size)
        assertEquals(2, entries.filter { it.file.parentId.isEmpty() }.size)
        entries.filterNot { it.file.isFolder }.forEach { leaf ->
            assertEquals(leaf.sourceKey, entries.single { it.file.id == leaf.file.parentId }.sourceKey)
        }
    }
}
