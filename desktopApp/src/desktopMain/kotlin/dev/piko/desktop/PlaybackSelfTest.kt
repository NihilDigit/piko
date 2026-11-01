package dev.piko.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.piko.desktop.ui.player.MediampPlaybackBackend
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.proxy.PikoMediaProxy
import dev.piko.shared.media.proxy.ProxyByteSource
import dev.piko.shared.media.proxy.ProxyReader
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.mpv.MpvMediampPlayer

/**
 * 装好的包里经回环 HTTP 依次打开两个播放窗口，验证关窗释放后仍能创建播放器并继续播放。
 * mpv 从安装包的资源目录加载，画面表面接上窗口的渲染上下文。
 * Linux 上画面经 GLX 与 Skiko 共享纹理，这一段 JVM 单测里没有窗口测不到（MediampProxyPlaybackSmokeTest 因此跳过）。
 *
 * 两轮播放均越过 [PASS_POSITION_MILLIS] 且关窗清理完成才通过；每轮播放与清理分别限时。
 * [holdMillis] 是第二轮播放达标后窗口再留多久，供外部截图检查画面。
 */
internal fun playbackSelfTest(file: File, holdMillis: Long, report: (String) -> Unit): Boolean {
    if (!file.isFile) {
        report("no such file: $file")
        return false
    }
    System.getProperty("compose.application.resources.dir")?.let { File(it, "mpv") }
        ?.takeIf { it.resolve(System.mapLibraryName("mediampv")).isFile }
        ?.let { MpvMediampPlayer.prepareLibraries(it.absolutePath, false) }
    var passed = false
    val proxy = PikoMediaProxy()
    // 独立于窗口组合的作用域，移除窗口后仍须完成原生播放器清理。
    val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val played = List(2) { CompletableDeferred<Boolean>() }
    val closed = List(2) { CompletableDeferred<Unit>() }
    try {
        application(exitProcessOnExit = false) {
            var activeRound: Int? by remember { mutableStateOf(1) }
            // 与主界面一样保留一个宿主窗口，避免 AppKit/AWT 在两轮之间进入零窗口状态。
            Window(
                onCloseRequest = ::exitApplication,
                title = "Piko 播放自检控制器",
                state = rememberWindowState(size = DpSize(240.dp, 120.dp)),
            ) {}

            LaunchedEffect(Unit) {
                for (round in 1..2) {
                    val reached = played[round - 1].await()
                    if (reached && round == 2 && holdMillis > 0L) delay(holdMillis)
                    activeRound = null
                    val didClose = withTimeoutOrNull(10_000L) {
                        closed[round - 1].await()
                        true
                    } ?: false
                    if (!didClose) report("round=$round close timed out")
                    if (!reached || !didClose) {
                        exitApplication()
                        return@LaunchedEffect
                    }
                    if (round == 1) {
                        delay(150L)
                        activeRound = 2
                    }
                }
                passed = true
                exitApplication()
            }

            activeRound?.let { round ->
                key(round) {
                    Window(
                        onCloseRequest = ::exitApplication,
                        title = "Piko 播放自检 $round",
                        state = rememberWindowState(size = DpSize(640.dp, 400.dp)),
                    ) {
                        // 不使用 rememberMediampPlayer：它会在 EDT 同步销毁 mpv。
                        val playerJob = remember { SupervisorJob() }
                        val playerScope = remember(playerJob) { CoroutineScope(Dispatchers.Main.immediate + playerJob) }
                        val player = remember(playerScope) { MediampPlayer(Unit, playerScope.coroutineContext) }
                        val backend = remember(player) {
                            MediampPlaybackBackend(player, playerScope).also { it.setVolume(0f) }
                        }
                        val stream = remember {
                            runBlocking(Dispatchers.IO) { proxy.register(LocalFileByteSource(file), file.name) }
                        }
                        val closedLatch = closed[round - 1]
                        DisposableEffect(backend, player, stream) {
                            onDispose {
                                closeScope.launch {
                                    withTimeoutOrNull(1_500L) { runCatching { backend.stopAndAwaitIdle() } }
                                    // 与 PlayerScreenState.release 一致，先断开代理以解除 FFmpeg recv() 阻塞。
                                    runCatching { stream.close() }
                                    backend.markClosing()
                                    playerJob.cancel()
                                    runCatching { player.close() }
                                    closedLatch.complete(Unit)
                                }
                            }
                        }
                        MediampPlayerSurface(backend.player, Modifier.fillMaxSize().background(Color.Black))
                        LaunchedEffect(backend) {
                            var failure: String? = null
                            val errors = launch(start = CoroutineStart.UNDISPATCHED) {
                                val error = backend.events.first { it is PlaybackBackendEvent.Error } as PlaybackBackendEvent.Error
                                failure = error.detail ?: "playback error"
                            }
                            // open 也算在限时内，覆盖表面未接上渲染上下文时的挂起。
                            val reached = withTimeoutOrNull(TIMEOUT_MILLIS) {
                                try {
                                    backend.open(PlaybackTarget.Url(stream.url), startMillis = 0L, playWhenReady = true, subtitles = emptyList())
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    failure = e.toString()
                                }
                                while (backend.positionMillis < PASS_POSITION_MILLIS && failure == null) delay(50L)
                                failure == null
                            } ?: false
                            errors.cancel()
                            report("round=$round position=${backend.positionMillis} duration=${backend.durationMillis} error=$failure")
                            played[round - 1].complete(reached)
                        }
                    }
                }
            }
        }
    } finally {
        proxy.close()
        closeScope.cancel()
    }
    return passed
}

/** 每个 HTTP 请求使用独立文件游标，支持 mpv 同时读取不同 Range。 */
private class LocalFileByteSource(private val file: File) : ProxyByteSource {
    override val size: Long = file.length()

    override suspend fun openReader(): ProxyReader = object : ProxyReader {
        private val input = RandomAccessFile(file, "r")
        override val position: Long get() = input.filePointer
        override suspend fun seekTo(position: Long) = input.seek(position)
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length)
        override fun close() = input.close()
    }

    override fun close() = Unit
}

private const val PASS_POSITION_MILLIS = 1_200L
private const val TIMEOUT_MILLIS = 30_000L
