package dev.piko.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 最近一次输入是不是键盘导航。Compose 自己的 InputModeManager 在桌面上被指针事件切到触摸后，
 * 代码里 moveFocus 挪焦点不会把它切回来，方向键走了几项也画不出描边，所以自己记：
 * 根上看到方向键或 Tab 记为键盘，看到指针按下记为鼠标，见 [trackInputModality]。
 */
private object InputModality {
    var keyboard by mutableStateOf(true)
}

/** 挂在界面的根上。 */
fun Modifier.trackInputModality(): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key in NavigationKeys) InputModality.keyboard = true
    false
}.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) InputModality.keyboard = false
        }
    }
}

private val NavigationKeys = setOf(Key.Tab, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/**
 * 焦点所在的那一项，也就是鼠标单击选中的那一项（见 [selectionClicks]）：
 * - 用键盘走过来的，沿边描一圈 3dp 的 secondary，M3 的 focus indicator。列表项自带的焦点状态层只是一层很淡的底色，
 *   用方向键在几十项里走的时候看不出停在哪一项。
 * - 鼠标点过来的，整项盖一层 secondary 的淡色，照资源管理器与 Finder 的选中底色：鼠标单击不再打开，得看得出点中了哪一项。
 */
@Composable
fun Modifier.focusIndication(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val keyboard = InputModality.keyboard
    val color = MaterialTheme.colorScheme.secondary
    return onFocusChanged { focused = it.hasFocus }.drawWithContent {
        drawContent()
        if (!focused) return@drawWithContent
        if (keyboard) {
            // 描在里侧：列表项紧挨着，下一项自带底色，画在外面的那一半会被它盖住
            val stroke = RingWidth.toPx()
            val outline = shape.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
            translate(stroke / 2, stroke / 2) {
                drawOutline(outline, color, style = Stroke(stroke))
            }
        } else {
            // 盖在内容上面而不是垫在下面：列表行与封面都自带不透明的底，垫在下面看不见
            drawOutline(shape.createOutline(size, layoutDirection, this), color.copy(alpha = PointerFocusAlpha))
        }
    }
}

private val RingWidth = 3.dp
private const val PointerFocusAlpha = 0.16f

/**
 * 键盘焦点的兜底，界面的根上一份（[focusFallbackRoot]）。治两件事，都会让此后的按键无处可去、快捷键全部失灵：
 * - 焦点所在的节点离开组合时（删掉的条目、收起的地址栏与搜索框），Compose 把焦点清空到根上，不交给任何上级。
 *   这里等一帧，还没人接就交给最近取得过焦点的页面落点（[pageFocusTarget]），都没有时交给 [root]。
 * - 鼠标点在不可聚焦的地方（空白、命令栏、侧边栏）不会挪走焦点，输入框一直停在输入态。
 *   输入框经 [releasesFocusOnOutsidePress] 登记，按在它外面即清掉焦点，再由上一条接住。
 */
class FocusFallback(private val root: FocusRequester) {
    private val pages = mutableListOf<FocusPage>()
    private var recent: FocusPage? = null
    internal var editor: EditorBounds? = null

    internal fun add(page: FocusPage) {
        pages += page
    }

    internal fun remove(page: FocusPage) {
        pages -= page
        if (recent === page) recent = null
    }

    internal fun focusChanged() {
        owner()?.let { recent = it }
    }

    // 焦点路径上的页面层层嵌套（侧栏的信息流在网盘页里面），最深的那个才是焦点所在的页面
    private fun owner(): FocusPage? = pages.filter { it.hasFocus }.maxByOrNull { it.depth() }

    /** 嵌在 [page] 里、眼下有焦点的页面。 */
    internal fun focusedInside(page: FocusPage): List<FocusPage> {
        val depth = page.depth()
        return pages.filter { it !== page && it.hasFocus && it.depth() > depth }
    }

    internal fun restore() {
        val target = recent ?: pages.lastOrNull()
        runCatching {
            if (target != null) target.requester.requestFocus() else root.requestFocus()
        }
    }
}

internal class FocusPage(val requester: FocusRequester) {
    var hasFocus = false
    var coordinates: LayoutCoordinates? = null

    fun depth(): Int = generateSequence(coordinates?.takeIf { it.isAttached }) { it.parentLayoutCoordinates }.count()

    fun contains(windowPosition: Offset): Boolean =
        coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.contains(windowPosition) == true
}

internal class EditorBounds {
    var bounds: Rect = Rect.Zero
}

val LocalFocusFallback = staticCompositionLocalOf<FocusFallback?> { null }

/** 挂在界面的根上，放在根自己的 focusRequester 与 focusable 之前：要看的是包括根在内整棵树有没有焦点。 */
@Composable
fun Modifier.focusFallbackRoot(fallback: FocusFallback): Modifier {
    val focusManager = LocalFocusManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var hasFocus by remember { mutableStateOf(false) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // 窗口失去焦点时不抢：要焦点会连带要窗口的焦点。窗口回来时 Compose 不会自己恢复被释放的焦点，由这里补上
    LaunchedEffect(hasFocus, windowFocused) {
        if (hasFocus || !windowFocused) return@LaunchedEffect
        // 等一帧再看：清空焦点的同一帧里常有别处接着要焦点（换目录后网盘页自己要一次），不必抢在它前面
        withFrameNanos {}
        if (!hasFocus) fallback.restore()
    }
    return onFocusChanged { hasFocus = it.hasFocus }
        .onGloballyPositioned { coordinates[0] = it }
        .pointerInput(fallback) {
            awaitPointerEventScope {
                while (true) {
                    // Initial 先于子节点：清掉之后，按下处的条目、另一个输入框照常接着要焦点
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press) continue
                    val editor = fallback.editor ?: continue
                    val change = event.changes.firstOrNull() ?: continue
                    // 只管鼠标：触屏上按在列表上多半是要滚动，收起软键盘会顶得列表跳一下
                    if (change.type != PointerType.Mouse) continue
                    val position = coordinates[0]?.localToWindow(change.position) ?: continue
                    if (!editor.bounds.contains(position)) focusManager.clearFocus()
                }
            }
        }
}

/**
 * 页面的快捷键落点：焦点丢了时回到这里（见 [FocusFallback]）；焦点在别处时鼠标按进这一页即取回焦点，
 * 网盘页与侧栏的信息流并排时，点哪一边，方向键就归哪一边。
 */
@Composable
fun Modifier.pageFocusTarget(requester: FocusRequester): Modifier {
    val fallback = LocalFocusFallback.current
    val page = remember(requester) { FocusPage(requester) }
    DisposableEffect(fallback, page) {
        fallback?.add(page)
        onDispose { fallback?.remove(page) }
    }
    return onFocusChanged { state ->
        page.hasFocus = state.hasFocus
        fallback?.focusChanged()
    }
        .onGloballyPositioned { page.coordinates = it }
        .pointerInput(page) {
            awaitPointerEventScope {
                while (true) {
                    // Initial 先于页里的条目：先把焦点拿回这一页，按下的条目随后照常取得焦点
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press) continue
                    val position = event.changes.firstOrNull()?.position ?: continue
                    // 焦点在嵌在里面的一页（信息流）上、而按在它外面时，这一页同样要拿回来
                    val inner = fallback?.focusedInside(page).orEmpty()
                    val windowPosition = page.coordinates?.takeIf { it.isAttached }?.localToWindow(position)
                    val pressedOutsideInner = inner.isNotEmpty() && windowPosition != null && inner.none { it.contains(windowPosition) }
                    if (!page.hasFocus || pressedOutsideInner) runCatching { requester.requestFocus() }
                }
            }
        }
        .focusRequester(requester)
        .focusable()
}

/**
 * 挂在输入框连同它的按钮的外框上（地址栏、搜索框）：鼠标按在外框以外即让出焦点，照桌面应用的惯例。
 * 外框里的清除、取消按钮不算外面，点它们不会先把输入框收起。
 */
@Composable
fun Modifier.releasesFocusOnOutsidePress(): Modifier {
    val fallback = LocalFocusFallback.current ?: return this
    val editor = remember { EditorBounds() }
    DisposableEffect(fallback) {
        onDispose { if (fallback.editor === editor) fallback.editor = null }
    }
    return onGloballyPositioned { editor.bounds = it.boundsInWindow() }
        .onFocusChanged { state ->
            if (state.hasFocus) fallback.editor = editor else if (fallback.editor === editor) fallback.editor = null
        }
}
