package dev.piko.shared.data

import dev.piko.shared.data.DriveTreeIndex.FolderCoverage
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DriveTreeIndexTest {
    private fun file(id: String, parent: String = "root", folder: Boolean = false) = FileStat(
        id = id, parentId = parent, name = id,
        kind = if (folder) FileKind.FOLDER else FileKind.FILE,
    )

    private fun vault(id: String) = VaultEntry(id, "$id.mkv", 10, "HASH", addedAt = 0)

    private fun assertStructure(state: DriveTreeIndex.State) {
        val seen = HashSet<String>()
        for ((parent, ids) in state.childrenByParent) {
            assertTrue(ids.isNotEmpty())
            for (id in ids) {
                assertTrue(seen.add(id), "duplicate parent for $id")
                assertEquals(parent, state.entries.getValue(id).parentId)
            }
        }
        assertEquals(state.entries.keys, seen)
        assertTrue(state.entries.values.none { it.trashed })
    }

    @Test
    fun coverageDistinguishesGlobalCompletenessAndPartialListings() {
        val index = DriveTreeIndex()
        index.markComplete()
        assertEquals(FolderCoverage.UNKNOWN, index.children("root").coverage)
        index.putFolders(listOf(file("empty", folder = true)))
        assertEquals(FolderCoverage.PARTIAL, index.children("empty").coverage)
        index.putFolderListing("root", listOf(file("one")), complete = false)
        assertEquals(FolderCoverage.PARTIAL, index.children("root").coverage)
        assertTrue(index.flow.value.listedFolders.isEmpty())
        index.putFolderListing("root", listOf(file("one")))
        assertEquals(FolderCoverage.COMPLETE, index.children("root").coverage)
        index.applyChange(DriveChange.Created("root", "two", "two", false))
        index.putFolderListing("root", listOf(file("two")), complete = false)
        assertEquals(setOf("one", "two"), index.children("root").files.map { it.id }.toSet())
        assertEquals(FolderCoverage.DIRTY, index.children("root").coverage)
        index.putFolderListing("root", emptyList())
        assertEquals(FolderCoverage.COMPLETE, index.children("root").coverage)
        assertTrue(index.children("root").files.isEmpty())
        assertStructure(index.flow.value)
    }

    @Test
    fun completeListingDetachesMissingFoldersAndPreservesTheirDescendantsAndVaultEntries() {
        val index = DriveTreeIndex()
        val archived = vault("archive")
        index.putFolderListing("root", listOf(file("folder", folder = true), file("other", folder = true), file("gone")))
        index.putFolderListing("folder", listOf(file("nested", "folder", folder = true)))
        index.putFolderListing("nested", listOf(file("leaf", "nested")))
        index.putFolderListing("other", listOf(file("otherLeaf", "other")))
        index.putVaultEntries("root", listOf(archived))
        index.putVaultEntries("folder", listOf(vault("inside")))
        val before = index.flow.value
        index.putFolderListing("root", listOf(file("other", folder = true), file("trashed").copy(trashed = true)))
        assertNull(index.file("folder"))
        assertNull(index.file("gone"))
        assertNull(index.file("trashed"))
        assertEquals(setOf("other", archived.virtualId), index.children("root").files.map { it.id }.toSet())
        assertEquals(listOf("other"), index.realChildren("root").files.map { it.id })
        assertEquals(FolderCoverage.COMPLETE, index.realChildren("root").coverage)
        assertEquals(FolderCoverage.DIRTY, index.children("folder").coverage)
        assertEquals(FolderCoverage.DIRTY, index.children("nested").coverage)
        assertEquals(FolderCoverage.COMPLETE, index.children("other").coverage)
        assertFalse("folder" in index.flow.value.listedFolders)
        assertFalse("nested" in index.flow.value.listedFolders)
        assertTrue("other" in index.flow.value.listedFolders)
        assertEquals(file("leaf", "nested"), index.file("leaf"))
        assertEquals(setOf("folder", "gone"), before.entries.keys - index.flow.value.entries.keys)
        assertStructure(before)
        assertStructure(index.flow.value)
        index.putFolders(listOf(file("folder", "elsewhere", folder = true)))
        assertEquals(setOf("folder"), index.children("elsewhere").files.map { it.id }.toSet())
        assertStructure(index.flow.value)
    }

    @Test
    fun everyUpsertReparentsAndVaultReplacementKeepsPhysicalChildrenAndCoverage() {
        val index = DriveTreeIndex()
        index.putFiles(listOf(file("one"), file("trash").copy(trashed = true)))
        index.putFolders(listOf(file("one", "second", folder = true)))
        assertTrue(index.children("root").files.isEmpty())
        index.putFolderListing("third", listOf(file("one", "third", folder = true)))
        assertTrue(index.children("second").files.isEmpty())
        val archived = vault("archive")
        index.putVaultEntries("third", listOf(archived))
        index.putVaultEntries("fourth", listOf(archived))
        assertEquals(listOf("one"), index.children("third").files.map { it.id })
        index.applyChange(DriveChange.ContentsChanged(setOf("fourth")))
        index.putFiles(listOf(file("physical", "fourth")))
        val before = index.flow.value
        index.putVaultEntries("fourth", listOf(archived.copy(restoredFileId = "physical"), vault("new")))
        assertNull(index.file(archived.virtualId))
        assertEquals(setOf("physical", vault("new").virtualId), index.children("fourth").files.map { it.id }.toSet())
        assertEquals(before.listedFolders, index.flow.value.listedFolders)
        assertEquals(before.dirtyFolders, index.flow.value.dirtyFolders)
        assertStructure(index.flow.value)
    }

    @Test
    fun movesAndRenamesUpdateSearchAndDirtyCoverageAtomically() {
        val index = DriveTreeIndex()
        index.putFolderListing("root", listOf(file("one"), file("two")))
        val before = index.flow.value
        index.applyChange(DriveChange.Moved(listOf("one", "two", "unknown"), null, "target"))
        assertEquals(before.revision + 1, index.flow.value.revision)
        assertTrue(index.children("root").files.isEmpty())
        assertEquals(setOf("one", "two"), index.children("target").files.map { it.id }.toSet())
        assertEquals(setOf("root", "target"), index.dirtyFolders)
        index.applyChange(DriveChange.Renamed("one", "한국영화", "target"))
        assertEquals(listOf("one"), index.searchLocal("한영화", "target", FolderIndex()).map { it.file.id })
        assertEquals("root", before.entries.getValue("one").parentId)
        index.putFolderListing("root", emptyList())
        index.putFolderListing("target", index.children("target").files)
        assertTrue(index.dirtyFolders.isEmpty())
        assertStructure(index.flow.value)
    }

    @Test
    fun removalTraversesCyclesVirtualChildrenAndDetachedSubtrees() {
        for (trash in listOf(false, true)) {
            val index = DriveTreeIndex()
            index.putFolderListing("b", listOf(file("a", "b", folder = true)))
            index.putFolderListing("a", listOf(file("b", "a", folder = true)))
            index.putVaultEntries("b", listOf(vault("inside")))
            index.applyChange(DriveChange.ContentsChanged(setOf("a", "b")))
            val change = if (trash) DriveChange.Trashed(listOf("a"), null)
                else DriveChange.Deleted(listOf("a"), emptySet())
            index.applyChange(change)
            assertEquals(DriveTreeIndex.State(revision = index.flow.value.revision), index.flow.value)
            index.putFolders(listOf(file("detached", folder = true)))
            index.putFiles(listOf(file("leaf", "detached"), file("survivor")))
            index.putFolderListing("root", listOf(file("survivor")))
            index.applyChange(DriveChange.Deleted(listOf("detached"), emptySet()))
            assertEquals(setOf("survivor"), index.flow.value.entries.keys)
            assertEquals(FolderCoverage.UNKNOWN, index.children("detached").coverage)
            assertStructure(index.flow.value)
        }
    }

    @Test
    fun invalidationRetainsStaleEntriesAndClearAndNoOpsHaveSemanticRevisions() {
        val index = DriveTreeIndex()
        val initial = index.flow.value
        index.clear()
        index.putFiles(emptyList())
        index.applyChange(DriveChange.Renamed("unknown", "name", null))
        index.applyChange(DriveChange.Deleted(listOf("unknown"), emptySet()))
        assertSame(initial, index.flow.value)
        index.putFolderListing("root", listOf(file("one")))
        index.markComplete()
        index.applyChange(DriveChange.Copied(listOf("one"), "target"))
        assertTrue(index.isComplete)
        assertEquals(setOf("target"), index.dirtyFolders)
        val before = index.flow.value
        index.putFolderListing("root", listOf(file("one")))
        index.putFiles(listOf(file("one")))
        index.markComplete()
        index.applyChange(DriveChange.Renamed("one", "one", "root"))
        assertSame(before, index.flow.value)
        index.applyChange(DriveChange.ContentsChanged(null))
        assertFalse(index.isComplete)
        assertTrue(index.flow.value.listedFolders.isEmpty())
        assertTrue(index.dirtyFolders.isEmpty())
        assertEquals(before.entries, index.flow.value.entries)
        index.putFolderListing("root", listOf(file("one")))
        index.markComplete()
        index.applyChange(DriveChange.Restored(listOf("unknown")))
        assertFalse(index.isComplete)
        assertTrue(index.flow.value.listedFolders.isEmpty())
        val revision = index.flow.value.revision
        index.clear()
        assertEquals(DriveTreeIndex.State(revision = revision + 1), index.flow.value)
        val cleared = index.flow.value
        index.clear()
        assertSame(cleared, index.flow.value)
    }

    @Test
    fun concurrentWritersPublishConsistentSnapshotsWithoutLosingRevisions() = runBlocking {
        val index = DriveTreeIndex()
        val snapshots = java.util.concurrent.ConcurrentLinkedQueue<DriveTreeIndex.State>()
        val startBarrier = kotlinx.coroutines.CompletableDeferred<Unit>()
        val jobs = List(4) { writer ->
            launch(Dispatchers.Default) {
                startBarrier.await()
                repeat(50) { step ->
                    index.putFiles(listOf(file("$writer-$step", "parent-$writer")))
                    snapshots.add(index.flow.value)
                }
            }
        }
        startBarrier.complete(Unit)
        jobs.forEach { it.join() }
        snapshots.forEach {
            assertStructure(it)
            assertEquals(it.entries.size.toLong(), it.revision)
        }
        assertEquals(200, index.flow.value.entries.size)
        assertEquals(200L, index.flow.value.revision)
    }

    @Test
    fun replaceTreePreservesDirtyFoldersAndReportsChangedParents() {
        val index = DriveTreeIndex()
        index.putFolderListing("", listOf(file("dirA", "", folder = true), file("dirB", "", folder = true)))
        index.putFolderListing("dirA", listOf(file("a1", "dirA")))
        index.putFolderListing("dirB", listOf(file("b1", "dirB")))
        index.markComplete()

        // Mutate dirB locally before shadow tree replacement completes
        index.putFiles(listOf(file("b2", "dirB")))
        index.applyChange(DriveChange.Created("dirB", "b2", "b2.mkv", isFolder = false))
        assertEquals(setOf("dirB"), index.dirtyFolders)

        val changed = index.replaceTree(
            folders = listOf(
                file("dirA", "", folder = true),
                file("dirB", "", folder = true),
            ),
            files = listOf(
                file("a1", "dirA"),
                file("a2", "dirA"), // added remotely in dirA
                file("b1", "dirB"), // remote snapshot doesn't have b2 yet
            ),
            vaultByFolder = emptyMap(),
            usageBytes = 4096L,
            keepDirtyFolders = setOf("dirB"),
        )

        assertEquals(setOf("dirA"), changed)
        assertEquals(setOf("a1", "a2"), index.realChildren("dirA").files.map { it.id }.toSet())
        // dirB was dirty so its local optimistic entry b2 is preserved and dirB remains dirty
        assertEquals(setOf("b1", "b2"), index.realChildren("dirB").files.map { it.id }.toSet())
        assertEquals(setOf("dirB"), index.dirtyFolders)
        assertEquals(4096L, index.flow.value.lastUsageBytes)
        assertStructure(index.flow.value)
    }

    @Test
    fun folderUsageAndSnapshotRoundTrip() {
        val index = DriveTreeIndex()
        val f1 = file("f1", "sub").copy(size = "100")
        val f2 = file("f2", "").copy(size = "250")
        val sub = file("sub", "", folder = true)
        val manifest = FileStat(
            id = "m1",
            parentId = "sub",
            name = ".piko-vault-v1-aaaa.json",
            kind = FileKind.FILE,
            size = "42",
            phase = io.github.nihildigit.pikpak.TaskPhase.COMPLETE,
        )
        val archived = vault("v1")
        index.putFolderListing("", listOf(sub, f2))
        index.putFolderListing("sub", listOf(f1, manifest))
        index.putVaultEntries("sub", listOf(archived))

        // Incomplete tree returns null for root folderUsage when sub was not reached or when incomplete
        val partialIndex = DriveTreeIndex()
        partialIndex.putFolderListing("", listOf(sub, f2))
        assertNull(partialIndex.folderUsage(""))

        index.markComplete()
        index.setLastUsageBytes(392L)

        // Complete & clean tree computes usage without counting virtual vault entries
        assertEquals(FolderUsage(fileCount = 3, bytes = 392L, progress = FolderUsage.Progress.COMPLETE), index.folderUsage(""))
        assertEquals(FolderUsage(fileCount = 2, bytes = 142L, progress = FolderUsage.Progress.COMPLETE), index.folderUsage("sub"))

        // Snapshot encode/decode round-trip
        val json = DriveTreeIndex.encodeSnapshot(index.flow.value, savedAtMillis = 1234L)
        val restored = DriveTreeIndex()
        val snapshot = DriveTreeIndex.decodeSnapshot(json)!!
        restored.restoreSnapshot(snapshot, mapOf(manifest.id to listOf(archived)))

        assertTrue(restored.isComplete)
        assertEquals(392L, restored.flow.value.lastUsageBytes)
        assertEquals(FolderUsage(fileCount = 3, bytes = 392L, progress = FolderUsage.Progress.COMPLETE), restored.folderUsage(""))
        assertEquals(manifest.id, restored.flow.value.manifestsByFolder["sub"]?.id)
        assertEquals(listOf(archived), restored.flow.value.vaultEntriesByFolder["sub"])
        assertEquals(setOf("f1", "m1", archived.virtualId), restored.children("sub").files.map { it.id }.toSet())
        assertStructure(restored.flow.value)

        // Marking sub dirty invalidates folderUsage for both root and sub
        restored.applyChange(DriveChange.ContentsChanged(setOf("sub")))
        assertNull(restored.folderUsage(""))
        assertNull(restored.folderUsage("sub"))
    }

    @Test
    fun repositoryRestoresSnapshotFromCacheAndCanaryProbesRemoteChanges() = dev.piko.shared.smoke.smoke {
        val server = dev.piko.shared.smoke.FakePikPakServer()
        val show = server.addFolder("Show")
        val ep1 = server.addFile("E01.mkv", show.id, content = ByteArray(100), hash = "H1")
        server.quotaUsage = 100L
        val cacheStore = dev.piko.shared.smoke.MemoryCacheStore()

        val repo1 = PikoDriveRepository(server.provider(), cacheStore = cacheStore)
        repo1.getQuota().getOrThrow()
        repo1.warmDriveTree().getOrThrow()
        val snapKey = DriveTreeIndex.snapshotKeyOf("smoke@piko.dev")
        dev.piko.shared.smoke.awaitUntil("树快照写入磁盘缓存") {
            runBlocking { cacheStore.read(snapKey) } != null
        }

        // Cold start a second repository with the same cacheStore
        val repo2 = PikoDriveRepository(server.provider(), cacheStore = cacheStore)
        dev.piko.shared.smoke.awaitUntil("冷启动恢复完整本地树") {
            repo2.cachedBrowsable(show.id, PikoFileSortOrder.NAME_ASC)?.map { it.id } == listOf(ep1.id)
        }

        // 1. Unchanged remote -> UNCHANGED
        assertEquals(
            PikoDriveRepository.CanaryProbeResult.UNCHANGED,
            repo2.probeRemoteChanges(force = true).getOrThrow(),
        )

        // 2. Remote addition with matching byte delta -> INCREMENTAL_PATCH
        val ep2 = server.addFile("E02.mkv", show.id, content = ByteArray(200), hash = "H2")
        server.quotaUsage = 300L
        assertEquals(
            PikoDriveRepository.CanaryProbeResult.INCREMENTAL_PATCH,
            repo2.probeRemoteChanges(force = true).getOrThrow(),
        )
        assertEquals(
            setOf(ep1.id, ep2.id),
            repo2.cachedBrowsable(show.id, PikoFileSortOrder.NAME_ASC)?.map { it.id }?.toSet(),
        )

        // 3. Remote empty folder deletion without quota change -> FULL_REBUILD
        val emptyDir = server.addFolder("EmptyDir")
        assertEquals(
            PikoDriveRepository.CanaryProbeResult.INCREMENTAL_PATCH,
            repo2.probeRemoteChanges(force = true).getOrThrow(),
        )
        assertTrue(emptyDir.id in repo2.driveTreeIndex.flow.value.entries)
        repo1.trash(listOf(emptyDir.id)).getOrThrow()
        assertEquals(
            PikoDriveRepository.CanaryProbeResult.FULL_REBUILD,
            repo2.probeRemoteChanges(force = true).getOrThrow(),
        )
        assertFalse(emptyDir.id in repo2.driveTreeIndex.flow.value.entries)

        // 4. Remote quota drop -> FULL_REBUILD
        server.quotaUsage = 100L
        assertEquals(
            PikoDriveRepository.CanaryProbeResult.FULL_REBUILD,
            repo2.probeRemoteChanges(force = true).getOrThrow(),
        )
    }

    @Test
    fun shadowSwapHealsPreExistingDirtyAndPreservesConcurrentMutationsAndManifestInvalidation() {
        val index = DriveTreeIndex()
        val m1 = FileStat(
            id = "m1",
            parentId = "dirA",
            name = ".piko-vault-v1-0001.json",
            kind = FileKind.FILE,
            phase = io.github.nihildigit.pikpak.TaskPhase.COMPLETE,
        )
        val m2 = FileStat(
            id = "m2",
            parentId = "dirA",
            name = ".piko-vault-v2-0002.json",
            kind = FileKind.FILE,
            phase = io.github.nihildigit.pikpak.TaskPhase.COMPLETE,
        )
        val vOld = vault("vOld")
        index.putFolderListing("", listOf(file("dirA", "", folder = true), file("dirB", "", folder = true), file("dirC", "", folder = true)))
        index.putFolderListing("dirA", listOf(file("a1", "dirA"), m1))
        index.putVaultEntries("dirA", listOf(vOld))
        index.putFolderListing("dirB", listOf(file("b1", "dirB")))
        index.putFolderListing("dirC", listOf(file("c1", "dirC")))
        index.markComplete()

        // When winning manifest in dirA changes from m1 to m2, stale vaultEntries for m1 are evicted
        index.putFolderListing("dirA", listOf(file("a1", "dirA"), m2))
        assertNull(index.flow.value.vaultEntriesByFolder["dirA"])
        assertNull(index.file(vOld.virtualId))
        // Late completion of an older read for m1 must be rejected
        assertFalse(index.putVaultEntries("dirA", listOf(vOld), expectedManifestId = "m1", requireMatch = true))
        assertNull(index.flow.value.vaultEntriesByFolder["dirA"])

        // Pre-existing dirty folder dirA before rebuild starts; during rebuild:
        // - dirB is freshly listed with b2 AND a newly discovered subfolder subNew (whose own children haven't been listed yet)
        // - c1 in dirC is deleted, and a1 is renamed to "a1-renamed"
        index.applyChange(DriveChange.ContentsChanged(setOf("dirA")))
        assertEquals(setOf("dirA"), index.dirtyFolders)
        val rebuildStartSeq = index.currentMutationSeq

        index.applyChange(DriveChange.Created("dirB", "b2", "b2", isFolder = false))
        index.putFolderListing("dirB", listOf(file("b1", "dirB"), file("b2", "dirB"), file("subNew", "dirB", folder = true)))
        index.applyChange(DriveChange.Deleted(listOf("c1"), setOf("dirC")))
        index.applyCanaryPatch(listOf(file("cCanary", "dirC"), m2), usageBytes = 640L)
        index.applyChange(DriveChange.Renamed("a1", "a1-renamed", "dirA"))

        index.replaceTree(
            folders = listOf(
                file("dirA", "", folder = true),
                file("dirB", "", folder = true),
                file("dirC", "", folder = true),
            ),
            files = listOf(
                file("a1", "dirA"),
                m1, // stale manifest in rebuild candidate; canary patched m2 during rebuild
                file("b1", "dirB"), // stale in rebuild snapshot, but dirB was mutated and then freshly listed
                file("c1", "dirC"), // stale in rebuild snapshot, but c1 was deleted during rebuild
            ),
            vaultByFolder = mapOf("dirA" to listOf(vOld)),
            usageBytes = 512L,
            sinceMutationSeq = rebuildStartSeq,
            keepDirtyFolders = setOf("dirB"),
            syncedAtMillis = 777L,
        )

        // dirA is healed by full rebuild, has the renamed file, selects canary-patched m2 as winner, and rejects m1's stale vaultByFolder!
        assertEquals(777L, index.lastFullSyncAtMillis)
        assertEquals(640L, index.lastUsageBytes)
        assertEquals("m2", index.flow.value.manifestsByFolder["dirA"]?.id)
        assertNull(index.flow.value.vaultEntriesByFolder["dirA"])
        assertNull(index.file(vOld.virtualId))
        assertEquals(setOf("subNew"), index.dirtyFolders)
        assertEquals(FolderCoverage.DIRTY, index.children("subNew").coverage)
        assertNull(index.folderUsage("dirB"))
        assertEquals("a1-renamed", index.file("a1")?.name)
        // dirB keeps its fresh listing (b1, b2, subNew) and stays clean itself
        assertEquals(setOf("b1", "b2", "subNew"), index.realChildren("dirB").files.map { it.id }.toSet())
        assertEquals(FolderCoverage.COMPLETE, index.realChildren("dirB").coverage)
        // c1 was deleted during rebuild and is not resurrected, while canary patch cCanary survives swap
        assertNull(index.file("c1"))
        assertEquals(setOf("cCanary"), index.realChildren("dirC").files.map { it.id }.toSet())
        assertStructure(index.flow.value)

        // Listing subNew clears its dirty flag
        index.putFolderListing("subNew", emptyList())
        assertTrue(index.dirtyFolders.isEmpty())

        // Snapshot preserves dirtyFolders, lastFullSyncAtMillis (distinct from savedAtMillis), and non-COMPLETE phase
        index.applyChange(DriveChange.Deleted(listOf("m1"), emptySet()))
        val unfinishedManifest = m2.copy(phase = "PHASE_TYPE_RUNNING")
        index.putFiles(listOf(unfinishedManifest))
        index.applyChange(DriveChange.ContentsChanged(setOf("dirB")))
        val encoded = DriveTreeIndex.encodeSnapshot(
            state = index.flow.value,
            savedAtMillis = 999L,
            lastFullSyncAtMillis = index.lastFullSyncAtMillis,
        )
        val decoded = DriveTreeIndex.decodeSnapshot(encoded)!!
        assertEquals(setOf("dirB"), decoded.dirtyFolders)
        assertEquals(777L, decoded.lastFullSyncAtMillis)

        val coldIndex = DriveTreeIndex()
        // Simulate pre-listing deletion & rename, and a folder dirC mutated then listed BEFORE cold snapshot finishes loading
        coldIndex.applyChange(DriveChange.Deleted(listOf("b1"), emptySet()))
        coldIndex.applyChange(DriveChange.Renamed("a1", "a1-cold-renamed", null))
        coldIndex.applyChange(DriveChange.Created("dirC", "cNew", "cNew", isFolder = false))
        coldIndex.putFolderListing("dirC", listOf(file("cNew", "dirC"), file("coldSub", "dirC", folder = true)))
        assertTrue(coldIndex.restoreSnapshot(decoded, mapOf(unfinishedManifest.id to listOf(vOld))))
        assertEquals(777L, coldIndex.lastFullSyncAtMillis)
        // Unfinished manifest must NOT win
        assertNull(coldIndex.flow.value.manifestsByFolder["dirA"])
        // Pre-listing deletion of b1 and rename of a1 are preserved across snapshot restore
        assertNull(coldIndex.file("b1"))
        assertEquals("a1-cold-renamed", coldIndex.file("a1")?.name)
        // Live listing of dirC is preserved, dirty status of dirB is preserved, and newly observed coldSub is marked dirty
        assertEquals(setOf("cNew", "coldSub"), coldIndex.realChildren("dirC").files.map { it.id }.toSet())
        assertEquals(setOf("dirB", "coldSub"), coldIndex.dirtyFolders)
        assertEquals(FolderCoverage.DIRTY, coldIndex.children("coldSub").coverage)
        // Second restoreSnapshot after complete is a no-op
        assertFalse(coldIndex.restoreSnapshot(decoded, emptyMap()))
    }

    @Test
    fun localDeletionAndFolderListingKeepLastUsageBytesAligned() {
        val index = DriveTreeIndex()
        val f1 = FileStat(id = "f1", parentId = "dirA", name = "ep1.mp4", size = "1000", kind = FileKind.FILE)
        val f2 = FileStat(id = "f2", parentId = "dirA", name = "ep2.mp4", size = "2500", kind = FileKind.FILE)
        val manifest = FileStat(
            id = "m1",
            parentId = "dirA",
            name = ".piko-vault-v1-abcd.json",
            size = "120",
            kind = FileKind.FILE,
            phase = io.github.nihildigit.pikpak.TaskPhase.COMPLETE,
        )
        index.replaceTree(
            folders = listOf(file("dirA", "", folder = true)),
            files = listOf(f1, f2),
            vaultByFolder = emptyMap(),
            usageBytes = 3500L,
            syncedAtMillis = 100L,
        )
        assertEquals(3500L, index.lastUsageBytes)

        // 本机归档写入新清单并重列该目录、随后彻底删除原文件：lastUsageBytes 应精确跟进为 120，避免误触发全盘重扫
        index.putFolderListing("dirA", listOf(f1, f2, manifest), complete = true)
        assertEquals(3620L, index.lastUsageBytes)

        index.applyChange(DriveChange.Deleted(listOf("f1", "f2"), setOf("dirA")))
        assertEquals(120L, index.lastUsageBytes)
    }
}
