package dev.piko.shots

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.piko.ui.PikoApp
import dev.piko.ui.VideoPlayerHost
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.ThemeMode
import java.io.File
import javax.swing.SwingUtilities
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * 在 [ShotEnv] 上无头运行整个应用（[PikoApp]，与桌面入口同一个），按真实时间推进帧，
 * 让假服务端的异步响应落进界面。窗口外框（自绘标题栏、拖放层）不在其中，页面从窗口顶端开始。
 */
class AppScene private constructor(private val scene: ImageComposeScene) : AutoCloseable {
    private val start = System.nanoTime()
    private var image: Image = edt { scene.render(0) }

    private fun frame() {
        val next = edt { scene.render(System.nanoTime() - start) }
        image.close()
        image = next
    }

    /** 推进 [ms] 毫秒的真实时间，约每 16ms 一帧。 */
    fun pump(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            frame()
            Thread.sleep(16)
        }
        frame()
    }

    fun pumpUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            pump(50)
            if (condition()) return true
        }
        return false
    }

    private fun nodes(): List<SemanticsNode> = edt {
        scene.semanticsOwners.flatMap { owner ->
            val out = mutableListOf<SemanticsNode>()
            fun walk(node: SemanticsNode) {
                out += node
                node.children.forEach(::walk)
            }
            walk(owner.unmergedRootSemanticsNode)
            out
        }
    }

    private fun SemanticsNode.texts(): List<String> =
        (config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) +
            (config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList())

    /** 文本或内容描述等于 [text] 的节点；没有时退而找包含它的。弹层（对话框、菜单）里的也算。 */
    fun find(text: String): SemanticsNode? {
        val all = nodes()
        return all.firstOrNull { node -> node.texts().any { it == text } }
            ?: all.firstOrNull { node -> node.texts().any { it.contains(text) } }
    }

    fun hasText(text: String) = find(text) != null

    fun click(text: String, button: PointerButton = PointerButton.Primary) {
        val node = find(text) ?: error("找不到「$text」。界面上现有的文本：${texts().take(60)}")
        click(node.boundsInRoot.center, button)
    }

    fun click(at: Offset, button: PointerButton = PointerButton.Primary) {
        edt { scene.sendPointerEvent(PointerEventType.Move, at) }
        pump(30)
        edt { scene.sendPointerEvent(PointerEventType.Press, at, button = button) }
        pump(30)
        edt { scene.sendPointerEvent(PointerEventType.Release, at, button = button) }
        pump(50)
    }

    /** 鼠标移到 [text] 上停着，看悬停态与提示。 */
    fun hover(text: String) {
        val node = find(text) ?: error("找不到「$text」")
        edt { scene.sendPointerEvent(PointerEventType.Move, node.boundsInRoot.center) }
        pump(1_200)
    }

    fun texts(): List<String> = nodes().flatMap { it.texts() }.distinct()

    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    override fun close() {
        image.close()
        edt { scene.close() }
    }

    companion object {
        /** 开一个 [width]×[height]（dp，密度 1）的窗口，等根目录列出来再交给调用方。 */
        fun open(env: ShotEnv, width: Int, height: Int, mode: ThemeMode): AppScene {
            val player = VideoPlayerHost.Detached(open = {}, openClipFeed = {}, isClipFeedOpen = { false }, closeClipFeed = {})
            val scene = edt {
                ImageComposeScene(width, height, Density(1f)) {
                    PikoApp(env.services, env.platform, Appearance(mode = mode), player)
                }
            }
            val app = AppScene(scene)
            // 以根目录里一部作品的名字为准：原名或解析后的名字都含这一段
            if (!app.pumpUntil { app.hasText("Kusuriya") }) {
                System.err.println("根目录没有列出来，界面文本：${app.texts().take(40)}")
            }
            app.pump(600)
            return app
        }
    }
}

/**
 * Compose 的 Lifecycle 要求在主线程（Swing EDT）上操作场景。等待放在调用线程，
 * EDT 空出来跑 Dispatchers.Main。
 */
fun <T> edt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}
