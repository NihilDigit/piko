package dev.piko.shared.smoke

import dev.piko.shared.data.*
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TaskPhase
import kotlin.test.*
import kotlin.time.Duration

class ReferenceSaveSmokeTest {
    @Test
    fun unknownManifestConfirmationRetainsTheAttemptWithoutCreatingContent() = smoke { _ ->
        val server = FakePikPakServer()
        val io = MemoryManifestIo(failConfirmation = true)
        val vault = VaultStore(io, confirmDelay = Duration.ZERO)
        val files = listOf(InstantFileItem(ResolvedFile("原名.mkv", 100, "GCID1"), true))
        val result = InstantMagnetRepository(server.provider()).saveReferences(files, "", false, emptyMap(), emptyMap(), vault, null)
        val receipt = assertIs<InstantSaveIncomplete>(result.exceptionOrNull()).receipt
        assertEquals(setOf("原名.mkv"), receipt.unconfirmed)
        assertTrue(receipt.requiresInspection)
        assertEquals(0, server.instantCreates.get())
        assertEquals(1, io.uploads)
    }

    @Test
    fun referencesKeepPathsAndRenamesWithoutInstantCreatingTheContent() = smoke { _ ->
        val server = FakePikPakServer()
        val io = MemoryManifestIo()
        val vault = VaultStore(io, confirmDelay = Duration.ZERO)
        val repository = InstantMagnetRepository(server.provider())
        val files = listOf(InstantFileItem(ResolvedFile("正片/原名.mkv", 100, "GCID1"), true),
            InstantFileItem(ResolvedFile("字幕/原名.ass", 10, "GCID2"), true))
        val members = mutableMapOf<String, Set<String>>()
        val ids = repository.saveReferences(files + InstantFileItem(ResolvedFile("未知.mkv", 50, null), false), "", true,
            mapOf("正片/原名.mkv" to "最终名.mkv"), emptyMap(), vault, "magnet:source") { added ->
            added.forEach { (ancestor, leaves) -> members[ancestor] = members[ancestor].orEmpty() + leaves }
        }.getOrThrow()
        assertEquals(0, server.instantCreates.get())
        assertEquals(2, ids.size)
        assertTrue(ids.all(VaultEntry::isVaulted))
        val folders = server.children("").associateBy { it.name }
        assertEquals(folders.values.map { it.id }.toSet(), members[""])
        assertEquals(setOf(folders.getValue("正片").id), members[folders.getValue("正片").id])
        val video = vault.committed(folders.getValue("正片").id).getOrThrow().single()
        assertEquals("最终名.mkv", video.name)
        assertEquals("magnet:source", video.source)
        assertEquals("GCID1", video.gcid)
        assertEquals("原名.ass", vault.committed(folders.getValue("字幕").id).getOrThrow().single().name)
    }

    @Test
    fun nestedReferenceSavePreservesCreatedChildSubfoldersInLocalTree() = smoke { _ ->
        val server = FakePikPakServer()
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        repo.listAllFiles("").getOrThrow()
        val repository = InstantMagnetRepository(server.provider())
        val items = listOf(
            InstantFileItem(ResolvedFile("A/file1.mkv", 100, "GCID1"), true),
            InstantFileItem(ResolvedFile("A/B/file2.mkv", 200, "GCID2"), true),
        )
        var resolvedFolders = emptyMap<String, String>()
        repository.saveReferences(
            items = items,
            targetParentId = "",
            keepStructure = true,
            names = emptyMap(),
            existingFolders = emptyMap(),
            vault = repo.vault,
            source = "magnet:?xt=urn:btih:" + "1".repeat(40),
            onFolderCreated = { parentId, id, name ->
                repo.applyChange(DriveChange.Created(parentId, id, name, isFolder = true))
            },
            onFoldersResolved = { resolvedFolders = it },
        ).getOrThrow()

        val folderAId = resolvedFolders.getValue("A")
        val folderBId = resolvedFolders.getValue("A/B")
        val childrenOfA = repo.cachedBrowsable(folderAId, PikoFileSortOrder.NAME_ASC).orEmpty()
        assertTrue(childrenOfA.any { it.isFolder && it.id == folderBId && it.name == "B" }, "A 下的新建子目录 B 不得被快速通道抹除")
        assertTrue(childrenOfA.any { it.isVaulted && it.name == "file1.mkv" })
        val childrenOfB = repo.cachedBrowsable(folderBId, PikoFileSortOrder.NAME_ASC).orEmpty()
        assertTrue(childrenOfB.any { it.isVaulted && it.name == "file2.mkv" })
    }

    @Test
    fun normalNestedInstantSaveReportsResolvedFoldersSoNewSubfolderDoesNotStayEmptyComplete() = smoke { _ ->
        val server = FakePikPakServer()
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        repo.listAllFiles("").getOrThrow()
        val repository = InstantMagnetRepository(server.provider())
        val items = listOf(InstantFileItem(ResolvedFile("Nested/ep1.mkv", 100, "GCID1"), true))
        var resolvedFolders = emptyMap<String, String>()
        repository.instantSave(
            items = items,
            targetParentId = "",
            keepStructure = true,
            onFolderCreated = { parentId, id, name ->
                repo.applyChange(DriveChange.Created(parentId, id, name, isFolder = true))
            },
            onFoldersResolved = { resolvedFolders = it },
        ).getOrThrow()
        val nestedId = resolvedFolders.getValue("Nested")
        repo.applyChange(DriveChange.ContentsChanged(resolvedFolders.values.toSet() + ""))
        assertNull(repo.localRealChildrenIfClean(nestedId), "接收了秒传文件的新建目录必须标脏，不能留在空完整假象")
        val scanned = repo.scanRealChildren(nestedId)
        assertEquals(listOf("ep1.mkv"), scanned.map { it.name })
    }

    @Test
    fun freshTopContainerReferenceSaveSkipsBaselineListEnrichesMetadataAndMarksFullyVaulted() = smoke { scope ->
        val server = FakePikPakServer()
        val existingSourceFolder = server.addFolder("ArchivedBefore")
        val existingOriginal = server.addFile("known.mkv", existingSourceFolder.id, content = ByteArray(100), hash = "GCID-KNOWN")
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        // 先归档一份真实文件，使本机已知 GCID-KNOWN 的取样 CID
        val vaultSession = dev.piko.shared.state.FolderVaultSession(repo, scope)
        vaultSession.archive(PikoPathBreadcrumb(existingSourceFolder.id, existingSourceFolder.name), includeUnsourced = true)
        awaitUntil("前置归档完成") { repo.changes.latest.value != null }
        val knownCid = repo.vault.knownCidOf("GCID-KNOWN", 100L)
        assertNotNull(knownCid)

        val freshContainer = server.addFolder("FreshShow")
        repo.applyChange(DriveChange.Created("", freshContainer.id, freshContainer.name, isFolder = true))
        val listsBefore = MemoryManifestIo()
        val isolatedVault = VaultStore(listsBefore, confirmDelay = Duration.ZERO)
        val repository = InstantMagnetRepository(server.provider())
        val items = listOf(
            InstantFileItem(
                file = ResolvedFile("ep01.mkv", 100, "GCID-KNOWN"),
                isInstantReady = true,
                meta = VaultMediaMeta(duration = "1440", width = "1920", height = "1080", mimeType = "video/x-matroska"),
            ),
        )
        var freshVaulted = emptySet<String>()
        repository.saveReferences(
            items = items,
            targetParentId = freshContainer.id,
            keepStructure = true,
            names = emptyMap(),
            existingFolders = emptyMap(),
            vault = isolatedVault,
            source = "https://mypikpak.com/s/share123?pwd=abcd",
            targetIsFreshFolder = true,
            knownMetaLookup = repo::knownVaultMetaOf,
            onFreshVaultFolders = { freshVaulted = it },
        ).getOrThrow()

        assertEquals(0, listsBefore.lists, "顶层新建容器目录应走快速单次直写通道，零列目录请求")
        assertEquals(1, listsBefore.uploads)
        assertEquals(setOf(freshContainer.id), freshVaulted)
        val entry = isolatedVault.committed(freshContainer.id).getOrThrow().single()
        assertEquals(knownCid, entry.cid, "以引用保存应自动复用本机已知同 GCID 的取样 CID")
        assertEquals("1440", entry.duration)
        assertEquals("1920", entry.width)
        assertEquals("1080", entry.height)
        assertEquals("video/x-matroska", entry.mimeType)
    }

    private class MemoryManifestIo(private val failConfirmation: Boolean = false) : VaultFolderIo {
        private val files = linkedMapOf<String, Pair<FileStat, ByteArray>>()
        var uploads = 0
            private set
        var lists = 0
            private set
        // 不同目录的归档写入会并行，读取快照与修改必须使用同一把锁。
        override suspend fun list(folderId: String): List<FileStat> = synchronized(files) {
            lists++
            if (failConfirmation && uploads > 0) throw java.io.IOException("confirmation unavailable")
            files.values.map { it.first }.filter { it.parentId == folderId }
        }
        override suspend fun read(fileId: String) = synchronized(files) { files.getValue(fileId).second }
        override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String = synchronized(files) {
            uploads++
            val id = "manifest-${files.size}-$name"
            files[id] = FileStat(id = id, parentId = folderId, name = name, size = bytes.size.toString(), kind = FileKind.FILE, phase = TaskPhase.COMPLETE) to bytes
            id
        }
        override suspend fun delete(ids: List<String>) { synchronized(files) { ids.forEach(files::remove) } }
    }
}
