package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultFolderIo
import dev.piko.shared.data.VaultStore
import dev.piko.shared.presentation.ActionAvailability
import dev.piko.shared.presentation.FileCommands
import dev.piko.shared.presentation.VaultEntryCommandResult
import dev.piko.shared.presentation.VaultEntryCommands
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteSession
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.VaultWorkTargets
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.VaultEntryEditCommand
import dev.piko.shared.state.VaultEntryEditCompletion
import dev.piko.shared.state.VaultEntryEditSession
import io.github.nihildigit.pikpak.batchDelete
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class VaultEntryEditSmokeTest {
    private class Fixture {
        val server = FakePikPakServer()
        val preferences = MemoryPreferences()
        var beforeUpload: suspend (String) -> Unit = {}
        var beforeList: suspend (String) -> Unit = {}
        var failCleanup = false
        var uploads = 0
        val repository = object : PikoDriveRepository(server.provider(), preferences) {
            override val vault = VaultStore(object : VaultFolderIo {
                override suspend fun list(folderId: String): List<io.github.nihildigit.pikpak.FileStat> {
                    beforeList(folderId)
                    return listAllFiles(folderId).getOrThrow()
                }
                override suspend fun read(fileId: String) = server.node(fileId)!!.content
                override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String {
                    uploads++
                    beforeUpload(folderId)
                    return server.addFile(name, folderId, content = bytes).id
                }
                override suspend fun delete(ids: List<String>) {
                    if (failCleanup) throw IOException("stale manifest cleanup unavailable")
                    server.client().batchDelete(ids)
                }
            }, Duration.ZERO, onChanged = { applyChange(DriveChange.ContentsChanged(setOf(it))) })
        }
        fun entry(name: String) = VaultEntry.create(name, 100, "G-$name", null, 0)
        suspend fun seed(folder: String, vararg entries: VaultEntry) {
            repository.vault.update(folder, VaultEdits.add(entries.toList())).getOrThrow()
        }
        fun commands(session: VaultEntryEditSession, scope: CoroutineScope): VaultEntryCommands = VaultEntryCommands(
            repository, session, FileCommands(repository, FileWriteSession(repository, scope), vaultEdits = session),
        )
    }

    @Test
    fun `editing a manifest blocks moving its folder and restoring its tree but permits other files`() = smoke { scope ->
        val f = Fixture()
        val folder = f.server.addFolder("folder")
        val unrelated = f.server.addFolder("unrelated")
        val entry = f.entry("before")
        f.seed(folder.id, entry)
        f.repository.listAllFiles("").getOrThrow()
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.beforeUpload = { entered.send(Unit); release.receive() }
        val session = VaultEntryEditSession(f.repository, scope)
        val vault = FolderVaultSession(f.repository, scope)
        val files = FileCommands(f.repository, FileWriteSession(f.repository, scope), vault = vault, vaultEdits = session)
        vault.conflictReason = { (files.vaultAvailability(it) as? ActionAvailability.Blocked)?.reason }
        f.commands(session, scope).submit(VaultEntryEditCommand.Rename(folder.id, entry.id, "after"))
        withTimeout(5_000) { entered.receive() }
        assertIs<ActionAvailability.Blocked>(files.availability(FileWriteCommand.Delete(listOf(folder.id))))
        assertEquals(ActionAvailability.Available, files.availability(FileWriteCommand.Rename(folder.id, "new-folder-name")))
        assertEquals(ActionAvailability.Available, files.availability(FileWriteCommand.Delete(listOf(unrelated.id))))
        assertIs<ActionAvailability.Blocked>(files.vaultAvailability(VaultWorkTargets(roots = setOf(folder.id))))
        vault.restoreFolder(PikoPathBreadcrumb(folder.id, folder.name))
        assertFalse(vault.isRunning, "被 UX 动作规则拦下时不能先开始扫描或恢复")
        assertEquals(ActionAvailability.Available, files.vaultAvailability(VaultWorkTargets(roots = setOf(unrelated.id))))
        release.send(Unit)
        awaitUntil("编辑结束解除限定目录的冲突") { session.works.value.single().result != null }
        assertEquals(ActionAvailability.Available, files.vaultAvailability(VaultWorkTargets(roots = setOf(folder.id))))
    }

    @Test
    fun `restoring a tree blocks metadata edits only within that tree`() = smoke { scope ->
        val f = Fixture()
        val folder = f.server.addFolder("folder")
        val other = f.server.addFolder("other")
        val entry = f.entry("episode")
        f.seed(folder.id, entry)
        f.repository.listAllFiles("").getOrThrow()
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        var held = false
        f.beforeList = { id -> if (id == folder.id && !held) { held = true; entered.send(Unit); release.receive() } }
        val session = VaultEntryEditSession(f.repository, scope)
        val vault = FolderVaultSession(f.repository, scope)
        val files = FileCommands(f.repository, FileWriteSession(f.repository, scope), vault = vault, vaultEdits = session)
        val commands = VaultEntryCommands(f.repository, session, files)
        vault.restoreFolder(PikoPathBreadcrumb(folder.id, folder.name))
        withTimeout(5_000) { entered.receive() }
        assertIs<ActionAvailability.Blocked>(commands.availability(VaultEntryEditCommand.Rename(folder.id, entry.id, "after")))
        assertEquals(ActionAvailability.Available, commands.availability(VaultEntryEditCommand.Remove(mapOf(other.id to setOf("another-entry")))))
        assertIs<ActionAvailability.Blocked>(files.availability(FileWriteCommand.Delete(listOf(folder.id))))
        release.send(Unit)
        awaitUntil("恢复完成后解除冲突") { vault.records.isNotEmpty() }
        assertEquals(ActionAvailability.Available, commands.availability(VaultEntryEditCommand.Rename(folder.id, entry.id, "after")))
    }

    @Test
    fun `rename survives its page and its exact inverse preserves a later unrelated rename`() = smoke { scope ->
        val f = Fixture()
        val entry = f.entry("before.mkv")
        val unrelated = f.server.addFile("before.txt")
        val otherFolder = f.server.addFolder("other")
        val otherEntry = f.entry("other.mkv")
        f.seed("", entry)
        f.seed(otherFolder.id, otherEntry)
        val session = VaultEntryEditSession(f.repository, scope)
        val pageScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val page = DriveScreenState(f.repository, f.preferences, pageScope, vaultEditSession = session)
        page.load()
        awaitUntil("归档条目已列出") { page.files.any { it.id == entry.virtualId } }
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.beforeUpload = { folder -> if (folder.isEmpty()) { entered.send(Unit); release.receive() } }
        page.rename(entry.virtualId, "after.mkv")
        withTimeout(5_000) { entered.receive() }
        pageScope.cancel()
        val commands = f.commands(session, scope)
        assertIs<VaultEntryCommandResult.Rejected>(commands.submit(VaultEntryEditCommand.Rename("", entry.id, "again.mkv")))
        assertIs<VaultEntryCommandResult.Accepted>(commands.submit(VaultEntryEditCommand.Rename(otherFolder.id, otherEntry.id, "other-after.mkv")))
        val files = FileCommands(f.repository, FileWriteSession(f.repository, scope), vaultEdits = session)
        assertEquals(ActionAvailability.Available, files.availability(FileWriteCommand.Rename(unrelated.id, "after.txt")), "根目录清单不能锁住所有真实文件")
        release.send(Unit)
        awaitUntil("离页后两次独立编辑均完成") { session.works.value.size == 2 && session.works.value.all { it.result != null } }
        val work = session.works.value.first { it.command.entries.keys == setOf("") }
        assertEquals(VaultEntryEditCompletion.SUCCEEDED, work.result!!.completion)
        val inverseId = work.result!!.undoChangeIds.single()
        f.beforeUpload = {}
        f.repository.rename(unrelated.id, "after.txt").getOrThrow()
        val later = DriveChangeJournal.Change.Rename(listOf(DriveChangeJournal.Renamed(unrelated.id, "before.txt", "after.txt")), "later")
        f.repository.changes.record(later)
        assertTrue(files.undo(inverseId) is dev.piko.shared.presentation.FileCommandResult.Accepted)
        awaitUntil("具体编辑已撤销") { f.repository.changes.state.value.undo[inverseId]?.status == DriveChangeJournal.UndoStatus.UNDONE }
        assertEquals("before.mkv", f.repository.vault.committed("").getOrThrow().single().name)
        assertEquals("after.txt", unrelated.name)
        assertEquals("other-after.mkv", f.repository.vault.committed(otherFolder.id).getOrThrow().single().name)
        assertTrue(f.repository.changes.state.value.available.any { it.id == later.id })
    }

    @Test
    fun `partial removal retains only uncertain recovery data and rechecks without another write`() = smoke { scope ->
        val f = Fixture()
        val folders = List(3) { f.server.addFolder("folder-$it") }
        val entries = List(3) { f.entry("entry-$it") }
        folders.zip(entries).forEach { (folder, entry) -> f.seed(folder.id, entry) }
        f.beforeUpload = { if (it == folders[1].id) throw IOException("manifest response unavailable") }
        val session = VaultEntryEditSession(f.repository, scope)
        val command = VaultEntryEditCommand.Remove(folders.zip(entries).associate { (folder, entry) -> folder.id to setOf(entry.id) })
        val id = assertIs<VaultEntryCommandResult.Accepted>(f.commands(session, scope).submit(command)).workId
        awaitUntil("批次保留部分结果") { session.works.value.single().result != null }
        val work = session.works.value.single()
        val result = assertNotNull(work.result)
        assertEquals(VaultEntryEditCompletion.PARTIAL, result.completion)
        assertEquals(setOf(folders[0].id), result.confirmed.keys)
        assertEquals(setOf(folders[1].id), result.unconfirmed.keys)
        assertEquals(setOf(folders[2].id), result.notAttempted.keys)
        assertEquals(setOf(folders[1].id), work.before.keys)
        assertEquals(1, result.undoChangeIds.size)
        val uploads = f.uploads
        val oldResult = session.works.value.single().result
        assertTrue(session.recheck(id))
        awaitUntil("只读查询结束") { session.works.value.single().result?.let { it !== oldResult } == true }
        assertEquals(uploads, f.uploads)
        assertEquals(result.confirmed, session.works.value.single().result!!.confirmed)
        f.beforeUpload = {}
        f.repository.vault.update(folders[1].id, VaultEdits.remove(setOf(entries[1].id))).getOrThrow()
        val beforeRecheck = f.uploads
        assertTrue(session.recheck(id))
        awaitUntil("后来已可核对的结果加入具体撤销") { session.works.value.single().result?.unconfirmed?.isEmpty() == true }
        val checked = session.works.value.single()
        assertEquals(beforeRecheck, f.uploads)
        assertTrue(checked.before.isEmpty())
        assertEquals(2, checked.result!!.undoChangeIds.size)
        assertEquals(result.undoChangeIds, checked.result!!.undoChangeIds.take(1))
        assertEquals(entries[2], f.repository.vault.committed(folders[2].id).getOrThrow().single(), "查询不能补执行从未提交的部分")
    }

    @Test
    fun `a trusted commit stays successful when old manifest cleanup fails`() = smoke { scope ->
        val f = Fixture()
        val entry = f.entry("before")
        f.seed("", entry)
        f.failCleanup = true
        val session = VaultEntryEditSession(f.repository, scope)
        f.commands(session, scope).submit(VaultEntryEditCommand.Rename("", entry.id, "after"))
        awaitUntil("清理失败不改变已可信确认的业务结果") { session.works.value.single().result != null }
        val result = session.works.value.single().result!!
        assertEquals(VaultEntryEditCompletion.SUCCEEDED, result.completion)
        assertEquals("after", f.repository.vault.committed("").getOrThrow().single().name)
        assertEquals(1, result.undoChangeIds.size)
        assertEquals(2, f.repository.listAllFiles("").getOrThrow().count { VaultStore.looksLikeManifest(it) })
    }

    @Test
    fun `committed verification ignores the optimistic edit and keeps an unsuccessful upload uncertain`() = smoke { scope ->
        val f = Fixture()
        val entry = f.entry("before")
        f.seed("", entry)
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.beforeUpload = { entered.send(Unit); release.receive(); throw IOException("no acknowledgement") }
        val session = VaultEntryEditSession(f.repository, scope)
        val id = assertIs<VaultEntryCommandResult.Accepted>(f.commands(session, scope).submit(VaultEntryEditCommand.Rename("", entry.id, "after"))).workId
        withTimeout(5_000) { entered.receive() }
        assertEquals("after", f.repository.vault.read("", emptyList()).getOrThrow().single().name)
        assertEquals("before", f.repository.vault.committed("").getOrThrow().single().name)
        assertFalse(session.forget(id))
        release.send(Unit)
        awaitUntil("缺少确认不能宣称编辑成功") { session.works.value.single().result != null }
        val work = session.works.value.single()
        assertEquals(VaultEntryEditCompletion.UNCONFIRMED, work.result!!.completion)
        assertEquals(listOf(entry), work.before[""])
        assertTrue(work.result!!.undoChangeIds.isEmpty())
        assertTrue(session.canRecheck(id))
    }
}
