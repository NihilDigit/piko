package dev.piko.shared.smoke

import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.DriveLibrary
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 秒传工作台从解析到落盘的完整流程。两端都只剩布局，这里坏了两端一起坏。
 */
class InstantFlowSmokeTest {
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
        awaitUntil("临时预览副本已清理") { server.node(folder.id) == null }
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

    private class Rig(val server: FakePikPakServer, val prefs: MemoryPreferences, backgroundScope: CoroutineScope) {
        private val provider = server.provider()
        val instantRepo = InstantMagnetRepository(provider)
        val driveRepo = PikoDriveRepository(provider, prefs)
        val tracker = OfflinePackTracker(instantRepo, driveRepo, prefs)
        val previewFolder = PreviewTempFolder(driveRepo, instantRepo, backgroundScope)
        val saveRecords = InstantSaveRecords(provider, null, backgroundScope)

        fun sheet(scope: CoroutineScope, magnet: String) =
            InstantSheetState(instantRepo, driveRepo, prefs, previewFolder, tracker, saveRecords, scope, magnet)

        /** 网盘页停在 [folder] 里，与用户点进去之后的栈相同。 */
        fun openInDrive(folder: FakePikPakServer.Node) {
            driveRepo.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(folder.id, folder.name)))
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
    fun `a previewed episode is moved on save and Piko-Temp goes with the session`() = smoke { scope ->
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
        awaitUntil("Piko-Temp 被删除") { server.node(tempFolder.id) == null }
        assertNotNull(server.node(previewId), "已保存的文件不能随 Piko-Temp 一起删掉")
    }

    /**
     * 防的是开着按番号规范命名时只有面板上改了名：秒传要带规范名，移用的预览副本移过来后另改名，
     * 字幕跟着视频换主干。开关默认关着，关着时原名照存。
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
        assertEquals(false, state.useCanonicalNames)
        assertTrue(state.offersCanonicalNames)
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
     * 防的是设置开着时面板仍按关着处理，以及多文件时片名在文件夹与文件上各写一遍：新建的文件夹取「番号 片名」，
     * 里面的文件只写番号与分段；所选里有两个番号时文件夹照原名，文件各自带片名。整包离线完成后产出文件夹改成同一个名字。
     */
    @Test
    fun `with the setting on a pack is saved as a code-named folder of bare code files`() = smoke { scope ->
        val server = FakePikPakServer()
        val files = listOf(
            Triple("[site.net] ABC-123 某片名/abc00123hhb1.mp4", 900L shl 20, "GCIDA1"),
            Triple("[site.net] ABC-123 某片名/abc00123hhb2.mp4", 900L shl 20, "GCIDA2"),
            Triple("[site.net] ABC-123 某片名/abc00123hhb1.zh.srt", 40L shl 10, "GCIDAS"),
            Triple("[site.net] ABC-123 某片名/xyz-456 另一部.mp4", 800L shl 20, "GCIDX"),
        )
        server.indexMagnet(magnet, resourceListBody("[site.net] ABC-123 某片名", files))
        val rig = Rig(server, MemoryPreferences().apply { autoCanonicalNamesFlow.value = true }, scope)
        scope.launch { rig.tracker.run("smoke@piko.dev") }
        val state = rig.sheet(scope, magnet)
        awaitUntil("解析完成、目标与余量确定") { state.resolution != null && state.target != null && state.remainingBytes != null }
        assertTrue(state.useCanonicalNames, "开关的初值取设置")
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
            rig.driveRepo.updateFolderStack(stack)
            val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
            val state = rig.sheet(sessionScope, "")
            awaitUntil("保存目标确定：${stack.last().name}") { state.target != null }
            assertEquals(expected, state.target?.id, "停在「${stack.last().name}」时的保存目标")
            assertNull(state.targetNotice, "停在「${stack.last().name}」时不该提示目录已不存在")
            sessionScope.cancel()
        }
    }
}
