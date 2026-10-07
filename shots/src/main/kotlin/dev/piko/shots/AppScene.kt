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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import dev.piko.ui.PikoApp
import dev.piko.ui.platform.LocalWindowCaption
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.platform.WindowCaption
import dev.piko.ui.VideoPlayerHost
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.ThemeMode
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * 在 [ShotEnv] 上无头运行整个应用（[PikoApp]，与桌面入口同一个），按真实时间推进帧，
 * 让假服务端的异步响应落进界面。窗口外框（自绘标题栏、拖放层）不在其中，页面从窗口顶端开始。
 */
class AppScene private constructor(private val scene: ImageComposeScene, private val touch: Boolean) : AutoCloseable {
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

    private fun nodes(topmost: Boolean = false): List<SemanticsNode> = edt {
        val owners = scene.semanticsOwners.toList()
        (if (topmost) owners.takeLast(1) else owners).flatMap { owner ->
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
    fun find(text: String, topmost: Boolean = false): SemanticsNode? {
        val all = nodes(topmost)
        return all.firstOrNull { node -> node.texts().any { it == text } }
            ?: all.firstOrNull { node -> node.texts().any { it.contains(text) } }
    }

    fun hasText(text: String) = find(text) != null

    /** 文本恰为 [text] 的节点里最靠上的那个：页顶的标签与列表行里的同名标注（「云端」「上传」）并存时用。 */
    fun clickHighest(text: String) {
        pumpUntil(5_000) { nodes().any { node -> node.texts().any { it == text } } }
        // 预取而未摆放的列表项边界是零，top 也是 0，要排除
        val node = nodes().filter { node -> node.texts().any { it == text } && node.boundsInRoot.width > 0f }.minByOrNull { it.boundsInRoot.top }
            ?: error("找不到「$text」。界面上现有的文本：${texts().take(60)}")
        click(node.boundsInRoot.center)
    }

    /** [topmost] 只在最上层的弹层里找：对话框底下的页面有同名节点时，默认先找到的是页面上的那个。 */
    fun click(text: String, button: PointerButton = PointerButton.Primary, topmost: Boolean = false) {
        // 目标可能还在路上（文件夹的解析名要等描述取回来），等一会儿再算找不到
        if (find(text, topmost) == null) pumpUntil(5_000) { find(text, topmost) != null }
        val node = find(text, topmost) ?: error("找不到「$text」。界面上现有的文本：${texts().take(60)}")
        click(scrollIntoView(node, text, topmost), button)
    }

    /**
     * 节点在可滚动祖先的可见范围外（设置页下方的行）时滚过去，返回滚完后的中心。
     * 只对已组合的节点有效：懒加载列表里没组合出来的项本来就找不到。
     */
    private fun scrollIntoView(node: SemanticsNode, text: String, topmost: Boolean): Offset {
        // 看的是可滚动容器自己的可见范围：底部导航栏、FAB 盖着的那一截也在窗口里，点下去落在它们上面。
        // 用滚轮一格格滚、每格重新量：预取未摆放的项边界是零，按它算一次滚动距离会滚错方向
        var parent = node.parent
        while (parent != null && parent.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) == null) parent = parent.parent
        val viewport = parent?.boundsInRoot ?: return node.boundsInRoot.center
        val visible = (viewport.top + 24f)..(viewport.bottom - 96f)
        var bounds = node.boundsInRoot
        var center = bounds.center
        var scrolled = false
        repeat(60) {
            // 懒加载列表预取出来、还没摆放的项边界是零，它在下方
            val placed = bounds.width > 0f || bounds.height > 0f
            if (placed && center.y in visible) {
                // 滚轮滚动带惯性动画，动画没停时按下去只会让它停住，不算点击
                if (scrolled) {
                    pump(800)
                    center = find(text, topmost)?.boundsInRoot?.center ?: center
                }
                return center
            }
            scrolled = true
            val down = !placed || center.y > visible.endInclusive
            edt {
                scene.sendPointerEvent(PointerEventType.Move, viewport.center)
                scene.sendPointerEvent(PointerEventType.Scroll, viewport.center, scrollDelta = Offset(0f, if (down) 1f else -1f))
            }
            pump(60)
            bounds = find(text, topmost)?.boundsInRoot ?: return center
            center = bounds.center
        }
        return center
    }

    /** 按住不放再松开，触屏上进多选的那一下。 */
    fun longPress(text: String) {
        if (find(text, topmost = false) == null) pumpUntil(5_000) { find(text, topmost = false) != null }
        val at = (find(text, topmost = false) ?: error("找不到「$text」。界面上现有的文本：${texts().take(60)}")).boundsInRoot.center
        longPress(at)
    }

    /** 按住 [holdMs] 再松开；[releaseAfter] 为 false 时不松手，看按住期间的样子（长按倍速）。 */
    fun longPress(at: Offset, holdMs: Long = 800, releaseAfter: Boolean = true) {
        press(at, PointerButton.Primary)
        pump(holdMs)
        if (releaseAfter) {
            releasePointer(at, PointerButton.Primary)
            pump(50)
        }
    }

    fun click(at: Offset, button: PointerButton = PointerButton.Primary) {
        press(at, button)
        pump(30)
        releasePointer(at, button)
        pump(50)
    }

    /** 连点两下，间隔短于双击窗口：桌面上打开条目，信息流里是收藏。 */
    fun doubleClick(at: Offset) {
        press(at, PointerButton.Primary)
        pump(20)
        releasePointer(at, PointerButton.Primary)
        pump(40)
        press(at, PointerButton.Primary)
        pump(20)
        releasePointer(at, PointerButton.Primary)
        pump(50)
    }

    // 移动端的左键按成触屏：条目单击即打开、长按进多选、没有悬停，与手指一致。右键仍是鼠标，移动端接鼠标时同样可用
    private fun press(at: Offset, button: PointerButton) = edt {
        if (touch && button == PointerButton.Primary) {
            scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Touch)
        } else {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, button = button)
        }
    }

    private fun releasePointer(at: Offset, button: PointerButton) = edt {
        if (touch && button == PointerButton.Primary) {
            scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Touch)
        } else {
            scene.sendPointerEvent(PointerEventType.Release, at, button = button)
        }
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

    /** 手指从 [from] 划到 [to] 再抬起：下拉刷新、翻页这类只认触屏的手势。 */
    fun swipe(from: Offset, to: Offset, steps: Int = 16) {
        edt { scene.sendPointerEvent(PointerEventType.Press, from, type = PointerType.Touch) }
        pump(30)
        for (i in 1..steps) {
            val at = from + (to - from) * (i / steps.toFloat())
            edt { scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Touch) }
            pump(20)
        }
        edt { scene.sendPointerEvent(PointerEventType.Release, to, type = PointerType.Touch) }
        pump(100)
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
        fun open(
            env: ShotEnv,
            width: Int,
            height: Int,
            mode: ThemeMode,
            player: PlayerShot? = null,
            caption: Boolean = false,
            mobile: Boolean = false,
            /** 换掉平台的部分能力（假的更新服务），再按 [mobile] 套上移动端。 */
            wrapPlatform: (PikoPlatform) -> PikoPlatform = { it },
            /** 等到界面上出现它再交给调用方；null 不等，登录页、加载中这类画面要的就是开头那一刻。 */
            waitFor: String? = "Kusuriya",
        ): AppScene {
            val base = wrapPlatform(env.platform)
            val platform = if (mobile) MobileShotPlatform(base) else base
            val showPlayer = player != null
            // 信息流的独立窗口不画，只记开没开着：应用内据此收起侧栏，弹出后的样子也能截
            val feedWindow = mutableStateOf(false)
            val host = VideoPlayerHost.Detached(
                open = {},
                openClipFeed = { feedWindow.value = true },
                isClipFeedOpen = { feedWindow.value },
                closeClipFeed = { feedWindow.value = false },
            )
            // 效果的协程放在 EDT 上，与桌面入口一样。场景默认用 Unconfined，挂起后在哪个线程恢复就在哪里接着跑，
            // 目录选择器进子文件夹后在后台线程上 scrollToItem，撞上界面线程正在测量，抛
            // 「performMeasureAndLayout called during measure layout」
            val scene = edt {
                ImageComposeScene(width, height, Density(1f), coroutineContext = Dispatchers.Main) {
                    if (player != null) {
                        PlayerPreview(env, mode, player, platform)
                    } else {
                        CompositionLocalProvider(LocalWindowCaption provides if (caption) ShotWindowCaption else null) {
                            PikoApp(env.services, platform, Appearance(mode = mode), host)
                        }
                    }
                }
            }
            val app = AppScene(scene, touch = mobile)
            // 默认以根目录里一部作品的名字为准：原名或解析后的名字都含这一段
            if (!showPlayer && waitFor != null && !app.pumpUntil { app.hasText(waitFor) }) {
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
    "comma" -> Key.Comma
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

/**
 * 截图里的窗口按钮：尺寸与间距照 desktopApp 的 WindowFrame（三个 40dp 圆钮、间隔 2dp），只画三个字形，不接窗口过程。
 * 桌面端窄窗口里各页顶栏放不放得下，看的就是这一截宽度；场景本身没有窗口外框，不加它看不出来。
 */
private object ShotWindowCaption : WindowCaption {
    @Composable
    override fun Host() = Unit

    @Composable
    override fun Buttons() {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            for (glyph in listOf("—", "☐", "✕")) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(glyph, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    override fun setDragArea(key: Any, bounds: Rect?) = Unit
}
