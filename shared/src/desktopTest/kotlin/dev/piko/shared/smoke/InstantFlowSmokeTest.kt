package dev.piko.shared.smoke


import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.state.InstantBatchRowStatus
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.SaveRoute
import dev.piko.shared.state.SaveTargetContext
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.ResolvedFile
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 秒传工作台从解析到落盘的完整流程。两端都只剩布局，这里坏了两端一起坏。
 */
class InstantFlowSmokeTest {
    @Test
    fun sharesUseResolvedWorkspaceAndInstantRouteAlongsideMagnets() = smoke { scope ->
        val server = FakePikPakServer()
        val first = server.addFile("first.mkv", content = ByteArray(100), hash = "SHARE1")
        val second = server.addFile("second.mkv", content = ByteArray(200), hash = "SHARE2")
        server.shares["shared"] = listOf(first.id, second.id)
        val rig = Rig(server, MemoryPreferences(), scope)
        val sheet = rig.sheet(scope, "https://mypikpak.net/s/shared?pwd=1234")
        awaitUntil("分享解析完成") { sheet.resolution != null && sheet.target != null }
        assertNull(sheet.shareSave)
        assertEquals(listOf("SHARE1", "SHARE2"), sheet.items.map { it.file.gcid })
        sheet.setSourceSelected(sheet.items.indices.toSet(), true)
        assertEquals(SaveRoute.INSTANT, sheet.savePlan?.route)
        assertEquals(2, sheet.projectedSaveFiles.size)
        val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
        server.indexMagnet(magnet, resourceListBody("magnet", listOf(Triple("third.mkv", 100L, "MAGNET"))))
        val mixed = rig.sheet(scope, "https://mypikpak.com/s/shared\n$magnet")
        val batch = assertNotNull(mixed.batch)
        awaitUntil("分享与磁力均完成解析") { batch.rows.all { it.state.resolution != null } }
        assertEquals(2, batch.rows.size)
    }

    @Test
    fun `incoming text keeps all links from unconsumed shares`() {
        val repo = InstantMagnetRepository(FakePikPakServer().provider())
        val first = "magnet:?xt=urn:btih:" + "a".repeat(40)
        val second = "magnet:?xt=urn:btih:" + "b".repeat(40)
        val third = "https://example.com/file.zip"
        repo.onIncomingMagnet(first)
        repo.onIncomingMagnet("$second\n$third")
        assertEquals(listOf(first, second, third), dev.piko.shared.state.extractLinks(repo.pendingMagnetFlow.value!!).map { it.uri })
        repo.clearPendingMagnet()
        assertNull(repo.pendingMagnetFlow.value)
    }

    @Test
    fun `repository shares four file permits across independent saves`() = smoke { scope ->
        val server = FakePikPakServer()
        val http = server.httpClient()
        val client = PikPakClient("smoke@piko.dev", "pw",
            sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0))
        val repo = InstantMagnetRepository(server.provider(client))
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files")) {
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try {
                    entered.send(Unit)
                    release.receive()
                    execute(request)
                } finally { active.decrementAndGet() }
            } else execute(request)
        }
        try {
            val saves = (1..3).map { row -> scope.async {
                repo.instantSave((1..3).map { file -> InstantFileItem(
                    ResolvedFile("$row-$file.mkv", 10, "HASH$row$file"), true) }).getOrThrow()
            } }
            withTimeout(5_000) { repeat(4) { entered.receive() } }
            assertEquals(4, peak.get())
            assertTrue(entered.tryReceive().isFailure)
            repeat(9) { release.send(Unit) }
            saves.forEach { assertEquals(3, it.await().size) }
            assertEquals(4, peak.get())
            assertEquals(9, server.instantCreates.get())
        } finally { release.close() }
    }

    @Test
    fun `batch rows overlap and stopping leaves queued rows unsubmitted`() = smoke { scope ->
        val server = FakePikPakServer()
        val http = server.httpClient()
        val provider = server.provider(PikPakClient("smoke@piko.dev", "pw",
            sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0)))
        val rig = Rig(server, MemoryPreferences(), scope, provider)
        val links = ('a'..'f').map { "magnet:?xt=urn:btih:" + it.toString().repeat(40) }
        links.forEachIndexed { index, link ->
            server.indexMagnet(link, resourceListBody("row$index", listOf(Triple("file$index.mkv", 100L, "HASH$index"))))
        }
        val sheet = rig.sheet(scope, links.joinToString("\n"))
        val batch = assertNotNull(sheet.batch)
        awaitUntil("批量解析完成") { batch.canSaveAll }
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        http.plugin(HttpSend).intercept { request ->
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            if (request.method == HttpMethod.Post && body.contains("HASH")) {
                entered.send(Unit)
                release.receive()
            }
            execute(request)
        }
        try {
            batch.saveAll()
            withTimeout(5_000) { repeat(3) { entered.receive() } }
            assertTrue(entered.tryReceive().isFailure)
            batch.stopSaving()
            repeat(3) { release.send(Unit) }
            awaitUntil("已开始的行保留回执") { !batch.isSaving }
            assertEquals(3, server.instantCreates.get())
            assertEquals(3, batch.rows.size)
            assertEquals(3, rig.saveRecords.records.value.size)
            assertTrue(batch.saveErrors.isEmpty())
            assertEquals(links.drop(3), batch.rows.map { it.link.uri })
            val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { sheet.outcomes.first() }
            repeat(3) { release.send(Unit) }
            batch.saveAll()
            val saved = assertIs<InstantSaveOutcome.InstantSaved>(outcome.await())
            assertEquals(listOf("HASH3", "HASH4", "HASH5"), saved.createdIds.map { server.node(it)?.hash })
            assertEquals(6, server.instantCreates.get())
        } finally { release.close() }
    }

    @Test
    fun flattenedBatchSelectionKeepsLinkOwnershipAndAppliesOneReferencePolicy() = smoke { scope ->
        val server = FakePikPakServer()
        val links = listOf('a', 'b', 'c').map { "magnet:?xt=urn:btih:" + it.toString().repeat(40) }
        links.forEachIndexed { index, link ->
            val files = listOf(Triple("相同文件名.mkv", 100L, "HASH$index")) +
                if (index == 2) listOf(Triple("缺少引用.mkv", 100L, "")) else emptyList()
            server.indexMagnet(link, resourceListBody("视频", files))
        }
        val state = Rig(server, MemoryPreferences(), scope).sheet(scope, links.joinToString("\n"))
        val batch = assertNotNull(state.batch)
        awaitUntil("所有来源解析完成") { batch.rows.all { it.state.resolution != null } && state.target != null }
        batch.updateSaveAsReference(true)
        assertTrue(batch.rows.all { it.state.saveAsReference })
        assertTrue(batch.canSaveAll)
        assertNull(batch.blockedReason)
        val mixed = batch.rows.last().state
        assertEquals(1, mixed.missingReferenceCount)
        assertEquals(1, mixed.savePlan?.fileCount)
        assertEquals(1, mixed.savePlan?.skippedCount)
        assertEquals(1, mixed.projectedSaveFiles.size)
        assertTrue(mixed.canSaveSelection)
        mixed.setSourceSelected(mixed.items.indices.toSet(), false)
        mixed.setSourceSelected(mixed.items.indices.filter { mixed.items[it].file.gcid == null }.toSet(), true)
        assertTrue(!mixed.canSaveSelection)
        assertEquals(0, mixed.savePlan?.fileCount)
        assertTrue(mixed.projectedSaveFiles.isEmpty())
        mixed.setSourceSelected(mixed.items.indices.toSet(), true)
        mixed.deselectUnindexed()
        assertEquals(0, mixed.missingReferenceCount)
        batch.rows.last().state.let { it.setSourceSelected(it.items.indices.toSet(), false) }
        assertTrue(batch.canSaveAll)
        batch.updateSaveAsReference(false)
        assertTrue(batch.rows.none { it.state.saveAsReference })
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        batch.saveAll()
        assertEquals(2, assertIs<InstantSaveOutcome.InstantSaved>(outcome.await()).createdIds.size)
        assertEquals(setOf("HASH0", "HASH1"), server.children(state.target!!.id).map { it.hash }.toSet())
    }

    @Test
    fun explicitSourceSubtitleSelectionOverridesTheDefaultAndMatchesSavedProjection() = smoke { scope ->
        val server = FakePikPakServer()
        server.indexMagnet(magnet, resourceListBody("Show", listOf(
            Triple("E01.mkv", 100L, "VIDEO"), Triple("E01.zh.ass", 10L, "SUBTITLE"),
        )))
        val rig = Rig(server, MemoryPreferences(), scope)
        val state = rig.sheet(scope, magnet)
        awaitUntil("来源解析完成") { state.resolution != null && state.target != null }
        val subtitle = state.items.indexOfFirst { it.file.name.endsWith(".ass") }
        assertTrue(subtitle >= 0)
        assertTrue(subtitle !in state.sourceSelectedIndices)
        state.setSourceSelected(setOf(subtitle), true)
        assertTrue(subtitle in state.sourceSelectedIndices)
        val projected = state.projectedSaveFiles.map { it.name }.toSet()
        assertEquals(setOf("E01.mkv", "E01.zh.ass"), projected)
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        state.saveSelection()
        assertIs<InstantSaveOutcome.InstantSaved>(outcome.await())
        assertEquals(projected, server.children(state.target!!.id).filter { !it.trashed }.map { it.name }.toSet())
    }

    @Test
    fun `shared video preview reuses the temporary copy and cleans it on close`() = smoke { scope ->
        val server = FakePikPakServer()
        val rig = Rig(server, MemoryPreferences(), scope)
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = rig.sheet(sessionScope, "https://mypikpak.com/s/S1")
        val file = io.github.nihildigit.pikpak.FileStat(id = "owner-video", name = "episode.mkv", hash = "SHARED", size = "1024")
        suspend fun preview(): String {
            val request = scope.async(start = CoroutineStart.UNDISPATCHED) { state.previewRequests.first() }
            state.previewSharedFile(file)
            return request.await().fileId
        }
        val first = preview()
        val folder = server.children("").single { it.name == PreviewTempFolder.FOLDER_NAME }
        assertEquals(folder.id, server.node(first)?.parentId)
        awaitUntil("预览状态复位") { state.previewingIndex == null }
        assertEquals(first, preview())
        assertEquals(1, server.instantCreates.get())
        sessionScope.cancel()
        awaitUntil("临时预览副本已清理，共享目录保留") { server.children(folder.id).isEmpty() }
        assertNull(server.node(first))
    }

    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
    private val season = listOf(
        Triple("E01.mkv", 700L shl 20, "GCID01"),
        Triple("E02.mkv", 700L shl 20, "GCID02"),
        // 体积过了十分之一的门槛，只能靠次要目录名把它排除
        Triple("sample/sample.mkv", 300L shl 20, "GCID03"),
        Triple("info.nfo", 2048L, ""),
    )

    private class Rig(
        val server: FakePikPakServer,
        val prefs: MemoryPreferences,
        backgroundScope: CoroutineScope,
        private val provider: dev.piko.shared.data.PikoClientProvider = server.provider(),
    ) {
        val instantRepo = InstantMagnetRepository(provider)
        val driveRepo = PikoDriveRepository(provider, prefs)
        val workspace = dev.piko.shared.presentation.DriveWorkspace(driveRepo, backgroundScope = backgroundScope)
        val tracker = OfflinePackTracker(instantRepo, driveRepo, prefs)
        val previewFolder = PreviewTempFolder(provider, driveRepo, instantRepo, backgroundScope)
        val saveRecords = InstantSaveRecords(provider, null, backgroundScope)

        fun sheet(
            scope: CoroutineScope,
            magnet: String,
            targetContext: SaveTargetContext = workspace.saveTargetContext,
            saveScope: CoroutineScope = scope,
        ) = InstantSheetState(instantRepo, driveRepo, prefs, previewFolder, tracker, saveRecords, scope, magnet,
            targetContext, saveScope = saveScope)

        /** 网盘页停在 [folder] 里，与用户点进去之后的栈相同。 */
        fun openInDrive(folder: FakePikPakServer.Node) {
            workspace.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(folder.id, folder.name)))
        }
    }

    /**
     * 防的是整包离线的后半程断掉：网盘页停着的目录已进回收站仍被当成可用、提交后没人跟踪、
     * 完成后没删未选的文件、产出文件夹没改成面板里填的名字、跟踪记录没落盘。
     */
    @Test
    fun `a multi-file selection goes offline whole, then is pruned and renamed`() = smoke { scope ->
        val server = FakePikPakServer()
        val trashed = server.addFolder("旧目录", trashed = true)
        server.indexMagnet(magnet, resourceListBody("Show S01", season))
        val rig = Rig(server, MemoryPreferences(), scope)
        rig.openInDrive(trashed)
        scope.launch { rig.tracker.run("smoke@piko.dev") }
        val state = rig.sheet(scope, magnet)
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }

        awaitUntil("解析完成、目标与余量确定") {
            state.resolution != null && state.target != null && state.remainingBytes != null
        }
        val target = state.target!!
        assertEquals("My Packs", target.name)
        assertNotNull(state.targetNotice, "目标被替换时要告诉用户")
        assertEquals(setOf("E01.mkv", "E02.mkv"), state.selectedItems.map { it.file.name }.toSet())
        assertEquals(SaveRoute.OFFLINE_PACK, state.savePlan?.route)

        state.updateFolderName("Show S01 精选")
        state.saveSelection()
        assertIs<InstantSaveOutcome.OfflineTaskCreated>(outcome.await())
        val task = server.tasksSnapshot().single()
        assertEquals(target.id, task.parentId)
        assertEquals(0, server.instantCreates.get(), "整包离线不能先秒传任何文件")
        assertTrue(task.id in rig.prefs.offlinePacks, "跟踪记录要落盘，重启后才能接着清理")
        assertEquals(emptyList(), rig.saveRecords.records.value, "离线在传输页里以任务出现，不另记秒传")

        server.completeTask(task.id, "Show S01", season.map { it.first })
        awaitUntil("清理与改名完成", timeoutMs = 15_000) {
            rig.tracker.jobs.value.singleOrNull()?.stage == OfflinePackStage.DONE
        }
        val output = assertNotNull(server.node(rig.tracker.jobs.value.single().outputId))
        assertEquals("Show S01 精选", output.name)
        assertEquals(target.id, output.parentId)
        assertEquals(setOf("E01.mkv", "E02.mkv"), server.tree(output.id).toSet())
    }

    /**
     * 防的是空间不够时仍提交整包：离线要先把整包落进网盘才能删，放不下就会失败在云端。
     * 退路只秒传选中的、已收录的文件，并保留种子里的子目录，不拍平。
     */
    @Test
    fun `a pack that does not fit is not submitted and the selection can be instant-saved instead`() = smoke { scope ->
        val server = FakePikPakServer()
        server.quotaUsage = server.quotaLimit - (1L shl 30)
        server.indexMagnet(magnet, resourceListBody("Show S01", season))
        val rig = Rig(server, MemoryPreferences(), scope)
        scope.launch { rig.tracker.run("smoke@piko.dev") }
        val state = rig.sheet(scope, magnet)
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }

        awaitUntil("解析完成、目标与余量确定") {
            state.resolution != null && state.target != null && state.remainingBytes != null
        }
        state.toggleSelectAll()
        val plan = assertNotNull(state.savePlan)
        assertTrue(plan.lacksSpace)
        assertEquals(false, state.primaryAction?.enabled, "放不下整包时主按钮不可用")
        val fallback = assertNotNull(plan.fallback)
        assertEquals(3, fallback.fileCount)
        assertEquals(1, fallback.skippedCount, "未收录的 nfo 秒传不了")

        state.saveSelectionInstantly()
        awaitUntil("所选文件也超过余量，停止保存") { !state.isSaving && state.errorMessage != null }
        assertEquals("网盘空间不足，无法保存所选文件", state.errorMessage)
        assertEquals(0, server.instantCreates.get())
        // 腾出空间后只容得下已收录部分，整包仍多出未收录的 nfo。
        server.quotaUsage = server.quotaLimit - season.filter { it.third.isNotEmpty() }.sumOf { it.second }
        state.saveSelectionInstantly()
        val saved = assertIs<InstantSaveOutcome.InstantSaved>(outcome.await())
        assertEquals("Show S01", saved.target.name)
        assertEquals(setOf("E01.mkv", "E02.mkv", "sample/sample.mkv"), server.tree(saved.target.id).toSet())
        assertEquals(emptyList(), server.tasksSnapshot())
        // 文件散在新文件夹的子目录里，传输页的记录要定位到文件夹本身
        val record = rig.saveRecords.records.value.single()
        assertEquals(saved.target.id, record.locateId)
        assertEquals(3, record.fileCount)
    }

    /**
     * 防的是预览把额度扣两遍：同一集预览两次、预览后再保存，都只该秒传一次。
     * 也防面板关闭时的清理误删已保存的文件，或漏删 Piko-Temp。
     */
    @Test
    fun `a previewed episode is moved on save and only unsaved previews go with the session`() = smoke { scope ->
        val server = FakePikPakServer()
        server.indexMagnet(magnet, resourceListBody("Show S01", season))
        val rig = Rig(server, MemoryPreferences(), scope)
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = rig.sheet(sessionScope, magnet)

        awaitUntil("解析完成且保存目标确定") { state.resolution != null && state.target != null }
        val e01 = state.items.indexOfFirst { it.file.name == "E01.mkv" }
        val e02 = state.items.indexOfFirst { it.file.name == "E02.mkv" }
        state.setItemSelected(e02, false)
        assertEquals(SaveRoute.INSTANT, state.savePlan?.route)
        assertTrue(state.canPreview(e01))

        suspend fun preview(index: Int): String {
            val request = scope.async(start = CoroutineStart.UNDISPATCHED) { state.previewRequests.first() }
            state.preview(index)
            return request.await().fileId
        }
        val previewId = preview(e01)
        val tempFolder = assertNotNull(server.children("").singleOrNull { it.name == PreviewTempFolder.FOLDER_NAME })
        assertEquals(tempFolder.id, server.node(previewId)?.parentId)
        assertEquals(previewId, preview(e01))
        preview(e02)
        assertEquals(2, server.instantCreates.get())

        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        state.saveSelection()
        val saved = assertIs<InstantSaveOutcome.InstantSaved>(outcome.await())
        assertEquals(listOf(previewId), saved.createdIds)
        assertEquals(state.target?.id, server.node(previewId)?.parentId)
        assertEquals(2, server.instantCreates.get(), "预览过的文件保存时移动过去，不再秒传")
        assertEquals(previewId, rig.saveRecords.records.value.single().locateId, "传输页的记录指向保存下来的那一份")

        sessionScope.cancel()
        awaitUntil("未保存的预览被删除，共享临时目录保留") { server.children(tempFolder.id).isEmpty() }
        assertNotNull(server.node(previewId), "已保存的文件不能随 Piko-Temp 一起删掉")
    }

    /**
     * 防的是开着按番号规范命名时只有面板上改了名：秒传要带规范名，移用的预览副本移过来后另改名，
     * 字幕跟着视频换主干。默认开启，手动关闭时原名照存。
     */
    @Test
    fun `canonical code names are what lands in the drive`() = smoke { scope ->
        val server = FakePikPakServer()
        val files = listOf(
            Triple("[xxx.com]abc00123hhb.mp4", 900L shl 20, "GCIDAV"),
            Triple("[xxx.com]abc00123hhb.zh.srt", 40L shl 10, "GCIDSUB"),
        )
        server.indexMagnet(magnet, resourceListBody("abc00123", files))
        val rig = Rig(server, MemoryPreferences(), scope)
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = rig.sheet(sessionScope, magnet)
        awaitUntil("解析完成且保存目标确定") { state.resolution != null && state.target != null }
        assertEquals(true, state.useCanonicalNames)
        assertTrue(state.offersCanonicalNames)
        state.updateUseCanonicalNames(false)
        assertEquals(listOf("[xxx.com]abc00123hhb.mp4", "[xxx.com]abc00123hhb.zh.srt"), state.items.indices.map(state::nameToSave))
        val video = state.items.indexOfFirst { it.file.name.endsWith(".mp4") }
        val row = assertNotNull(state.tree?.rowOf(video))
        assertNull(state.renamedLabel(row))

        state.updateUseCanonicalNames(true)
        assertEquals("ABC-123.mp4", state.renamedLabel(row))
        val request = scope.async(start = CoroutineStart.UNDISPATCHED) { state.previewRequests.first() }
        state.preview(video)
        val previewId = request.await().fileId
        awaitUntil("预览状态复位") { state.previewingIndex == null }

        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        state.saveSelection()
        assertIs<InstantSaveOutcome.InstantSaved>(outcome.await())
        val target = assertNotNull(state.target)
        // 测试偏好里「保存配套字幕」关着，只存视频；字幕的规范名已在上面核对
        assertEquals(listOf("ABC-123.mp4"), server.children(target.id).map { it.name }.filter { it != PreviewTempFolder.FOLDER_NAME })
        assertEquals("ABC-123.mp4", server.node(previewId)?.name, "预览副本移过来后也要改名")
        sessionScope.cancel()
    }

    /**
     * 防的是多文件时片名在文件夹与文件上各写一遍：新建的文件夹取「番号 片名」，里面的文件只写番号与分段；
     * 所选里有两个番号时文件夹照原名，文件各自带片名。整包离线完成后产出文件夹改成同一个名字。
     */
    @Test
    fun `with canonical names on a pack is saved as a code-named folder of bare code files`() = smoke { scope ->
        val server = FakePikPakServer()
        val files = listOf(
            Triple("[site.net] ABC-123 某片名/abc00123hhb1.mp4", 900L shl 20, "GCIDA1"),
            Triple("[site.net] ABC-123 某片名/abc00123hhb2.mp4", 900L shl 20, "GCIDA2"),
            Triple("[site.net] ABC-123 某片名/abc00123hhb1.zh.srt", 40L shl 10, "GCIDAS"),
            Triple("[site.net] ABC-123 某片名/xyz-456 另一部.mp4", 800L shl 20, "GCIDX"),
        )
        server.indexMagnet(magnet, resourceListBody("[site.net] ABC-123 某片名", files))
        val rig = Rig(server, MemoryPreferences(), scope)
        scope.launch { rig.tracker.run("smoke@piko.dev") }
        val state = rig.sheet(scope, magnet)
        awaitUntil("解析完成、目标与余量确定") { state.resolution != null && state.target != null && state.remainingBytes != null }
        state.updateUseCanonicalNames(true)
        fun index(name: String) = state.items.indexOfFirst { it.file.path.endsWith(name) }

        state.items.indices.forEach { state.setItemSelected(it, true) }
        assertEquals("[site.net] ABC-123 某片名", state.folderNameToSave, "两个番号时文件夹照原名")
        assertEquals("ABC-123-CD1.mp4", state.nameToSave(index("hhb1.mp4")), "片名只在文件夹名里，文件名里本没有")
        assertEquals("XYZ-456 另一部.mp4", state.nameToSave(index("xyz-456 另一部.mp4")))

        state.setItemSelected(index("xyz-456 另一部.mp4"), false)
        assertEquals("ABC-123 某片名", state.folderNameToSave)
        assertEquals("ABC-123-CD1.mp4", state.nameToSave(index("hhb1.mp4")))
        assertEquals("ABC-123-CD2.mp4", state.nameToSave(index("hhb2.mp4")))
        assertEquals("ABC-123-CD1.zh.srt", state.nameToSave(index("hhb1.zh.srt")))

        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        assertEquals(SaveRoute.OFFLINE_PACK, state.savePlan?.route)
        state.saveSelection()
        assertIs<InstantSaveOutcome.OfflineTaskCreated>(outcome.await())
        val task = server.tasksSnapshot().single()
        server.completeTask(task.id, "[site.net] ABC-123 某片名", files.map { it.first.substringAfter('/') })
        awaitUntil("清理与改名完成", timeoutMs = 15_000) { rig.tracker.jobs.value.singleOrNull()?.stage == OfflinePackStage.DONE }
        assertEquals("ABC-123 某片名", server.node(rig.tracker.jobs.value.single().outputId)?.name)
    }

    /**
     * 防的是批量保存逐条查空间：两个整包各自放得下、合起来放不下时仍全部提交。
     * 也防未收录的链接被当成解析失败而挡住保存，以及移除的行照样被提交。
     */
    @Test
    fun `pasted links are checked for space together and saved by their own routes`() = smoke { scope ->
        val server = FakePikPakServer()
        // 一季约 1.7 GiB，剩 2 GiB：单个放得下，两个放不下
        server.quotaUsage = server.quotaLimit - (2L shl 30)
        val magnetB = "magnet:?xt=urn:btih:" + "b".repeat(40)
        val unindexed = "magnet:?xt=urn:btih:" + "c".repeat(40)
        server.indexMagnet(magnet, resourceListBody("Show S01", season))
        server.indexMagnet(magnetB, resourceListBody("Show S02", season))
        val rig = Rig(server, MemoryPreferences(), scope)
        scope.launch { rig.tracker.run("smoke@piko.dev") }
        val state = rig.sheet(scope, "第一季 $magnet\n第二季 $magnetB\n花絮 ${"c".repeat(40)}")
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }

        val batch = assertNotNull(state.batch)
        assertEquals(3, batch.rows.size)
        awaitUntil("各行解析完成、目标与余量确定") {
            batch.rows.none { it.status == InstantBatchRowStatus.RESOLVING } &&
                state.target != null && batch.remainingBytes != null
        }
        assertEquals(
            listOf(InstantBatchRowStatus.READY, InstantBatchRowStatus.READY, InstantBatchRowStatus.WHOLE_OFFLINE),
            batch.rows.map { it.status },
        )
        assertTrue(batch.lacksSpace)
        assertTrue(!batch.canSaveAll)

        batch.remove(batch.rows[1])
        assertTrue(batch.canSaveAll)
        batch.saveAll()
        val created = assertIs<InstantSaveOutcome.OfflineTaskCreated>(outcome.await())
        assertEquals(2, created.submittedCount)
        assertEquals(setOf(magnet, unindexed), server.tasksSnapshot().map { it.url }.toSet())
    }

    /**
     * 防的是解析带着 gcid、云端却没有内容（别人还在上传）时直接报「保存失败」：秒传只建出等上传的占位，
     * 单文件资源应改交离线任务，占位不能留在目录里，重试时也不该冒出「(1)」的副本。
     */
    @Test
    fun `a single file whose content is not held falls back to offline without leaving a placeholder`() = smoke { scope ->
        val server = FakePikPakServer()
        server.indexMagnet(magnet, resourceListBody("The.Citadel.rar", listOf(Triple("The.Citadel.rar", 700L shl 20, "GCIDPENDING"))))
        server.unheldHashes += "GCIDPENDING"
        val rig = Rig(server, MemoryPreferences(), scope)
        val state = rig.sheet(scope, magnet)
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }

        awaitUntil("解析完成且保存目标确定") { state.resolution != null && state.target != null }
        assertEquals(SaveRoute.INSTANT, state.savePlan?.route)

        state.saveSelection()
        assertIs<InstantSaveOutcome.OfflineTaskCreated>(outcome.await())
        assertEquals(magnet, server.tasksSnapshot().single().url)
        assertEquals(emptyList(), server.children(state.target!!.id).map { it.name }, "秒传留下的占位要删掉")
        assertNull(state.errorMessage, "改走离线成功了，不该再报保存失败")
    }

    /** 防的是外部分享进来的链接云端没收录时面板卡死：没有可点的出口。 */
    @Test
    fun `an unindexed magnet can still be submitted offline`() = smoke { scope ->
        val server = FakePikPakServer()
        val rig = Rig(server, MemoryPreferences(), scope)
        val state = rig.sheet(scope, magnet)
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }

        awaitUntil("解析结束并给出说明") { !state.isResolving && state.errorMessage != null && state.target != null }
        assertNull(state.resolution)

        state.submitOfflineTask()
        assertIs<InstantSaveOutcome.OfflineTaskCreated>(outcome.await())
        assertEquals(magnet, server.tasksSnapshot().single().url)
    }

    /**
     * 防的是外部带链接打开的会话换不了链接：输入框里填着那条链接，改成另一条就在同一个会话里重新解析，
     * 旧的解析结果与勾选不留在新链接上。
     */
    @Test
    fun `editing the link of an externally opened session resolves the new one in place`() = smoke { scope ->
        val server = FakePikPakServer()
        val other = "magnet:?xt=urn:btih:" + "b".repeat(40)
        server.indexMagnet(magnet, resourceListBody("Show S01", season))
        server.indexMagnet(other, resourceListBody("Movie", listOf(Triple("Movie.mkv", 2L shl 30, "GCIDMOVIE"))))
        val rig = Rig(server, MemoryPreferences(), scope)
        val session = InstantSession({ CoroutineScope(SupervisorJob() + Dispatchers.Default) }) { sessionScope, link -> rig.sheet(sessionScope, link) }
        session.start(magnet)
        val state = assertNotNull(session.state)
        assertEquals(magnet, state.input, "外部打开的链接填在输入框里")

        awaitUntil("第一条解析完成") { state.resolution?.resource?.name == "Show S01" }
        state.toggleItem(state.selectedIndices.first())
        state.updateInput(other)
        awaitUntil("换成第二条并解析完成") { state.resolution?.resource?.name == "Movie" }
        assertEquals(listOf("Movie.mkv"), state.selectedItems.map { it.file.name })
        assertTrue(!session.endNeedsConfirm, "换了链接，上一条挑过的勾选不再算数")
        assertTrue(session.state === state, "换链接不另开会话")
        session.end()
    }

    /**
     * × 先确认的条件：人亲手挑过、还勾着要保存的文件。只粘了链接、解析后的默认勾选、全部取消了的都直接结束，
     * 否则 × 几乎每次都要问，确认就成了点过就忘的一步。
     */
    @Test
    fun `discarding asks only when the user has picked files to save`() = smoke { scope ->
        val server = FakePikPakServer()
        server.indexMagnet(magnet, resourceListBody("Show S01", season))
        val rig = Rig(server, MemoryPreferences(), scope)
        val session = InstantSession({ CoroutineScope(SupervisorJob() + Dispatchers.Default) }) { sessionScope, link -> rig.sheet(sessionScope, link) }

        session.start(magnet)
        val state = assertNotNull(session.state)
        assertTrue(!session.endNeedsConfirm, "只粘了链接")
        awaitUntil("解析完成") { state.resolution != null }
        assertTrue(state.selectedIndices.isNotEmpty())
        assertTrue(!session.endNeedsConfirm, "默认勾选不算挑过")

        state.toggleItem(state.items.indexOfFirst { it.file.name == "info.nfo" })
        assertTrue(session.endNeedsConfirm, "勾了一项，还没保存")
        state.toggleSelectAll()
        state.toggleSelectAll()
        assertTrue(state.selectedIndices.isEmpty())
        assertTrue(!session.endNeedsConfirm, "全部取消后没有要保存的")
        session.end()
        assertTrue(!session.endNeedsConfirm)
    }

    @Test
    fun `collapsing the presentation preserves the draft and a new session opens independently`() = smoke { scope ->
        val server = FakePikPakServer()
        server.indexMagnet(magnet, resourceListBody("Show S01", season))
        val rig = Rig(server, MemoryPreferences(), scope)
        val session = InstantSession({ CoroutineScope(SupervisorJob() + Dispatchers.Default) }) { child, link -> rig.sheet(child, link) }
        val view = dev.piko.shared.presentation.AddLinkViewModel(session)
        view.open(magnet)
        val draft = assertNotNull(session.state)
        awaitUntil("解析完成") { draft.resolution != null }
        draft.toggleItem(draft.items.indexOfFirst { it.file.name == "info.nfo" })
        val selected = draft.selectedIndices.toSet()

        view.collapse()
        assertTrue(view.hasCollapsedWork)
        assertTrue(session.state === draft)
        view.resume()
        assertTrue(view.isExpanded)
        assertEquals(selected, draft.selectedIndices.toSet())

        view.collapse()
        session.end()
        assertTrue(!view.isExpanded)
        session.start(magnet)
        assertTrue(view.isExpanded, "外部打开的新会话不能继承上一会话的收起状态")
        assertTrue(session.state !== draft)
        view.discard()
        assertTrue(session.state == null)
    }

    @Test
    fun `collapsing a blank draft releases it without retaining presentation state`() = smoke { scope ->
        val rig = Rig(FakePikPakServer(), MemoryPreferences(), scope)
        val session = InstantSession({ CoroutineScope(SupervisorJob() + Dispatchers.Default) }) { child, link -> rig.sheet(child, link) }
        val view = dev.piko.shared.presentation.AddLinkViewModel(session)
        view.resume()
        assertTrue(view.isExpanded)
        view.collapse()
        assertTrue(session.state == null)
        assertTrue(!view.hasCollapsedWork)
        view.resume()
        assertTrue(view.isExpanded)
        view.discard()
    }

    @Test
    fun `saving survives a replacement request and completes without a screen subscriber`() = smoke { scope ->
        val server = FakePikPakServer()
        server.indexMagnet(magnet, resourceListBody("Movie", listOf(Triple("Movie.mkv", 1024L, "GCIDMOVIE"))))
        val started = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.toString().substringBefore('?').endsWith("/drive/v1/files")) {
                started.send(Unit)
                release.receive()
            }
            execute(request)
        }
        val client = PikPakClient(
            account = "smoke@piko.dev", password = "pw",
            sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
            httpClient = http,
        )
        val rig = Rig(server, MemoryPreferences(), scope, server.provider(client))
        val target = server.addFolder("目标")
        rig.openInDrive(target)
        val session = InstantSession({ CoroutineScope(SupervisorJob() + Dispatchers.Default) }) { child, link -> rig.sheet(child, link) }
        val view = dev.piko.shared.presentation.AddLinkViewModel(session)
        view.open(magnet)
        val draft = assertNotNull(session.state)
        awaitUntil("可以保存") { draft.canSaveSelection }
        draft.saveSelection()
        withTimeout(5_000) { started.receive() }

        view.open("magnet:?xt=urn:btih:" + "b".repeat(40))
        assertTrue(session.state === draft)
        assertNotNull(view.blockedAction)
        view.discard()
        assertTrue(session.state === draft)
        view.collapse()
        assertTrue(view.hasCollapsedWork)
        release.send(Unit)

        awaitUntil("没有页面订阅时仍完成") { session.state == null && session.pendingCompletions.value.isNotEmpty() }
        val completion = session.pendingCompletions.value.single()
        val outcome = assertIs<InstantSaveOutcome.InstantSaved>(completion.outcome)
        assertEquals(target.id, outcome.target.id)
        assertEquals(listOf("Movie.mkv"), server.children(target.id).map { it.name })
        session.acknowledgeCompletion(completion.id + 1)
        assertEquals(listOf(completion), session.pendingCompletions.value)
        session.acknowledgeCompletion(completion.id)
        assertTrue(session.pendingCompletions.value.isEmpty())
        session.clear()
    }

    @Test
    fun `closing one draft preserves its accepted save and another draft's fixed target`() = smoke { scope ->
        val server = FakePikPakServer()
        server.indexMagnet(magnet, resourceListBody("Movie", listOf(Triple("Movie.mkv", 1024L, "GCIDMOVIE"))))
        val firstFolder = server.addFolder("First")
        val secondFolder = server.addFolder("Second")
        val elsewhere = server.addFolder("Elsewhere")
        val started = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.toString().substringBefore('?').endsWith("/drive/v1/files")) {
                started.send(Unit)
                release.receive()
            }
            execute(request)
        }
        val client = PikPakClient(
            account = "smoke@piko.dev", password = "pw",
            sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
            httpClient = http,
        )
        val rig = Rig(server, MemoryPreferences(), scope, server.provider(client))
        val session = InstantSession(
            newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Default) },
            newState = { child, link -> rig.sheet(child, link) },
            newStateForDraft = { child, save, link, target -> rig.sheet(child, link, target, save) },
        )
        val first = session.openDraft(magnet, SaveTargetContext.fixed(PikoPathBreadcrumb(firstFolder.id, firstFolder.name)))
        val second = session.openDraft(magnet, SaveTargetContext.fixed(PikoPathBreadcrumb(secondFolder.id, secondFolder.name)))
        awaitUntil("两个草稿各有固定目标") {
            first.state.canSaveSelection && second.state.canSaveSelection &&
                first.state.target?.id == firstFolder.id && second.state.target?.id == secondFolder.id
        }
        rig.openInDrive(elsewhere)
        assertEquals(firstFolder.id, first.state.target?.id)
        assertEquals(secondFolder.id, second.state.target?.id)

        first.state.saveSelection()
        withTimeout(5_000) { started.receive() }
        assertEquals(listOf(first.id), session.executingSaves.map { it.draftId })
        assertTrue(session.executingSaves.single().trackedInstantAttempt)
        val firstView = dev.piko.shared.presentation.AddLinkViewModel(first)
        assertTrue(!firstView.discardNeedsConfirm, "accepted work continues after the editor closes")
        firstView.discard()
        assertTrue(first.isClosed)
        assertNull(firstView.state)
        assertEquals(listOf(second), session.drafts)
        assertEquals(listOf(first.id), session.executingSaves.map { it.draftId })
        assertTrue(second.state.canSaveSelection)
        release.send(Unit)

        awaitUntil("关闭后的保存产生独立回执") { session.pendingCompletions.value.any { it.id == first.id } }
        val outcome = assertIs<InstantSaveOutcome.InstantSaved>(session.pendingCompletions.value.single().outcome)
        assertEquals(firstFolder.id, outcome.target.id)
        assertEquals(listOf("Movie.mkv"), server.children(firstFolder.id).map { it.name })
        assertTrue(server.children(secondFolder.id).isEmpty())
        assertEquals(secondFolder.id, second.state.target?.id)
        awaitUntil("保存完成后不再算后台执行") { session.executingSaves.isEmpty() }
        session.clear()
    }

    @Test
    fun `closing a draft keeps an unknown save result available for inspection`() = smoke { scope ->
        val server = FakePikPakServer()
        server.indexMagnet(magnet, resourceListBody("Movie", listOf(Triple("Movie.mkv", 1024L, "GCIDMOVIE"))))
        val started = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.toString().substringBefore('?').endsWith("/drive/v1/files")) {
                started.send(Unit)
                release.receive()
                throw java.io.IOException("file acknowledgement lost")
            }
            response
        }
        val client = PikPakClient(
            account = "smoke@piko.dev", password = "pw",
            sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
            httpClient = http,
        )
        val rig = Rig(server, MemoryPreferences(), scope, server.provider(client))
        val session = InstantSession(
            newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Default) },
            newState = { child, link -> rig.sheet(child, link) },
            newStateForDraft = { child, save, link, target -> rig.sheet(child, link, target, save) },
        )
        val draft = session.openDraft(magnet, SaveTargetContext.fixed())
        awaitUntil("可以保存") { draft.state.canSaveSelection }
        draft.state.saveSelection()
        withTimeout(5_000) { started.receive() }
        draft.close()
        release.send(Unit)

        awaitUntil("关闭后保存回执仍可检查") {
            rig.saveRecords.attempts.value.any { it.receipt?.requiresInspection == true && !it.isRunning }
        }
        assertTrue(session.pendingCompletions.value.isEmpty())
        assertEquals(listOf("Movie.mkv"), server.children("").filter { it.name == "Movie.mkv" }.map { it.name })
        session.clear()
    }

    @Test
    fun `account leave clears a closed draft's executing save projection`() = smoke { scope ->
        val server = FakePikPakServer()
        server.indexMagnet(magnet, resourceListBody("Movie", listOf(Triple("Movie.mkv", 1024L, "GCIDMOVIE"))))
        val started = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.toString().substringBefore('?').endsWith("/drive/v1/files")) {
                started.send(Unit)
                release.receive()
            }
            execute(request)
        }
        val client = PikPakClient(
            account = "smoke@piko.dev", password = "pw",
            sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
            httpClient = http,
        )
        val rig = Rig(server, MemoryPreferences(), scope, server.provider(client))
        val session = InstantSession(
            newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Default) },
            newState = { child, link -> rig.sheet(child, link) },
            newStateForDraft = { child, save, link, target -> rig.sheet(child, link, target, save) },
        )
        val draft = session.openDraft(magnet, SaveTargetContext.fixed())
        awaitUntil("可以保存") { draft.state.canSaveSelection }
        draft.state.saveSelection()
        withTimeout(5_000) { started.receive() }
        draft.close()
        assertEquals(listOf(draft.id), session.executingSaves.map { it.draftId })
        session.clear()
        assertTrue(session.executingSaves.isEmpty())
        assertTrue(session.pendingCompletions.value.isEmpty())
    }

    /**
     * 防的是存进用户没在看的目录：默认目标是网盘页的当前目录；面板收起着留在后台时
     * 用户换了目录，目标跟着换；在面板里另选之后就不再跟。
     */
    @Test
    fun `the save target follows the drive folder until the user picks one`() = smoke { scope ->
        val server = FakePikPakServer()
        val shows = server.addFolder("Shows")
        val movies = server.addFolder("Movies")
        val rig = Rig(server, MemoryPreferences(), scope)
        rig.openInDrive(shows)
        val state = rig.sheet(scope, "")

        awaitUntil("保存目标确定") { state.target != null }
        assertEquals(shows.id, state.target?.id)
        assertNull(state.targetNotice)

        rig.openInDrive(movies)
        awaitUntil("目标跟随网盘页换目录") { state.target?.id == movies.id }

        state.changeTarget(PikoDriveRepository.ROOT_BREADCRUMB)
        rig.openInDrive(shows)
        delay(300)
        assertEquals("", state.target?.id, "另选过之后不再跟随网盘页")
    }

    /** 库、查重与压缩包的 ID 是虚拟的，曾被当作已删除的目录，改存 My Packs 并提示「当前目录已不存在」。 */
    @Test
    fun `a virtual place saves into the nearest real folder without a notice`() = smoke { scope ->
        val server = FakePikPakServer()
        val shows = server.addFolder("Shows")
        val rig = Rig(server, MemoryPreferences(), scope)
        val showsCrumb = PikoPathBreadcrumb(shows.id, shows.name)
        val archiveTop = ArchiveLocation("ARCHIVE01", "GCIDA1", "")
        val archiveSub = ArchiveLocation("ARCHIVE01", "GCIDA1", "Disc 1/")
        val cases = listOf(
            listOf(DriveLibrary.STARRED.crumb) to "",
            listOf(DriveLibrary.DUPLICATES.crumb) to "",
            listOf(
                PikoDriveRepository.ROOT_BREADCRUMB,
                showsCrumb,
                PikoPathBreadcrumb(archiveTop.id, "pack.zip"),
                PikoPathBreadcrumb(archiveSub.id, "Disc 1"),
            ) to shows.id,
        )
        for ((stack, expected) in cases) {
            rig.workspace.updateFolderStack(stack)
            val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
            val state = rig.sheet(sessionScope, "")
            awaitUntil("保存目标确定：${stack.last().name}") { state.target != null }
            assertEquals(expected, state.target?.id, "停在「${stack.last().name}」时的保存目标")
            assertNull(state.targetNotice, "停在「${stack.last().name}」时不该提示目录已不存在")
            sessionScope.cancel()
        }
    }
}
