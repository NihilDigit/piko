package dev.piko.ui.screens.player

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.proxy.PikoMediaProxy
import dev.piko.shared.media.proxy.ProxyByteSource
import dev.piko.shared.media.proxy.ProxyReader
import dev.piko.shared.media.proxy.ProxyStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 手机端的播放冒烟：testdata 里的老格式样片经本机代理交给 libmpv 真实播放。
 *
 * 这几种格式正是换掉 ExoPlayer 的原因，任何一种在这里放不起来都意味着用户那里也放不起来。
 * 断言的都是用户看得到的结果：能打开、时长对、画面尺寸对、有声音、能往前走、能拖动、
 * 没有中途报错。
 */
@RunWith(AndroidJUnit4::class)
class LegacyFormatPlaybackSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val proxy = PikoMediaProxy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
        proxy.close()
    }

    /**
     * 只解码不出画面（vo=null）：模拟器的 swiftshader 上 GPU 输出是否稳定与格式覆盖无关，
     * 不让它干扰这里要回答的问题。画面链路由下一条用例单独验证。
     */
    @Test
    fun legacyFormatsDecodeThroughTheProxy() = runBlocking {
        val failures = FIXTURES.mapNotNull { name ->
            runCatching { decodeHeadless(name) }.exceptionOrNull()?.let { "$name：${it.message}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun surfaceIsReattachedAfterGoingToBackground() = runBlocking {
        val name = "wmv2-wmav2.wmv"
        val stream = register(name)
        val backend = withContext(Dispatchers.Main) { MpvPlaybackBackend(targetContext) }
        val probe = Probe(name, backend)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { MpvVideoSurface(backend, Modifier.fillMaxSize()) } }
                // Surface 回调都在主线程，open 也放到主线程，与生产环境一致
                withContext(Dispatchers.Main) { backend.open(PlaybackTarget.Url(stream.url), startMillis = 0L) }

                probe.step("挂上 Surface 后开播") { PlaybackBackendEvent.Ready in probe.events }
                probe.step("画面输出为 gpu") { backend.readStringProperty("current-vo") == "gpu" }
                probe.step("播放越过 1 秒") { probe.mpvPositionSeconds() > 1.0 }

                scenario.moveToState(Lifecycle.State.CREATED)
                probe.step("进后台后让出 Surface") { backend.readStringProperty("current-vo") != "gpu" }

                scenario.moveToState(Lifecycle.State.RESUMED)
                withContext(Dispatchers.Main) {
                    backend.seekTo(0L)
                    backend.play()
                }
                probe.step("回到前台后画面重新挂上") { backend.readStringProperty("current-vo") == "gpu" }
                probe.step("回到前台后继续播放") { probe.mpvPositionSeconds() > 1.0 }
                probe.assertNoErrors()
            }
        } finally {
            probe.close()
            withContext(Dispatchers.Main) { backend.release() }
            stream.close()
        }
    }

    private suspend fun decodeHeadless(name: String) {
        val stream = register(name)
        val backend = MpvPlaybackBackend(targetContext, headless = true)
        val probe = Probe(name, backend)
        try {
            backend.open(PlaybackTarget.Url(stream.url), startMillis = 0L)
            probe.step("打开") { PlaybackBackendEvent.Ready in probe.events }
            probe.step("读取时长约 4 秒") { backend.durationMillis in 3_500L..4_500L }
            probe.step("解码出 176x144 的画面") {
                backend.readIntProperty("video-params/w") == 176 && backend.readIntProperty("video-params/h") == 144
            }
            probe.step("找到音轨") { backend.readStringProperty("current-tracks/audio/codec") != null }
            probe.step("播放越过 1 秒") { probe.mpvPositionSeconds() > 1.0 }

            // 先暂停再拖，落点不会被继续播放带走
            backend.pause()
            backend.seekTo(2_500L)
            probe.step("拖动到 2.5 秒附近") {
                !backend.isBuffering && probe.mpvPositionSeconds() in 2.2..3.0
            }
            probe.assertNoErrors()
        } finally {
            probe.close()
            backend.release()
            stream.close()
        }
    }

    private suspend fun register(name: String): ProxyStream {
        val file = File(targetContext.cacheDir, "smoke-$name")
        instrumentation.context.assets.open(name).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return proxy.register(FileSource(file), name)
    }

    /** 记录一个样片的事件，失败时把格式、卡住的步骤与 mpv 的现场一起报出来。 */
    private inner class Probe(private val format: String, private val backend: MpvPlaybackBackend) {
        val events = CopyOnWriteArrayList<PlaybackBackendEvent>()
        private val collector = scope.launch { backend.events.collect { events += it } }

        fun mpvPositionSeconds(): Double = backend.readStringProperty("time-pos")?.toDoubleOrNull() ?: -1.0

        suspend fun step(name: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + STEP_TIMEOUT_MILLIS
            while (!condition()) {
                errors().firstOrNull()?.let { throw AssertionError("[$format] $name 时出错：${it.detail}") }
                if (System.currentTimeMillis() > deadline) {
                    throw AssertionError(
                        "[$format] $name 超时：time-pos=${backend.readStringProperty("time-pos")}，" +
                            "duration=${backend.durationMillis}，buffering=${backend.isBuffering}，" +
                            "vo=${backend.readStringProperty("current-vo")}，事件 $events",
                    )
                }
                delay(50)
            }
        }

        fun assertNoErrors() {
            val errors = errors()
            if (errors.isNotEmpty()) throw AssertionError("[$format] 出现 end-file 错误：$errors")
        }

        private fun errors() = events.filterIsInstance<PlaybackBackendEvent.Error>()

        fun close() = collector.cancel()
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
        // 模拟器上软解加冷启动，给足余量；超时说明卡死，而不是慢
        const val STEP_TIMEOUT_MILLIS = 60_000L
        val FIXTURES = listOf(
            "control-mpeg4-aac.mp4",
            "wmv2-wmav2.wmv",
            "mpeg4asp-mp3.avi",
            "msmpeg4v3-mp2.avi",
            "rv20-ra144.rm",
        )
    }
}
