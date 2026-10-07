package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.VaultFolderIo
import dev.piko.shared.data.VaultStore
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.VaultScope
import io.github.nihildigit.pikpak.batchDelete
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * 单独选的文件走文件夹归档的同一条路：清单写进各自所在的文件夹，确认写成后才把原文件移入回收站，撤销按同一条改动恢复。
 * 清单与来源文件经假网盘读写，回收站与撤销走 SDK 与假服务端。
 */
class VaultFilesSmokeTest {
    private fun repository(server: FakePikPakServer) =
        object : PikoDriveRepository(server.provider(), MemoryPreferences()) {
            override val vault = VaultStore(object : VaultFolderIo {
                override suspend fun list(folderId: String) = listAllFiles(folderId).getOrThrow()
                override suspend fun read(fileId: String) = server.node(fileId)!!.content
                override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String =
                    server.addFile(name, folderId, content = bytes).id
                override suspend fun delete(ids: List<String>) { server.client().batchDelete(ids) }
            }, confirmDelay = Duration.ZERO)
        }

    private suspend fun PikoDriveRepository.row(folderId: String, name: String) =
        listBrowsable(folderId, PikoFileSortOrder.NAME_ASC).getOrThrow().single { it.name == name }

    private suspend fun PikoDriveRepository.archived(folderId: String) =
        vault.read(folderId, listAllFiles(folderId).getOrThrow()).getOrThrow().filter { it.isArchived }

    @Test
    fun `a single file is archived into its own folder and undo brings it back`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Show")
        val episode = server.addFile("episode.mkv", folder.id, content = ByteArray(100), hash = "G1")
        val sibling = server.addFile("extra.mkv", folder.id, content = ByteArray(100), hash = "G2")
        val repo = repository(server)
        val session = FolderVaultSession(repo, scope)
        val target = VaultScope("episode.mkv", files = listOf(repo.row(folder.id, "episode.mkv")))

        val survey = session.survey(target).getOrThrow()
        assertEquals(1, survey.files)
        assertEquals(1, survey.unsourcedFiles)

        session.archive(target, includeUnsourced = true)
        awaitUntil("单个文件归档完成") { repo.changes.latest.value != null }
        assertTrue(server.node(episode.id)!!.trashed)
        assertFalse(server.node(sibling.id)!!.trashed, "同目录没选的文件不动")
        assertEquals(listOf("G1"), repo.archived(folder.id).map { it.gcid })
        // 清单就在这一层，标记靠这一层直接放着条目，不记进目录表
        assertTrue(repo.vaultTrees.flow.value.isEmpty())
        repo.listBrowsable(folder.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
        awaitUntil("所在文件夹挂上归档标记") { folder.id in repo.vaultedFolders.value }

        repo.changes.undo(assertNotNull(repo.changes.latest.value))
        withTimeout(10_000) { while (repo.archived(folder.id).isNotEmpty()) delay(20) }
        awaitUntil("原文件从回收站回来") { !server.node(episode.id)!!.trashed }
    }

    @Test
    fun `a mixed selection writes one manifest per folder in a single change`() = smoke { scope ->
        val server = FakePikPakServer()
        val left = server.addFolder("Left")
        val right = server.addFolder("Right")
        val picked = server.addFolder("Picked")
        val nested = server.addFolder("Season 1", picked.id)
        val a = server.addFile("a.mkv", left.id, content = ByteArray(100), hash = "GA")
        val b = server.addFile("b.mkv", right.id, content = ByteArray(100), hash = "GB")
        val untouched = server.addFile("c.mkv", right.id, content = ByteArray(100), hash = "GC")
        val deep = server.addFile("d.mkv", nested.id, content = ByteArray(100), hash = "GD")
        val repo = repository(server)
        val session = FolderVaultSession(repo, scope)
        val target = VaultScope(
            "所选 3 项",
            folders = listOf(PikoPathBreadcrumb(picked.id, picked.name)),
            files = listOf(repo.row(left.id, "a.mkv"), repo.row(right.id, "b.mkv")),
        )

        session.archive(target, includeUnsourced = true)
        awaitUntil("混选归档完成") { repo.changes.latest.value != null }
        assertEquals(listOf("GA"), repo.archived(left.id).map { it.gcid })
        assertEquals(listOf("GB"), repo.archived(right.id).map { it.gcid })
        assertEquals(listOf("GD"), repo.archived(nested.id).map { it.gcid })
        assertTrue(listOf(a, b, deep).all { server.node(it.id)!!.trashed })
        assertFalse(server.node(untouched.id)!!.trashed)
        // 选的文件夹照整棵树的规则记进目录表；单独文件所在的两层不进
        assertEquals(mapOf(picked.id to setOf(nested.id)), repo.vaultTrees.flow.value)
        val change = assertIs<DriveChangeJournal.Change.Vault>(repo.changes.latest.value)
        assertEquals(setOf(a.id, b.id, deep.id), change.untrashOnRevert.toSet())
    }
}
