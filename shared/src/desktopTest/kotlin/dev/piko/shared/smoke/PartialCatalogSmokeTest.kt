package dev.piko.shared.smoke

import dev.piko.shared.data.*
import dev.piko.shared.state.*
import io.github.nihildigit.pikpak.DriveTask
import io.github.nihildigit.pikpak.TaskPhase
import kotlin.test.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

class PartialCatalogSmokeTest {
    private val magnet = "magnet:?xt=urn:btih:" + "e".repeat(40)
    private val files = listOf(Triple("保留.mkv", 100L, "A".repeat(40)), Triple("其他.mkv", 200L, ""))
    private fun catalog(partial: Boolean) = resourceListBody("合集", files).let {
        if (partial) it.replace("\"page_size\":500", "\"page_size\":500,\"next_page_token\":\"NEXT\"") else it
    }

    @Test
    fun aPartialCatalogCannotSubmitAnOfflinePackButCanSaveKnownReferences() = smoke { scope ->
        val server = FakePikPakServer()
        server.indexMagnet(magnet, catalog(partial = true))
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val repo = InstantMagnetRepository(provider)
        val drive = PikoDriveRepository(provider, prefs)
        val state = InstantSheetState(repo, drive, prefs, PreviewTempFolder(provider, drive, repo, scope),
            OfflinePackTracker(repo, drive, prefs), InstantSaveRecords(provider, null, scope), scope, magnet,
            SaveTargetContext.fixed())
        awaitUntil("解析部分清单") { state.resolution != null && state.target != null }
        state.setSourceSelected(setOf(0, 1), true)
        assertTrue(assertNotNull(state.savePlan).blocked)
        state.saveSelection()
        assertTrue(server.tasksSnapshot().isEmpty())
        state.setSourceSelected(setOf(1), false)
        assertEquals(SaveRoute.INSTANT, state.savePlan?.route)
        state.updateSaveAsReference(true)
        assertTrue(state.canSaveSelection)
    }

    @Test
    fun cleanupRevalidatesCatalogsBeforeDeletingIncludingPreviouslyPersistedJobs() = smoke { _ ->
        val server = FakePikPakServer()
        val root = server.addFolder("合集")
        server.addFile("保留.mkv", root.id)
        server.addFile("其他.mkv", root.id)
        server.addFile("未列出.mkv", root.id)
        val repo = InstantMagnetRepository(server.provider())
        val task = DriveTask(id = "old-job", phase = TaskPhase.COMPLETE, fileId = root.id)
        server.indexMagnet(magnet, catalog(partial = true))
        assertTrue(repo.pruneOfflineOutput(task, setOf("保留.mkv"), magnet).isFailure)
        assertEquals(setOf("保留.mkv", "其他.mkv", "未列出.mkv"), server.tree(root.id).toSet())
        assertEquals(0, server.count("POST /drive/v1/files:batchDelete"))
        server.indexMagnet(magnet, catalog(partial = false))
        assertTrue(repo.pruneOfflineOutput(task, setOf("不存在.mkv"), magnet).isFailure)
        assertEquals(3, server.tree(root.id).size)
        assertTrue(repo.pruneOfflineOutput(task, setOf("保留.mkv"), magnet).isSuccess)
        assertEquals(listOf("保留.mkv"), server.tree(root.id))
    }

    @Test
    fun aRestoredPartialJobStopsWithAnExplanationAndCanRetryAfterTheCatalogIsComplete() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val account = provider.currentClient.value!!.account
        val repo = InstantMagnetRepository(provider)
        val prefs = MemoryPreferences()
        val drive = PikoDriveRepository(provider, prefs)
        val task = assertIs<io.github.nihildigit.pikpak.CreateUrlResult.Queued>(repo.enqueueOfflineTask(magnet).getOrThrow()).task
        server.completeTask(task.id, "合集", listOf("保留.mkv", "其他.mkv"))
        val output = server.tasksSnapshot().single().fileId
        val restored = OfflinePackJob(account, task.id, magnet, "", "合集", setOf("保留.mkv"), 2, 300,
            kotlin.time.Clock.System.now().toEpochMilliseconds(), stage = OfflinePackStage.PRUNING)
        prefs.saveOfflinePacks(kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(OfflinePackJob.serializer()), listOf(restored)))
        server.indexMagnet(magnet, catalog(partial = true))
        val tracker = OfflinePackTracker(repo, drive, prefs)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { tracker.run(account) }
        awaitUntil("不完整清单停止裁剪") { tracker.jobs.value.singleOrNull()?.stage == OfflinePackStage.FAILED }
        val blocked = tracker.jobs.value.single()
        assertTrue(blocked.cleanupFailed)
        assertTrue(blocked.message.contains("清单不完整"))
        assertEquals(2, server.tree(output).size)
        awaitUntil("清理处理已退出") { tracker.busy.value.isEmpty() }
        server.indexMagnet(magnet, catalog(partial = false))
        tracker.retry(task.id).getOrThrow()
        awaitUntil("完整清单重试成功") { tracker.jobs.value.single().stage == OfflinePackStage.DONE }
        assertEquals(listOf("保留.mkv"), server.tree(output))
    }
}
