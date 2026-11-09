@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.piko.desktop.ui.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.piko.shared.media.PreparedClip
import dev.piko.shared.media.player.ClipPlaybackBinding
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.proxy.*
import kotlinx.coroutines.*
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.compose.MediampPlayerSurface
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Opt in because a real rendering surface is required by MediaMP's native load path. */
class ClipRetirementNativeTest {
    @Test fun retiringAndReplacingPreviewDrainsNativeInputBeforeClosingSource() {
        org.junit.Assume.assumeTrue(System.getenv("PIKO_NATIVE_CLIP_TEST") == "1")
        val file = File(requireNotNull(System.getProperty("piko.testdata")), "control-mpeg4-aac.mp4")
        check(file.isFile)
        val proxy = PikoMediaProxy()
        var outcome: Result<Unit>? = null
        try {
            application(exitProcessOnExit = false) {
                Window(onCloseRequest = ::exitApplication, title = "Piko clip retirement test",
                    state = rememberWindowState(size = DpSize(480.dp, 300.dp))) {
                    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
                    val player = remember { MediampPlayer(Unit, scope.coroutineContext) }
                    val backend = remember { MediampPlaybackBackend(player, scope, preview = true).also { it.setVolume(0f) } }
                    val binding = remember { ClipPlaybackBinding(backend) }
                    MediampPlayerSurface(player, Modifier.fillMaxSize())
                    LaunchedEffect(Unit) {
                        outcome = runCatching {
                            withTimeout(45_000) {
                                repeat(2) {
                                    val closed = AtomicBoolean()
                                    val closedBeforeIdle = AtomicBoolean()
                                    val source = object : ProxyByteSource {
                                        override val size = file.length()
                                        override suspend fun openReader(): ProxyReader = object : ProxyReader {
                                            private val input = RandomAccessFile(file, "r")
                                            override val position get() = input.filePointer
                                            override suspend fun seekTo(position: Long) = input.seek(position)
                                            override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = input.read(buffer, offset, length)
                                            override fun close() = input.close()
                                        }
                                        override fun close() {
                                            closedBeforeIdle.set(backend.mpvProperty("idle-active") != "yes")
                                            closed.set(true)
                                        }
                                    }
                                    val stream = proxy.register(source, file.name)
                                    // White-box fixture; playback itself uses the production binding and native backend.
                                    val clip = PreparedClip(stream, null, false, 0, 0.0)
                                    try {
                                        binding.open(clip, PlaybackTarget.Url(stream.url), 0, true)
                                        while (backend.positionMillis < 500) delay(20)
                                        clip.close()
                                        while (!closed.get()) delay(20)
                                        assertFalse(closedBeforeIdle.get())
                                    } finally { clip.close() }
                                }
                            }
                        }
                        try {
                            withTimeout(10_000) { binding.retire().join() }
                        } finally {
                            backend.markClosing()
                            scope.cancel()
                            withContext(Dispatchers.IO) { player.close() }
                            exitApplication()
                        }
                    }
                }
            }
            requireNotNull(outcome) { "Native test window exited before completion" }.getOrThrow()
        } finally { proxy.close() }
    }
}
