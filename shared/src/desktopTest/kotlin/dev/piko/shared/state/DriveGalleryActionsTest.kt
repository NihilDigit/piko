package dev.piko.shared.state

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultFolderIo
import dev.piko.shared.data.VaultStore
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.batchDelete
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class DriveGalleryActionsTest {
    @Test fun cuttingADescendantFromTheGalleryCanPasteItIntoTheGalleryRoot() = smoke { scope ->
        val server = FakePikPakServer()
        val child = server.addFolder("child")
        val video = server.addVideo("video.mkv", child.id)
        val repo = PikoDriveRepository(server.provider())
        val listed = repo.listBrowsable(child.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
        val state = DriveScreenState(repo, MemoryPreferences(), scope, supplementalFiles = { listed })
        assertTrue(state.files.isEmpty(), "补充来源不替换普通目录列表")
        state.putOnClipboard(listOf(video.id), cut = true)
        assertEquals(mapOf(video.id to child.id), state.clipboardFlow.value?.sources)
        state.paste()
        awaitUntil("子目录视频移动到图库根") { server.node(video.id)?.parentId == "" }
        assertNull(state.clipboardFlow.value)
    }

    @Test fun supplementalLookupUsesTheCurrentScopeWithoutRetainingAnOldSnapshot() = smoke { scope ->
        val server = FakePikPakServer()
        val first = server.addFolder("first")
        val next = server.addFolder("next")
        val video = server.addVideo("video.mkv", first.id)
        val repo = PikoDriveRepository(server.provider())
        var displayed = repo.listBrowsable(first.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
        val state = DriveScreenState(repo, MemoryPreferences(), scope, supplementalFiles = { displayed })
        state.putOnClipboard(listOf(video.id), cut = true)
        assertEquals(first.id, state.clipboardFlow.value?.sources?.get(video.id))
        displayed = listOf(displayed.single().copy(parentId = next.id))
        state.putOnClipboard(listOf(video.id), cut = true)
        assertEquals(next.id, state.clipboardFlow.value?.sources?.get(video.id))
        displayed = emptyList()
        state.putOnClipboard(listOf(video.id), cut = true)
        assertEquals("", state.clipboardFlow.value?.sources?.get(video.id), "图库关闭后不能沿用先前的补充来源")
    }

    @Test fun descendantVaultRenameAndDeleteEditTheChildManifest() = smoke { scope ->
        val server = FakePikPakServer()
        val child = server.addFolder("child")
        val repo = repositoryWithVault(server)
        val rootEntry = VaultEntry.create("root.mkv", 100, "ROOT", null, 0)
        val childEntry = VaultEntry.create("before.mkv", 100, "CHILD", null, 0)
        repo.vault.update("", VaultEdits.add(listOf(rootEntry))).getOrThrow()
        repo.vault.update(child.id, VaultEdits.add(listOf(childEntry))).getOrThrow()
        var displayed = repo.listBrowsable(child.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
        val edits = VaultEntryEditSession(repo, scope)
        val state = DriveScreenState(repo, MemoryPreferences(), scope, vaultEditSession = edits, supplementalFiles = { displayed })

        state.rename(displayed.single().id, "after.mkv")
        assertEquals(child.id, (edits.works.value.single().command as VaultEntryEditCommand.Rename).folderId)
        awaitUntil("子目录归档改名完成") { edits.works.value.single().result != null }
        assertEquals(VaultEntryEditCompletion.SUCCEEDED, edits.works.value.single().result?.completion)
        val renamed = repo.vault.committed(child.id).getOrThrow().single()
        assertEquals("after.mkv", renamed.name)
        displayed = listOf(renamed.toFileStat(child.id))

        state.moveToTrash(listOf(displayed.single().id))
        assertEquals(setOf(child.id), edits.works.value.last().command.entries.keys)
        awaitUntil("子目录归档删除完成") { edits.works.value.size == 2 && edits.works.value.last().result != null }
        assertEquals(VaultEntryEditCompletion.SUCCEEDED, edits.works.value.last().result?.completion)
        assertTrue(repo.vault.committed(child.id).getOrThrow().isEmpty())
        assertEquals(listOf(rootEntry), repo.vault.committed("").getOrThrow(), "根目录清单不能被子目录操作改写")
    }

    private fun repositoryWithVault(server: FakePikPakServer) = object : PikoDriveRepository(server.provider(), MemoryPreferences()) {
        override val vault = VaultStore(object : VaultFolderIo {
            override suspend fun list(folderId: String): List<FileStat> = listAllFiles(folderId).getOrThrow()
            override suspend fun read(fileId: String) = assertNotNull(server.node(fileId)).content
            override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String =
                server.addFile(name, folderId, content = bytes).id
            override suspend fun delete(ids: List<String>) { server.client().batchDelete(ids) }
        }, confirmDelay = Duration.ZERO)
    }
}
