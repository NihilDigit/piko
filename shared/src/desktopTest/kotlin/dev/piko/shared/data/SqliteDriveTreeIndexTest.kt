package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.io.Buffer
import kotlinx.coroutines.flow.first
import kotlinx.io.writeString
import java.nio.file.Files
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteDriveTreeIndexTest {
    private fun fixture(block: (DesktopPikoTreeDatabaseFactory) -> Unit) {
        val directory = Files.createTempDirectory("piko-sqlite-tree").toFile()
        try { block(DesktopPikoTreeDatabaseFactory(directory)) } finally { directory.deleteRecursively() }
    }
    private fun open(factory: DesktopPikoTreeDatabaseFactory, account: String = "account") =
        DriveTreeIndex(database = factory.open(account), ownerAccount = account)
    private fun folder(id: String, parent: String = "") = FileStat(id = id, parentId = parent, name = id, kind = FileKind.FOLDER)
    private fun file(id: String, parent: String = "", hash: String = id) = FileStat(id = id, parentId = parent, kind = FileKind.FILE, name = "$id.mp4", size = "100", hash = hash, mimeType = "video/mp4", createdTime = "2025-01-01T00:00:00Z", modifiedTime = "2025-01-02T00:00:00Z")

    @Test fun restoredInvalidationRemainsIncompleteAfterReopen() = fixture { factory ->
        var index = open(factory)
        try {
            val generation = index.beginCandidate()
            index.appendCandidate(generation, listOf(folder("a"), file("existing", "a")))
            index.commitCandidate(generation, 100)
            assertTrue(index.isComplete)
            index.applyChange(DriveChange.Restored(listOf("restored")))
            assertFalse(index.isComplete)
            index.close()
            index = open(factory)
            assertFalse(index.isComplete)
            assertNotNull(index.file("existing"))
        } finally { index.close() }
    }

    @Test fun candidatesReplayUnknownRenameMoveAndDeletionWithoutPublishingPages() = fixture { factory ->
        val index = open(factory)
        try {
            val generation = index.beginCandidate()
            index.appendCandidate(generation, listOf(folder("a"), folder("b"), file("gone", "a")))
            assertNull(index.file("gone"))
            index.applyChange(DriveChange.Renamed("late", "renamed.mp4", "a"))
            index.applyChange(DriveChange.Moved(listOf("late"), setOf("a"), "b"))
            index.applyChange(DriveChange.Deleted(listOf("gone"), setOf("a")))
            index.appendCandidate(generation, listOf(file("late", "a")))
            index.commitCandidate(generation, 100)
            assertNull(index.file("gone"))
            assertEquals("renamed.mp4", index.file("late")?.name)
            assertEquals("b", index.file("late")?.parentId)
        } finally { index.close() }
    }

    @Test fun candidateCannotResurrectClearedVaultOrOverwriteNewerUsage() = fixture { factory ->
        val index = open(factory)
        try {
            index.putFolders(listOf(folder("a")))
            val entry = VaultEntry("entry", "archived.mp4", 100, "GCID", addedAt = 123)
            index.putVaultEntries("a", listOf(entry))
            val generation = index.beginCandidate()
            index.appendCandidate(generation, listOf(folder("a")))
            index.appendCandidateVault(generation, "a", listOf(entry))
            index.putVaultEntries("a", emptyList())
            index.applyCanaryPatch(emptyList(), usageBytes = 777)
            index.commitCandidate(generation, 100)
            assertEquals(emptyList(), index.vaultEntries("a"))
            assertTrue(index.children("a").files.none { VaultEntry.isVaulted(it.id) })
            assertEquals(777L, index.lastUsageBytes)
            val next = index.beginCandidate()
            index.setLastUsageBytes(888)
            index.commitCandidate(next, null)
            assertEquals(888L, index.lastUsageBytes)
        } finally { index.close() }
    }

    @Test fun publishedFolderStateAndAggregatesRemainAtTheirRevision() = fixture { factory ->
        val index = open(factory)
        try {
            index.putFolders(listOf(folder("a")))
            index.putFiles(listOf(file("video", "a")))
            index.markComplete()
            val captured = index.flow.value
            assertEquals(setOf("a"), captured.entries.keys)
            assertEquals(1L, captured.sortProjection?.subtreeStats?.get("")?.physical?.fileCount)
            index.applyChange(DriveChange.Renamed("a", "renamed", ""))
            index.applyChange(DriveChange.Deleted(listOf("video"), setOf("a")))
            assertEquals("a", captured.entries["a"]?.name)
            assertEquals(1L, captured.sortProjection?.subtreeStats?.get("")?.physical?.fileCount)
            assertEquals(0L, index.sortContext().subtreeStats.getValue("").physical.fileCount)
        } finally { index.close() }
    }

    @Test fun authoritativeListingSurvivesCandidateAndClosedInstanceCannotWrite() = fixture { factory ->
        val index = open(factory)
        val generation = index.beginCandidate()
        index.appendCandidate(generation, listOf(folder("a"), file("stale", "a")))
        index.putFolderListing("a", listOf(file("fresh", "a")))
        index.commitCandidate(generation, 100)
        assertNull(index.file("stale"))
        assertNotNull(index.file("fresh"))
        index.close()
        val reopened = open(factory)
        try {
            index.putFiles(listOf(file("late-write")))
            index.clear()
            index.applyChange(DriveChange.Deleted(listOf("fresh"), setOf("a")))
            assertNull(reopened.file("late-write"))
            assertNotNull(reopened.file("fresh"))
        } finally { reopened.close() }
    }

    @Test fun originalInstanceTimeRequiresRestoredIdentityAndContentAndSurvivesMoveReopen() = fixture { factory ->
        var index = open(factory)
        val original = 123456789000L
        val physical = file("restored", "a", "CONTENT")
        val claim = VaultEntry("entry", "before.mp4", 100, "content", addedAt = original + 1000, createdAt = original)
        try {
            index.putFolders(listOf(folder("a"), folder("b")))
            index.putVaultEntries("a", listOf(claim))
            index.putFiles(listOf(physical, file("wrong-hash", "a"), file("wrong-size", "a", "CONTENT").copy(size = "101")))
            index.putVaultEntries("a", listOf(claim.copy(restoredFileId = physical.id), claim.copy(id = "bad-hash", restoredFileId = "wrong-hash"), claim.copy(id = "bad-size", restoredFileId = "wrong-size")))
            assertEquals(original, index.sortContext(listOf(physical)).createdAtMillis[physical.id])
            assertEquals(treeEpochMillis(physical.createdTime), index.sortContext(listOf(file("wrong-hash"))).createdAtMillis["wrong-hash"])
            assertEquals(treeEpochMillis(physical.createdTime), index.sortContext(listOf(file("wrong-size"))).createdAtMillis["wrong-size"])
            index.applyChange(DriveChange.Moved(listOf(physical.id), setOf("a"), "b"))
            index.putFiles(listOf(physical.copy(parentId = "b", params = mapOf("piko_original_created_at" to "1"))))
            index.close(); index = open(factory)
            val restored = assertNotNull(index.file(physical.id))
            assertEquals("b", restored.parentId)
            assertEquals(physical.createdTime, restored.createdTime)
            assertEquals(physical.modifiedTime, restored.modifiedTime)
            assertEquals(original, index.sortContext(listOf(restored)).createdAtMillis[restored.id])
            assertEquals(treeEpochMillis(restored.modifiedTime), index.sortContext(listOf(restored)).modifiedAtMillis[restored.id])
        } finally { index.close() }
    }

    @Test fun metadataAndMaterializedStatsSurviveColdOpenWithoutRescanningFiles() = fixture { factory ->
        var index = open(factory)
        index.putFolders(listOf(folder("a"), folder("b", "a")))
        val video = file("video", "b", "CONTENT")
        index.putFiles(listOf(video))
        index.observeContentMetadata(ContentKey("content", 100), ContentMetadata(durationMillis = 7000, qualityRank = 2))
        index.markComplete()
        assertEquals(7000L, index.sortContext(listOf(video)).subtreeStats.getValue("").physical.durationMillis)
        index.close()
        val queries = ArrayList<String>()
        val raw = factory.open("account")
        val recording = object : PikoTreeDatabase by raw {
            override fun execute(sql: String, parameters: List<Any?>) { queries += sql; raw.execute(sql, parameters) }
            override fun query(sql: String, parameters: List<Any?>): PikoTreeCursor { queries += sql; return raw.query(sql, parameters) }
        }
        index = DriveTreeIndex(database = recording, ownerAccount = "account")
        try {
            assertFalse(queries.any { it.startsWith("INSERT INTO tree_aggregate") || it.startsWith("DELETE FROM tree_subtree") })
            assertEquals(100L, index.folderUsage("")?.bytes)
            assertEquals(7000L, index.sortContext(listOf(video)).contentMetadata[ContentKey("CONTENT", 100)]?.durationMillis)
            assertEquals(7000L, index.sortContext().subtreeStats.getValue("a").physical.durationMillis)
        } finally { index.close() }
    }

    @Test fun streamMigrationAcceptsFieldOrderAndRollsBackTruncatedInput() = fixture { factory ->
        for (version in 1..2) {
            val index = open(factory, "v$version")
            try {
                val source = if (version == 1) "\"pa\":{\"url\":\"magnet:legacy\"}" else "\"sr\":0"
                val valid = "{\"entries\":[{\"id\":\"f\",\"n\":\"f.mp4\",\"s\":\"100\",\"h\":\"HASH\",\"oc\":123,$source}],\"schemaVersion\":$version,\"sources\":[\"magnet:legacy\"],\"complete\":true}"
                assertFalse(index.importLegacySnapshot(Buffer().apply { writeString(valid.dropLast(1)) }))
                assertNull(index.file("f"))
                assertTrue(index.importLegacySnapshot(Buffer().apply { writeString(valid) }))
                val restored = assertNotNull(index.file("f"))
                assertEquals("magnet:legacy", restored.params["url"])
                assertEquals(123L, index.sortContext(listOf(restored)).createdAtMillis["f"])
            } finally { index.close() }
        }
    }

    @Test fun ownerMismatchRebuildsInsteadOfExposingAnotherAccount() = fixture { factory ->
        open(factory).also { it.putFiles(listOf(file("private"))); it.close() }
        val index = DriveTreeIndex(database = factory.open("account"), ownerAccount = "different-owner")
        try { assertNull(index.file("private")); assertFalse(index.isComplete) } finally { index.close() }
    }

    /** Run with -Dpiko.test.lowheap=true and the test worker itself constrained to -Xmx256m. */
    @Test fun hundredThousandNodesUsePagedWritesLightweightSearchAndPersistentAggregates() = fixture { factory ->
        val lowHeap = java.lang.Boolean.getBoolean("piko.test.lowheap")
        val maxHeap = Runtime.getRuntime().maxMemory()
        if (lowHeap) assertTrue(maxHeap <= 256L * 1024 * 1024, "Set the test-worker maximum heap, not only the Gradle daemon")
        val sampling = AtomicBoolean(true); val peak = AtomicLong()
        val bean = ManagementFactory.getMemoryMXBean()
        val sampler = Thread({ while (sampling.get()) { peak.accumulateAndGet(bean.heapMemoryUsage.used, ::maxOf); Thread.sleep(5) } }, "sqlite-tree-heap").apply { isDaemon = true; start() }
        val database = factory.open("account")
        database.query("PRAGMA journal_mode").use { assertTrue(it.next()); assertEquals("wal", it.string(0)) }
        var index = DriveTreeIndex(database = database, ownerAccount = "account")
        val started = System.nanoTime()
        try {
            val generation = index.beginCandidate()
            index.appendCandidate(generation, (0 until 100).map { folder("d$it") })
            for (start in 0 until 100000 step 250) {
                index.appendCandidate(generation, (start until start + 250).map { n ->
                    file("f$n", "d${n % 100}", "HASH${n % 15000}").copy(name = "資料$n.mp4", params = mapOf("duration" to "10"))
                })
            }
            val commitStarted = System.nanoTime()
            index.commitCandidate(generation, 10_000_000, 1234)
            val commitMs = (System.nanoTime() - commitStarted) / 1_000_000
            val ingestMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(100000, index.folderUsage("")?.fileCount)
            assertEquals(10_000_000L, index.folderUsage("")?.bytes)
            val searchStart = System.nanoTime()
            assertEquals(listOf("f99999"), index.searchLocal("资料99999", "", FolderIndex()).map { it.file.id })
            val searchMs = (System.nanoTime() - searchStart) / 1_000_000
            index.applyChange(DriveChange.Renamed("f99999", "櫻花.mp4", "d99"))
            index.applyChange(DriveChange.Moved(listOf("f99999"), setOf("d99"), "d0"))
            index.applyChange(DriveChange.Deleted(listOf("f99998"), setOf("d98")))
            assertEquals(99999, index.folderUsage("")?.fileCount)
            assertEquals(listOf("f99999"), index.searchLocal("樱花", "", FolderIndex()).map { it.file.id })
            index.close()
            val reopenStart = System.nanoTime()
            index = open(factory)
            val reopenMs = (System.nanoTime() - reopenStart) / 1_000_000
            assertEquals(99999, index.folderUsage("")?.fileCount)
            assertEquals("d0", index.file("f99999")?.parentId)
            assertEquals(1234L, index.lastFullSyncAtMillis)
            val space = spaceFolderSnapshot(index, "", includeVaulted = true, isBuilding = false)
            assertEquals(100, space.items.size)
            assertEquals(99999L, space.fileCount)
            assertEquals(24, space.items.count { it.previewChildren.isNotEmpty() })
            assertTrue(space.items.filter { it.previewChildren.isNotEmpty() }.all { it.previewChildren.size == 24 })
            index.close()
            val provider = object : PikoClientProvider {
                override val currentClient = kotlinx.coroutines.flow.MutableStateFlow<io.github.nihildigit.pikpak.PikPakClient?>(null)
            }
            val repository = PikoDriveRepository(provider, treeDatabaseFactory = factory)
            try {
                repository.enterAccount("account")
                val observed = kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(5_000) { repository.observeSpaceFolder("", true).first() }
                }
                assertEquals(99999L, observed.fileCount)
                assertEquals(100, observed.items.size)
            } finally { repository.enterAccount(null) }
            index = open(factory)
            assertEquals(100, index.flow.value.entries.size, "Only folder nodes may remain in the published State")
            assertEquals(99999, index.folderUsage("")?.fileCount)
            System.gc()
            val retainedHeapBytes = bean.heapMemoryUsage.used
            assertTrue(retainedHeapBytes < 96L * 1024 * 1024, "No full file-object tree may remain resident: $retainedHeapBytes")
            println("SqliteDriveTreeIndexTest wal=true nodes=100000 ingestMs=$ingestMs commitMs=$commitMs searchMs=$searchMs reopenMs=$reopenMs maxHeap=$maxHeap sampledPeakBytes=${peak.get()} retainedHeapBytes=$retainedHeapBytes")
            if (lowHeap) assertTrue(peak.get() < 240L * 1024 * 1024, "Need 16 MiB worker heap headroom")
        } finally { index.close(); sampling.set(false); sampler.join() }
    }

    @Test
    fun unchangedSqliteFolderListingAndSelfSyncUploadKeepRevisionAndCanaryClean() = dev.piko.shared.smoke.smoke {
        val dir = Files.createTempDirectory("piko-sqlite-perf").toFile()
        try {
            val server = dev.piko.shared.smoke.FakePikPakServer()
            val pikoDir = server.addFolder(".piko")
            val oldSync = server.addFile("settings-100.json", pikoDir.id, content = ByteArray(240), hash = "S100")
            val mediaDir = server.addFolder("Media")
            val ep1 = server.addFile("E01.mp4", mediaDir.id, content = ByteArray(1000), hash = "E1")
            val ep2 = server.addFile("E02.mp4", mediaDir.id, content = ByteArray(1500), hash = "E2")
            server.quotaUsage = 240L + 1000L + 1500L

            val factory = DesktopPikoTreeDatabaseFactory(dir)
            val repo = PikoDriveRepository(server.provider(), treeDatabaseFactory = factory)
            try {
                repo.getQuota().getOrThrow()
                repo.warmDriveTree().getOrThrow()
                assertTrue(repo.driveTreeIndex.isComplete)

                val listing = repo.listBrowsable(mediaDir.id, PikoFileSortOrder.TIME_DESC).getOrThrow()
                assertEquals(setOf(ep1.id, ep2.id), listing.map { it.id }.toSet())
                val revAfterFirst = repo.driveTreeIndex.flow.value.revision
                repo.driveTreeIndex.putFolderListing(mediaDir.id, listing, complete = true)
                assertEquals(revAfterFirst, repo.driveTreeIndex.flow.value.revision, "内容未变的完整列举不应触发 SQLite 重写与 revision 广播")

                val newPayload = ByteArray(310) { 0x41 }
                repo.uploadBytes(pikoDir.id, "settings-200.json", newPayload).getOrThrow()
                repo.delete(listOf(oldSync.id)).getOrThrow()
                server.quotaUsage = 310L + 1000L + 1500L

                val probeResult = repo.probeRemoteChanges(force = true).getOrThrow()
                assertTrue(
                    probeResult != PikoDriveRepository.CanaryProbeResult.FULL_REBUILD,
                    "本机 .piko 同步轮换不应击穿哨兵探针导致全盘重扫，实际结果：$probeResult",
                )
            } finally {
                repo.enterAccount(null)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

}
