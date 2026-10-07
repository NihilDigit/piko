package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.scrape.MetaTubeService
import dev.piko.shared.state.InstantBatchRowStatus
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.InstantTitleFill
import dev.piko.shared.state.launchOnAccountLeave
import io.github.nihildigit.pikpak.PikPakClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 添加链接按番号规范命名时先存后补：保存不等 MetaTube，片名随后查到再把这次存下的改成带片名的规范名，记一条可撤销的改动。
 * MetaTube 由 MockEngine 顶替，搜索在 [gate] 打开前一直挂着，模拟外部站点慢。
 *
 * 这条功能尚未接线（应用里 PikoServices 不把 InstantTitleFill 交给面板），这里直接交给面板测。
 * 本测试通过而实机不生效，见 docs/development/av-naming.md 末节。
 */
class InstantTitleFillSmokeTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
    private val gate = CompletableDeferred<Unit>()
    private val searches = AtomicInteger()

    private val metaTube = MetaTubeService(
        MemoryPreferences().apply { metaTubeUrlFlow.value = "http://metatube.test"; metaTubeTokenFlow.value = "secret" },
    ) {
        HttpClient(
            MockEngine { request ->
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                if (request.url.encodedPath != "/v1/movies/search") return@MockEngine respond("{}", HttpStatusCode.NotFound, json)
                searches.incrementAndGet()
                gate.await()
                when (request.url.parameters["q"]) {
                    "ABC-123" -> respond("""{"data":[{"id":"a1","number":"ABC-123","title":"查到的片名","provider":"A"}]}""", headers = json)
                    "XYZ-456" -> respond("""{"data":[{"id":"x1","number":"XYZ-456","title":"另一部片名","provider":"A"}]}""", headers = json)
                    else -> respond("""{"error":{"code":404,"message":"info not found"}}""", HttpStatusCode.NotFound, json)
                }
            },
        )
    }

    private class Rig(
        val server: FakePikPakServer,
        provider: PikoClientProvider,
        metaTube: MetaTubeService,
        scope: CoroutineScope,
    ) {
        val prefs = MemoryPreferences()
        val instantRepo = InstantMagnetRepository(provider)
        val driveRepo = PikoDriveRepository(provider, prefs)
        val fill = InstantTitleFill(provider, driveRepo, metaTube, scope)
        private val tracker = OfflinePackTracker(instantRepo, driveRepo, prefs)
        private val previewFolder = PreviewTempFolder(provider, driveRepo, instantRepo, scope)
        private val saveRecords = InstantSaveRecords(provider, null, scope)

        init {
            // 面板在 snapshotFlow 里等开关与解析结果再查片名。应用里由 Compose 的 GlobalSnapshotManager 发出快照改动，测试里没有
            scope.launch {
                while (true) {
                    Snapshot.sendApplyNotifications()
                    delay(20)
                }
            }
        }

        /** 打开规范命名的面板。开关对整个会话生效，批量时各行同样开着。 */
        fun sheet(scope: CoroutineScope, input: String) =
            InstantSheetState(instantRepo, driveRepo, prefs, previewFolder, tracker, saveRecords, scope, input, fill)
                .apply { updateUseCanonicalNames(true) }

        /** 存下一条单文件的链接，存完会话随即结束，与面板保存成功后一样。返回目标目录。 */
        suspend fun saveAndEnd(scope: CoroutineScope, input: String): String {
            val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val state = sheet(sessionScope, input)
            val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
            awaitUntil("解析完成且可以保存") { state.primaryAction?.enabled == true }
            state.saveSelection()
            assertIs<InstantSaveOutcome.InstantSaved>(outcome.await())
            sessionScope.cancel()
            return state.target!!.id
        }

        fun names(folderId: String) = server.children(folderId).map { it.name }

        /** 改动日志在自己的协程里记下，改名做完时未必已记上。 */
        suspend fun recordedRename(): DriveChangeJournal.Change.Rename {
            awaitUntil("记下改动") { driveRepo.changes.latest.value != null }
            return assertIs<DriveChangeJournal.Change.Rename>(driveRepo.changes.latest.value)
        }
    }

    private fun single(server: FakePikPakServer, name: String, gcid: String = "GCIDAV", link: String = magnet) =
        server.indexMagnet(link, resourceListBody(name, listOf(Triple(name, 900L shl 20, gcid))))

    /**
     * 防的是保存等片名、或片名到了只改面板不改网盘：保存时片名未到，照原名里的片名落盘；会话结束后片名才到，
     * 仍要改成带 MetaTube 片名的规范名，记一条可撤销的改动。查询已在进行时等同一次，不另查。
     */
    @Test
    fun `a title that arrives after the save renames the saved file and can be undone`() = smoke { scope ->
        val server = FakePikPakServer()
        single(server, "[xxx.com]ABC-123 原名里的片名.mp4")
        val rig = Rig(server, server.provider(), metaTube, scope)
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = rig.sheet(sessionScope, magnet)
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        awaitUntil("可以保存、片名查询已发出") { state.primaryAction?.enabled == true && searches.get() == 1 }
        assertEquals("ABC-123 原名里的片名.mp4", state.nameToSave(0))

        state.saveSelection()
        assertIs<InstantSaveOutcome.InstantSaved>(outcome.await())
        sessionScope.cancel()
        val target = state.target!!.id
        assertEquals(listOf("ABC-123 原名里的片名.mp4"), rig.names(target), "保存不等片名")
        assertNull(rig.driveRepo.changes.latest.value)

        gate.complete(Unit)
        awaitUntil("片名补上") { rig.names(target) == listOf("ABC-123 查到的片名.mp4") }
        assertEquals(1, searches.get(), "保存后等的是面板发起的那一次查询")
        val change = rig.recordedRename()
        assertTrue("MetaTube" in change.summary)

        rig.driveRepo.changes.undo(change)
        awaitUntil("撤销后回到保存时的名字") { rig.names(target) == listOf("ABC-123 原名里的片名.mp4") }
    }

    /** 防的是补名覆盖用户的改动：保存后、片名到之前用户已改过名的，名字留着。 */
    @Test
    fun `a file renamed by the user before the title arrives is left alone`() = smoke { scope ->
        val server = FakePikPakServer()
        single(server, "[xxx.com]ABC-123 原名里的片名.mp4")
        val rig = Rig(server, server.provider(), metaTube, scope)
        val target = rig.saveAndEnd(scope, magnet)
        val saved = server.children(target).single()
        rig.driveRepo.rename(saved.id, "我自己起的名字.mp4").getOrThrow()

        gate.complete(Unit)
        rig.fill.awaitIdle()
        assertEquals(1, searches.get())
        assertEquals(listOf("我自己起的名字.mp4"), rig.names(target))
        assertNull(rig.driveRepo.changes.latest.value, "没改成什么就不记改动")
    }

    /**
     * 防的是把 A 的补名做到 B 上，或换号后又切回 A 时补名照旧进行：换号即放弃上一个账号的补名。
     * 与 PikoServices 一样经 launchOnAccountLeave 结束。
     */
    @Test
    fun `switching accounts drops the pending title fill`() = smoke { scope ->
        val serverA = FakePikPakServer("A")
        val serverB = FakePikPakServer("B")
        single(serverA, "[xxx.com]ABC-123 原名里的片名.mp4")
        val a = serverA.client(account = "a@piko.dev")
        val accounts = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(a)
        }
        val rig = Rig(serverA, accounts, metaTube, scope)
        val left = AtomicInteger()
        scope.launchOnAccountLeave(accounts) {
            rig.fill.endAccount()
            left.incrementAndGet()
        }
        val target = rig.saveAndEnd(scope, magnet)

        accounts.currentClient.value = serverB.client(account = "b@piko.dev")
        awaitUntil("甲的会话结束") { left.get() == 1 }
        accounts.currentClient.value = a
        awaitUntil("切回甲") { rig.fill.currentAccount() == "a@piko.dev" }
        gate.complete(Unit)
        rig.fill.awaitIdle()
        assertEquals(listOf("ABC-123 原名里的片名.mp4"), rig.names(target), "换号前的补名在换号后仍然做了")
        assertTrue(serverB.children("").isEmpty())
        assertNull(rig.driveRepo.changes.latest.value)
    }

    /**
     * 防的是按番号命名的新建文件夹漏补，或片名补到文件上、与文件夹各写一遍：文件夹换成 MetaTube 片名，
     * 里面只写番号的文件不动。
     */
    @Test
    fun `a folder named by its code gets the title and the bare files inside stay`() = smoke { scope ->
        val server = FakePikPakServer()
        val files = listOf(
            Triple("abc00123hhb1.mp4", 900L shl 20, "GCIDA1"),
            Triple("abc00123hhb2.mp4", 900L shl 20, "GCIDA2"),
            Triple("info.nfo", 2048L, ""),
        )
        server.indexMagnet(magnet, resourceListBody("ABC-123 原名里的片名", files))
        // 整包放不下，只秒传所选的两段
        server.quotaUsage = server.quotaLimit - (1800L shl 20) - 1024
        val rig = Rig(server, server.provider(), metaTube, scope)
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = rig.sheet(sessionScope, magnet)
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        awaitUntil("解析完成、目标与余量确定") { state.target != null && state.savePlan?.fallback != null }
        assertEquals("ABC-123 原名里的片名", state.folderNameToSave)
        state.saveSelectionInstantly()
        val saved = assertIs<InstantSaveOutcome.InstantSaved>(outcome.await())
        sessionScope.cancel()
        val target = state.target!!.id
        assertEquals(listOf("ABC-123 原名里的片名"), rig.names(target))
        assertEquals(setOf("ABC-123-CD1.mp4", "ABC-123-CD2.mp4"), rig.names(saved.target.id).toSet())

        gate.complete(Unit)
        awaitUntil("文件夹补上片名") { rig.names(target) == listOf("ABC-123 查到的片名") }
        rig.fill.awaitIdle()
        assertEquals(setOf("ABC-123-CD1.mp4", "ABC-123-CD2.mp4"), rig.names(saved.target.id).toSet())
        val change = rig.recordedRename()
        assertEquals(listOf(saved.target.id), change.renames.map { it.id })
    }

    /** 批量添加链接各行的补名合成一条改动：一次保存只弹一次提示，撤销一步全部撤回。 */
    @Test
    fun `pasted links get their titles as one undoable change`() = smoke { scope ->
        val server = FakePikPakServer()
        val other = "magnet:?xt=urn:btih:" + "b".repeat(40)
        single(server, "[xxx.com]ABC-123 原名里的片名.mp4")
        single(server, "[xxx.com]XYZ-456 另一个原名.mp4", gcid = "GCIDXYZ", link = other)
        val rig = Rig(server, server.provider(), metaTube, scope)
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = rig.sheet(sessionScope, "$magnet\n$other")
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        val batch = assertNotNull(state.batch)
        awaitUntil("各行解析完成") { batch.rows.all { it.status == InstantBatchRowStatus.READY } && batch.canSaveAll }
        batch.saveAll()
        assertIs<InstantSaveOutcome.InstantSaved>(outcome.await())
        sessionScope.cancel()
        val target = state.target!!.id
        assertEquals(setOf("ABC-123 原名里的片名.mp4", "XYZ-456 另一个原名.mp4"), rig.names(target).toSet())

        gate.complete(Unit)
        awaitUntil("两行都补上片名") { rig.names(target).toSet() == setOf("ABC-123 查到的片名.mp4", "XYZ-456 另一部片名.mp4") }
        val change = rig.recordedRename()
        assertEquals(2, change.renames.size)
    }
}
