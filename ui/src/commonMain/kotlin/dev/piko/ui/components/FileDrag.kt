package dev.piko.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.isDriveFolderId
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 被拖着的一批网盘条目。[perform] 由拖出来的那一页给：落到哪个文件夹、是移动还是复制，交回它去做，
 * 落点（侧边栏、路径栏）不必认识网盘页的状态。
 */
class FileDragPayload(
    val ids: List<String>,
    /** 这批条目眼下所在的目录，拖回这里什么也不做。 */
    val parentIds: Set<String>,
    /** 浮在指针旁的说明：一项时是它的名字，几项时是「3 项」。 */
    val label: String,
    val perform: (target: PikoPathBreadcrumb, copy: Boolean) -> Unit,
) {
    fun accepts(target: PikoPathBreadcrumb) = target.id !in ids && parentIds != setOf(target.id)
}

/**
 * 应用内拖放文件的状态，整个窗口一份（[LocalFileDrag]），由根上的 [FileDragOverlay] 画出指针旁的说明。
 *
 * 不用平台的拖放：桌面端要经 AWT，在无头截图里也跑不了；这里只在应用内挪网盘条目，
 * 一个记着落点位置的表就够了。落点在布局时登记自己的范围（[fileDropTarget]），拖动时按指针位置查表，
 * 不走重组：表是普通的 Map，只有当前悬停的是哪一个落点才是状态。
 */
@Stable
class FileDragState {
    var payload by mutableStateOf<FileDragPayload?>(null)
        private set

    /** 指针在窗口里的位置。 */
    var position by mutableStateOf(Offset.Zero)
        private set

    var copy by mutableStateOf(false)
        private set

    /** 指针下能接住这批条目的落点。 */
    var hovered by mutableStateOf<Any?>(null)
        private set

    private class Target(val bounds: Rect, val folder: PikoPathBreadcrumb)

    // 要按登记先后倒着找，所以用 LinkedHashMap；HashMap 的遍历顺序与登记先后无关
    private val targets = LinkedHashMap<Any, Target>()

    val hoveredFolder: PikoPathBreadcrumb? get() = hovered?.let { targets[it]?.folder }

    // 拖着的时候指针不动也会换落点：滚轮滚动列表、侧栏展开都会挪动落点的范围。所以登记、撤销与松手时
    // 都按眼下的指针位置重新找一遍，不只在指针移动时找，否则松手落到已经滚走的那个文件夹上
    internal fun register(key: Any, bounds: Rect, folder: PikoPathBreadcrumb) {
        targets[key] = Target(bounds, folder)
        retarget()
    }

    internal fun unregister(key: Any) {
        targets.remove(key)
        retarget()
    }

    /** 拖着这批条目的那个指针。起拖之后由根上的 [fileDragHost] 按它跟到松手，拖出来的条目滚走了也不断。 */
    internal var pointer: PointerId? = null
        private set

    internal fun start(payload: FileDragPayload, pointer: PointerId, at: Offset, copy: Boolean) {
        this.payload = payload
        this.pointer = pointer
        move(at, copy)
    }

    internal fun move(at: Offset, copy: Boolean) {
        position = at
        this.copy = copy
        retarget()
    }

    private fun retarget() {
        val payload = payload
        if (payload == null) {
            hovered = null
            return
        }
        // 后登记的在上层（弹出的侧栏、滚到上面的条目），倒着找先碰到它
        hovered = targets.entries.reversed().firstOrNull { (_, target) ->
            target.bounds.contains(position) && payload.accepts(target.folder)
        }?.key
    }

    internal fun drop() {
        val payload = payload ?: return
        retarget()
        val target = hoveredFolder
        cancel()
        if (target != null) payload.perform(target, copy)
    }

    internal fun cancel() {
        payload = null
        pointer = null
        hovered = null
    }
}

val LocalFileDrag = staticCompositionLocalOf<FileDragState?> { null }

/**
 * 按住鼠标左键拖动这一项，拖出 [payload] 给的那一批。超过拖动阈值才开始，此前的按下、抬起照常是单击；
 * 开始之后吃掉移动，条目自己的单击随之作废。按着修饰键按下的（Ctrl 点选、Shift 连选）已被
 * [selectionClicks] 吃掉，这里不接。拖动中按着 Ctrl（mac 上 ⌥）是复制，与资源管理器、Finder 相同。
 * 触屏不接：长按是多选。
 *
 * 这里只管起拖。起拖之后交给根上的 [fileDragHost] 跟到松手：条目在懒加载的网格里，拖着的时候滚轮一滚、
 * 列表自动滚动，它就离开组合，挂在它上面的手势随之撤销，由它跟下去整个拖动就没了，滚进来的文件夹也接不住。
 */
@Composable
fun Modifier.fileDragSource(payload: () -> FileDragPayload?): Modifier {
    val drag = LocalFileDrag.current ?: return this
    val copyWithAlt = LocalPikoPlatform.current.shortcutModifier == ShortcutModifier.Command
    val currentPayload by rememberUpdatedState(payload)
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onGloballyPositioned { coordinates[0] = it }.pointerInput(drag, copyWithAlt) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Mouse || down.isConsumed || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            val threshold = DragThreshold.toPx()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // 外面的框选已接手这次拖动（按在没选中的条目上，见 marqueeSelection），这里就不起拖
                if (!change.pressed || change.isConsumed) break
                if ((change.position - down.position).getDistance() > threshold) {
                    val batch = currentPayload() ?: break
                    change.consume()
                    val at = coordinates[0].rootOf(change.position)
                    drag.start(batch, down.id, at, isCopy(copyWithAlt, event.keyboardModifiers))
                    break
                }
            }
        }
    }
}

/**
 * 装在铺满窗口的根上，与 [LocalFileDrag] 同处：起拖之后由它跟着 [FileDragState.pointer] 到松手，
 * 拖出来的条目离开组合也不断。根在按下时就在命中路径上，此后同一指针的事件照样送到这里；
 * Initial 阶段由外向内传，它在条目之前先看到并消费掉，里面的单击、框选因此不再当真。
 *
 * 收起有三种，都不落下：Esc；指针被系统取消；窗口失去焦点，这时松手多半送不回来。
 */
@Composable
fun Modifier.fileDragHost(drag: FileDragState): Modifier {
    val copyWithAlt = LocalPikoPlatform.current.shortcutModifier == ShortcutModifier.Command
    val windowInfo = LocalWindowInfo.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Esc 按下时已收起，它的松开也吃掉，免得再被当成返回
    val escPending = remember { BooleanArray(1) }
    return onGloballyPositioned { coordinates[0] = it }
        .onPreviewKeyEvent { event ->
            if (drag.payload == null) {
                val swallow = escPending[0] && event.key == Key.Escape && event.type == KeyEventType.KeyUp
                if (swallow) escPending[0] = false
                return@onPreviewKeyEvent swallow
            }
            when (event.key) {
                Key.Escape -> {
                    if (event.type == KeyEventType.KeyDown) {
                        drag.cancel()
                        escPending[0] = true
                    }
                    true
                }
                // 停着不动时按下、松开修饰键没有指针事件，说明要跟着换成复制或移动
                Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight -> {
                    drag.move(drag.position, if (copyWithAlt) event.isAltPressed else event.isCtrlPressed)
                    false
                }
                else -> false
            }
        }
        .pointerInput(drag, copyWithAlt, windowInfo) {
            coroutineScope {
                launch {
                    snapshotFlow { windowInfo.isWindowFocused }.collect { focused -> if (!focused) drag.cancel() }
                }
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = drag.pointer ?: continue
                        val change = event.changes.firstOrNull { it.id == pointer } ?: continue
                        // 松手这一下的位置与修饰键先记上再落：只在移动时记的话，快速拖到另一个文件夹随即松手
                        // 落在上一个落点，停下后按住 Ctrl 再松手仍是移动
                        drag.move(coordinates[0].rootOf(change.position), isCopy(copyWithAlt, event.keyboardModifiers))
                        when {
                            change.pressed -> {
                                // 滚轮不消费，拖着的时候照样能滚动列表去找落点
                                if (event.type != PointerEventType.Scroll) change.consume()
                            }
                            // 系统取消指针时补发的「松开」一开始就是已消费的，真的松开在根上还没人碰过
                            change.isConsumed -> drag.cancel()
                            else -> {
                                change.consume()
                                drag.drop()
                            }
                        }
                    }
                }
            }
        }
}

private fun isCopy(copyWithAlt: Boolean, modifiers: PointerKeyboardModifiers) =
    if (copyWithAlt) modifiers.isAltPressed else modifiers.isCtrlPressed

private fun LayoutCoordinates?.rootOf(local: Offset): Offset =
    this?.takeIf { it.isAttached }?.localToRoot(local) ?: local

/**
 * 能接住拖来的条目的文件夹：侧边栏的快捷访问、路径栏的上级、网格里的文件夹。拖到上面时描一圈并垫一层底色。
 * [key] 在整个窗口里唯一。
 */
@Composable
fun Modifier.fileDropTarget(key: Any, folder: PikoPathBreadcrumb): Modifier {
    // 地址栏与标签上的库（星标、回收站）与压缩包都不是网盘里的文件夹，放不进东西
    if (!isDriveFolderId(folder.id)) return this
    val drag = LocalFileDrag.current ?: return this
    val currentFolder by rememberUpdatedState(folder)
    DisposableEffect(drag, key) { onDispose { drag.unregister(key) } }
    val color = MaterialTheme.colorScheme.primary
    return onGloballyPositioned { drag.register(key, it.boundsInRoot(), currentFolder) }
        .drawWithContent {
            drawContent()
            if (drag.hovered == key) {
                val radius = CornerRadius(12.dp.toPx())
                drawRoundRect(color.copy(alpha = 0.12f), cornerRadius = radius)
                drawRoundRect(color, cornerRadius = radius, style = Stroke(2.dp.toPx()))
            }
        }
}

/**
 * 拖动时浮在指针右下方的说明：「移动 3 项到 电影」。放在铺满窗口内容的那一层最上面；
 * 这一层未必从窗口原点开始（桌面端上面还有自绘标题栏），指针位置按窗口算，要换成这一层里的位置。
 */
@Composable
fun FileDragOverlay(drag: FileDragState) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val payload = drag.payload ?: return@Box
        val target = drag.hoveredFolder
        val verb = if (drag.copy) "复制" else "移动"
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 3.dp,
            modifier = Modifier
                .widthIn(max = 320.dp)
                // 放在指针右下方；靠右、靠下放不下时翻到另一边，不伸出窗口
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val gap = 16.dp.roundToPx()
                    val at = drag.position - origin
                    var x = at.x.roundToInt() + gap
                    var y = at.y.roundToInt() + gap
                    if (x + placeable.width > constraints.maxWidth) x = at.x.roundToInt() - gap - placeable.width
                    if (y + placeable.height > constraints.maxHeight) y = at.y.roundToInt() - gap - placeable.height
                    layout(placeable.width, placeable.height) { placeable.place(x, y) }
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (drag.copy) Icons.Outlined.ContentCopy else Icons.Outlined.DriveFileMove,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 10.dp).size(18.dp),
                )
                // 头一行是要做的事，落点的名字一定看得见；拖的是什么放第二行，长文件名在这里截断
                Column {
                    Text(
                        text = if (target != null) "${verb}到 ${target.name}" else verb,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = payload.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
    }
}

private val DragThreshold = 8.dp
