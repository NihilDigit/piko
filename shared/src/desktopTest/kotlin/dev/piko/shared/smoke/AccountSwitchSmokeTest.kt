package dev.piko.shared.smoke

import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.state.ClipFeedSession
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.launchOnAccountLeave
import dev.piko.shared.sync.DriveSettingsStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * 换号时上一个账号的东西不能漏到下一个账号：两个账号各一个假服务端，ID 前缀不同，
 * 进程级的会话、仓库与缓存目录照两端入口那样共用一份，结束会话走与 PikoServices 同一个 launchOnAccountLeave。
 *
 * 只覆盖 shared 里的状态。挂起的信息流（继续刷、临时标签）、目录图的展开与过滤都记在主界面的组合里，
 * 换号时主界面整个重建（PikoApp 按账号区分根状态），不在这里测。
 */
class AccountSwitchSmokeTest {
    private class Accounts(val a: PikPakClient, val b: PikPakClient) : PikoClientProvider {
        override val currentClient = MutableStateFlow<PikPakClient?>(a)
    }

    private val magnetA = "magnet:?xt=urn:btih:" + "e".repeat(40) + "&dn=Jia"

    /** 主线程只有一条：会话的 Compose 状态在上面改，与两端一致。 */
    private fun onMainThread(block: suspend CoroutineScope.(main: CoroutineScope) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val main = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            runBlocking { withTimeout(30_000) { withContext(dispatcher) { block(main) } } }
        } finally {
            main.cancel()
            dispatcher.close()
            executor.shutdown()
        }
    }

    @Test
    fun `switching accounts leaves nothing of the previous one behind and switching back restores its own`() = onMainThread { main ->
        val serverA = FakePikPakServer("A")
        val serverB = FakePikPakServer("B")
        (1..6).forEach { serverA.addVideo("甲-$it.mkv") }
        val kept = serverA.addFolder("甲收藏")
        serverA.addVideo("甲收藏-1.mkv", kept.id)
        serverA.addFile("甲源.mkv", kept.id, content = ByteArray(4096), hash = "GSHARED")
        serverA.indexMagnet(magnetA, resourceListBody("甲源.mkv", listOf(Triple("甲源.mkv", 4096L, "GSHARED"))))
        (1..6).forEach { serverB.addVideo("乙-$it.mkv") }
        // 同一份内容也在乙的网盘里：甲的来源账本不能补到它身上
        serverB.addFile("乙同内容.mkv", content = ByteArray(4096), hash = "GSHARED")

        val accounts = Accounts(serverA.client(account = "a@piko.dev"), serverB.client(account = "b@piko.dev"))
        val cache = MemoryCacheStore()
        val prefs = MemoryPreferences()
        val driveRepo = PikoDriveRepository(accounts, prefs, cache)
        val instantRepo = InstantMagnetRepository(accounts)
        val previewFolder = PreviewTempFolder(accounts, driveRepo, instantRepo, main)
        val feed = ClipFeedSession(accounts, driveRepo, PikoMediaRepository(accounts), cache, main)
        val instant = InstantSession(
            newScope = { CoroutineScope(main.coroutineContext.minusKey(Job) + SupervisorJob()) },
            newState = { scope, magnet ->
                InstantSheetState(
                    instantRepo, driveRepo, prefs, previewFolder, OfflinePackTracker(instantRepo, driveRepo, prefs),
                    InstantSaveRecords(accounts, null, main), scope, magnet,
                )
            },
        )
        main.launchOnAccountLeave(accounts) { left ->
            instant.end()
            feed.endAccount(left)
        }
        suspend fun names(folderId: String) = driveRepo.listBrowsable(folderId, PikoFileSortOrder.NAME_ASC).getOrThrow()
        fun feedNames() = (feed.clips + feed.upcoming).map { it.name }
        fun indexedNames() = driveRepo.folderIndex.flow.value.nodes.values.map { it.name }

        // 甲：信息流开着（跳去网盘看文件时的挂起也是这个样子：队列留着、会话不关），添加链接解析完摆着，账本里记着来源
        feed.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        awaitUntil("甲的信息流排满") { feed.upcoming.size >= 7 && !feed.isCollecting }
        instant.start(magnetA)
        awaitUntil("甲的链接解析完") { instant.state?.resolution != null }
        driveRepo.sourceLedger.record(magnetA, listOf(ResolvedFile(path = "甲源.mkv", size = 4096, gcid = "GSHARED")))
        assertEquals(magnetA, names(kept.id).single { it.name == "甲源.mkv" }.sourceUrl)
        val tempA = previewFolder.folderId().getOrThrow()
        assertTrue(tempA.startsWith("A"))
        assertTrue("甲收藏" in indexedNames())

        // 换到乙
        accounts.currentClient.value = accounts.b
        awaitUntil("甲的会话结束") { feed.root == null && instant.state == null }
        awaitUntil("文件夹索引换成乙的") { "甲收藏" !in indexedNames() }
        assertFalse(feed.reopensFor("b@piko.dev"), "乙进来时不该照甲留下的开关自动打开信息流")
        assertNull(names("").single { it.name == "乙同内容.mkv" }.sourceUrl, "甲的来源补到了乙的文件上")
        assertTrue(previewFolder.folderId().getOrThrow().startsWith("B"), "乙借对象用的是甲的 Piko-Temp")
        feed.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        awaitUntil("乙的信息流排满") { feed.upcoming.size >= 6 && !feed.isCollecting }
        delay(200)
        assertTrue(feedNames().none { it.startsWith("甲") }, "乙的信息流里有甲的视频：${feedNames()}")
        assertTrue("甲收藏" !in indexedNames(), "甲存下的目录名进了乙的文件夹索引")

        // 切回甲：甲自己存下的照样回来，乙的不跟过来
        accounts.currentClient.value = accounts.a
        awaitUntil("乙的信息流结束") { feed.root == null }
        feed.open(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        assertTrue(feedNames().isNotEmpty() && feedNames().all { it.startsWith("甲") }, "切回甲后接着放的不是甲存下的队列：${feedNames()}")
        awaitUntil("甲的账本从磁盘读回") {
            driveRepo.sourceLedger.flow.value.isNotEmpty()
        }
        assertEquals(magnetA, names(kept.id).single { it.name == "甲源.mkv" }.sourceUrl)
    }

    /**
     * 防的是同步做到一半换了号：网盘读写走仓库眼前的账号，没有核对时，旧账号的 .piko 被记成新账号根目录里的那个，
     * 旧账号的同步文件推进新账号的网盘。
     */
    @Test
    fun `a sync caught by an account switch writes nothing into the new account`() = onMainThread { main ->
        val serverA = FakePikPakServer("A")
        val serverB = FakePikPakServer("B")
        val accounts = Accounts(serverA.client(account = "a@piko.dev"), serverB.client(account = "b@piko.dev"))
        val driveRepo = PikoDriveRepository(accounts, MemoryPreferences())
        val store = DriveSettingsStore(accounts, driveRepo, "sources-")
        serverA.apiDelayMs = 500
        val write = main.async { runCatching { store.write("a@piko.dev", "{}", 1L) } }
        delay(200)
        accounts.currentClient.value = accounts.b
        assertTrue(write.await().isFailure)
        assertTrue(serverB.tree("").isEmpty() && serverB.children("").isEmpty(), "甲的同步写进了乙的网盘：${serverB.children("").map { it.name }}")
    }
}
