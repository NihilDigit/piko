package dev.piko.shared.media.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.testing.FakePikPakCloud
import dev.piko.shared.media.testing.RecordedPositions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 共用取流策略的行为冒烟。两端的播放器都跑这同一份策略，所以这里一次覆盖两端：
 * 续播、放到末尾归零、本地优先、开播失败的换源顺序、播放中断的退避重连。
 *
 * 后端是假的，只记录被要求打开什么、从哪里开始，并按脚本回事件；仓库、代理与
 * SDK 是真的，云端是 [FakePikPakCloud]。
 */
class PlayerScreenStateSmokeTest {
    private val stateThread = Executors.newSingleThreadExecutor { Thread(it, "player-state") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + stateThread)
    private val positions = RecordedPositions()
    private val cloud = FakePikPakCloud(
        origin = FakePikPakCloud.payload(1024 * 1024),
        transcode = FakePikPakCloud.payload(512 * 1024),
    )
    private val repository = PikoMediaRepository(cloud.provider, positions.preferences)
    private val backend = FakeBackend()

    @AfterTest
    fun tearDown() {
        scope.coroutineContext.job.cancel()
        stateThread.close()
    }

    private suspend fun newState(resolveLocalPath: suspend (String, String?) -> String? = { _, _ -> null }) =
        withContext(stateThread) {
            PlayerScreenState(
                repository = repository,
                backend = backend,
                scope = scope,
                initialFileId = "f1",
                initialFileName = cloud.fileName,
                resolveLocalPath = resolveLocalPath,
            ).apply {
                // 与两端调用方一致：列表总会填一次。不填时第一次打开要等满播放列表的超时
                playlist = emptyList()
            }
        }

    /** 离开播放器：作用域取消时补写续播位置。 */
    private suspend fun leavePlayer(state: PlayerScreenState) {
        withContext(stateThread) { state.release() }
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun resumesFromSavedPositionAndPersistsOnLeave() = runBlocking {
        positions.saved["f1"] = 120_000L
        val state = newState()

        val opened = backend.awaitOpens(1).single()
        assertIs<PlaybackTarget.Url>(opened.target)
        assertTrue(opened.target.url.startsWith("http://127.0.0.1:"), "云端文件应当经本机代理播放")
        assertEquals(120_000L, opened.startMillis)
        assertEquals(120_000L, state.resumedFromMillis)

        backend.emit(PlaybackBackendEvent.Ready)
        backend.progress(positionMillis = 130_000L, durationMillis = 600_000L)
        leavePlayer(state)

        assertEquals(130_000L, positions.saved["f1"])
    }

    @Test
    fun endOfFileResetsTheResumePosition() = runBlocking {
        positions.saved["f1"] = 120_000L
        val state = newState()
        backend.awaitOpens(1)
        backend.emit(PlaybackBackendEvent.Ready)
        backend.progress(positionMillis = 595_000L, durationMillis = 600_000L)
        backend.emit(PlaybackBackendEvent.Ended)
        eventually("播放结束后续播位置归零") { positions.saved["f1"] == 0L }

        // 最后十秒内离开，也不能把片尾记成续播点
        leavePlayer(state)
        assertEquals(0L, positions.saved["f1"])
    }

    @Test
    fun completedLocalCopyIsPlayedWithoutTouchingTheCloud() = runBlocking {
        val state = newState { _, _ -> "/downloads/movie.mkv" }

        val opened = backend.awaitOpens(1).single()
        assertEquals(PlaybackTarget.LocalFile("/downloads/movie.mkv"), opened.target)
        assertEquals(0, cloud.detailCalls.get(), "本地已有完整副本时不应再请求云端")
        assertTrue(state.isLocalPlayback)
        assertTrue(state.qualityOptions.isEmpty())
    }

    @Test
    fun failureBeforeFirstFrameFallsBackProxyThenDirectLinkThenTranscode() = runBlocking {
        val messages = CopyOnWriteArrayList<String>()
        backend.onOpen = { emit(PlaybackBackendEvent.Error("demuxer failed")) }
        val state = newState()
        scope.launch { state.messages.collect { messages += it } }

        val opens = backend.awaitOpens(3)
        assertTrue(opens[0].urlOrNull()!!.startsWith("http://127.0.0.1:"), "第一步：代理")
        assertTrue(
            opens[1].urlOrNull()!!.startsWith("https://${FakePikPakCloud.CDN_HOST}/origin/"),
            "第二步：同一份字节的直链，实际是 ${opens[1].target}",
        )
        assertTrue(opens[2].urlOrNull()!!.startsWith("http://127.0.0.1:"), "第三步：转码流仍经代理")
        eventually("三条路都失败后报错") { state.errorMessage != null }
        assertEquals("480P", state.currentQuality)
        assertEquals(1, messages.count { "转码" in it })

        // 走完三级就停下，不能在失败之间来回打转
        delay(1_000)
        assertEquals(3, backend.opens.size)
    }

    @Test
    fun midPlaybackFailureReconnectsFromTheSamePositionWithBackoff() = runBlocking {
        backend.onOpen = {
            emit(PlaybackBackendEvent.Ready)
            progress(positionMillis = 50_000L, durationMillis = 600_000L)
            emit(PlaybackBackendEvent.Error("connection reset"))
        }
        val state = newState()

        // 首次打开加三次退避重连（0.5 s、1.5 s、4 s），之后才把错误交给用户
        val opens = backend.awaitOpens(4, timeoutMillis = 20_000)
        opens.drop(1).forEach { reconnect ->
            assertTrue(reconnect.urlOrNull()!!.startsWith("http://127.0.0.1:"), "重连应当原路走代理，不是换源")
            assertEquals(50_000L, reconnect.startMillis, "重连应当从断点续上")
        }
        eventually("退避用尽后报错") { state.errorMessage != null }
        delay(1_000)
        assertEquals(4, backend.opens.size)
    }

    @Test
    fun releaseDrainsAsyncBackendStopBeforeClosingProxyStream() = runBlocking {
        backend.requiresAsyncStopDrain = true
        val drainGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val drainEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        backend.onStopDrain = {
            drainEntered.complete(Unit)
            drainGate.await()
        }
        val state = newState()
        val proxyUrl = assertNotNull(backend.awaitOpens(1).single().urlOrNull())

        withContext(stateThread) { state.release() }
        drainEntered.await()
        assertTrue(backend.stopCalls > 0, "release() 应当先停止后端")

        // 后端异步卸载输入期间（例如 libmpv demuxer 正在退出），本机回环代理会话必须保持可读而非提前 404
        val statusDuringDrain = java.net.URI(proxyUrl).toURL().openConnection().let { conn ->
            (conn as java.net.HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 2_000
                readTimeout = 2_000
            }.useCode()
        }
        assertEquals(200, statusDuringDrain, "后端尚未完成停止排空前代理会话不应提前关闭")

        drainGate.complete(Unit)
        eventually("后端排空完成后代理会话关闭并返回 404") {
            val code = java.net.URI(proxyUrl).toURL().openConnection().let { conn ->
                (conn as java.net.HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 2_000
                    readTimeout = 2_000
                }.useCode()
            }
            code == 404
        }
    }

    @Test
    fun cancellingReloadDuringStopDrainNeverLeaksDetachedProxyStream() = runBlocking<Unit> {
        backend.requiresAsyncStopDrain = true
        val drainEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val drainBlock = kotlinx.coroutines.CompletableDeferred<Unit>()
        var blockNextStop = false
        backend.onStopDrain = {
            if (blockNextStop) {
                drainEntered.complete(Unit)
                drainBlock.await()
            }
        }
        val state = newState()
        val firstProxyUrl = assertNotNull(backend.awaitOpens(1).single().urlOrNull())

        blockNextStop = true
        withContext(stateThread) { state.retry() }
        drainEntered.await()

        // 排空尚未结束时立即触发下一次 reload（取消上一轮 prepareJob），旧代理流必须在 finally 中安全关闭，绝不泄漏
        blockNextStop = false
        withContext(stateThread) { state.retry() }
        eventually("取消排空后旧代理会话仍被可靠关闭并返回 404") {
            val code = java.net.URI(firstProxyUrl).toURL().openConnection().let { conn ->
                (conn as java.net.HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 2_000
                    readTimeout = 2_000
                }.useCode()
            }
            code == 404
        }
        drainBlock.complete(Unit)
        backend.awaitOpens(2)
    }

    private fun java.net.HttpURLConnection.useCode(): Int = try {
        responseCode
    } finally {
        disconnect()
    }

    private suspend fun eventually(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) =
        dev.piko.shared.smoke.awaitUntil(what, timeoutMillis, condition)

    private fun Opened.urlOrNull(): String? = (target as? PlaybackTarget.Url)?.url

    class Opened(val target: PlaybackTarget, val startMillis: Long)

    /** 按脚本回事件的假后端。位置变化后手动发快照通知，替代 Compose 帧时钟。 */
    private inner class FakeBackend : PlaybackBackend {
        override var positionMillis by mutableLongStateOf(0L)
        override var durationMillis by mutableLongStateOf(0L)
        override val bufferedPositionMillis: Long = 0L
        override var isPlaying by mutableStateOf(true)
        override val isBuffering: Boolean = false
        override val speed: Float = 1f
        override val supportsSpeed: Boolean = true
        override val aspectRatio: PlayerAspectRatio = PlayerAspectRatio.Fit
        override val videoAspect: Float? = null
        override val volume: Float? = null

        private val _events = MutableSharedFlow<PlaybackBackendEvent>(extraBufferCapacity = 64)
        override val events = _events.asSharedFlow()

        val opens = CopyOnWriteArrayList<Opened>()
        var onOpen: suspend FakeBackend.() -> Unit = {}
        override var requiresAsyncStopDrain: Boolean = false
        @Volatile var stopCalls: Int = 0
        var onStopDrain: suspend () -> Unit = {}

        fun emit(event: PlaybackBackendEvent) {
            check(_events.tryEmit(event))
        }

        suspend fun progress(positionMillis: Long, durationMillis: Long) {
            withContext(stateThread) {
                this@FakeBackend.positionMillis = positionMillis
                this@FakeBackend.durationMillis = durationMillis
                Snapshot.sendApplyNotifications()
            }
            // 让 state holder 里的 snapshotFlow 收集者先跑完
            delay(200)
        }

        suspend fun awaitOpens(count: Int, timeoutMillis: Long = 10_000): List<Opened> {
            eventually("等待第 $count 次打开，目前 ${opens.size} 次", timeoutMillis) { opens.size >= count }
            return opens.toList()
        }

        override suspend fun open(
            target: PlaybackTarget,
            startMillis: Long,
            playWhenReady: Boolean,
            subtitles: List<ExternalSubtitle>,
        ) {
            opens += Opened(target, startMillis)
            positionMillis = 0L
            onOpen()
        }

        override fun stop() {
            stopCalls += 1
        }
        override suspend fun stopAndAwaitIdle() {
            stop()
            onStopDrain()
        }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMillis: Long) {
            this.positionMillis = positionMillis
        }
        override fun setSpeed(speed: Float) = Unit
        override fun setAspectRatio(mode: PlayerAspectRatio) = Unit
        override fun setVolume(volume: Float) = Unit
    }
}
