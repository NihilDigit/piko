package dev.piko.shared.smoke

import dev.piko.download.DownloadBatch
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.AddLinkViewModel
import dev.piko.shared.presentation.TaskContinuations
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.TrashSession
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransferItem
import dev.piko.shared.state.TransfersState
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.PikoUploadSources
import dev.piko.shared.upload.UploadFolder
import dev.piko.shared.upload.UploadSourceInfo
import kotlinx.io.RawSource
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.TaskPhase
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/** 传输页只列当前账号的下载；别的账号的任务原样留在任务表里，切回去照旧看得到。 */
class TransfersAccountSmokeTest {
    private object NoSources : PikoUploadSources {
        override fun describe(uri: String): UploadSourceInfo? = null
        override fun readAt(uri: String, offset: Long, length: Int): ByteArray = error("不该读源文件")
        override fun open(uri: String, offset: Long): RawSource = error("不该读源文件")
        override fun listFolder(uri: String): UploadFolder? = null
    }

    @Test
    fun `task page includes resumable drafts reports and cleanup without owning their execution`() = smoke { ownerScope ->
        val server = FakePikPakServer()
        val original = server.addFile("a.mkv", content = byteArrayOf(1), hash = "SAME")
        val copy = server.addFile("copy.mkv", content = byteArrayOf(1), hash = "SAME")
        val other = server.addFolder("other")
        val provider = server.provider()
        val account = assertNotNull(provider.currentClient.value).account
        val prefs = MemoryPreferences()
        val drive = PikoDriveRepository(provider, prefs)
        val instant = InstantMagnetRepository(provider)
        val packs = OfflinePackTracker(instant, drive, prefs)
        val records = InstantSaveRecords(provider, null, ownerScope)
        val preview = PreviewTempFolder(provider, drive, instant, ownerScope)
        fun childScope() = CoroutineScope(ownerScope.coroutineContext + SupervisorJob(ownerScope.coroutineContext[Job]))
        val links = AddLinkViewModel(InstantSession(::childScope) { child, input ->
            InstantSheetState(instant, drive, prefs, preview, packs, records, child, input)
        })
        val duplicates = DuplicateSession(::childScope) { child, root -> DuplicateFinderState(provider, drive, child, root) }
        val cleanup = TrashSession(drive, ownerScope)
        val continuations = TaskContinuations(links, duplicates, cleanup)
        val vaultSession = FolderVaultSession(drive, ownerScope)
        val fileWrites = dev.piko.shared.state.FileWriteSession(drive, ownerScope)
        val historyEdits = dev.piko.shared.state.HistoryEditSession(drive, ownerScope)
        val vaultEdits = dev.piko.shared.state.VaultEntryEditSession(drive, ownerScope)
        val state = TransfersState(
            PikoDownloadCoordinator(provider, prefs, DirectoryStorage(Files.createTempDirectory("piko-resume").toFile()), ownerScope),
            TaskRepository(provider, drive, instant), packs, ownerScope, drive,
            PikoUploadCoordinator(provider, prefs, NoSources, drive, ownerScope), records, account,
            ArchiveExtractSession(provider, drive, ArchivePasswordVault(provider, MemorySessionStore(), prefs), ownerScope),
            vaultSession, listsServerWork = true, continuations = continuations, fileWrites = fileWrites, historyEdits = historyEdits, vaultEdits = vaultEdits,
        )
        assertTrue(state.isEmpty)
        links.open("draft text")
        links.collapse()
        duplicates.open(PikoDriveRepository.ROOT_BREADCRUMB)
        val firstReport = assertNotNull(duplicates.state)
        awaitUntil("没有报告页面时扫描仍完成") { firstReport.phase == DuplicateFinderState.Phase.DONE }
        assertEquals(2, state.counts[TransferKind.ALL])
        assertEquals(1, state.counts[TransferKind.FILES])
        assertEquals(0, state.counts[TransferKind.CLOUD])
        assertTrue(!state.isEmpty)
        assertEquals(1, firstReport.report.identical.size)

        cleanup.start(listOf(copy.id))
        awaitUntil("任务页可继续处理清理结果") { cleanup.completedCount == 1 && cleanup.progress == null }
        assertEquals(3, state.counts[TransferKind.ALL])
        assertEquals(2, state.counts[TransferKind.FILES])
        links.resume()
        assertEquals("draft text", assertNotNull(links.state).input)
        links.discard()
        duplicates.open(PikoPathBreadcrumb(other.id, other.name))
        val replacement = assertNotNull(duplicates.state)
        continuations.discardDuplicate(firstReport)
        continuations.stopDuplicate(firstReport)
        assertTrue(duplicates.state === replacement, "旧报告界面的晚到动作不能结束新扫描")
        continuations.discardDuplicate(replacement)
        cleanup.dismiss()
        assertTrue(state.isEmpty)
        vaultSession.archive(PikoPathBreadcrumb(other.id, other.name), includeUnsourced = true)
        awaitUntil("归档结果在任务列表保留") { state.completed.any { it is TransferItem.Vault } }
        val vaultResult = state.completed.filterIsInstance<TransferItem.Vault>().single()
        assertEquals(FolderVaultSession.Completion.SUCCEEDED, assertNotNull(vaultResult.record).completion)
        assertEquals(1, state.counts[TransferKind.FILES])
        state.remove(vaultResult)
        assertTrue(state.isEmpty)
        assertTrue(server.node(other.id) != null, "移除任务结果不删除网盘目录")
        val commands = dev.piko.shared.presentation.FileCommands(drive, fileWrites)
        assertTrue(commands.submit(dev.piko.shared.state.FileWriteCommand.Rename(original.id, "renamed.mkv")) is dev.piko.shared.presentation.FileCommandResult.Accepted)
        awaitUntil("普通文件操作结果出现在任务页") { state.completed.any { it is TransferItem.Write } }
        val writeResult = state.completed.filterIsInstance<TransferItem.Write>().single()
        assertEquals(1, state.counts[TransferKind.FILES])
        assertTrue(state.canUndoWrite(writeResult.work))
        state.undoWrite(writeResult.work)
        awaitUntil("任务页显示对应撤销结局") { state.writeUndoStatus(writeResult.work)?.status == dev.piko.shared.data.DriveChangeJournal.UndoStatus.UNDONE }
        assertEquals("a.mkv", original.name)
        state.remove(writeResult)
        awaitUntil("移除结果不修改网盘文件") { state.isEmpty }
        assertTrue(server.node(original.id) != null)
        val played = server.addEvent(io.github.nihildigit.pikpak.EventType.PLAY, original.id)
        val historyCommands = dev.piko.shared.presentation.HistoryCommands(drive, historyEdits)
        assertTrue(historyCommands.submit(dev.piko.shared.state.HistoryEditCommand.Remove(dev.piko.shared.data.HistoryKind.PLAY, listOf(played.id))) is dev.piko.shared.presentation.HistoryCommandResult.Accepted)
        try {
            awaitUntil("历史处理结果出现在任务页") { state.completed.any { it is TransferItem.History } }
            val sections = state.sectionsOf(TransferKind.ALL)
            val projected = sections.inProgress + sections.needsAttention + sections.completed + sections.outputDeleted
            assertEquals(projected.size, projected.distinctBy { it.key }.size, "同一任务在一份投影中只能出现一次")
            assertEquals(1, state.counts[TransferKind.FILES])
        } catch (error: AssertionError) {
            throw AssertionError("progress=${state.inProgress.map { it.key }}, attention=${state.needsAttention.map { it.key }}, completed=${state.completed.map { it.key }}, counts=${state.counts}, cleanup=${continuations.hasCleanup}, duplicate=${continuations.duplicate != null}, writes=${fileWrites.works.value.map { it.id to it.result?.completion }}, history=${historyEdits.works.value.map { it.id to it.result?.completion }}", error)
        }
        assertEquals(1, state.counts[TransferKind.FILES])
        state.remove(state.completed.filterIsInstance<TransferItem.History>().single())
        awaitUntil("移除历史操作的结果记录") { state.isEmpty }
        assertNotNull(server.node(original.id))
        val vaultCommands = dev.piko.shared.presentation.VaultEntryCommands(drive, vaultEdits, commands)
        assertTrue(vaultCommands.submit(dev.piko.shared.state.VaultEntryEditCommand.Remove(mapOf(other.id to setOf("absent")))) is dev.piko.shared.presentation.VaultEntryCommandResult.Accepted)
        awaitUntil("归档条目编辑结果进入同一任务投影") { state.completed.any { it is TransferItem.VaultEdit } }
        val edit = state.completed.filterIsInstance<TransferItem.VaultEdit>().single()
        assertEquals(1, state.counts[TransferKind.FILES])
        assertEquals(dev.piko.shared.state.VaultEntryEditCompletion.SUCCEEDED, edit.work.result!!.completion)
        assertTrue(!state.canUndoVaultEdit(edit.work), "实际清单未变的操作不能制造撤销数据")
        state.remove(edit)
        awaitUntil("移除编辑结果不改变目录") { state.isEmpty }
        assertNotNull(server.node(other.id))
        val attemptId = records.beginAttempt(account, "保存尝试", PikoPathBreadcrumb(other.id, other.name),
            listOf(io.github.nihildigit.pikpak.ResolvedFile("partial.mkv", 10, "PARTIAL")))!!
        awaitUntil("保存尝试在进行中投影，草稿不重复计数") { state.inProgress.any { it.key == "instant:$attemptId" } }
        assertEquals(1, state.counts[TransferKind.CLOUD])
        records.finishAttempt(attemptId, dev.piko.shared.data.InstantSaveReceipt(account, other.id, unconfirmed = setOf("partial.mkv")))
        awaitUntil("不确定结果进入需要处理") { state.needsAttention.any { it.key == "instant:$attemptId" } }
        records.add("partial.mkv", 1, 10, other.name, original.id, id = attemptId, expectedAccount = account)
        awaitUntil("成功历史沿用同一身份替换运行期尝试") { state.completed.any { it.key == "instant:$attemptId" } && state.needsAttention.none { it.key == "instant:$attemptId" } }
        assertEquals(1, state.counts[TransferKind.CLOUD])
        state.remove(state.completed.single { it.key == "instant:$attemptId" })
        awaitUntil("移除历史不删除文件") { state.isEmpty }
        assertNotNull(server.node(original.id))
        server.offline = true
        val offline = assertNotNull(state.offlineSubmits)
        assertTrue(offline.submit("https://example.invalid/task", PikoPathBreadcrumb(other.id, other.name), "提交测试", account).isFailure)
        server.offline = false
        awaitUntil("未知离线提交进入需要处理的云端类别") { state.needsAttention.any { it is TransferItem.OfflineSubmit } }
        val offlineItem = state.needsAttention.filterIsInstance<TransferItem.OfflineSubmit>().single()
        assertEquals(1, state.counts[TransferKind.CLOUD])
        assertEquals(1, state.counts[TransferKind.ALL])
        assertEquals(dev.piko.shared.state.OfflineSubmitStatus.UNCONFIRMED, offlineItem.work.status)
        state.remove(offlineItem)
        awaitUntil("移除未知提交只移除本地记录") { state.isEmpty }
        assertNotNull(server.node(original.id))
    }

    @Test
    fun `leaving a task page stops its polling but preserves state and accepted work`() = smoke { ownerScope ->
        val server = FakePikPakServer()
        server.addTask("cloud", TaskPhase.RUNNING)
        val started = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Delete && request.url.encodedPath.endsWith("/drive/v1/tasks")) {
                started.send(Unit)
                release.receive()
            }
            execute(request)
        }
        val provider = server.provider(PikPakClient(
            account = "a@piko.dev", password = "pw", sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = http,
        ))
        val prefs = MemoryPreferences()
        val drive = PikoDriveRepository(provider, prefs)
        val coordinator = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(Files.createTempDirectory("piko-task-owner").toFile()), ownerScope)
        val state = TransfersState(
            coordinator, TaskRepository(provider, drive), OfflinePackTracker(InstantMagnetRepository(provider), drive, prefs),
            ownerScope, drive, PikoUploadCoordinator(provider, prefs, NoSources, drive, ownerScope),
            InstantSaveRecords(provider, null, ownerScope), "a@piko.dev",
            ArchiveExtractSession(provider, drive, ArchivePasswordVault(provider, MemorySessionStore(), prefs), ownerScope),
            FolderVaultSession(drive, ownerScope), listsServerWork = true,
        )
        val pageJob = SupervisorJob(ownerScope.coroutineContext[Job])
        val pageScope = CoroutineScope(ownerScope.coroutineContext + pageJob)
        val polling = pageScope.launch { state.whileVisible() }
        awaitUntil("任务页加载云端任务") { state.inProgress.any { it is TransferItem.Cloud } }
        val item = state.inProgress.filterIsInstance<TransferItem.Cloud>().single()
        state.changeFilter(TransferKind.CLOUD)
        state.selectOnly(item.key)
        state.deleteCloud(item.task.id)
        withTimeout(5_000) { started.receive() }
        pageJob.cancelAndJoin()
        assertTrue(polling.isCancelled, "离开页面结束可见期查询")
        assertEquals(TransferKind.CLOUD, state.filter)
        assertEquals(setOf(item.key), state.selectedKeys)
        release.send(Unit)
        awaitUntil("离开页面后已接受的删除仍完成") {
            server.tasksSnapshot().isEmpty() && state.inProgress.none { it.key == item.key }
        }
        assertEquals(TransferKind.CLOUD, state.filter, "任务更新不覆盖页面筛选")
    }

    @Test
    fun `downloads of another account are left out of the transfers page`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider(server.client(account = "a@piko.dev"))
        fun paused(id: String, account: String, batch: DownloadBatch? = null) = DownloadTask(
            taskId = id, fileId = id, fileName = "$id.mkv", gcid = "G$id", totalBytes = 100, downloadedBytes = 10,
            status = DownloadStatus.PAUSED, account = account, batch = batch,
        )
        val prefs = MemoryPreferences().apply {
            downloadTasks = Json.encodeToString(
                ListSerializer(DownloadTask.serializer()),
                listOf(
                    paused("mine", "a@piko.dev"),
                    // 有多账号之前建的任务不带账号，哪个账号都列
                    paused("legacy", ""),
                    paused("theirs", "b@piko.dev"),
                    paused("their-batch-file", "b@piko.dev", DownloadBatch("their-batch", "乙的文件夹")),
                ),
            )
        }
        val coordinator = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(Files.createTempDirectory("piko-transfers").toFile()), scope)
        awaitUntil("任务表读回") { coordinator.tasks.value.size == 4 }

        val driveRepo = PikoDriveRepository(provider, prefs)
        val instantRepo = InstantMagnetRepository(provider)
        val state = TransfersState(
            coordinator,
            TaskRepository(provider, driveRepo),
            OfflinePackTracker(instantRepo, driveRepo, prefs),
            scope,
            driveRepo,
            PikoUploadCoordinator(provider, prefs, NoSources, driveRepo, scope),
            InstantSaveRecords(provider, null, scope),
            "a@piko.dev",
            ArchiveExtractSession(provider, driveRepo, ArchivePasswordVault(provider, MemorySessionStore(), prefs), scope),
            FolderVaultSession(driveRepo, scope),
            listsServerWork = false,
        )

        fun listed() = with(state.sectionsOf(TransferKind.ALL)) { inProgress + needsAttention + completed + outputDeleted }.map { it.key }.toSet()
        awaitUntil("列出当前账号的下载") { listed().isNotEmpty() }
        assertEquals(setOf("local:mine", "local:legacy"), listed())
        assertEquals(4, coordinator.tasks.value.size, "别的账号的任务不该被删")
    }
}
