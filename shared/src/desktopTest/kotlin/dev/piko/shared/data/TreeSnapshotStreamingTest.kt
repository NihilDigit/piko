package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.MediaVariant
import io.github.nihildigit.pikpak.VideoInfo
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TreeSnapshotStreamingTest {
    private fun decode(text: String) = DriveTreeIndex.readSnapshot(Buffer().apply { writeString(text) })
    private fun file(id: String, parent: String = "", folder: Boolean = false) = FileStat(
        id = id, parentId = parent, name = "$id.mp4", kind = if (folder) FileKind.FOLDER else FileKind.FILE,
        size = "10", hash = if (folder) "" else "hash", mimeType = if (folder) "" else "video/mp4",
    )

    @Test fun sourceOrderAndInvalidReferencesAreSafe() {
        val snapshot = assertNotNull(decode("""{"entries":[{"id":"a","n":"a","sr":0},{"id":"b","n":"b","sr":-1},{"id":"c","n":"c","sr":9}],"sources":["magnet:test"],"schemaVersion":2}"""))
        assertTrue(snapshot.entries.isEmpty())
        val physical = assertNotNull(snapshot.physicalEntries)
        assertEquals("magnet:test", physical.getValue("a").sourceUrl)
        assertNull(physical.getValue("b").sourceUrl)
        assertNull(physical.getValue("c").sourceUrl)
        assertNull(decode("""{"entries":[],"schemaVersion":999}"""))
        assertNull(decode("""{"entries":[{"id":"a","n":"unfinished"""))
        assertNull(decode("""{"entries":[],"entries":[]}"""))
    }

    @Test fun metadataRoundTripKeepsPhysicalFactsAndInstanceTimesSeparate() {
        val index = DriveTreeIndex()
        val a = file("a").copy(createdTime = "2020-01-01T00:00:00Z", modifiedTime = "2021-01-01T00:00:00Z",
            params = mapOf("piko_original_created_at" to "100"))
        val b = file("b").copy(params = mapOf("piko_original_created_at" to "200"))
        index.putFiles(listOf(a, b))
        index.observeDetail(FileDetail(id = "temporary-lease", parentId = "temp", hash = "HASH", size = "10", medias = listOf(
            MediaVariant(isOrigin = false, video = VideoInfo(width = 7680, height = 4320, duration = 999)),
            MediaVariant(isOrigin = true, video = VideoInfo(width = 1920, height = 1080, duration = 10)),
        )))
        index.observeContentMetadata(ContentKey("hash", 10), ContentMetadata(width = 8000, qualityRank = 0))
        val captured = index.sortContext()
        assertSame(captured, index.sortContext(), "one immutable projection is reused for each revision")
        assertEquals(1920, captured.contentMetadata.getValue(ContentKey("HASH", 10)).width)
        assertEquals(100L, captured.createdAtMillis["a"])
        assertEquals(200L, captured.createdAtMillis["b"])
        assertNull(index.file("temporary-lease"))
        assertEquals(a.createdTime, index.file("a")?.createdTime)
        assertEquals(a.modifiedTime, index.file("a")?.modifiedTime)
        val buffer = Buffer()
        DriveTreeIndex.writeSnapshot(buffer, index.flow.value)
        val restored = DriveTreeIndex()
        restored.restoreSnapshot(assertNotNull(DriveTreeIndex.readSnapshot(buffer)), emptyMap())
        assertEquals(captured.contentMetadata, restored.sortContext().contentMetadata)
        assertEquals(100L, restored.sortContext().createdAtMillis["a"])
        index.setEntryMetadata("a", EntryMetadata(300))
        assertEquals(100L, captured.createdAtMillis["a"])
    }

    @Test fun compactMapHonorsMapContractAndCanonicalizerBound() {
        val expected = mapOf("a" to "1", "b" to "2")
        val compact = CompactStringMap.of(expected)
        assertEquals(expected, compact)
        assertEquals(compact, expected)
        assertEquals(expected.hashCode(), compact.hashCode())
        assertEquals(expected.entries, compact.entries)
        assertEquals(expected.keys, compact.keys)
        assertEquals(expected.values.toSet(), compact.values.toSet())
        val canonical = TreeCanonicalizer(maxEntries = 1)
        val first = charArrayOf('a', 'b').concatToString()
        val second = charArrayOf('a', 'b').concatToString()
        assertSame(first, canonical.canonical(first))
        assertSame(first, canonical.canonical(second))
        canonical.canonical("other")
        assertSame(first, canonical.canonical(second))
    }

    @Test fun iterativeStatsExcludeInternalSubtreesRestoredVaultAndOrphansAndDetectCycles() {
        val index = DriveTreeIndex()
        index.putFiles(listOf(file("folder", folder = true), file("a", "folder"), file("b", "folder"),
            file("internal", folder = true).copy(name = ".piko"), file("secret", "internal"),
            file("orphan", "missing"), file("cycle1", "cycle2", true), file("cycle2", "cycle1", true)))
        index.putVaultEntries("folder", listOf(
            VaultEntry("v1", "one.mp4", 20, "vhash", addedAt = 0, duration = "5"),
            VaultEntry("v2", "two.mp4", 10, "hash", addedAt = 0, restoredFileId = "b"),
        ))
        assertTrue(index.flow.value.entries.values.filter { VaultEntry.isVaulted(it.id) }.all {
            VaultEntry.entryIdOf(it.id) == "v1"
        }, "restored vault entries must not be projected into the virtual tree")
        index.markComplete()
        val stats = index.sortContext().subtreeStats
        assertEquals(20L, stats.getValue("").physical.bytes)
        assertEquals(20L, stats.getValue("").vaulted.bytes)
        assertEquals(5000L, stats.getValue("").vaulted.durationMillis)
        assertFalse(stats.getValue("cycle1").structuralValid)
        assertFalse(stats.getValue("cycle2").structuralValid)
        assertFalse(stats.getValue("folder").physical.metadataComplete)
        index.observeContentMetadata(ContentKey("HASH", 10), ContentMetadata(durationMillis = 3000))
        assertEquals(6000L, index.sortContext().subtreeStats.getValue("folder").physical.durationMillis)
        assertEquals(0L, stats.getValue("folder").physical.durationMillis)
    }

    @Test fun deepTreeAggregationDoesNotUseTheCallStack() {
        val index = DriveTreeIndex()
        val depth = 12000
        index.putFiles(List(depth) { n -> file("dir$n", if (n == 0) "" else "dir${n - 1}", true) } + file("leaf", "dir${depth - 1}"))
        index.markComplete()
        assertEquals(10L, index.sortContext().subtreeStats.getValue("").physical.bytes)
        assertTrue(index.sortContext().subtreeStats.getValue("").structuralValid)
    }

    @Test fun timestampsUseEpochNotLexicalOrderAndUnknownIsAbsent() {
        assertEquals(treeEpochMillis("2020-01-01T00:00:00Z"), treeEpochMillis("2020-01-01T08:00:00+08:00"))
        assertEquals(1L, treeEpochMillis("1970-01-01T00:00:00.001Z"))
        assertEquals(0L, treeEpochMillis("1970-01-01T00:00:00Z"))
        assertNull(treeEpochMillis("invalid"))
    }

    @Test fun historyPinProtectsConcurrentRebuildAndOldSnapshotIsImmutable() {
        val index = DriveTreeIndex()
        val a = file("a")
        index.putFiles(listOf(a))
        val before = index.flow.value
        val pin = index.retainMutationHistory()
        index.applyChange(DriveChange.Renamed("a", "new", ""))
        index.pruneMutationHistory(Long.MAX_VALUE)
        index.replaceTree(emptyList(), listOf(a), emptyMap(), null, sinceMutationSeq = pin.sequence)
        assertEquals("new", index.file("a")?.name)
        assertEquals(a.name, before.entries.getValue("a").name)
        pin.close(); pin.close()
        index.pruneMutationHistory(Long.MAX_VALUE)
    }
}
