package dev.piko.shared.data

import dev.piko.shared.presentation.FileCommands
import dev.piko.shared.presentation.VaultEntryCommandResult
import dev.piko.shared.presentation.VaultEntryCommands
import dev.piko.shared.rename.BatchRenameMemory
import dev.piko.shared.rename.BatchRenameState
import dev.piko.shared.rename.CanonicalTreeScan
import dev.piko.shared.rename.RenameProblem
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import dev.piko.shared.state.FileWriteSession
import dev.piko.shared.state.GallerySession
import dev.piko.shared.state.VaultEntryEditCommand
import dev.piko.shared.state.VaultEntryMutations
import dev.piko.shared.state.VideoDiscovery
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.listFiles
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class VaultLifecycleAuditTest {

    private fun repoWithVault(
        server: FakePikPakServer,
        treeDatabaseFactory: PikoTreeDatabaseFactory? = null,
        clients: PikoClientProvider = server.provider(),
    ): PikoDriveRepository = object : PikoDriveRepository(
        clients,
        MemoryPreferences(),
        treeDatabaseFactory = treeDatabaseFactory,
    ) {
        override val vault: VaultStore = VaultStore(
            io = object : VaultFolderIo {
                override suspend fun list(folderId: String): List<FileStat> = server.client().listFiles(folderId)
                override suspend fun read(fileId: String): ByteArray = assertNotNull(server.node(fileId)).content
                override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String =
                    server.addFile(name, folderId, content = bytes).id
                override suspend fun delete(ids: List<String>) {
                    server.client().batchDelete(ids)
                }
            },
            confirmDelay = Duration.ZERO,
            onChanged = { folder -> applyChange(DriveChange.ContentsChanged(setOf(folder))) },
            onWritten = { folder, entries -> vaultEntriesKnown(folder, entries) },
        )
    }

    @Test
    fun `mixed and all-vaulted multi-select batch rename canonical naming sibling collision and single undo`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Mix")
        val real1 = server.addFile("abc00123.mp4", folder.id, hash = "R1")
        val real2 = server.addFile("def00456.mp4", folder.id, hash = "R2")
        val repo = repoWithVault(server)
        val prefs = MemoryPreferences()

        val vaulted1 = VaultEntry.create("ghi00789.mp4", 1024, "V1", null, 1L)
        val vaulted2 = VaultEntry.create("jkl00101.mp4", 2048, "V2", null, 2L)
        val unselectedVaulted = VaultEntry.create("TAKEN.mp4", 512, "V3", null, 3L)
        repo.vault.update(folder.id, VaultEdits.add(listOf(vaulted1, vaulted2, unselectedVaulted))).getOrThrow()
        repo.vaultStars.starEntries(folder.id, listOf(vaulted1), starred = true)

        val listed = repo.listBrowsable(folder.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
        val selected = listed.filter { it.name != "TAKEN.mp4" }
        assertEquals(4, selected.size)

        // 1. Verify unselected vaulted sibling collision detection
        val collisionState = BatchRenameState(repo, prefs, scope, selected, BatchRenameMemory(), initialTextMode = true)
        awaitUntil("同目录名称加载完成") { !collisionState.isCheckingSiblings }
        collisionState.options = collisionState.options.copy(search = "^ghi00789$", replacement = "TAKEN")
        assertEquals(
            RenameProblem.TAKEN,
            collisionState.plan.rows.single { it.source.name == "ghi00789.mp4" }.problem,
            "重命名为未选中的归档兄弟文件名应标出 TAKEN 冲突",
        )

        // 2. Verify canonical AV naming applies to BOTH real and vaulted files in one mixed batch
        val state = BatchRenameState(repo, prefs, scope, selected, BatchRenameMemory(), initialAvNaming = true)
        awaitUntil("规范命名同目录名称加载完成") { !state.isCheckingSiblings }
        assertTrue(state.avNamingAvailable)
        assertTrue(state.avNaming)
        val planned = state.plan.rows.associate { it.source.name to it.newName }
        assertEquals(
            mapOf(
                "abc00123.mp4" to "ABC-123.mp4",
                "def00456.mp4" to "DEF-456.mp4",
                "ghi00789.mp4" to "GHI-789.mp4",
                "jkl00101.mp4" to "JKL-101.mp4",
            ),
            planned,
        )
        assertTrue(state.canRename)
        state.rename()
        awaitUntil("混合批量重命名完成") { state.phase == BatchRenameState.Phase.DONE }
        assertTrue(state.failures.isEmpty())

        assertEquals("ABC-123.mp4", server.node(real1.id)?.name)
        assertEquals("DEF-456.mp4", server.node(real2.id)?.name)
        val afterVaultNames = repo.vault.committed(folder.id).getOrThrow().associate { it.id to it.name }
        assertEquals("GHI-789.mp4", afterVaultNames[vaulted1.id])
        assertEquals("JKL-101.mp4", afterVaultNames[vaulted2.id])
        assertEquals("GHI-789.mp4", repo.vaultStars.flow.value[vaulted1.id]?.name)

        // 3. Single Undo reverts BOTH real files AND vaulted entries (plus VaultStars)
        awaitUntil("改动记录入账") { repo.changes.latest.value != null }
        val undoId = assertNotNull(repo.changes.latest.value).id
        assertTrue(repo.changes.undoLast())
        awaitUntil("混合批量重命名撤销完成") {
            repo.changes.state.value.undo[undoId]?.status == DriveChangeJournal.UndoStatus.UNDONE &&
                server.node(real1.id)?.name == "abc00123.mp4" &&
                repo.driveTreeIndex.vaultEntries(folder.id)?.firstOrNull { it.id == vaulted1.id }?.name == "ghi00789.mp4"
        }
        assertEquals("def00456.mp4", server.node(real2.id)?.name)
        val revertedVaultNames = repo.vault.committed(folder.id).getOrThrow().associate { it.id to it.name }
        assertEquals("ghi00789.mp4", revertedVaultNames[vaulted1.id])
        assertEquals("jkl00101.mp4", revertedVaultNames[vaulted2.id])
        assertEquals("ghi00789.mp4", repo.vaultStars.flow.value[vaulted1.id]?.name)
    }

    @Test
    fun `canonical folder tree scan includes archived entries alongside real files`() = smoke { scope ->
        val server = FakePikPakServer()
        val root = server.addFolder("Collection")
        val sub = server.addFolder("[site] ssis00101 作品名", root.id)
        val subFile = server.addFile("ssis00101.chs.srt", sub.id)
        val clients = server.provider()
        val repo = repoWithVault(server, clients = clients)
        val prefs = MemoryPreferences()
        val archivedVideo = VaultEntry.create("ssis00101.mp4", 4096, "VG1", null, 1L)
        repo.vault.update(sub.id, VaultEdits.add(listOf(archivedVideo))).getOrThrow()

        val scan = CanonicalTreeScan(clients, repo, scope, PikoPathBreadcrumb(root.id, root.name))
        awaitUntil("子树扫描完成") { scan.phase == CanonicalTreeScan.Phase.DONE }
        assertTrue(scan.files.any { it.isVaulted && it.name == "ssis00101.mp4" }, "子树规范命名扫描应包含归档条目")

        val state = BatchRenameState(repo, prefs, scope, scan.files, BatchRenameMemory(), tree = scan.tree)
        awaitUntil("同目录名称就绪") { !state.isCheckingSiblings }
        assertEquals(
            "SSIS-101.mp4",
            state.plan.rows.single { it.source.name == "ssis00101.mp4" }.newName,
        )
        state.rename()
        awaitUntil("子树改名完成") { state.phase == BatchRenameState.Phase.DONE }
        assertEquals("SSIS-101.mp4", repo.vault.committed(sub.id).getOrThrow().single().name)
        assertEquals("SSIS-101.chs.srt", server.node(subFile.id)?.name)
    }

    @Test
    fun `gallery and video discovery include archived media from indexed unvisited folders`() = smoke { scope ->
        val dir = Files.createTempDirectory("piko-vault-gallery").toFile()
        var repoToClose: PikoDriveRepository? = null
        try {
            val server = FakePikPakServer()
            val vaultVideoFolder = server.addFolder("ArchivedVideos")
            val vaultImageFolder = server.addFolder("ArchivedImages")
            val repo = repoWithVault(server, DesktopPikoTreeDatabaseFactory(dir)).also { repoToClose = it }

            val archivedVideo = VaultEntry.create("ep01.mkv", 2048, "VIDGCID01", null, 10L)
            val archivedImage = VaultEntry.create("poster.jpg", 1024, "IMGGCID01", null, 20L)
            // Write manifests directly on server without entering either folder in DriveTreeIndex
            val writerRepo = repoWithVault(server)
            writerRepo.vault.update(vaultVideoFolder.id, VaultEdits.add(listOf(archivedVideo))).getOrThrow()
            writerRepo.vault.update(vaultImageFolder.id, VaultEdits.add(listOf(archivedImage))).getOrThrow()

            // Simulate full tree index having folder + manifest rows WITHOUT resolved tree_vault_state yet
            val client = server.client()
            val rootReal = client.listFiles("")
            val vidReal = client.listFiles(vaultVideoFolder.id)
            val imgReal = client.listFiles(vaultImageFolder.id)
            repo.driveTreeIndex.putFolderListing("", rootReal, complete = true)
            repo.driveTreeIndex.putFolderListing(vaultVideoFolder.id, vidReal, complete = true)
            repo.driveTreeIndex.putFolderListing(vaultImageFolder.id, imgReal, complete = true)

            // Unresolved manifest MUST report vaulted.listingComplete = false so VideoDiscovery never prunes it!
            val videoStats = assertNotNull(repo.folderSubtreeStats(vaultVideoFolder.id))
            assertFalse(videoStats.vaulted.listingComplete, "未解析归档清单的目录不能谎报 vaulted.listingComplete=true")

            // VideoDiscovery (even with includeImages=false, e.g. ClipFeed) must still enqueue vaultVideoFolder
            val feedDiscovery = VideoDiscovery(repo, assertNotNull(repo.currentAccount), "", includeImages = false)
            val found = mutableListOf<FileStat>()
            while (feedDiscovery.hasPending) {
                found += assertNotNull(feedDiscovery.next()).files
            }
            assertTrue(found.any { it.isVaulted && it.name == "ep01.mkv" }, "信息流发现未进入的归档视频目录")

            // GallerySession must discover BOTH archived video AND archived image without ever entering those folders
            val gallery = GallerySession(repo, scope)
            gallery.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB), showImages = true)
            awaitUntil("瀑布流扫描完成") { !gallery.loading && gallery.isComplete }
            val names = gallery.files.map { it.name }.toSet()
            assertTrue("ep01.mkv" in names, "瀑布流应包含未点进的归档视频: $names")
            assertTrue("poster.jpg" in names, "瀑布流应包含未点进的归档图片: $names")
            gallery.dispose()
        } finally {
            repoToClose?.driveTreeIndex?.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `individual vaulted entry move rename undo vaultStars and selected gallery survival`() = smoke { scope ->
        val server = FakePikPakServer()
        val folderA = server.addFolder("FolderA")
        val folderB = server.addFolder("FolderB")
        val repo = repoWithVault(server)

        val e1 = VaultEntry.create("movie01.mkv", 4096, "GCID_M1", "magnet:?xt=urn:btih:1", 100L)
        val e2 = VaultEntry.create("movie02.mkv", 4096, "GCID_M2", "magnet:?xt=urn:btih:2", 200L)
        val existingInTarget = VaultEntry.create("movie01.mkv", 4096, "GCID_M1", "magnet:?xt=urn:btih:orig-target", 50L)
        repo.vault.update(folderA.id, VaultEdits.add(listOf(e1, e2))).getOrThrow()
        repo.vault.update(folderB.id, VaultEdits.add(listOf(existingInTarget))).getOrThrow()
        repo.vaultStars.starEntries(folderA.id, listOf(e1), starred = true)

        val mutations = VaultEntryMutations(repo, scope)
        val commands = VaultEntryCommands(repo, mutations, FileCommands(repo, FileWriteSession(repo, scope)))

        // 1. Move e1 from FolderA -> FolderB (deduplicating same-(gcid, name) existingInTarget)
        assertIs<VaultEntryCommandResult.Accepted>(
            commands.submit(VaultEntryEditCommand.Move(mapOf(folderA.id to setOf(e1.id)), folderB.id, folderB.name)),
        )
        awaitUntil("归档条目移动完成") {
            repo.driveTreeIndex.vaultEntries(folderB.id)?.any { it.id == e1.id } == true &&
                repo.driveTreeIndex.vaultEntries(folderA.id)?.none { it.id == e1.id } == true &&
                repo.changes.latest.value != null
        }
        assertTrue(repo.vault.committed(folderB.id).getOrThrow().any { it.id == e1.id })
        assertTrue(repo.vault.committed(folderA.id).getOrThrow().none { it.id == e1.id })
        assertTrue(repo.vaultStars.isStarred(e1.id), "移动归档条目后星标不能丢失")
        assertEquals(folderB.id, repo.vaultStars.folderOf(e1.id), "移动归档条目后星标目录应更新为目标目录")

        // 2. Undo Move -> returns e1 to FolderA, restores overwritten existingInTarget in FolderB, preserves VaultStars
        val moveUndoId = assertNotNull(repo.changes.latest.value).id
        assertTrue(repo.changes.undoLast())
        awaitUntil("归档条目移动撤销完成") {
            repo.changes.state.value.undo[moveUndoId]?.status == DriveChangeJournal.UndoStatus.UNDONE &&
                repo.driveTreeIndex.vaultEntries(folderA.id)?.any { it.id == e1.id } == true &&
                repo.driveTreeIndex.vaultEntries(folderB.id)?.none { it.id == e1.id } == true
        }
        val restoredTarget = repo.vault.committed(folderB.id).getOrThrow()
        assertEquals(listOf(existingInTarget.id), restoredTarget.map { it.id }, "撤销移动应无损恢复目标目录被同(gcid,name)覆盖的原条目")
        assertEquals(existingInTarget.source, restoredTarget.single().source)
        assertTrue(repo.vaultStars.isStarred(e1.id), "撤销移动后星标应保留")
        assertEquals(folderA.id, repo.vaultStars.folderOf(e1.id), "撤销移动后星标目录应恢复到源目录")

        // 3. Selected Gallery retains vaulted video after rename (despite virtualId changing!)
        val initialStats = repo.listBrowsable(folderA.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
        val selectedGallery = GallerySession(repo, scope)
        selectedGallery.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB), selected = initialStats)
        awaitUntil("多选瀑布流就绪") { !selectedGallery.loading && selectedGallery.files.size == 2 }

        assertIs<VaultEntryCommandResult.Accepted>(
            commands.submit(
                VaultEntryEditCommand.RenameMany(
                    mapOf(folderA.id to mapOf(e1.id to "movie01-renamed.mkv", e2.id to "movie02-renamed.mkv")),
                ),
            ),
        )
        awaitUntil("批量归档重命名完成") {
            repo.driveTreeIndex.vaultEntries(folderA.id)?.any { it.name == "movie01-renamed.mkv" } == true &&
                repo.vaultStars.flow.value[e1.id]?.name == "movie01-renamed.mkv"
        }
        assertTrue(repo.vault.committed(folderA.id).getOrThrow().any { it.name == "movie01-renamed.mkv" })
        assertEquals("movie01-renamed.mkv", repo.vaultStars.flow.value[e1.id]?.name)
        selectedGallery.refresh()
        awaitUntil("重命名后多选瀑布流刷新完成") {
            !selectedGallery.loading && selectedGallery.files.any { it.name == "movie01-renamed.mkv" }
        }
        assertEquals(setOf("movie01-renamed.mkv", "movie02-renamed.mkv"), selectedGallery.files.map { it.name }.toSet())
        selectedGallery.dispose()
    }

    @Test
    fun `moving and renaming folders containing vault manifests updates sqlite stats vaultedFolders and covers`() = smoke { _ ->
        val dir = Files.createTempDirectory("piko-vault-folder-move").toFile()
        var repoToClose: PikoDriveRepository? = null
        try {
            val server = FakePikPakServer()
            val oldParent = server.addFolder("OldParent")
            val newParent = server.addFolder("NewParent")
            val sub = server.addFolder("Sub", oldParent.id)
            val repo = repoWithVault(server, DesktopPikoTreeDatabaseFactory(dir)).also { repoToClose = it }

            val archived = VaultEntry.create("film.mkv", 8192, "COVERGCID", null, 50L)
            repo.vault.update(sub.id, VaultEdits.add(listOf(archived))).getOrThrow()

            // Seed full tree + stale VaultTrees edge (OldParent -> Sub)
            val client = server.client()
            val rootChildren = client.listFiles("")
            val oldChildren = client.listFiles(oldParent.id)
            val newChildren = client.listFiles(newParent.id)
            val subChildren = client.listFiles(sub.id)
            repo.driveTreeIndex.putFolderListing("", rootChildren, complete = true)
            repo.driveTreeIndex.putFolderListing(oldParent.id, oldChildren, complete = true)
            repo.driveTreeIndex.putFolderListing(newParent.id, newChildren, complete = true)
            repo.driveTreeIndex.putFolderListing(sub.id, subChildren, complete = true)
            repo.vaultEntriesKnown(sub.id, listOf(archived))
            repo.vaultTrees.merge(mapOf(oldParent.id to setOf(sub.id)))

            awaitUntil("初始归档标记落在 OldParent 与 Sub") {
                oldParent.id in repo.vaultedFolders.value && sub.id in repo.vaultedFolders.value
            }
            assertFalse(newParent.id in repo.vaultedFolders.value)

            // 1. Rename folder Sub -> SubRenamed
            repo.applyChange(DriveChange.Renamed(sub.id, "SubRenamed", oldParent.id))
            assertEquals("SubRenamed", repo.driveTreeIndex.file(sub.id)?.name)
            assertEquals(1L, repo.folderSubtreeStats(sub.id)?.vaulted?.fileCount)
            assertEquals(1L, repo.folderSubtreeStats(oldParent.id)?.vaulted?.fileCount)

            // 2. Move Sub from OldParent -> NewParent
            repo.applyChange(DriveChange.Moved(listOf(sub.id), setOf(oldParent.id), newParent.id))
            assertEquals(0L, repo.folderSubtreeStats(oldParent.id)?.vaulted?.fileCount)
            assertEquals(1L, repo.folderSubtreeStats(newParent.id)?.vaulted?.fileCount)

            awaitUntil("移动后归档标记从 OldParent 迁移至 NewParent") {
                oldParent.id !in repo.vaultedFolders.value && newParent.id in repo.vaultedFolders.value
            }

            // 3. Vault cover must move from OldParent -> NewParent too
            val oldStat = FileStat(kind = FileKind.FOLDER, id = oldParent.id, name = oldParent.name)
            val newStat = FileStat(kind = FileKind.FOLDER, id = newParent.id, name = newParent.name)
            val covered = repo.withVaultCovers(listOf(oldStat, newStat)).associateBy { it.id }
            assertEquals("", covered.getValue(oldParent.id).thumbnailLink, "移出归档子目录后 OldParent 不应保留旧归档封面")
            assertTrue(covered.getValue(newParent.id).thumbnailLink.isNotEmpty(), "移入归档子目录后 NewParent 应继承归档封面")

            // 4. Directly moving a manifest file invalidates vault state in both source & target folders
            val manifestFile = subChildren.single(VaultStore::looksLikeManifest)
            repo.applyChange(DriveChange.Moved(listOf(manifestFile.id), setOf(sub.id), newParent.id))
            assertNull(repo.driveTreeIndex.vaultEntries(sub.id), "清单移出后源目录归档缓存应清空")
            assertNull(repo.driveTreeIndex.vaultEntries(newParent.id), "清单移入后目标目录归档缓存应等待重新解析")
            assertFalse(
                assertNotNull(repo.folderSubtreeStats(newParent.id)).vaulted.listingComplete,
                "清单移入目标目录但未解析前 vaulted.listingComplete 必须为 false",
            )
        } finally {
            repoToClose?.driveTreeIndex?.close()
            dir.deleteRecursively()
        }
    }
}
