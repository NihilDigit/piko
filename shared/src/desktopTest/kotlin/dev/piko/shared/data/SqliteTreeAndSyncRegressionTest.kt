package dev.piko.shared.data

import dev.piko.shared.runtime.PikoWorkloadGovernor
import dev.piko.shared.runtime.WorkPhase
import dev.piko.shared.runtime.WorkPurpose
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.smoke
import dev.piko.shared.sync.DriveSettingsStore
import io.github.nihildigit.pikpak.ApiRequestPriority
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.MediaVariant
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.VideoInfo
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.encodedPath
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteTreeAndSyncRegressionTest {
    private fun fixture(block: (DesktopPikoTreeDatabaseFactory) -> Unit) {
        val directory = Files.createTempDirectory("piko-sqlite-regressions").toFile()
        try {
            block(DesktopPikoTreeDatabaseFactory(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun open(factory: DesktopPikoTreeDatabaseFactory, account: String = "acct@piko.dev") =
        DriveTreeIndex(database = factory.open(account), ownerAccount = account)

    private fun folder(id: String, parent: String = "") =
        FileStat(id = id, parentId = parent, name = id, kind = FileKind.FOLDER)

    private fun file(id: String, parent: String = "", name: String = "$id.mp4", hash: String = id) =
        FileStat(
            id = id,
            parentId = parent,
            kind = FileKind.FILE,
            name = name,
            size = "1024",
            hash = hash,
            mimeType = "video/mp4",
            phase = TaskPhase.COMPLETE,
        )

    @Test
    fun listingOldParentDuringShadowRebuildDoesNotDeleteMovedSubtree() = fixture { factory ->
        val index = open(factory)
        try {
            // Seed initial tree: A -> sub -> (f1, f2), and sibling folder B.
            val seedGen = index.beginCandidate()
            index.appendCandidate(
                seedGen,
                listOf(folder("A"), folder("B"), folder("sub", "A"), file("f1", "sub"), file("f2", "sub")),
            )
            index.commitCandidate(seedGen, 2048)

            // Start shadow rebuild where server already moved `sub` from A to B.
            val rebuildGen = index.beginCandidate()
            index.appendCandidate(
                rebuildGen,
                listOf(folder("A"), folder("B"), folder("sub", "B"), file("f1", "sub"), file("f2", "sub")),
            )

            // User opens folder A in foreground during shadow rebuild; A no longer contains `sub`.
            index.putFolderListing("A", emptyList(), complete = true)

            // Commit shadow rebuild: `sub` and its children under B MUST survive.
            index.commitCandidate(rebuildGen, 2048)

            val sub = assertNotNull(index.file("sub"), "Moved folder must not be deleted by listing its old parent")
            assertEquals("B", sub.parentId)
            assertEquals("sub", assertNotNull(index.file("f1")).parentId)
            assertEquals("sub", assertNotNull(index.file("f2")).parentId)
            assertEquals(setOf("sub"), index.children("B").files.map { it.id }.toSet())
            assertTrue(index.children("A").files.isEmpty())
        } finally {
            index.close()
        }
    }

    @Test
    fun listingParentDuringShadowRebuildStillRemovesTrulyDeletedFolderAndOrphans() = fixture { factory ->
        val index = open(factory)
        try {
            val seedGen = index.beginCandidate()
            index.appendCandidate(
                seedGen,
                listOf(folder("A"), folder("sub", "A"), file("f1", "sub"), file("keep", "A")),
            )
            index.commitCandidate(seedGen, 2048)

            // Shadow rebuild scanned A before `sub` was deleted remotely.
            val rebuildGen = index.beginCandidate()
            index.appendCandidate(
                rebuildGen,
                listOf(folder("A"), folder("sub", "A"), file("f1", "sub"), file("keep", "A")),
            )

            // Authoritative listing of A shows `sub` is gone and `keep` remains.
            index.putFolderListing("A", listOf(file("keep", "A")), complete = true)
            index.commitCandidate(rebuildGen, 1024)

            assertNotNull(index.file("keep"))
            assertNull(index.file("sub"), "Deleted folder removed by authoritative listing should not survive")
            assertNull(index.file("f1"), "Orphaned children of deleted folder should be cleaned up")
        } finally {
            index.close()
        }
    }

    @Test
    fun unrelatedDeleteDoesNotWipeLiveNodesFromStaleTreeDeletedAndRestoredClearsTombstone() = fixture { factory ->
        val db = factory.open("acct@piko.dev")
        val index = DriveTreeIndex(database = db, ownerAccount = "acct@piko.dev")
        try {
            val seedGen = index.beginCandidate()
            index.appendCandidate(
                seedGen,
                listOf(folder("A"), file("victim", "A"), file("old-sync", "A")),
            )
            index.commitCandidate(seedGen, 2048)

            // Simulate a lingering tombstone row from an earlier seq while `victim` is live in tree_nodes.
            db.execute("INSERT OR REPLACE INTO tree_deleted VALUES ('victim', 1)")

            // Deleting an unrelated file (e.g. rotating .piko/settings-*.json) must only delete that batch's seq.
            index.applyChange(DriveChange.Deleted(listOf("old-sync"), setOf("A")))

            assertNull(index.file("old-sync"))
            assertNotNull(index.file("victim"), "Unrelated delete must never wipe live nodes from stale tree_deleted rows")

            // When no candidate is active, delete must also prune obsolete tree_deleted rows.
            assertEquals("0", db.scalarString("SELECT COUNT(*) FROM tree_deleted"))

            // During an active candidate, Deleted records tombstones and Restored removes restored IDs from tree_deleted.
            val activeGen = index.beginCandidate()
            index.applyChange(DriveChange.Deleted(listOf("victim"), setOf("A")))
            assertEquals("1", db.scalarString("SELECT COUNT(*) FROM tree_deleted WHERE id='victim'"))
            index.applyChange(DriveChange.Restored(listOf("victim")))
            assertEquals("0", db.scalarString("SELECT COUNT(*) FROM tree_deleted WHERE id='victim'"))
            index.abortCandidate(activeGen)
        } finally {
            index.close()
        }
    }

    @Test
    fun dirtyFolderDuringShadowRebuildDoesNotOverwriteFreshCandidateOrWipeVault() = fixture { factory ->
        val index = open(factory)
        try {
            val oldManifest = file("m1", "V", name = ".piko-vault-v1-0000000000000001.json", hash = "M1")
            val newManifest = file("m2", "V", name = ".piko-vault-v2-0000000000000002.json", hash = "M2")
            val oldEntry = VaultEntry("v1", "old-vaulted.mp4", 500, "GCID1", addedAt = 100)
            val newEntry = VaultEntry("v2", "new-vaulted.mp4", 900, "GCID2", addedAt = 200)

            val seedGen = index.beginCandidate()
            index.appendCandidate(seedGen, listOf(folder("V"), oldManifest, file("old-phys", "V")))
            index.appendCandidateVault(seedGen, "V", listOf(oldEntry))
            index.commitCandidate(seedGen, 1024)
            assertEquals(listOf(oldEntry), index.vaultEntries("V"))

            // Shadow rebuild discovers a newer manifest, fresh physical file, and updated vault entries.
            val rebuildGen = index.beginCandidate()
            index.appendCandidate(rebuildGen, listOf(folder("V"), newManifest, file("new-phys", "V")))
            index.appendCandidateVault(rebuildGen, "V", listOf(newEntry))

            // Folder V gets marked dirty=1 during the shadow rebuild (NOT authoritatively listed).
            index.applyChange(DriveChange.ContentsChanged(setOf("V")))

            index.commitCandidate(rebuildGen, 1024)

            assertNotNull(index.file("new-phys"), "Fresh candidate file must survive dirty=1 mark")
            assertNull(index.file("old-phys"), "Stale generation=0 file must not overwrite candidate for dirty=1 folder")
            assertEquals(listOf(newEntry), index.vaultEntries("V"), "Fresh candidate vault entries must survive dirty=1 mark")
            assertTrue(
                index.children("V").files.any { VaultEntry.entryIdOf(it.id) == "v2" },
                "Virtual vaulted row must remain projected after commit",
            )
        } finally {
            index.close()
        }
    }

    @Test
    fun backgroundManifestWarmupUsesBackgroundApiPriorityAndYieldsToUrgentPlayback() = smoke {
        val dir = Files.createTempDirectory("piko-manifest-prio").toFile()
        val server = FakePikPakServer()
        val folder = server.addFolder("Vaulted")
        val manifestJson = """{"entries":[{"id":"e1","name":"clip.mp4","size":2048,"gcid":"GCID1234","addedAt":1000}]}"""
        val manifestFile = server.addFile(
            name = ".piko-vault-v1-0000000000000001.json",
            parentId = folder.id,
            content = manifestJson.encodeToByteArray(),
            hash = "MANIFEST1",
        )
        val manifestGetPriorities = CopyOnWriteArrayList<Int?>()
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("/drive/v1/files/${manifestFile.id}")) {
                manifestGetPriorities += currentCoroutineContext()[ApiRequestPriority]?.priority
            }
            execute(request)
        }
        val client = PikPakClient("prio@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http)
        val repo = PikoDriveRepository(server.provider(client), treeDatabaseFactory = DesktopPikoTreeDatabaseFactory(dir))
        try {
            repo.warmDriveTree().getOrThrow()
            assertTrue(manifestGetPriorities.isNotEmpty(), "Expected manifest detail fetch during tree warmup")
            assertTrue(
                manifestGetPriorities.all { it != null && it <= ApiRequestPriority.BACKGROUND },
                "Manifest warmup must run with ApiRequestPriority.BACKGROUND, got: $manifestGetPriorities",
            )
            assertEquals(1, repo.driveTreeIndex.vaultEntries(folder.id)?.size)
        } finally {
            repo.enterAccount(null)
            http.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun metadataSinkObserveDoesNotBlockWhenSqliteWriteLockIsHeld() = smoke {
        val dir = Files.createTempDirectory("piko-observe-nonblock").toFile()
        val server = FakePikPakServer()
        val video = server.addFile("movie.mp4", content = ByteArray(1024), hash = "HASH123")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = server.client(account = "obs@piko.dev")
        val repo = PikoDriveRepository(server.provider(client), treeDatabaseFactory = DesktopPikoTreeDatabaseFactory(dir))
        try {
            val observer = assertNotNull(repo.metadataSink.capture(client))
            val detail = FileDetail(
                id = video.id,
                name = video.name,
                size = "1024",
                hash = "HASH123",
                medias = listOf(MediaVariant(isOrigin = true, video = VideoInfo(duration = 42))),
            )
            // Hold DriveTreeIndex lock on a background thread and verify observe() returns immediately.
            val blocker = Thread {
                repo.driveTreeIndex.withReadState {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }.apply { start() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val startedNs = System.nanoTime()
                observer.observe(video.id, detail)
                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
                assertTrue(elapsedMs < 500, "metadataSink.observe must not block on contended tree/SQLite lock (took ${elapsedMs}ms)")
            } finally {
                release.countDown()
                blocker.join(5_000)
            }
            // Once unlocked, background queue drains and persists the observed duration.
            var persisted: Long? = null
            repeat(50) {
                persisted = repo.contentMetadataOf("HASH123", 1024L)?.durationMillis
                if (persisted == 42_000L) return@repeat
                delay(20)
            }
            assertEquals(42_000L, persisted)
        } finally {
            repo.enterAccount(null)
            dir.deleteRecursively()
        }
    }

    @Test
    fun driveSettingsStoreSharesPikoFolderListingAcrossSyncersAndInvalidatesOnWrite() = smoke {
        val server = FakePikPakServer()
        val pikoDir = server.addFolder(".piko")
        server.addFile("settings-100.json", pikoDir.id, content = """{"version":1,"values":{}}""".encodeToByteArray())
        server.addFile("pinned-tabs-100.json", pikoDir.id, content = """{"version":1,"entries":[]}""".encodeToByteArray())
        val pikoListCalls = AtomicInteger(0)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("/drive/v1/files") && request.url.parameters["parent_id"] == pikoDir.id) {
                pikoListCalls.incrementAndGet()
            }
            execute(request)
        }
        val client = PikPakClient("sync@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http)
        val provider = server.provider(client)
        val repo = PikoDriveRepository(provider)
        try {
            val pinnedStore = DriveSettingsStore(provider, repo, filePrefix = "pinned-tabs-")
            val settingsStore = DriveSettingsStore(provider, repo, filePrefix = "settings-")
            val metaStore = DriveSettingsStore(provider, repo, filePrefix = "metatube-")

            assertNotNull(pinnedStore.read("sync@piko.dev"))
            assertNotNull(settingsStore.read("sync@piko.dev"))
            assertNull(metaStore.read("sync@piko.dev"))
            assertEquals(1, pikoListCalls.get(), "Consecutive syncers within TTL should reuse a single .piko listing")

            // Writing invalidates the cached listing so the next read observes the newly uploaded file.
            metaStore.write("sync@piko.dev", """{"version":1}""", 200L)
            val beforeReadAfterWrite = pikoListCalls.get()
            assertEquals("""{"version":1}""", metaStore.read("sync@piko.dev"))
            assertTrue(pikoListCalls.get() > beforeReadAfterWrite, "Write must invalidate cached .piko listing")
        } finally {
            repo.enterAccount(null)
            http.close()
        }
    }

    @Test
    fun workloadGovernorTracksUrgentPlaybackGate() = smoke {
        val lease = PikoWorkloadGovernor.Shared.register(WorkPurpose.Playback, WorkPhase.Urgent)
        try {
            assertTrue(PikoWorkloadGovernor.Shared.budget.value.hasUrgentPlayback)
            lease.updatePhase(WorkPhase.Steady)
            PikoWorkloadGovernor.Shared.awaitNoUrgentPlayback()
        } finally {
            lease.close()
        }
    }

    @Test
    fun nestedSavepointRollbackPrefixesSqlCommentAndPreservesOuterTransaction() {
        val executed = mutableListOf<String>()
        val db = object : ManagedPikoTreeDatabase(release = {}) {
            override fun executeRaw(sql: String, parameters: List<Any?>) {
                executed += sql
            }
            override fun queryRaw(sql: String, parameters: List<Any?>): PikoTreeCursor = EmptyTreeCursor
            override fun closeRaw() = Unit
        }
        try {
            db.transaction {
                runCatching {
                    db.transaction {
                        error("inner failure")
                    }
                }
            }
            assertEquals(
                listOf(
                    "BEGIN IMMEDIATE",
                    "SAVEPOINT piko_tree_1",
                    "/*sp*/ ROLLBACK TO SAVEPOINT piko_tree_1",
                    "RELEASE SAVEPOINT piko_tree_1",
                    "COMMIT",
                ),
                executed,
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun fullInvalidationAfterColdStartResumableCandidateDeletesOrphanGenerationRows() = fixture { factory ->
        var index = open(factory)
        try {
            val gen = index.beginCandidate()
            index.appendCandidate(
                gen,
                listOf(folder("A"), file("f1", "A")),
                checkpoint = DriveTreeIndex.CandidateResumeCheckpoint(
                    generation = gen,
                    pageToken = "page-2",
                    firstSyncTime = "2026-11-21T00:00:00Z",
                    foldersRead = 1,
                    filesRead = 1,
                ),
            )
        } finally {
            index.close()
        }

        // Reopen with cold-start resumable candidate rows (generation > 0), then invalidate full tree.
        val reopenedDb = factory.open("acct@piko.dev")
        index = DriveTreeIndex(database = reopenedDb, ownerAccount = "acct@piko.dev")
        try {
            assertNotNull(index.resumableCandidate())
            assertEquals("1", reopenedDb.scalarString("SELECT 1 FROM tree_nodes WHERE generation>0 LIMIT 1"))
            index.applyChange(DriveChange.ContentsChanged(folders = null))
            assertNull(index.resumableCandidate())
            assertNull(reopenedDb.scalarString("SELECT 1 FROM tree_nodes WHERE generation>0 LIMIT 1"))
            assertNull(reopenedDb.scalarString("SELECT 1 FROM tree_vault WHERE generation>0 LIMIT 1"))
            assertNull(reopenedDb.scalarString("SELECT 1 FROM tree_vault_state WHERE generation>0 LIMIT 1"))
        } finally {
            index.close()
        }
    }
}
