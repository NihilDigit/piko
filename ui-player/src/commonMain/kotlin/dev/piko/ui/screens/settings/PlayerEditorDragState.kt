package dev.piko.ui.screens.settings

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import dev.piko.shared.media.player.CenterSlotAction
import dev.piko.shared.media.player.CenterSlotPosition
import dev.piko.shared.media.player.PlayerBarSlot
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerFormFactorLayout
import dev.piko.shared.media.player.PlayerLayoutMode
import dev.piko.shared.media.player.PlayerPanelItem
import dev.piko.shared.media.player.allowedControlsFor
import dev.piko.shared.media.player.allowsSpacerIn
import kotlin.coroutines.cancellation.CancellationException

/**
 * 播放器自定义面板的拖拽负载类型。
 */
internal sealed interface DragPayload {
    data class PaletteControl(val id: PlayerControlId) : DragPayload
    data object PaletteSpacer : DragPayload
    data class PaletteCenterAction(val action: CenterSlotAction) : DragPayload
    data class PlacedBarItem(
        val bar: PlayerBarSlot,
        val index: Int,
        val item: PlayerPanelItem,
    ) : DragPayload

    fun toCenterSlotActionOrNull(seekSeconds: Int): CenterSlotAction? = when (this) {
        is PaletteCenterAction -> action
        is PaletteControl -> id.toCenterActionOrNull(seekSeconds)
        is PlacedBarItem -> (item as? PlayerPanelItem.Control)?.id?.toCenterActionOrNull(seekSeconds)
        PaletteSpacer -> null
    }

    fun toPanelItemOrNull(): PlayerPanelItem? = when (this) {
        is PaletteControl -> PlayerPanelItem.Control(id)
        PaletteSpacer -> PlayerPanelItem.Spacer
        is PaletteCenterAction -> action.toControlIdOrNull()?.let { PlayerPanelItem.Control(it) }
        is PlacedBarItem -> item
    }
}

internal fun PlayerControlId.toCenterActionOrNull(seekSeconds: Int): CenterSlotAction? = when (this) {
    PlayerControlId.Previous -> CenterSlotAction.Previous
    PlayerControlId.Next -> CenterSlotAction.Next
    PlayerControlId.SeekBackward -> CenterSlotAction.SeekBackward(seekSeconds)
    PlayerControlId.SeekForward -> CenterSlotAction.SeekForward(seekSeconds)
    else -> null
}

internal data class ActiveDrag(
    val payload: DragPayload,
    val pointerInWindow: Offset,
)

/**
 * 拖放命中目标：栏位插入、中央/音频侧槽位替换或移除。
 */
internal sealed interface DropTarget {
    data class BarInsert(val bar: PlayerBarSlot, val insertIndex: Int) : DropTarget
    data class CenterSlot(val position: CenterSlotPosition) : DropTarget
    data object Remove : DropTarget
}

internal class LayoutCoordsRef(var value: LayoutCoordinates? = null)

/**
 * 竖屏信息流的 [PlayerBarSlot.Bottom] 在画面右侧竖排渲染，插入点按 Y 轴中线计算。
 */
internal fun isBarVerticalRail(mode: PlayerLayoutMode, bar: PlayerBarSlot): Boolean =
    mode == PlayerLayoutMode.ClipsPortrait && bar == PlayerBarSlot.Bottom

/**
 * 轻点托盘控件芯片时自动挑选最合理的合法栏位，省去手动切换「顶栏/底栏」目标的步骤。
 */
internal fun defaultBarForQuickAdd(controlId: PlayerControlId, mode: PlayerLayoutMode): PlayerBarSlot {
    if (mode.isClips) {
        val inTop = controlId in allowedControlsFor(mode, PlayerBarSlot.Top)
        val inBottom = controlId in allowedControlsFor(mode, PlayerBarSlot.Bottom)
        return when {
            inTop && !inBottom -> PlayerBarSlot.Top
            inBottom && !inTop -> PlayerBarSlot.Bottom
            else -> PlayerBarSlot.Bottom
        }
    }
    return when (controlId) {
        PlayerControlId.Title,
        PlayerControlId.SystemStatus,
        PlayerControlId.Lock,
        PlayerControlId.PinOnTop,
        -> PlayerBarSlot.Top
        else -> PlayerBarSlot.Bottom
    }
}

/**
 * 轻点托盘弹性空白时的默认目标栏位（信息流仅顶栏支持空白）。
 */
internal fun defaultBarForQuickAddSpacer(mode: PlayerLayoutMode): PlayerBarSlot? = when {
    allowsSpacerIn(mode, PlayerBarSlot.Bottom) -> PlayerBarSlot.Bottom
    allowsSpacerIn(mode, PlayerBarSlot.Top) -> PlayerBarSlot.Top
    else -> null
}

private val SIDE_SLOT_POSITIONS = listOf(
    CenterSlotPosition.OuterLeft,
    CenterSlotPosition.InnerLeft,
    CenterSlotPosition.InnerRight,
    CenterSlotPosition.OuterRight,
)

/**
 * 播放器自定义面板拖放状态与命中解析引擎（与 Compose 渲染解耦，可独立单元测试）。
 */
@Stable
internal class PlayerEditorDragState {
    var activeDrag by mutableStateOf<ActiveDrag?>(null)
    var rootOriginInWindow: Offset = Offset.Zero
    var topBarBounds: Rect? = null
    var bottomBarBounds: Rect? = null
    var paletteBounds: Rect? = null
    var previewCanvasBounds: Rect? = null
    val barItemBounds = mutableMapOf<Pair<PlayerBarSlot, Int>, Rect>()
    val centerSlotBounds = mutableMapOf<CenterSlotPosition, Rect>()

    fun startDrag(payload: DragPayload, pointerInWindow: Offset) {
        if (!pointerInWindow.x.isFinite() || !pointerInWindow.y.isFinite()) return
        activeDrag = ActiveDrag(payload, pointerInWindow)
    }

    fun updateDrag(pointerInWindow: Offset) {
        if (!pointerInWindow.x.isFinite() || !pointerInWindow.y.isFinite()) return
        val current = activeDrag ?: return
        activeDrag = current.copy(pointerInWindow = pointerInWindow)
    }

    fun cancelDrag() {
        activeDrag = null
    }

    fun pruneBarBounds(topSize: Int, bottomSize: Int) {
        barItemBounds.keys.removeAll { (bar, idx) ->
            when (bar) {
                PlayerBarSlot.Top -> idx >= topSize
                PlayerBarSlot.Bottom -> idx >= bottomSize
            }
        }
    }

    fun clearBounds() {
        topBarBounds = null
        bottomBarBounds = null
        paletteBounds = null
        previewCanvasBounds = null
        barItemBounds.clear()
        centerSlotBounds.clear()
    }

    fun resolveDropTarget(
        layout: PlayerFormFactorLayout,
        mode: PlayerLayoutMode,
        seekSeconds: Int,
    ): DropTarget? {
        val drag = activeDrag ?: return null
        val pointer = drag.pointerInWindow

        // 1. 视频模式中央/底栏主控簇 4 侧槽位命中检测（仅接受可映射为中央动作的负载）
        if (mode.isVideo && drag.payload.toCenterSlotActionOrNull(seekSeconds) != null) {
            for (pos in SIDE_SLOT_POSITIONS) {
                val rect = centerSlotBounds[pos] ?: continue
                if (rect.inflate(14f).contains(pointer)) {
                    return DropTarget.CenterSlot(pos)
                }
            }
        }

        // 2. 顶栏与底栏（含竖屏信息流右侧竖排操作栏）插入位置计算，严格校验每栏白名单
        val panelItem = drag.payload.toPanelItemOrNull()
        val blockedVideoBack = mode.isVideo &&
            panelItem is PlayerPanelItem.Control &&
            panelItem.id == PlayerControlId.Back
        if (panelItem != null && !blockedVideoBack) {
            val topRect = topBarBounds
            if (
                topRect != null &&
                topRect.inflate(18f).contains(pointer) &&
                layout.isItemAllowedInBar(panelItem, PlayerBarSlot.Top, mode)
            ) {
                val insertIndex = computeBarInsertIndex(
                    bar = PlayerBarSlot.Top,
                    items = layout.topBar,
                    pointer = pointer,
                    mode = mode,
                )
                return DropTarget.BarInsert(PlayerBarSlot.Top, insertIndex)
            }
            val bottomRect = bottomBarBounds
            if (
                bottomRect != null &&
                bottomRect.inflate(18f).contains(pointer) &&
                layout.isItemAllowedInBar(panelItem, PlayerBarSlot.Bottom, mode)
            ) {
                val insertIndex = computeBarInsertIndex(
                    bar = PlayerBarSlot.Bottom,
                    items = layout.bottomBar,
                    pointer = pointer,
                    mode = mode,
                )
                return DropTarget.BarInsert(PlayerBarSlot.Bottom, insertIndex)
            }
        }

        // 3. 拖回托盘或拖出预览画布外移除已放置的栏位项
        if (drag.payload is DragPayload.PlacedBarItem) {
            val overPalette = paletteBounds?.inflate(12f)?.contains(pointer) == true
            val outsideCanvas = previewCanvasBounds?.let { !it.inflate(24f).contains(pointer) } == true
            if (overPalette || outsideCanvas) {
                return DropTarget.Remove
            }
        }

        return null
    }

    /**
     * 听音频预览的 4 侧槽位命中检测：拖入快捷动作即可直接指派槽位。
     */
    fun resolveListenDropTarget(seekSeconds: Int): DropTarget.CenterSlot? {
        val drag = activeDrag ?: return null
        if (drag.payload.toCenterSlotActionOrNull(seekSeconds) == null) return null
        val pointer = drag.pointerInWindow
        for (pos in SIDE_SLOT_POSITIONS) {
            val rect = centerSlotBounds[pos] ?: continue
            if (rect.inflate(16f).contains(pointer)) {
                return DropTarget.CenterSlot(pos)
            }
        }
        return null
    }

    internal fun computeBarInsertIndex(
        bar: PlayerBarSlot,
        items: List<PlayerPanelItem>,
        pointer: Offset,
        mode: PlayerLayoutMode,
    ): Int {
        val vertical = isBarVerticalRail(mode, bar)
        var idx = items.size
        for (i in items.indices) {
            val rect = barItemBounds[bar to i] ?: continue
            val beforeMidpoint = if (vertical) pointer.y < rect.center.y else pointer.x < rect.center.x
            if (beforeMidpoint) {
                idx = i
                break
            }
        }
        val minIndex = if (
            mode.isVideo &&
            bar == PlayerBarSlot.Top &&
            items.firstOrNull().let { it is PlayerPanelItem.Control && it.id == PlayerControlId.Back }
        ) {
            1
        } else {
            0
        }
        return idx.coerceIn(minIndex, items.size)
    }
}

/**
 * 按真实指针类型分流的编辑器拖动手势：
 * - 鼠标（[PointerType.Mouse]）在超过拖动阈值后立即起拖，点击（未超阈值）正常流向 `clickable`；
 * - 触屏/手写笔在滚动容器内（[requireLongPressOnTouch] = true，如控件托盘）通过长按起拖，
 *   彻底避免与页面纵向滚动误抢手势；在非滚动预览栏位上则超过阈值即可直拖。
 */
internal fun Modifier.playerEditorDraggable(
    key1: Any?,
    key2: Any? = null,
    key3: Any? = null,
    enabled: Boolean = true,
    requireLongPressOnTouch: Boolean,
    coordsRef: LayoutCoordsRef,
    onDragActivated: (() -> Unit)? = null,
    onDragStart: (windowPos: Offset) -> Unit,
    onDragMove: (windowPos: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(key1, key2, key3, requireLongPressOnTouch) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val isMouse = down.type == PointerType.Mouse
            var pointerWindow = Offset.Zero
            var started = false

            fun toWindow(localPos: Offset, delta: Offset): Offset {
                val coords = coordsRef.value
                return if (coords != null && coords.isAttached) {
                    coords.localToWindow(localPos)
                } else {
                    pointerWindow + delta
                }
            }

            try {
                if (requireLongPressOnTouch && !isMouse) {
                    val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    val coords = coordsRef.value
                    pointerWindow = if (coords != null && coords.isAttached) {
                        coords.localToWindow(longPress.position)
                    } else {
                        longPress.position
                    }
                    started = true
                    onDragActivated?.invoke()
                    onDragStart(pointerWindow)
                    val completed = drag(longPress.id) { change ->
                        val delta = change.positionChange()
                        change.consume()
                        pointerWindow = toWindow(change.position, delta)
                        onDragMove(pointerWindow)
                    }
                    if (completed) {
                        currentEvent.changes.forEach { if (it.changedToUp()) it.consume() }
                        onDragEnd()
                    } else {
                        onDragCancel()
                    }
                } else {
                    var totalOverSlop = Offset.Zero
                    val dragChange = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                        change.consume()
                        totalOverSlop = overSlop
                    } ?: return@awaitEachGesture

                    val coords = coordsRef.value
                    pointerWindow = if (coords != null && coords.isAttached) {
                        coords.localToWindow(dragChange.position)
                    } else {
                        down.position + totalOverSlop
                    }
                    started = true
                    onDragStart(pointerWindow)
                    val completed = drag(dragChange.id) { change ->
                        val delta = change.positionChange()
                        change.consume()
                        pointerWindow = toWindow(change.position, delta)
                        onDragMove(pointerWindow)
                    }
                    if (completed) {
                        currentEvent.changes.forEach { if (it.changedToUp()) it.consume() }
                        onDragEnd()
                    } else {
                        onDragCancel()
                    }
                }
            } catch (c: CancellationException) {
                if (started) onDragCancel()
                throw c
            }
        }
    }
}
