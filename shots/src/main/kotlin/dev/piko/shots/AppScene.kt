package dev.piko.shots

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
        // 目标可能还在路上（文件夹的解析名要等描述取回来），等一会儿再算找不到
        if (find(text) == null) pumpUntil(5_000) { find(text) != null }
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

    /**
     * 按一次键，[chord] 写成 `Down`、`Tab`、`Enter`、`F2`、`Ctrl+A`、`Shift+Tab` 这样。按下与抬起各发一次，
     * 与真实键盘一样先经焦点所在的节点。构造按键事件的函数标着 Compose 内部 API：这是开发工具，
     * 跟着 compose-ui 同版本升级即可。
     */
    @OptIn(InternalComposeUiApi::class)
    fun key(chord: String) {
        val parts = chord.split('+')
        val key = keyNamed(parts.last())
        val mods = parts.dropLast(1).map { it.lowercase() }.toSet()
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            val event = KeyEvent(
                key = key,
                type = type,
                isCtrlPressed = "ctrl" in mods,
                isMetaPressed = "meta" in mods || "cmd" in mods,
                isAltPressed = "alt" in mods,
                isShiftPressed = "shift" in mods,
            )
            edt { scene.sendKeyEvent(event) }
            pump(30)
        }
        pump(150)
    }

    /** 按住鼠标左键从 [from] 拖到 [to]，中间走 [steps] 步，看框选与拖放。 */
    fun drag(from: Offset, to: Offset, steps: Int = 12) {
        edt { scene.sendPointerEvent(PointerEventType.Move, from) }
        pump(30)
        edt { scene.sendPointerEvent(PointerEventType.Press, from, button = PointerButton.Primary) }
        pump(30)
        for (i in 1..steps) {
            val at = from + (to - from) * (i / steps.toFloat())
            edt { scene.sendPointerEvent(PointerEventType.Move, at) }
            pump(30)
        }
        // 松手前留一张：框还画着
        pump(200)
    }

    fun release(at: Offset) {
        edt { scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary) }
        pump(100)
    }

    /**
     * 往有焦点的输入框里打字。桌面端的文字输入走 AWT 的 KEY_TYPED 事件，这里逐字造一个交给场景；
     * 中文一样可以，不经输入法。
     */
    @OptIn(InternalComposeUiApi::class)
    fun type(text: String) {
        val source = javax.swing.JPanel()
        for (c in text) {
            val awt = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_UNDEFINED, c)
            // 与桌面端把 KEY_TYPED 换成的事件一样：类型未知、带着字符，原生事件留着，文本框据它认出这是打字
            val event = KeyEvent(key = Key.Unknown, type = KeyEventType.Unknown, codePoint = c.code, nativeEvent = awt)
            edt { scene.sendKeyEvent(event) }
            pump(20)
        }
        pump(200)
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
            // 信息流的独立窗口不画，只记开没开着：应用内据此收起侧栏，弹出后的样子也能截
            val feedWindow = mutableStateOf(false)
            val player = VideoPlayerHost.Detached(
                open = {},
                openClipFeed = { feedWindow.value = true },
                isClipFeedOpen = { feedWindow.value },
                closeClipFeed = { feedWindow.value = false },
            )
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

private fun keyNamed(name: String): Key = when (name.lowercase()) {
    "up" -> Key.DirectionUp
    "down" -> Key.DirectionDown
    "left" -> Key.DirectionLeft
    "right" -> Key.DirectionRight
    "tab" -> Key.Tab
    "enter" -> Key.Enter
    "esc", "escape" -> Key.Escape
    "space" -> Key.Spacebar
    "delete", "del" -> Key.Delete
    "backspace" -> Key.Backspace
    "home" -> Key.MoveHome
    "end" -> Key.MoveEnd
    "pageup" -> Key.PageUp
    "pagedown" -> Key.PageDown
    "f1" -> Key.F1
    "f2" -> Key.F2
    "f3" -> Key.F3
    "f4" -> Key.F4
    "f5" -> Key.F5
    "f6" -> Key.F6
    "f7" -> Key.F7
    "f8" -> Key.F8
    "f9" -> Key.F9
    "f10" -> Key.F10
    "f11" -> Key.F11
    "f12" -> Key.F12
    "menu" -> Key.Menu
    else -> if (name.length == 1 && name[0].isLetterOrDigit()) {
        Key(nativeKeyCode = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(name[0].uppercaseChar().code))
    } else {
        throw IllegalArgumentException("不认识的键：$name")
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
