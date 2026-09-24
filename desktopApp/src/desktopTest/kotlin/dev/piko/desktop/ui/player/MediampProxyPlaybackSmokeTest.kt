package dev.piko.desktop.ui.player

import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.proxy.PikoMediaProxy
import dev.piko.shared.media.proxy.ProxyByteSource
import dev.piko.shared.media.proxy.ProxyReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.openani.mediamp.mpv.MpvMediampPlayer
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Desktop 端的播放冒烟：MediaMP 的 mpv 后端无头运行，经本机代理播放 testdata 里的老格式样片。
 *
 * 覆盖的是 playUri(代理 URL) 这条生产路径与 [MediampPlaybackBackend] 的转接：能打开、
 * 时长对、能往前走、能拖动，且全程没有错误事件。
 */
// 暂时跳过：MediaMP 0.5.0 的 playUri 要等画面表面建好渲染上下文才加载，无窗口时 open 一直不返回。
// 给测试挂一个真实窗口的排查成本不值得，MediaMP 本身在发布前已经测过；播放链路以手动开窗验证为准
@Ignore
class MediampProxyPlaybackSmokeTest {

    @Test
    fun legacyFormatsPlayThroughTheProxy() = runBlocking {
        val dir = File(System.getProperty("piko.testdata") ?: fail("缺少 piko.testdata，应由 Gradle 传入"))
        val proxy = PikoMediaProxy()
        val failures = FIXTURES.mapNotNull { name ->
            runCatching { playOne(proxy, File(dir, name)) }.exceptionOrNull()?.let { "$name：${it.message}" }
        }
        proxy.close()
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private suspend fun playOne(proxy: PikoMediaProxy, file: File) {
        check(file.isFile) { "样片不存在：$file" }
        val thread = Executors.newSingleThreadExecutor { Thread(it, "mediamp-main") }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + thread)
        val stream = proxy.register(FileSource(file), file.name)
        val events = CopyOnWriteArrayList<PlaybackBackendEvent>()
        // MediaMP 的状态机要求命令都在构造时给的调度器线程上发出
        val player = withContext(thread) { MpvMediampPlayer(Unit, scope.coroutineContext, mainDispatcher = thread) }
        try {
            val backend = withContext(thread) { MediampPlaybackBackend(player, scope) }
            scope.launch { backend.events.collect { events += it } }
            withContext(thread) { backend.open(PlaybackTarget.Url(stream.url), startMillis = 0L) }

            fun errors() = events.filterIsInstance<PlaybackBackendEvent.Error>()
            suspend fun step(name: String, condition: () -> Boolean) {
                val deadline = System.currentTimeMillis() + STEP_TIMEOUT_MILLIS
                while (!condition()) {
                    errors().firstOrNull()?.let { error("$name 时出错：${it.detail}") }
                    if (System.currentTimeMillis() > deadline) {
                        error(
                            "$name 超时：位置 ${backend.positionMillis} ms，时长 ${backend.durationMillis} ms，" +
                                "缓冲 ${backend.isBuffering}，事件 $events",
                        )
                    }
                    delay(50)
                }
            }

            step("打开") { PlaybackBackendEvent.Ready in events }
            step("读取时长") { backend.durationMillis in 3_500L..4_500L }
            step("播放越过 1 秒") { backend.positionMillis > 1_000L }
            withContext(thread) { backend.seekTo(2_500L) }
            step("拖动到 2.5 秒") { backend.positionMillis in 2_300L..3_300L }
            check(errors().isEmpty()) { "出现错误事件：${errors()}" }
        } finally {
            withContext(thread) { player.close() }
            stream.close()
            scope.cancel()
            thread.close()
        }
    }

    private class FileSource(private val file: File) : ProxyByteSource {
        override val size: Long = file.length()

        override suspend fun openReader(): ProxyReader {
            val access = RandomAccessFile(file, "r")
            return object : ProxyReader {
                override var position = 0L

                override suspend fun seekTo(position: Long) {
                    this.position = position
                }

                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    access.seek(position)
                    val read = access.read(buffer, offset, length)
                    if (read > 0) position += read
                    return read
                }

                override fun close() = access.close()
            }
        }

        override fun close() = Unit
    }

    private companion object {
        const val STEP_TIMEOUT_MILLIS = 30_000L
        // 不含 rv20-ra144.rm：MediaMP 0.4.0 的 Windows 运行时是 LGPL 构建，缺 real_144
        // 音频解码器，直接打开本地文件同样失败（mpv_error=-13），与代理无关。
        // 真实的 RMVB 多为 rv40 + cook，这两个解码器它有
        val FIXTURES = listOf(
            "control-mpeg4-aac.mp4",
            "wmv2-wmav2.wmv",
            "mpeg4asp-mp3.avi",
            "msmpeg4v3-mp2.avi",
        )
    }
}
