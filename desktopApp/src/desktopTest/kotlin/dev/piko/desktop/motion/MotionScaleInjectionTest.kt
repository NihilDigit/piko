package dev.piko.desktop.motion

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.awaitApplication
import dev.piko.ui.theme.PikoMotionScale
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Main.kt 把动画时长缩放放进 runBlocking 的上下文，指望它经应用的 Recomposer 传到每个窗口、弹层与对话框。
 * 这条链是 Compose Desktop 的内部实现（SwingWindow 取 rememberCoroutineScope 的上下文建窗口的 Recomposer），
 * 升级 Compose 时可能断掉而不报错，断了「减少动画」就只剩一部分地方生效。
 * 这里在各处真跑一段 10 秒的动画，缩放为 0 时都应当当场结束。会弹出几个窗口，只在有显示器的机器上跑。
 */
class MotionScaleInjectionTest {
    @Test
    fun `a zero scale ends animations at once in every window, popup and dialog`() {
        val scale = PikoMotionScale().apply { appReduced = true }
        val elapsed = ConcurrentHashMap<String, Long>()
        val places = listOf("window", "popup", "dialog", "dialogWindow", "secondWindow")

        @Composable
        fun Probe(place: String) = LaunchedEffect(Unit) {
            val start = System.nanoTime()
            animate(0f, 1f, animationSpec = tween(10_000)) { _, _ -> }
            elapsed[place] = (System.nanoTime() - start) / 1_000_000
        }

        runBlocking(scale) {
            withTimeout(30_000) {
                awaitApplication {
                    Window(onCloseRequest = ::exitApplication, title = "motion-scale") {
                        Probe("window")
                        Popup { Probe("popup") }
                        Dialog(onDismissRequest = {}) { Probe("dialog") }
                        DialogWindow(onCloseRequest = {}) { Probe("dialogWindow") }
                    }
                    Window(onCloseRequest = ::exitApplication, title = "motion-scale-2") { Probe("secondWindow") }
                    LaunchedEffect(Unit) {
                        while (!elapsed.keys.containsAll(places)) delay(50)
                        exitApplication()
                    }
                }
            }
        }
        // 缩放为 0 时首帧即结束；给慢机器留足余量，仍远小于 10 秒
        assertEquals(places.toSet(), elapsed.filterValues { it < 2_000 }.keys, "各处耗时：$elapsed")
    }
}
