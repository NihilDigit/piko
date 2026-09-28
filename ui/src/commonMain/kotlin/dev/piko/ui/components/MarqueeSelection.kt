package dev.piko.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 文件管理器的框选：在网格的空白处按住鼠标左键拖动，拉出一个框，框住的条目选中。挂在网格本身上，
 * 坐标与网格的条目位置同一个原点。
 *
 * - 按在已选中的条目（[movable]）上拖动是拖放移动，留给 fileDragSource；按在别处，空白或没选中的条目上，都是框选。
 *   照相册的做法而不是资源管理器的只从空白处开始：卡片铺满的海报墙与图库几乎没有空白可按。
 *   代价是移动一项要先点一下它，再拖。
 * - 按着主修饰键或 Shift 开始时保留原来的选择，框住的加进去；否则框住的就是全部。
 * - 拖到网格上下边缘自动滚动；滚出视口、先前框住的条目仍算框住，框缩回来时再按眼前的位置重算。
 * - 空白处单击（没拖动、没按修饰键）调 [onBackgroundClick]，网盘页用它退出多选。
 * - 只认鼠标：触屏在空白处拖动是滚动。
 *
 * [boxedKey] 把条目的 key 换成可选中的 ID，整行项返回 null。
 */
@Composable
fun Modifier.marqueeSelection(
    gridState: LazyGridState,
    selectedIds: Set<String>,
    boxedKey: (Any) -> String?,
    onSelect: (base: Set<String>, boxed: Set<String>) -> Unit,
    onBackgroundClick: () -> Unit,
    /** 按在这一项上拖动是移动它（选中的、焦点所在的），不是框选。 */
    movable: (String) -> Boolean = { it in selectedIds },
): Modifier {
    val shortcut = LocalPikoPlatform.current.shortcutModifier
    val selected by rememberUpdatedState(selectedIds)
    val keyOf by rememberUpdatedState(boxedKey)
    val canMove by rememberUpdatedState(movable)
    val select by rememberUpdatedState(onSelect)
    val backgroundClick by rememberUpdatedState(onBackgroundClick)
    var box by remember { mutableStateOf<Rect?>(null) }
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val outline = MaterialTheme.colorScheme.primary
    return pointerInput(gridState, shortcut) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                val pressedItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.bounds().contains(down.position) }
                val onItem = pressedItem != null
                if (pressedItem?.let { keyOf(it.key) }?.let(canMove) == true) return@awaitEachGesture
                val modifiers = currentEvent.keyboardModifiers
                val keep = shortcut.isPressed(modifiers) || modifiers.isShiftPressed
                val base = if (keep) selected else emptySet()
                val start = down.position
                var pointer = start
                // 自动滚动走过的距离：起点跟着内容走，在视口里的位置要减掉它
                var scrolled = 0f
                var dragging = false
                var offscreen = emptySet<String>()
                var boxed = emptySet<String>()

                fun update() {
                    val startY = start.y - scrolled
                    val rect = Rect(
                        left = min(start.x, pointer.x),
                        top = min(startY, pointer.y),
                        right = max(start.x, pointer.x),
                        bottom = max(startY, pointer.y),
                    )
                    box = rect
                    val visible = gridState.layoutInfo.visibleItemsInfo
                    val visibleIds = visible.mapNotNullTo(HashSet()) { keyOf(it.key) }
                    offscreen = (offscreen + boxed).filterTo(HashSet()) { it !in visibleIds }
                    val inView = visible.mapNotNullTo(HashSet()) { item -> keyOf(item.key)?.takeIf { item.bounds().overlaps(rect) } }
                    boxed = offscreen + inView
                    select(base, boxed)
                }

                // 离上下边缘 EdgeDp 以内开始滚，越靠边越快
                fun edgeSpeed(): Float {
                    val edge = EdgeDp.dp.toPx()
                    return when {
                        pointer.y < edge -> -(edge - pointer.y)
                        pointer.y > size.height - edge -> pointer.y - (size.height - edge)
                        else -> 0f
                    } * SPEED
                }

                var autoScroll: Job? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    pointer = change.position
                    // 按在条目上时要比条目的拖放（8dp 起拖）先认出是拖动，否则那边先起了拖
                    val slop = if (onItem) ItemMarqueeSlopDp.dp.toPx() else viewConfiguration.touchSlop
                    if (!dragging && (pointer - start).getDistance() > slop) dragging = true
                    if (!dragging) continue
                    change.consume()
                    update()
                    if (edgeSpeed() != 0f && autoScroll?.isActive != true) {
                        autoScroll = launch {
                            while (isActive) {
                                val speed = edgeSpeed()
                                if (speed == 0f) break
                                scrolled += gridState.scrollBy(speed)
                                update()
                                delay(FRAME_MS)
                            }
                        }
                    }
                }
                autoScroll?.cancel()
                box = null
                // 按在条目上没拖动是点了那一项，由条目自己处理，不算点了空白
                if (!dragging && !keep && !onItem) backgroundClick()
            }
        }
    }.drawWithContent {
        drawContent()
        box?.let { rect ->
            drawRect(fill, rect.topLeft, rect.size)
            drawRect(outline, rect.topLeft, rect.size, style = Stroke(1.dp.toPx()))
        }
    }
}

private fun LazyGridItemInfo.bounds() = Rect(offset.toOffset(), size.toSize())

private const val EdgeDp = 48
private const val ItemMarqueeSlopDp = 4
private const val SPEED = 0.4f
private const val FRAME_MS = 16L
