package dev.piko.shared.smoke

import dev.piko.shared.data.DesktopPikoTreeDatabaseFactory
import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.VaultBatchSyncState
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VirtualVaultLocation
import dev.piko.shared.state.VaultEntryEditCommand
import dev.piko.shared.state.VaultEntryEditCompletion
import dev.piko.shared.state.VaultEntryMutations
import dev.piko.shared.sync.VaultOpsSync
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

class AccountVaultLedgerSmokeTest {
    private fun makeClient(http: HttpClient, account: String = "vault-ops@piko.dev") = PikPakClient(
        account = account,
        password = "pw",
        sessionStore = InMemorySessionStore(),
        httpClient = http,
        rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
        retryPolicy = RetryPolicy.None,
    )

    @Test
    fun `186 files across 49 nested folders commit locally with zero cloud writes and survive space_full and restart`() = smoke { scope ->
        val workDirA = Files.createTempDirectory("piko-vault-ledger-a").toFile()
        val workDirB = Files.createTempDirectory("piko-vault-ledger-b").toFile()
        try {
            val server = FakePikPakServer()
            val dramas = server.addFolder("Dramas")
            val rejectSpace = AtomicBoolean(true)
            val cloudPostCount = AtomicInteger(0)
            val http = server.httpClient()
            http.plugin(HttpSend).intercept { request ->
                if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files")) {
                    cloudPostCount.incrementAndGet()
                    if (rejectSpace.get()) {
                        throw PikPakException(8, "file_space_not_enough", httpStatus = 400, rawBody = """{"error":"file_space_not_enough","error_code":8}""")
                    }
                }
                execute(request)
            }

            val providerA = server.provider(makeClient(http))
            val repoA = object : PikoDriveRepository(
                providerA,
                MemoryPreferences(),
                durableDatabaseFactory = DesktopPikoTreeDatabaseFactory(workDirA),
            ) {
                override suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray): Result<String> = runCatching {
                    cloudPostCount.incrementAndGet()
                    if (rejectSpace.get()) {
                        throw PikPakException(8, "file_space_not_enough", httpStatus = 400, rawBody = """{"error":"file_space_not_enough","error_code":8}""")
                    }
                    server.addFile(name, parentId, content = bytes).id
                }
            }
            val ledgerA = assertNotNull(repoA.vaultLedger)

            // Build 49 distinct subdirectories and 186 files matching the real-world failure case
            val items = (0 until 186).map { idx ->
                val subIdx = idx % 49
                val path = "Show_${subIdx / 7}/Season_${(subIdx % 7) + 1}/EP${(idx + 1).toString().padStart(3, '0')}.mp4"
                InstantFileItem(
                    file = ResolvedFile(path = path, size = 10_000_000L + idx, gcid = "GCID_EP_${idx.toString().padStart(4, '0')}"),
                    isInstantReady = true,
                )
            }

            val mark = TimeSource.Monotonic.markNow()
            val outcome = ledgerA.importTree(
                account = "vault-ops@piko.dev",
                anchorFolderId = dramas.id,
                containerName = "Collection_2026",
                items = items,
                names = emptyMap(),
                keepStructure = true,
                source = "magnet:?xt=urn:btih:${"a".repeat(40)}",
            ).getOrThrow()
            val elapsedMs = mark.elapsedNow().inWholeMilliseconds

            assertEquals(186, outcome.confirmedByPath.size)
            assertEquals(0, cloudPostCount.get(), "Foreground reference save must execute 0 cloud POST requests")
            assertTrue(elapsedMs < 2_000L, "Local SQLite import took ${elapsedMs}ms")

            val containerId = assertNotNull(outcome.containerFolderId)
            assertTrue(VirtualVaultLocation.isVirtualFolder(containerId))

            // Background sync hits 400 file_space_not_enough — local tree must stay 100% intact and outbox marked SPACE_FULL
            val syncA = VaultOpsSync(providerA, repoA, scope, MutableStateFlow(true), pushDelayMs = 50_000L)
            assertFalse(syncA.syncNow(), "Quota-blocked sync must not report completion")
            assertEquals(VaultBatchSyncState.SPACE_FULL, ledgerA.projection.value.syncState)

            val topLevelInDramas = repoA.listBrowsable(dramas.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
            assertEquals(listOf("Collection_2026"), topLevelInDramas.map { it.name })
            val showsInContainer = repoA.listBrowsable(containerId, PikoFileSortOrder.NAME_ASC).getOrThrow()
            assertEquals(7, showsInContainer.size)

            // Search inside virtual tree works immediately
            val searchHits = repoA.searchRecursive("EP186", containerId).toList()
            assertEquals(1, searchHits.size)
            assertEquals("EP186.mp4", searchHits.single().file.name)

            // Locate virtual file resolves full breadcrumb chain without cloud calls
            val ep186VirtualId = outcome.confirmedByPath.values.last()
            val locatedBreadcrumbs = repoA.locateFolder(ep186VirtualId).getOrThrow()
            assertTrue(locatedBreadcrumbs.any { it.id == containerId && it.name == "Collection_2026" })

            // Space map includes virtual subtree bytes when includeVaulted = true
            val spaceSnap = repoA.observeSpaceFolder(containerId, includeVaulted = true).first()
            assertEquals(186L, spaceSnap.fileCount)
            assertEquals(items.sumOf { it.file.size }, spaceSnap.vaultedBytes)

            // Rename, remove & undo on virtual nodes via VaultEntryMutations
            val mutations = VaultEntryMutations(repoA, scope)
            val firstShowFolder = showsInContainer.first()
            val firstShowNodeId = assertNotNull(VirtualVaultLocation.nodeIdOf(firstShowFolder.id))
            val removeOutcomeFuture = kotlinx.coroutines.CompletableDeferred<dev.piko.shared.state.VaultEntryEditOutcome>()
            val collector = scope.launch {
                removeOutcomeFuture.complete(mutations.outcomes.first())
            }
            mutations.submit(VaultEntryEditCommand.Remove(mapOf(containerId to setOf(firstShowNodeId))))
            val removeRes = removeOutcomeFuture.await()
            collector.cancel()
            assertEquals(VaultEntryEditCompletion.SUCCEEDED, removeRes.result.completion)
            assertEquals(6, repoA.listBrowsable(containerId, PikoFileSortOrder.NAME_ASC).getOrThrow().size)

            // Undo restores the entire deleted virtual show folder and its episodes!
            val undoId = removeRes.result.undoChangeIds.single()
            assertTrue(repoA.changes.undo(undoId))
            awaitUntil("undo virtual folder removal") {
                repoA.changes.state.value.undo[undoId]?.status == dev.piko.shared.data.DriveChangeJournal.UndoStatus.UNDONE
            }
            assertEquals(7, repoA.listBrowsable(containerId, PikoFileSortOrder.NAME_ASC).getOrThrow().size)

            // Delete one single episode permanently (no undo) and then allow cloud space so Device A pushes to .piko/vault-ops/
            val ep001Id = assertNotNull(VaultEntry.entryIdOf(outcome.confirmedByPath.values.first()))
            val ep001Parent = assertNotNull(ledgerA.folderOfEntry(ep001Id))
            val delEpFuture = kotlinx.coroutines.CompletableDeferred<dev.piko.shared.state.VaultEntryEditOutcome>()
            val collector2 = scope.launch { delEpFuture.complete(mutations.outcomes.first()) }
            mutations.submit(VaultEntryEditCommand.Remove(mapOf(ep001Parent to setOf(ep001Id))))
            delEpFuture.await()
            collector2.cancel()

            rejectSpace.set(false)
            assertTrue(syncA.syncNow(), "Once space is available, outbox syncs cleanly to .piko/vault-ops/")
            assertEquals(0, ledgerA.projection.value.unsyncedCount)
            assertEquals(VaultBatchSyncState.SYNCED, ledgerA.projection.value.syncState)

            // Device B starts with empty durable DB and pulls .piko/vault-ops/
            val providerB = server.provider(makeClient(http))
            val repoB = PikoDriveRepository(
                providerB,
                MemoryPreferences(),
                durableDatabaseFactory = DesktopPikoTreeDatabaseFactory(workDirB),
            )
            val syncB = VaultOpsSync(providerB, repoB, scope, MutableStateFlow(true), pushDelayMs = 50_000L)
            assertTrue(syncB.syncNow())

            val dramasOnB = repoB.listBrowsable(dramas.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
            assertEquals(listOf("Collection_2026"), dramasOnB.map { it.name })
            val spaceOnB = repoB.observeSpaceFolder(containerId, includeVaulted = true).first()
            assertEquals(185L, spaceOnB.fileCount, "Device B must converge to 185 active files with EP001 tombstoned")
            assertFalse(repoB.vaultLedger!!.hasEntry(ep001Id), "Tombstoned EP001 must not resurrect on Device B")

            repoA.durableStore?.close()
            repoB.durableStore?.close()
        } finally {
            workDirA.deleteRecursively()
            workDirB.deleteRecursively()
        }
    }

    @Test
    fun `missing parent staging concurrent field edits and reciprocal folder moves converge across opposite delivery orders`() = smoke { _ ->
        val dirOriginA = Files.createTempDirectory("piko-ledger-orig-a").toFile()
        val dirOriginB = Files.createTempDirectory("piko-ledger-orig-b").toFile()
        val dirReplica1 = Files.createTempDirectory("piko-ledger-rep-1").toFile()
        val dirReplica2 = Files.createTempDirectory("piko-ledger-rep-2").toFile()
        try {
            val acct = "converge@piko.dev"
            val storeA = dev.piko.shared.work.PikoDurableStore(DesktopPikoTreeDatabaseFactory(dirOriginA)).also { it.enterAccount(acct) }
            val storeB = dev.piko.shared.work.PikoDurableStore(DesktopPikoTreeDatabaseFactory(dirOriginB)).also { it.enterAccount(acct) }
            val storeR1 = dev.piko.shared.work.PikoDurableStore(DesktopPikoTreeDatabaseFactory(dirReplica1)).also { it.enterAccount(acct) }
            val storeR2 = dev.piko.shared.work.PikoDurableStore(DesktopPikoTreeDatabaseFactory(dirReplica2)).also { it.enterAccount(acct) }
            val ledgerA = dev.piko.shared.data.AccountVaultLedger(storeA)
            val ledgerB = dev.piko.shared.data.AccountVaultLedger(storeB)
            val ledgerR1 = dev.piko.shared.data.AccountVaultLedger(storeR1)
            val ledgerR2 = dev.piko.shared.data.AccountVaultLedger(storeR2)

            // Batch 1 on A: create virtual folders F, G under root "", and file X inside F
            ledgerA.applyLocalOps(
                acct,
                listOf(
                    dev.piko.shared.data.VaultOp.UpsertFolder("folderF", "", "Folder_F", 1_000L),
                    dev.piko.shared.data.VaultOp.UpsertFolder("folderG", "", "Folder_G", 1_001L),
                    dev.piko.shared.data.VaultOp.UpsertFile("fileX", "vdir:folderF", "orig.mkv", 1234L, "GCID_X", createdAt = 1_002L),
                ),
                nowMs = 1_000L,
            ).getOrThrow()
            val b1 = ledgerA.listAllBatches(acct).single().batch
            ledgerB.applyRemoteBatches(acct, listOf("r1" to b1)).getOrThrow()

            // Concurrent edits after b1:
            // On A (lower clock 2_000): rename fileX -> "renamed.mkv" AND move folderF under folderG
            ledgerA.applyLocalOps(
                acct,
                listOf(
                    dev.piko.shared.data.VaultOp.RenameNode("fileX", "renamed.mkv", 2_000L),
                    dev.piko.shared.data.VaultOp.MoveNode("folderF", "vdir:folderG", 2_000L),
                ),
                nowMs = 2_000L,
            ).getOrThrow()
            val b2FromA = ledgerA.listAllBatches(acct).last().batch

            // On B (higher clock 3_000): move fileX -> vdir:folderG AND reciprocally move folderG under folderF
            ledgerB.applyLocalOps(
                acct,
                listOf(
                    dev.piko.shared.data.VaultOp.MoveNode("fileX", "vdir:folderG", 3_000L),
                    dev.piko.shared.data.VaultOp.MoveNode("folderG", "vdir:folderF", 3_000L),
                ),
                nowMs = 3_000L,
            ).getOrThrow()
            val b3FromB = ledgerB.listAllBatches(acct).last().batch

            // Replica 1 receives child batch b2FromA BEFORE parent b1 (missing-parent out-of-order!)
            ledgerR1.applyRemoteBatches(acct, listOf("f2" to b2FromA)).getOrThrow()
            assertTrue(ledgerR1.hasUnmaterializedBatches(acct), "Child batch without parent must be staged, not dropped")
            assertFalse(ledgerR1.hasEntry("fileX"))

            // Now b1 arrives on Replica 1 -> both b1 and staged b2FromA materialize, then b3FromB arrives
            ledgerR1.applyRemoteBatches(acct, listOf("f1" to b1)).getOrThrow()
            assertFalse(ledgerR1.hasUnmaterializedBatches(acct))
            assertEquals("renamed.mkv", ledgerR1.nodeOf("fileX")?.name)
            ledgerR1.applyRemoteBatches(acct, listOf("f3" to b3FromB)).getOrThrow()

            // Replica 2 receives batches in exact opposite order: [b3FromB], then [b2FromA], then [b1]
            ledgerR2.applyRemoteBatches(acct, listOf("f3" to b3FromB)).getOrThrow()
            ledgerR2.applyRemoteBatches(acct, listOf("f2" to b2FromA)).getOrThrow()
            ledgerR2.applyRemoteBatches(acct, listOf("f1" to b1)).getOrThrow()

            // Also cross-deliver between A and B!
            ledgerA.applyRemoteBatches(acct, listOf("f3" to b3FromB)).getOrThrow()
            ledgerB.applyRemoteBatches(acct, listOf("f2" to b2FromA)).getOrThrow()

            val allReplicas = listOf(ledgerA, ledgerB, ledgerR1, ledgerR2)
            for (rep in allReplicas) {
                val x = assertNotNull(rep.nodeOf("fileX"))
                assertEquals("renamed.mkv", x.name, "Concurrent rename on A must survive higher-clock move on B")
                assertEquals("vdir:folderG", x.parentFolderId, "Concurrent move on B must apply alongside rename on A")
                assertTrue(rep.hasVirtualFolder("vdir:folderF") && rep.hasVirtualFolder("vdir:folderG"), "Reciprocal moves must remain acyclic and reachable")
            }
            val expectedTree = ledgerR1.projection.value.activeById.mapValues { (_, n) -> Triple(n.name, n.parentFolderId, n.isFolder) }
            for (rep in allReplicas) {
                val actualTree = rep.projection.value.activeById.mapValues { (_, n) -> Triple(n.name, n.parentFolderId, n.isFolder) }
                assertEquals(expectedTree, actualTree, "All 4 replicas must converge to the exact same tree regardless of delivery order")
            }

            storeA.close()
            storeB.close()
            storeR1.close()
            storeR2.close()
        } finally {
            dirOriginA.deleteRecursively()
            dirOriginB.deleteRecursively()
            dirReplica1.deleteRecursively()
            dirReplica2.deleteRecursively()
        }
    }
}
