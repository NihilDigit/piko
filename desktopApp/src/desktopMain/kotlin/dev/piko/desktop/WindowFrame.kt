package dev.piko.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.desktop.winrt.WindowsCaption
import dev.piko.ui.platform.FramelessWindow
import dev.piko.ui.platform.LocalFramelessWindow
import java.awt.Container
import kotlinx.coroutines.delay
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.disableTitleBar

/** 标题栏的底色与文字颜色。主窗口跟随应用主题，播放器始终深色。 */
@Immutable
class TitleBarColors(val container: Color, val content: Color)

/**
 * 带自绘标题栏的窗口内容：标题栏在上，[content] 占满其余部分。
 *
 * Windows 上标题栏整条由 Compose 绘制，系统行为经 [WindowsCaption] 保留；macOS 上保留系统的
 * 红绿灯，内容延伸进标题栏区域，Compose 只画底色与标题。其他系统沿用系统标题栏。
 *
 * [showTitleBar] 为 false 时（全屏）不画标题栏，整个窗口交给内容。
 *
 * [onCloseInContent] 不为 null 时（Windows 上的播放窗口）不画标题栏：内容自己已有带标题的顶栏，
 * 再叠一条标题栏只是重复。窗口经 [LocalFramelessWindow] 交给内容，由它指定拖动区、放关窗按钮。
 * macOS 上仍画标题栏，红绿灯本就在那里。
 */
@Composable
fun FrameWindowScope.WindowFrame(
    title: String,
    icon: Painter?,
    colors: TitleBarColors,
    showTitleBar: Boolean = true,
    onCloseInContent: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    when {
        WinRTSupport.isWindows -> {
            // 在组合时就接管：此时窗口已有 HWND 而尚未显示，首帧就是去掉系统标题栏后的布局。
            // 接管失败时（拿不到 HWND 或 FFM 调用出错）系统标题栏还在，不再画第二条
            val caption = remember(window) { WindowsCaption(window).takeIf { it.install() } }
            if (onCloseInContent != null && caption != null) {
                // 直接设在 AWT 窗口上：Compose 的 Window 参数 alwaysOnTop 只在它自己的值变化时才下发，
                // 调用方不传它，不会把这里的设置盖回去
                var alwaysOnTop by remember(window) { mutableStateOf(window.isAlwaysOnTop) }
                // 全屏时整个窗口都是画面：拖动区不报给窗口过程，否则按住顶栏会把全屏的窗口拖走
                val frameless = remember(caption, showTitleBar, onCloseInContent) {
                    object : FramelessWindow {
                        override fun updateDragArea(bounds: Rect?) {
                            if (showTitleBar && bounds != null) caption.updateLayout(bounds, emptyMap()) else caption.clearLayout()
                        }

                        override fun close() = onCloseInContent()

                        override val isAlwaysOnTop: Boolean get() = alwaysOnTop

                        override fun setAlwaysOnTop(onTop: Boolean) {
                            window.isAlwaysOnTop = onTop
                            alwaysOnTop = onTop
                        }
                    }
                }
                DisposableEffect(frameless) { onDispose { caption.clearLayout() } }
                CompositionLocalProvider(LocalFramelessWindow provides frameless) { content() }
                return
            }
            Column(Modifier.fillMaxSize()) {
                if (showTitleBar && caption != null) WindowsTitleBar(caption, title, icon, colors)
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            }
        }
        isMacOs -> {
            MacTitleBarEffect()
            Column(Modifier.fillMaxSize()) {
                if (showTitleBar) MacTitleBar(title, colors)
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            }
        }
        else -> content()
    }
}

/** 与 Windows 11 标题栏按钮（46×32 epx）同高。 */
private val TitleBarHeight = 32.dp

@Composable
private fun FrameWindowScope.WindowsTitleBar(
    caption: WindowsCaption,
    title: String,
    icon: Painter?,
    colors: TitleBarColors,
) {
    // 各部分在根坐标系里的位置，每次变化都汇总成一份交给窗口过程做命中测试。只在布局回调里读写，
    // 不参与重组，所以不用 State
    val bounds = remember { CaptionBounds() }
    fun publish() = caption.updateLayout(bounds.bar, bounds.buttons.toMap())
    DisposableEffect(caption) {
        onDispose { caption.clearLayout() }
    }

    // Windows 11 的非活动窗口标题与按钮图标变灰
    val contentColor = if (caption.isActive) colors.content else colors.content.copy(alpha = 0.45f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(colors.container)
            .onGloballyPositioned {
                bounds.bar = it.boundsInWindow()
                publish()
            },
    ) {
        Spacer(Modifier.width(16.dp))
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            color = contentColor,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        for (button in WindowsCaption.Button.entries) {
            CaptionButton(
                button = button,
                isMaximized = caption.isMaximized,
                hovered = caption.hovered == button,
                pressed = caption.pressed == button && caption.hovered == button,
                contentColor = contentColor,
                modifier = Modifier.onGloballyPositioned {
                    bounds.buttons[button] = it.boundsInWindow()
                    publish()
                },
            )
        }
    }
}

private class CaptionBounds {
    var bar: Rect = Rect.Zero
    val buttons = mutableMapOf<WindowsCaption.Button, Rect>()
}

/**
 * 标题栏按钮。悬停与按下状态来自窗口过程而不是 Compose 的指针事件：这三块区域在系统看来是
 * 非客户区，鼠标消息不发给画布，见 [WindowsCaption]。配色取 Windows 11 的标题栏按钮：
 * 普通按钮悬停与按下各叠一层 6% 与 4% 的前景色，关闭按钮悬停为红底白字。
 */
@Composable
private fun CaptionButton(
    button: WindowsCaption.Button,
    isMaximized: Boolean,
    hovered: Boolean,
    pressed: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val isClose = button == WindowsCaption.Button.CLOSE
    val background = when {
        isClose && pressed -> CloseRed.copy(alpha = 0.9f)
        isClose && hovered -> CloseRed
        pressed -> contentColor.copy(alpha = 0.04f)
        hovered -> contentColor.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val glyphColor = when {
        isClose && pressed -> Color.White.copy(alpha = 0.7f)
        isClose && hovered -> Color.White
        pressed -> contentColor.copy(alpha = contentColor.alpha * 0.6f)
        else -> contentColor
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(46.dp, TitleBarHeight).background(background),
    ) {
        val glyph = when (button) {
            WindowsCaption.Button.MINIMIZE -> CaptionGlyph.Minimize
            WindowsCaption.Button.MAXIMIZE -> if (isMaximized) CaptionGlyph.Restore else CaptionGlyph.Maximize
            WindowsCaption.Button.CLOSE -> CaptionGlyph.Close
        }
        val iconFont = CaptionIconFont
        if (iconFont != null) {
            Text(glyph.char.toString(), color = glyphColor, fontFamily = iconFont, fontSize = 10.sp)
        } else {
            Icon(glyph.fallback, contentDescription = null, tint = glyphColor, modifier = Modifier.size(14.dp))
        }
    }
}

private val CloseRed = Color(0xFFC42B1C)

/** 码位在 Segoe Fluent Icons（Windows 11）与 Segoe MDL2 Assets（Windows 10）里相同。 */
private enum class CaptionGlyph(val char: Char, val fallback: ImageVector) {
    Minimize(Char(0xE921), Icons.Filled.Remove),
    Maximize(Char(0xE922), Icons.Filled.CropSquare),
    Restore(Char(0xE923), Icons.Filled.FilterNone),
    Close(Char(0xE8BB), Icons.Filled.Close),
}

/**
 * 系统标题栏按钮用的图标字体，与系统按钮的字形一致。两个都没有时用 Material 图标代替。
 * 按名字直接查 FontMgr：Compose 的 FontFamily(名字) 找不到字体时静默换成默认字体，私用区码位会画成方框。
 */
private val CaptionIconFont: FontFamily? by lazy {
    listOf("Segoe Fluent Icons", "Segoe MDL2 Assets").firstNotNullOfOrNull { name ->
        runCatching { FontMgr.default.matchFamilyStyle(name, FontStyle.NORMAL) }.getOrNull()
            ?.let { FontFamily(Typeface(it)) }
    }
}

/**
 * macOS：内容铺进标题栏，标题栏透明且不显示系统标题，红绿灯保留。这几个根面板属性由 JDK
 * 读取，窗口显示前设好即可。
 *
 * 光靠这些属性，拖动区只有系统默认的 28pt 高，且 Compose 的画布盖在上面。Skiko 的
 * disableTitleBar 把系统标题栏视图拉到 [TitleBarHeight]，在其中放一层接管拖动与双击缩放的视图，
 * 并让红绿灯在这个高度里垂直居中；进出全屏时它自己撤下与恢复这些改动。代价是标题栏这一条
 * 收不到 Compose 的点击，这里本来也只放标题文字。
 */
@Composable
private fun FrameWindowScope.MacTitleBarEffect() {
    DisposableEffect(window) {
        window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        onDispose {}
    }
    // 只调一次：每调一次 Skiko 就多挂一层拖动视图与一组全屏通知的监听。它按画布找原生窗口，
    // 画布的原生层在窗口显示时才建好，之前调用什么也不做，所以先等窗口显示出来
    LaunchedEffect(window) {
        while (!window.isShowing) delay(16)
        findSkiaLayer(window)?.disableTitleBar(TitleBarHeight.value)
    }
}

private fun findSkiaLayer(container: Container): SkiaLayer? {
    for (component in container.components) {
        if (component is SkiaLayer) return component
        if (component is Container) findSkiaLayer(component)?.let { return it }
    }
    return null
}

/** 红绿灯占去左端约 70pt（Skiko 把三个按钮排在 16pt 起、间隔 20pt 的位置），标题照系统习惯居中。 */
@Composable
private fun MacTitleBar(title: String, colors: TitleBarColors) {
    val active = LocalWindowInfo.current.isWindowFocused
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(colors.container)
            .padding(horizontal = 76.dp),
    ) {
        Text(
            text = title,
            color = if (active) colors.content else colors.content.copy(alpha = 0.45f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
