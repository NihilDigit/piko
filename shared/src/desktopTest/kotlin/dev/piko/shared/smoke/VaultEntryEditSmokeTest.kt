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
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.isVaulted
import dev.piko.shared.state.VaultEntryEditCommand
import dev.piko.shared.state.VaultEntryEditCompletion
import dev.piko.shared.state.VaultEntryMutations
import dev.piko.shared.state.VaultScope
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.thumbnailUrlOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
        fun commands(session: VaultEntryMutations, scope: CoroutineScope): VaultEntryCommands = VaultEntryCommands(
            repository, session, FileCommands(repository, FileWriteSession(repository, scope)),
        )
    }

    @Test
    fun `repeated rename commands queue immediately and preserve rollback and undo`() = smoke { scope ->
        for (rejectFirst in listOf(false, true)) {
            val f = Fixture()
            val entry = f.entry("original")
            f.seed("", entry)
            val entered = Channel<Int>(2)
            val release = Channel<Unit>(2)
            var attempt = 0
            f.beforeUpload = {
                val index = ++attempt
                entered.send(index)
                release.receive()
                if (index == 1 && rejectFirst) throw IOException("first rename rejected")
            }
            val session = VaultEntryMutations(f.repository, scope)
        val outcomes = observeMutationOutcomes(scope, session.outcomes)
            val commands = f.commands(session, scope)
            val first = assertIs<VaultEntryCommandResult.Accepted>(commands.submit(VaultEntryEditCommand.Rename("", entry.id, "first"))).workId
            try {
                withTimeout(5_000) { assertEquals(1, entered.receive()) }
                val second = assertIs<VaultEntryCommandResult.Accepted>(commands.submit(VaultEntryEditCommand.Rename("", entry.id, "second"))).workId
                awaitUntil("second command is projected while the first upload waits") {
                    f.repository.vault.cached("", emptyList())?.singleOrNull()?.name == "second"
                }
                release.send(Unit)
                withTimeout(5_000) { assertEquals(2, entered.receive()) }
                awaitUntil("first command settles independently") { outcomes.value.any { it.id == first } }
                assertEquals(if (rejectFirst) VaultEntryEditCompletion.FAILED else VaultEntryEditCompletion.SUCCEEDED,
                    outcomes.value.first { it.id == first }.result!!.completion)
                assertEquals("second", f.repository.vault.read("", emptyList()).getOrThrow().single().name)
                val files = FileCommands(f.repository, FileWriteSession(f.repository, scope))
                if (!rejectFirst) {
                    val firstUndo = outcomes.value.first { it.id == first }.result!!.undoChangeIds.single()
                    val change = f.repository.changes.state.value.available.first { it.id == firstUndo }
                    assertIs<ActionAvailability.Blocked>(files.undoAvailability(change))
                }
                release.send(Unit)
                awaitUntil("second command commits") { outcomes.value.any { it.id == second } }
                assertEquals("second", f.repository.vault.committed("").getOrThrow().single().name)
                f.beforeUpload = {}
                val undoId = outcomes.value.first { it.id == second }.result!!.undoChangeIds.single()
                assertIs<dev.piko.shared.presentation.FileCommandResult.Accepted>(files.undo(undoId))
                awaitUntil("second rename undo completes") { f.repository.changes.state.value.undo[undoId]?.status == DriveChangeJournal.UndoStatus.UNDONE }
                assertEquals(if (rejectFirst) "original" else "first", f.repository.vault.committed("").getOrThrow().single().name)
            } finally {
                release.trySend(Unit)
                release.trySend(Unit)
            }
        }
    }

    @Test
    fun `queued manifest projection survives its predecessor and rebases after rejection`() = smoke { scope ->
        for (rejectFirst in listOf(false, true)) {
            val f = Fixture()
            val first = f.entry("first")
            val second = f.entry("second")
            f.seed("", first, second)
            val entered = Channel<Int>(2)
            val release = Channel<Unit>(2)
            var attempt = 0
            f.beforeUpload = {
                val index = ++attempt
                entered.send(index)
                release.receive()
                if (index == 1 && rejectFirst) throw IOException("first manifest upload rejected")
            }
            val a = scope.async(start = CoroutineStart.UNDISPATCHED) {
                f.repository.vault.update("", VaultEdits.rename(first.id, "first-new"))
            }
            withTimeout(5_000) { assertEquals(1, entered.receive()) }
            val b = scope.async(start = CoroutineStart.UNDISPATCHED) {
                f.repository.vault.update("", VaultEdits.rename(second.id, "second-new"))
            }
            try {
                assertEquals(setOf("first-new", "second-new"), f.repository.vault.read("", emptyList()).getOrThrow().map { it.name }.toSet())
                release.send(Unit)
                assertEquals(!rejectFirst, a.await().isSuccess)
                withTimeout(5_000) { assertEquals(2, entered.receive()) }
                val expected = setOf(if (rejectFirst) "first" else "first-new", "second-new")
                assertEquals(expected, f.repository.vault.read("", emptyList()).getOrThrow().map { it.name }.toSet())
                release.send(Unit)
                assertTrue(b.await().isSuccess)
                assertEquals(expected, f.repository.vault.committed("").getOrThrow().map { it.name }.toSet())
            } finally {
                release.trySend(Unit)
                release.trySend(Unit)
            }
        }
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
        val session = VaultEntryMutations(f.repository, scope)
        val outcomes = observeMutationOutcomes(scope, session.outcomes)
        val vault = FolderVaultSession(f.repository, scope)
        val files = FileCommands(f.repository, FileWriteSession(f.repository, scope), vault = vault)
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
        awaitUntil("编辑结束解除限定目录的冲突") { outcomes.value.isNotEmpty() }
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
        val session = VaultEntryMutations(f.repository, scope)
        val outcomes = observeMutationOutcomes(scope, session.outcomes)
        val vault = FolderVaultSession(f.repository, scope)
        val files = FileCommands(f.repository, FileWriteSession(f.repository, scope), vault = vault)
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
        val session = VaultEntryMutations(f.repository, scope)
        val outcomes = observeMutationOutcomes(scope, session.outcomes)
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
        assertIs<ActionAvailability.Available>(commands.availability(VaultEntryEditCommand.Rename("", entry.id, "again.mkv")))
        assertIs<VaultEntryCommandResult.Accepted>(commands.submit(VaultEntryEditCommand.Rename(otherFolder.id, otherEntry.id, "other-after.mkv")))
        val files = FileCommands(f.repository, FileWriteSession(f.repository, scope))
        assertEquals(ActionAvailability.Available, files.availability(FileWriteCommand.Rename(unrelated.id, "after.txt")), "根目录清单不能锁住所有真实文件")
        release.send(Unit)
        awaitUntil("离页后两次独立编辑均完成") { outcomes.value.size == 2 && outcomes.value.all { it.result != null } }
        val work = outcomes.value.first { it.result.confirmed.keys == setOf("") }
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
    fun `partial removal retains undo and releases the manifest after failure`() = smoke { scope ->
        val f = Fixture()
        val folders = List(3) { f.server.addFolder("folder-$it") }
        val entries = List(3) { f.entry("entry-$it") }
        folders.zip(entries).forEach { (folder, entry) -> f.seed(folder.id, entry) }
        f.beforeUpload = { if (it == folders[1].id) throw IOException("manifest response unavailable") }
        val session = VaultEntryMutations(f.repository, scope)
        val outcomes = observeMutationOutcomes(scope, session.outcomes)
        val command = VaultEntryEditCommand.Remove(folders.zip(entries).associate { (folder, entry) -> folder.id to setOf(entry.id) })
        val id = assertIs<VaultEntryCommandResult.Accepted>(f.commands(session, scope).submit(command)).workId
        awaitUntil("批次保留部分结果") { outcomes.value.isNotEmpty() }
        val work = outcomes.value.single()
        val result = assertNotNull(work.result)
        assertEquals(VaultEntryEditCompletion.PARTIAL, result.completion)
        assertEquals(setOf(folders[0].id), result.confirmed.keys)
        assertEquals(setOf(folders[1].id, folders[2].id), result.failed.keys)
        assertEquals(1, result.undoChangeIds.size)
        assertEquals(entries[2], f.repository.vault.committed(folders[2].id).getOrThrow().single())
    }

    @Test
    fun `a trusted commit stays successful when old manifest cleanup fails`() = smoke { scope ->
        val f = Fixture()
        val entry = f.entry("before")
        f.seed("", entry)
        f.failCleanup = true
        val session = VaultEntryMutations(f.repository, scope)
        val outcomes = observeMutationOutcomes(scope, session.outcomes)
        f.commands(session, scope).submit(VaultEntryEditCommand.Rename("", entry.id, "after"))
        awaitUntil("清理失败不改变已可信确认的业务结果") { outcomes.value.isNotEmpty() }
        val result = outcomes.value.single().result!!
        assertEquals(VaultEntryEditCompletion.SUCCEEDED, result.completion)
        assertEquals("after", f.repository.vault.committed("").getOrThrow().single().name)
        assertEquals(1, result.undoChangeIds.size)
        assertEquals(2, f.repository.listAllFiles("").getOrThrow().count { VaultStore.looksLikeManifest(it) })
    }

    @Test
    fun `failed optimistic upload rolls back and releases the manifest`() = smoke { scope ->
        val f = Fixture()
        val entry = f.entry("before")
        f.seed("", entry)
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.beforeUpload = { entered.send(Unit); release.receive(); throw IOException("no acknowledgement") }
        val session = VaultEntryMutations(f.repository, scope)
        val outcomes = observeMutationOutcomes(scope, session.outcomes)
        val id = assertIs<VaultEntryCommandResult.Accepted>(f.commands(session, scope).submit(VaultEntryEditCommand.Rename("", entry.id, "after"))).workId
        withTimeout(5_000) { entered.receive() }
        assertEquals("after", f.repository.vault.read("", emptyList()).getOrThrow().single().name)
        assertEquals("before", f.repository.vault.committed("").getOrThrow().single().name)
        release.send(Unit)
        awaitUntil("缺少确认不能宣称编辑成功") { outcomes.value.isNotEmpty() }
        val work = outcomes.value.single()
        assertEquals(VaultEntryEditCompletion.FAILED, work.result!!.completion)
        assertTrue(work.result!!.undoChangeIds.isEmpty())
    }

    @Test
    fun `archiving a starred real file preserves its star in vault stars and restoring transfers it back`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Favorites")
        val starredReal = server.addFile("episode.mkv", parentId = folder.id, content = ByteArray(128) { 7 }, hash = "GCID_STAR_1")
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences(), MemoryCacheStore())
        repo.setStarred(listOf(starredReal.id), starred = true).getOrThrow()
        assertTrue(repo.starredFiles().getOrThrow().any { it.id == starredReal.id })

        val vaultSession = FolderVaultSession(repo, scope)
        vaultSession.archive(PikoPathBreadcrumb(folder.id, folder.name), includeUnsourced = true, moveToTrash = false)
        vaultSession.outcomes.first()

        val browsed = repo.listBrowsable(folder.id, PikoFileSortOrder.TIME_DESC).getOrThrow()
        val vaultedStat = browsed.single { it.isVaulted }
        assertTrue(vaultedStat.tags.any { it.name.equals("STAR", ignoreCase = true) })
        assertTrue(repo.starredFiles().getOrThrow().any { it.id == vaultedStat.id })
        assertEquals(folder.id, repo.locateFolder(vaultedStat.id).getOrThrow().last().id)

        repo.setStarred(listOf(vaultedStat.id), starred = false).getOrThrow()
        assertFalse(repo.starredFiles().getOrThrow().any { it.id == vaultedStat.id })
        repo.setStarred(listOf(vaultedStat.id), starred = true, mapOf(vaultedStat.id to vaultedStat)).getOrThrow()
        assertTrue(repo.starredFiles().getOrThrow().any { it.id == vaultedStat.id })

        vaultSession.restore(listOf(vaultedStat))
        vaultSession.outcomes.first()

        val starredAfterRestore = repo.starredFiles().getOrThrow()
        assertFalse(starredAfterRestore.any { VaultEntry.isVaulted(it.id) })
        val restoredReal = starredAfterRestore.single { !it.isFolder && it.name == "episode.mkv" }
        assertTrue(restoredReal.tags.any { it.name.equals("STAR", ignoreCase = true) })
    }

    @Test
    fun `single and mixed file selections archive per parent folder and support undo`() = smoke { scope ->
        val f1 = Fixture()
        val show = f1.server.addFolder("Show")
        val episode = f1.server.addFile("episode.mkv", show.id, content = ByteArray(100), hash = "G1")
        val sibling = f1.server.addFile("extra.mkv", show.id, content = ByteArray(100), hash = "G2")
        val session1 = FolderVaultSession(f1.repository, scope)
        val row = f1.repository.listBrowsable(show.id, PikoFileSortOrder.NAME_ASC).getOrThrow().single { it.name == "episode.mkv" }
        val singleTarget = VaultScope("episode.mkv", files = listOf(row))

        val survey = session1.survey(singleTarget).getOrThrow()
        assertEquals(1, survey.files)
        assertEquals(1, survey.unsourcedFiles)

        session1.archive(singleTarget, includeUnsourced = true)
        awaitUntil("single file archived") { f1.repository.changes.latest.value != null }
        assertTrue(f1.server.node(episode.id)!!.trashed)
        assertFalse(f1.server.node(sibling.id)!!.trashed)
        suspend fun PikoDriveRepository.archived(folderId: String) =
            vault.read(folderId, listAllFiles(folderId).getOrThrow()).getOrThrow().filter { it.isArchived }
        assertEquals(listOf("G1"), f1.repository.archived(show.id).map { it.gcid })
        assertTrue(f1.repository.vaultTrees.flow.value.isEmpty())
        f1.repository.listBrowsable(show.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
        awaitUntil("parent folder marked vaulted") { show.id in f1.repository.vaultedFolders.value }

        f1.repository.changes.undo(assertNotNull(f1.repository.changes.latest.value))
        withTimeout(10_000) { while (f1.repository.archived(show.id).isNotEmpty()) delay(20) }
        awaitUntil("trashed file restored") { !f1.server.node(episode.id)!!.trashed }

        val f2 = Fixture()
        val left = f2.server.addFolder("Left")
        val right = f2.server.addFolder("Right")
        val picked = f2.server.addFolder("Picked")
        val nested = f2.server.addFolder("Season 1", picked.id)
        val a = f2.server.addFile("a.mkv", left.id, content = ByteArray(100), hash = "GA")
        val b = f2.server.addFile("b.mkv", right.id, content = ByteArray(100), hash = "GB")
        val untouched = f2.server.addFile("c.mkv", right.id, content = ByteArray(100), hash = "GC")
        val deep = f2.server.addFile("d.mkv", nested.id, content = ByteArray(100), hash = "GD")
        val session2 = FolderVaultSession(f2.repository, scope)
        val rowA = f2.repository.listBrowsable(left.id, PikoFileSortOrder.NAME_ASC).getOrThrow().single { it.name == "a.mkv" }
        val rowB = f2.repository.listBrowsable(right.id, PikoFileSortOrder.NAME_ASC).getOrThrow().single { it.name == "b.mkv" }
        session2.archive(
            VaultScope("mixed", folders = listOf(PikoPathBreadcrumb(picked.id, picked.name)), files = listOf(rowA, rowB)),
            includeUnsourced = true,
        )
        awaitUntil("mixed archive finished") { f2.repository.changes.latest.value != null }
        assertEquals(listOf("GA"), f2.repository.archived(left.id).map { it.gcid })
        assertEquals(listOf("GB"), f2.repository.archived(right.id).map { it.gcid })
        assertEquals(listOf("GD"), f2.repository.archived(nested.id).map { it.gcid })
        assertTrue(listOf(a, b, deep).all { f2.server.node(it.id)!!.trashed })
        assertFalse(f2.server.node(untouched.id)!!.trashed)
        assertEquals(mapOf(picked.id to setOf(nested.id)), f2.repository.vaultTrees.flow.value)
        val change = assertIs<DriveChangeJournal.Change.Vault>(f2.repository.changes.latest.value)
        assertEquals(setOf(a.id, b.id, deep.id), change.untrashOnRevert.toSet())
    }

    @Test
    fun `archived folders resolve video covers across nested containers while ignoring restored entries`() = smoke { scope ->
        val oldManifest = """
            {"entries":[
              {"id":"a1","name":"Frieren - 02.mkv","size":4096,"gcid":"GCIDEP02","addedAt":1759000000000},
              {"id":"a2","name":"Frieren - 01.ass","size":10,"gcid":"GCIDSUB01","addedAt":1759000000000},
              {"id":"a3","name":"Frieren - 01.mkv","size":4096,"gcid":"GCIDEP01","cid":"CID01","source":"magnet:?xt=urn:btih:${"a".repeat(40)}","addedAt":1759000000000}
            ]}
        """.trimIndent()
        val expectedCover = thumbnailUrlOf("GCIDEP01")

        val server = FakePikPakServer()
        val frieren = server.addFolder("Frieren")
        server.addFile(".piko-vault-v1-0123456789abcdef.json", frieren.id, content = oldManifest.encodeToByteArray())
        val plain = server.addFolder("Plain")
        server.addFile("notes.txt", plain.id)
        val restoredFolder = server.addFolder("Restored")
        server.addFile(
            ".piko-vault-v2-0000000000000001.json",
            restoredFolder.id,
            content = """{"entries":[{"id":"r1","name":"E01.mkv","size":4096,"gcid":"GCIDR1","addedAt":1759000000000,"restoredFileId":"N999"}]}""".encodeToByteArray(),
        )
        val outer = server.addFolder("Show")
        val season = server.addFolder("Season", outer.id)
        val leaf = server.addFolder("Episodes", season.id)
        server.addFile(".piko-vault-v1-0123456789abcdef.json", leaf.id, content = oldManifest.encodeToByteArray())

        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val drive = DriveScreenState(repo, prefs, scope)
        drive.load()
        awaitUntil("root listed") { !drive.isLoading && drive.files.size == 4 }
        drive.files.forEach { drive.onFolderVisible(it) }
        awaitUntil("frieren cover populated") { drive.files.single { it.id == frieren.id }.thumbnailLink == expectedCover }
        assertEquals("", drive.files.single { it.id == plain.id }.thumbnailLink)

        repo.listBrowsable(restoredFolder.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
        repo.fetchVaultCover(restoredFolder.id)
        assertEquals("", repo.listBrowsable("", PikoFileSortOrder.NAME_ASC).getOrThrow().single { it.id == restoredFolder.id }.thumbnailLink)

        repo.listBrowsable(leaf.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
        repo.listBrowsable(season.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
        repo.listBrowsable(outer.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
        awaitUntil("nested containers fully vaulted") { setOf(outer.id, season.id, leaf.id).all { it in repo.fullyVaultedFolders.value } }
        val outerRow = repo.listBrowsable("", PikoFileSortOrder.NAME_ASC).getOrThrow().single { it.id == outer.id }
        assertEquals(expectedCover, repo.withVaultCovers(listOf(outerRow.copy(thumbnailLink = "leftover-image"))).single().thumbnailLink)
    }
}
