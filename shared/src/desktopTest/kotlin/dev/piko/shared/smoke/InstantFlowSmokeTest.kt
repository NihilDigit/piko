package dev.piko.shared.smoke

import dev.piko.data.auth.InstantTarget
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.SaveRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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

        fun sheet(scope: CoroutineScope, magnet: String) =
            InstantSheetState(instantRepo, driveRepo, prefs, previewFolder, tracker, scope, magnet)
    }

    /**
     * 防的是整包离线的后半程断掉：记住的目标进了回收站仍被当成可用、提交后没人跟踪、
     * 完成后没删未选的文件、产出文件夹没改成面板里填的名字、跟踪记录没落盘。
     */
    @Test
    fun `a multi-file selection goes offline whole, then is pruned and renamed`() = smoke { scope ->
        val server = FakePikPakServer()
        val trashed = server.addFolder("旧目标", trashed = true)
        server.indexMagnet(magnet, resourceListBody("Show S01", season))
        val rig = Rig(server, MemoryPreferences(InstantTarget(trashed.id, trashed.name)), scope)
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
        val saved = assertIs<InstantSaveOutcome.InstantSaved>(outcome.await())
        assertEquals("Show S01", saved.target.name)
        assertEquals(setOf("E01.mkv", "E02.mkv", "sample/sample.mkv"), server.tree(saved.target.id).toSet())
        assertEquals(emptyList(), server.tasksSnapshot())
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

        sessionScope.cancel()
        awaitUntil("Piko-Temp 被删除") { server.node(tempFolder.id) == null }
        assertNotNull(server.node(previewId), "已保存的文件不能随 Piko-Temp 一起删掉")
    }

    /** 防的是外部分享进来的链接云端没收录时面板卡死：输入框收起、没有可点的出口。 */
    @Test
    fun `an unindexed magnet reopens the input and can still be submitted offline`() = smoke { scope ->
        val server = FakePikPakServer()
        val rig = Rig(server, MemoryPreferences(), scope)
        val state = rig.sheet(scope, magnet)
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        assertTrue(!state.isInputVisible, "外部唤起时输入框先收起")

        awaitUntil("解析结束并给出说明") { !state.isResolving && state.errorMessage != null && state.target != null }
        assertTrue(state.isInputVisible)
        assertNull(state.resolution)

        state.submitOfflineTask()
        assertIs<InstantSaveOutcome.OfflineTaskCreated>(outcome.await())
        assertEquals(magnet, server.tasksSnapshot().single().url)
    }
}
