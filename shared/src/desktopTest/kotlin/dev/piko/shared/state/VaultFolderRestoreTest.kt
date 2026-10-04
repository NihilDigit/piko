package dev.piko.shared.state

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.VaultStore
import dev.piko.shared.data.VaultFolderIo
import kotlin.time.Duration
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.getFile
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class VaultFolderRestoreTest {
    // 清单读写留在假网盘中，文件恢复仍走 SDK 与假服务端。
    private fun repository(server: FakePikPakServer, prefs: MemoryPreferences) =
        object : PikoDriveRepository(server.provider(), prefs) {
            override val vault = VaultStore(object : VaultFolderIo {
                override suspend fun list(folderId: String) = listAllFiles(folderId).getOrThrow()
                override suspend fun read(fileId: String) = server.node(fileId)!!.content
                override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String {
                    delay(100)
                    return server.addFile(name, folderId, content = bytes).id
                }
                override suspend fun delete(ids: List<String>) { server.client().batchDelete(ids) }
            }, confirmDelay = Duration.ZERO)
        }

    @Test
    fun `instant restore retains portable sources and metadata through another archive after restart`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Show")
        val source = "magnet:?xt=urn:btih:" + "a".repeat(40) + "&dn=Show"
        val entry = VaultEntry.create("episode.mkv", 100, "GCID", source, 0, cid = "OLD-CID")
        val prefs = MemoryPreferences()
        val repo = repository(server, prefs)
        repo.vault.update(folder.id, VaultEdits.add(listOf(entry))).getOrThrow()
        FolderVaultSession(repo, scope).restoreFolder(PikoPathBreadcrumb(folder.id, folder.name))
        awaitUntil("取回且保留元数据") { repo.changes.latest.value != null }
        val metadata = repo.vault.read(folder.id, repo.listAllFiles(folder.id).getOrThrow()).getOrThrow().single()
        assertEquals(source, metadata.source)
        assertTrue(!metadata.isArchived)
        val restoredId = assertNotNull(metadata.restoredFileId)
        assertNull(server.client().getFile(restoredId).sourceUrl, "秒传文件本身没有来源")
        val restarted = repository(server, prefs)
        val real = restarted.listBrowsable(folder.id, PikoFileSortOrder.NAME_ASC).getOrThrow().single { it.id == restoredId }
        assertEquals(source, real.sourceUrl)
        assertEquals("OLD-CID", real.params["piko_vault_cid"])
        assertEquals(1, server.children(folder.id).count { it.name.endsWith(".magnet") })
        assertEquals(source + "\n", server.children(folder.id).single { it.name.endsWith(".magnet") }.content.decodeToString())
        val session = FolderVaultSession(restarted, scope)
        assertEquals(0, session.survey(PikoPathBreadcrumb(folder.id, folder.name)).getOrThrow().unsourcedFiles)
        session.archive(PikoPathBreadcrumb(folder.id, folder.name), includeUnsourced = false, moveToTrash = false)
        awaitUntil("再次归档成功") { restarted.changes.latest.value != null }
        val archived = restarted.vault.read(folder.id, restarted.listAllFiles(folder.id).getOrThrow()).getOrThrow().single()
        assertTrue(archived.isArchived)
        assertEquals(source, archived.source)
        assertEquals("OLD-CID", archived.cid)
        assertEquals(1, server.children(folder.id).count { it.name.endsWith(".magnet") })
        restarted.changes.undo(assertNotNull(restarted.changes.latest.value))
        // latest 在撤销开始时就已清空，不能拿它当撤销完成的信号，直接等清单
        suspend fun current() = restarted.vault.read(folder.id, restarted.listAllFiles(folder.id).getOrThrow()).getOrThrow().single()
        val undone = withTimeout(10_000) {
            var entry = current()
            while (entry.isArchived) { delay(20); entry = current() }
            entry
        }
        assertEquals(source, undone.source)
        assertEquals("OLD-CID", undone.cid)
    }

    @Test
    fun `restore the original from trash even when there is no free space`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Show")
        val original = server.addFile("episode.mkv", folder.id, content = ByteArray(100), hash = "GCID", trashed = true)
        val entry = VaultEntry.create("episode.mkv", 100, "GCID", null, 0)
        val body = buildJsonObject { put("entries", Json.encodeToJsonElement(listOf(entry))) }
        server.addFile(".piko-vault-v1-${"0".repeat(16)}.json", folder.id, content = body.toString().encodeToByteArray())
        server.quotaUsage = server.quotaLimit
        val prefs = MemoryPreferences()
        val repo = repository(server, prefs)
        FolderVaultSession(repo, scope).restoreFolder(PikoPathBreadcrumb(folder.id, folder.name))
        awaitUntil("回收站原文件恢复，归档记录清除") {
            repo.changes.latest.value != null && server.tree(folder.id).filterNot { it.substringAfterLast('/').startsWith(".piko-vault-") } == listOf("episode.mkv")
        }
        assertEquals(original.id, server.children(folder.id).single { it.name == "episode.mkv" }.id)
        assertEquals(0, server.instantCreates.get())
        assertTrue(repo.vault.read(folder.id, repo.listAllFiles(folder.id).getOrThrow()).getOrThrow().none { it.isArchived })
    }

    @Test
    fun `an entry renamed after archiving still takes back its original from trash`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Show")
        val original = server.addFile("episode.mkv", folder.id, content = ByteArray(100), hash = "GCID", trashed = true)
        val entry = VaultEntry.create("Episode 01.mkv", 100, "GCID", null, 0)
        val body = buildJsonObject { put("entries", Json.encodeToJsonElement(listOf(entry))) }
        server.addFile(".piko-vault-v1-${"0".repeat(16)}.json", folder.id, content = body.toString().encodeToByteArray())
        val prefs = MemoryPreferences()
        val repo = repository(server, prefs)
        val row = repo.listBrowsable(folder.id, PikoFileSortOrder.NAME_ASC).getOrThrow().single { it.name == "Episode 01.mkv" }
        FolderVaultSession(repo, scope).restore(listOf(row))
        awaitUntil("原文件恢复并换成条目的名字") {
            repo.changes.latest.value != null && server.children(folder.id).any { it.id == original.id && !it.trashed && it.name == "Episode 01.mkv" }
        }
        assertEquals(0, server.instantCreates.get())
    }

    @Test
    fun `restore a partially archived folder recursively without changing its real files`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Show")
        val child = server.addFolder("Extras", folder.id)
        val small = server.addFile("subtitle.ass", folder.id, content = byteArrayOf(1, 2))
        fun manifest(parent: String, name: String) {
            val entry = VaultEntry.create(name, 100, "G-$name", null, 0)
            val body = buildJsonObject { put("entries", Json.encodeToJsonElement(listOf(entry))) }
            server.addFile(".piko-vault-v1-${"0".repeat(16)}.json", parent, content = body.toString().encodeToByteArray())
        }
        manifest(folder.id, "episode.mkv")
        manifest(child.id, "making.mkv")
        val prefs = MemoryPreferences()
        val repo = repository(server, prefs)
        val state = FolderVaultSession(repo, scope)
        state.restoreFolder(PikoPathBreadcrumb(folder.id, "Show"))
        assertEquals("正在扫描归档条目", assertNotNull(state.restoreProgress).stage)
        state.restoreFolder(PikoPathBreadcrumb(folder.id, "Show")) // 进行中重复点击不启动第二次恢复
        awaitUntil("显示恢复总数与已扫描目录") { state.restoreProgress?.total == 2 }
        assertEquals(2, state.restoreProgress?.scannedFolders)
        awaitUntil("两层归档均恢复并移除清单") {
            repo.changes.latest.value != null &&
                server.tree(folder.id).filterNot { it.substringAfterLast('/').startsWith(".piko-vault-") }.toSet() == setOf("subtitle.ass", "episode.mkv", "Extras/making.mkv")
        }
        awaitUntil("恢复进度收起") { state.restoreProgress == null }
        assertNull(state.restoreProgress)
        assertEquals(2, server.instantCreates.get())
        for (id in listOf(folder.id, child.id)) {
            assertTrue(repo.vault.read(id, repo.listAllFiles(id).getOrThrow()).getOrThrow().none { it.isArchived })
        }
        assertEquals(small.id, server.children(folder.id).single { it.name == "subtitle.ass" }.id)
    }
}
