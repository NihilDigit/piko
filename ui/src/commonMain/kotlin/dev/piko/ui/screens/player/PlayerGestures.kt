package dev.piko.ui.screens.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.MAX_DOUBLE_TAP_ZONE_FRACTION
import dev.piko.shared.media.player.MAX_VERTICAL_ZONE_FRACTION
import dev.piko.shared.media.player.MIN_DOUBLE_TAP_ZONE_FRACTION
import dev.piko.shared.media.player.MIN_VERTICAL_ZONE_FRACTION
import dev.piko.shared.media.player.PlayerGestureConfig
import dev.piko.shared.media.player.VerticalSwipeAction
import kotlin.math.abs

/**
 * 竖滑手势与上下方向键调节的一路电平，取值 0 到 1。由平台实现：Android 的亮度是窗口属性、
 * 音量是系统媒体音量；桌面没有可调的屏幕亮度，音量是播放器自身的软件音量。
 */
@Stable
interface PlayerLevelControl {
    fun current(): Float

    /** 设置并返回实际落到的值。系统音量只有十几档，HUD 显示实际档位才不会与音量键对不上。 */
    fun set(fraction: Float): Float
}

internal enum class VerticalAdjust {
    Brightness,
    Volume,
}

/** 双击落点分区：两侧为快退/快进，中间为播放/暂停。 */
internal enum class DoubleTapZone { Rewind, PlayPause, Forward }

internal sealed interface PlayerGesture {
    /** 横划进退；[cancelled] 为真时松手取消（拖入顶部两角取消区）。 */
    data class Seek(
        val startPositionMillis: Long,
        val deltaMillis: Long,
        val cancelled: Boolean = false,
    ) : PlayerGesture {
        fun targetMillis(durationMillis: Long): Long =
            if (cancelled) startPositionMillis
            else (startPositionMillis + deltaMillis).coerceIn(0L, durationMillis.coerceAtLeast(0L))
    }

    /** 纵划或滚轮/按键调节的亮度或音量；[dockEnd] 决定贴靠右侧还是左侧（触屏纵划时贴对侧避开手指）。 */
    data class Adjust(
        val kind: VerticalAdjust,
        val fraction: Float,
        val dockEnd: Boolean = true,
    ) : PlayerGesture

    /** 纵划切集手势：direction 1 = 上滑下一集，-1 = 下滑上一集，0 = 未定方向。 */
    data class EpisodeSwipe(
        val direction: Int,
        val armed: Boolean,
    ) : PlayerGesture
}

private sealed interface SurfaceGestureRace {
    data object Ended : SurfaceGestureRace
    data class DragStarted(val change: PointerInputChange) : SurfaceGestureRace
}

/**
 * 覆盖整个播放区域的统一手势层（移植自 Bilby `PlayerShell` + `SpeedSelectionGestures`）：
 *
 * - **互斥状态机**：单个 `awaitEachGesture` 内在 `touchSlop` 与 `longPressTimeoutMillis` 之间二选一，
 *   拖拽（横划 seek / 纵划亮度、音量与切集）与长按倍速选档严格互斥，绝不串扰；
 * - **手势自定义**：完整响应 [PlayerGestureConfig]（双击进退开关与区域占比、长按倍速与拨盘开关、
 *   横划进退开关、灵敏度与顶部两角取消区、左右侧纵划动作与区域占比）；
 * - **连点续进退**：双击进退后在读数停留期间单点同侧继续累加步进，与现有移动端单测契约完全兼容；
 * - **边缘礼让**：从系统返回手势区起手的拖动不消费，交还系统导航。
 */
@Composable
internal fun PlayerGestureLayer(
    isLocked: Boolean,
    durationMillis: Long,
    positionProvider: () -> Long,
    brightness: PlayerLevelControl?,
    volume: PlayerLevelControl?,
    speedController: PlaybackSpeedController?,
    speedSupported: Boolean,
    gestureConfig: PlayerGestureConfig,
    canPrevious: Boolean,
    canNext: Boolean,
    onGestureChange: (PlayerGesture?) -> Unit,
    onToggleControls: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onDoubleTap: (zone: DoubleTapZone) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeedStart: () -> Unit,
    modifier: Modifier = Modifier,
    // 为 true 时两侧的单点也交给 onDoubleTap，不等第二下：双击进退之后，读数还在就接着点
    seekTapActive: () -> Boolean = { false },
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val window = LocalWindowInfo.current.containerSize
    val verticalDial = window.width > window.height

    val duration by rememberUpdatedState(durationMillis)
    val readPosition by rememberUpdatedState(positionProvider)
    val gestureChanged by rememberUpdatedState(onGestureChange)
    val toggleControls by rememberUpdatedState(onToggleControls)
    val seekTo by rememberUpdatedState(onSeekTo)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val prevCallback by rememberUpdatedState(onPrevious)
    val nextCallback by rememberUpdatedState(onNext)
    val speedStart by rememberUpdatedState(onSpeedStart)
    val seekTapActiveState by rememberUpdatedState(seekTapActive)
    val brightnessControl by rememberUpdatedState(brightness)
    val volumeControl by rememberUpdatedState(volume)
    val currentGestures by rememberUpdatedState(gestureConfig)
    val currentSpeedSupported by rememberUpdatedState(speedSupported)
    val currentCanPrevious by rememberUpdatedState(canPrevious)
    val currentCanNext by rememberUpdatedState(canNext)

    val gestureInsets = WindowInsets.systemGestures
    val edgeLeft by rememberUpdatedState(gestureInsets.getLeft(density, layoutDirection))
    val edgeRight by rememberUpdatedState(gestureInsets.getRight(density, layoutDirection))
    val episodeSwipeMinPx = with(density) { 48.dp.toPx() }

    var activeGesture by remember { mutableStateOf<PlayerGesture?>(null) }

    fun publish(next: PlayerGesture?) {
        activeGesture = next
        gestureChanged(next)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 外层点按/双击/连点续进退：放在互斥拖拽与长按状态机之前，PointerEventPass.Main 为链尾优先，
            // 仅当后面的状态机未消费（非拖拽、非长按倍速）时这里才收到干净的抬手事件。
            .pointerInput(isLocked, speedController) {
                if (isLocked) {
                    detectTapGestures(onTap = { toggleControls() })
                    return@pointerInput
                }
                fun zoneAt(x: Float): DoubleTapZone {
                    if (!currentGestures.doubleTapSeekEnabled) return DoubleTapZone.PlayPause
                    val zoneFraction = currentGestures.doubleTapZoneFraction.coerceIn(
                        MIN_DOUBLE_TAP_ZONE_FRACTION,
                        MAX_DOUBLE_TAP_ZONE_FRACTION,
                    )
                    return when {
                        x <= size.width * zoneFraction -> DoubleTapZone.Rewind
                        x >= size.width * (1f - zoneFraction) -> DoubleTapZone.Forward
                        else -> DoubleTapZone.PlayPause
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    if (isNonPrimaryMouseDown(down) || speedController?.active == true) return@awaitEachGesture
                    val zone = zoneAt(down.position.x)
                    val continuesSeek = zone != DoubleTapZone.PlayPause && seekTapActiveState()

                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    up.consume()

                    if (continuesSeek) {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        doubleTap(zone)
                        return@awaitEachGesture
                    }
                    val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                        awaitFirstDown(requireUnconsumed = true)
                    }
                    if (secondDown == null) {
                        toggleControls()
                        return@awaitEachGesture
                    }
                    val secondUp = waitForUpOrCancellation()
                    if (secondUp == null) {
                        // 第二下演变成了拖拽或长按，不补发单击
                        return@awaitEachGesture
                    }
                    secondUp.consume()
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    doubleTap(zoneAt(secondUp.position.x))
                }
            }
            // 内层（链尾优先）：统一互斥的拖拽（横划 seek / 纵划亮度、音量与切集）与长按倍速选档状态机
            .pointerInput(isLocked, speedController, verticalDial) {
                if (isLocked) return@pointerInput
                var speedOwned = false
                var dragActive = false
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        if (isNonPrimaryMouseDown(down) || speedController?.active == true) return@awaitEachGesture

                        val pointerId = down.id
                        val startPosition = down.position
                        var lastPosition = startPosition
                        val touchSlop = viewConfiguration.touchSlop
                        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                        val canSpeedDial = currentGestures.longPressSpeedEnabled &&
                            currentSpeedSupported &&
                            speedController != null

                        var dragStartChange: PointerInputChange? = null
                        var enterSpeedDial = false

                        if (canSpeedDial) {
                            val outcome: SurfaceGestureRace? = withTimeoutOrNull(longPressTimeout) {
                                var result: SurfaceGestureRace = SurfaceGestureRace.Ended
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                    if (change.isConsumed || !change.pressed) break
                                    lastPosition = change.position
                                    if ((change.position - startPosition).getDistance() > touchSlop) {
                                        result = SurfaceGestureRace.DragStarted(change)
                                        break
                                    }
                                }
                                result
                            }
                            when (outcome) {
                                null -> enterSpeedDial = true
                                SurfaceGestureRace.Ended -> return@awaitEachGesture
                                is SurfaceGestureRace.DragStarted -> dragStartChange = outcome.change
                            }
                        } else {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: return@awaitEachGesture
                                if (change.isConsumed || !change.pressed) {
                                    return@awaitEachGesture
                                }
                                lastPosition = change.position
                                if ((change.position - startPosition).getDistance() > touchSlop) {
                                    dragStartChange = change
                                    break
                                }
                            }
                        }

                        if (enterSpeedDial) {
                            val controller = speedController ?: return@awaitEachGesture
                            if (!currentGestures.longPressSpeedEnabled || controller.active) return@awaitEachGesture
                            val dial = currentGestures.longPressSpeedDialEnabled
                            val dockEnd = dial && verticalDial && lastPosition.x < size.width / 2f
                            val dockTop = dial && !verticalDial && lastPosition.y >= size.height / 2f
                            controller.open(temporary = true, dockEnd = dockEnd, dockTop = dockTop, showDial = dial)
                            speedOwned = controller.beginDrag(vertical = verticalDial)
                            var displacement = Offset.Zero
                            var rapidExitAccumDp = 0f
                            publish(null)
                            speedStart()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                if (change == null) {
                                    if (speedOwned) controller.cancel()
                                    speedOwned = false
                                    break
                                }
                                if (!change.pressed) {
                                    val releasedCleanly = change.changedToUp()
                                    change.consume()
                                    if (speedOwned) {
                                        if (releasedCleanly) {
                                            controller.releaseHoldDrag()
                                        } else {
                                            controller.cancel()
                                        }
                                    }
                                    speedOwned = false
                                    break
                                }
                                val amount = change.positionChange()
                                change.consume()
                                if (!speedOwned || !currentGestures.longPressSpeedDialEnabled || amount == Offset.Zero) continue
                                if (controller.isRapidScrubActive) {
                                    // 进入 ∞ 粗剪速览后：横向滑动直接扫过整片时间轴，横屏大幅上滑可退回 3.5x
                                    val viewportW = size.width.toFloat().coerceAtLeast(1f)
                                    if (amount.x != 0f) {
                                        controller.scrubRapidByFraction(amount.x / viewportW)
                                    }
                                    if (verticalDial && amount.y < 0f && abs(amount.y) > abs(amount.x) * 1.4f) {
                                        rapidExitAccumDp += -amount.y / density.density
                                        if (rapidExitAccumDp >= 44f) {
                                            rapidExitAccumDp = 0f
                                            controller.exitRapidScrubToMaxSpeed()
                                            displacement = Offset.Zero
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    } else {
                                        rapidExitAccumDp = 0f
                                    }
                                    continue
                                }
                                rapidExitAccumDp = 0f
                                displacement += amount
                                val before = controller.selection
                                val acrossPx = when {
                                    !verticalDial && dockTop -> -displacement.y
                                    !verticalDial -> displacement.y
                                    dockEnd -> displacement.x
                                    else -> -displacement.x
                                }
                                controller.move(
                                    alongDp = (if (verticalDial) displacement.y else displacement.x) / density.density,
                                    acrossDp = acrossPx / density.density,
                                )
                                val after = controller.selection
                                if (after?.locked != before?.locked || after?.isRapidScrub != before?.isRapidScrub) {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                }
                            }
                            return@awaitEachGesture
                        }

                        val firstChange = dragStartChange ?: return@awaitEachGesture
                        // 从系统返回手势区起手的拖动不消费，礼让系统导航
                        if (startPosition.x < edgeLeft || startPosition.x > size.width - edgeRight) {
                            return@awaitEachGesture
                        }

                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val height = size.height.toFloat().coerceAtLeast(1f)
                        val startXFraction = startPosition.x / width
                        var accumulated = firstChange.position - startPosition
                        var adjustBaseValue = 0f
                        // 起手就在取消区里则本次不启用取消，避免横屏拇指搭在角上误触发取消
                        var seekCancelArmed: Boolean? =
                            if (!currentGestures.swipeSeekCancelZone || inSeekCancelZone(startPosition, width, height)) {
                                null
                            } else {
                                false
                            }

                        dragActive = true
                        firstChange.consume()

                        val horizontal = abs(accumulated.x) >= abs(accumulated.y)
                        val started: PlayerGesture? = if (horizontal) {
                            if (currentGestures.swipeSeekEnabled && duration > 0L) {
                                PlayerGesture.Seek(readPosition(), 0L, cancelled = false)
                            } else {
                                null
                            }
                        } else if (currentGestures.swipeBrightnessVolumeEnabled) {
                            val zoneFraction = currentGestures.verticalZoneFraction.coerceIn(
                                MIN_VERTICAL_ZONE_FRACTION,
                                MAX_VERTICAL_ZONE_FRACTION,
                            )
                            val action = when {
                                startXFraction < zoneFraction -> currentGestures.leftVerticalSwipe
                                startXFraction > 1f - zoneFraction -> currentGestures.rightVerticalSwipe
                                else -> VerticalSwipeAction.None
                            }
                            val dockEnd = startXFraction < 0.5f
                            when (action) {
                                VerticalSwipeAction.Brightness -> brightnessControl?.let { ctrl ->
                                    adjustBaseValue = ctrl.current()
                                    PlayerGesture.Adjust(VerticalAdjust.Brightness, adjustBaseValue, dockEnd = dockEnd)
                                }
                                VerticalSwipeAction.Volume -> volumeControl?.let { ctrl ->
                                    adjustBaseValue = ctrl.current()
                                    PlayerGesture.Adjust(VerticalAdjust.Volume, adjustBaseValue, dockEnd = dockEnd)
                                }
                                VerticalSwipeAction.Episode -> if (currentCanPrevious || currentCanNext) {
                                    PlayerGesture.EpisodeSwipe(direction = 0, armed = false)
                                } else {
                                    null
                                }
                                VerticalSwipeAction.None -> null
                            }
                        } else {
                            null
                        }

                        if (started != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            publish(started)
                        }

                        fun updateDrag(position: Offset, w: Float, h: Float) {
                            if (seekCancelArmed != null) {
                                val inZone = inSeekCancelZone(position, w, h)
                                if (inZone && seekCancelArmed == false) {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                }
                                seekCancelArmed = inZone
                            }
                            when (val current = activeGesture) {
                                is PlayerGesture.Seek -> {
                                    val dur = duration
                                    val baseSpan = if (dur <= 60_000L) {
                                        dur.coerceAtLeast(1L)
                                    } else {
                                        (dur / 4L).coerceIn(60_000L, 300_000L)
                                    }
                                    val span = (baseSpan * currentGestures.swipeSeekSensitivity.spanMultiplier)
                                        .toLong()
                                        .coerceAtLeast(1L)
                                    val delta = (accumulated.x / w * span).toLong()
                                    val cancelled = seekCancelArmed == true
                                    publish(current.copy(deltaMillis = delta, cancelled = cancelled))
                                }

                                is PlayerGesture.Adjust -> {
                                    val requested = (adjustBaseValue - accumulated.y / (h * ADJUST_TRAVEL_RATIO))
                                        .coerceIn(0f, 1f)
                                    val applied = levelFor(current.kind, brightnessControl, volumeControl)?.set(requested)
                                        ?: requested
                                    if (applied != current.fraction && (applied == 0f || applied == 1f)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    }
                                    publish(current.copy(fraction = applied))
                                }

                                is PlayerGesture.EpisodeSwipe -> {
                                    val thresholdPx = (h * EPISODE_SWIPE_THRESHOLD_FRACTION)
                                        .coerceAtLeast(episodeSwipeMinPx)
                                    val dy = accumulated.y
                                    val dir = when {
                                        dy < -8f -> 1
                                        dy > 8f -> -1
                                        else -> 0
                                    }
                                    val canTrigger = when (dir) {
                                        1 -> currentCanNext
                                        -1 -> currentCanPrevious
                                        else -> false
                                    }
                                    val armed = canTrigger && abs(dy) >= thresholdPx
                                    if (armed && !current.armed) {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    }
                                    publish(PlayerGesture.EpisodeSwipe(direction = dir, armed = armed))
                                }

                                null -> Unit
                            }
                        }

                        if (accumulated != Offset.Zero) {
                            updateDrag(firstChange.position, width, height)
                        }

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == pointerId }
                            if (change == null || !change.pressed) {
                                val committed = change != null && change.changedToUp()
                                if (committed) {
                                    change?.consume()
                                    when (val finished = activeGesture) {
                                        is PlayerGesture.Seek -> if (!finished.cancelled) {
                                            seekTo(finished.targetMillis(duration))
                                        }
                                        is PlayerGesture.EpisodeSwipe -> if (finished.armed) {
                                            when (finished.direction) {
                                                1 -> nextCallback()
                                                -1 -> prevCallback()
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                                dragActive = false
                                publish(null)
                                break
                            }
                            if (change.isConsumed) {
                                dragActive = false
                                publish(null)
                                break
                            }
                            val delta = change.positionChange()
                            change.consume()
                            if (delta != Offset.Zero) {
                                accumulated += delta
                                updateDrag(
                                    change.position,
                                    size.width.toFloat().coerceAtLeast(1f),
                                    size.height.toFloat().coerceAtLeast(1f),
                                )
                            }
                        }
                    }
                } finally {
                    if (speedOwned) {
                        speedController?.cancel()
                        speedOwned = false
                    }
                    if (dragActive) {
                        dragActive = false
                        publish(null)
                    }
                }
            },
    )
}

private fun AwaitPointerEventScope.isNonPrimaryMouseDown(down: PointerInputChange): Boolean {
    if (down.type != PointerType.Mouse) return false
    val buttons = currentEvent.buttons
    val anyNonPrimary = buttons.isSecondaryPressed ||
        buttons.isTertiaryPressed ||
        buttons.isBackPressed ||
        buttons.isForwardPressed
    return anyNonPrimary && !buttons.isPrimaryPressed
}

private fun levelFor(
    kind: VerticalAdjust,
    brightness: PlayerLevelControl?,
    volume: PlayerLevelControl?,
): PlayerLevelControl? = if (kind == VerticalAdjust.Brightness) brightness else volume

/** 手指在顶部左上或右上 15% 区域内即触发「松手取消进退」（移植自 Bilby）。 */
private fun inSeekCancelZone(point: Offset, width: Float, height: Float): Boolean {
    val inTop = point.y <= height * SEEK_CANCEL_ZONE_FRACTION
    val nearSide = point.x <= width * SEEK_CANCEL_ZONE_FRACTION ||
        point.x >= width * (1f - SEEK_CANCEL_ZONE_FRACTION)
    return inTop && nearSide
}

private const val SEEK_CANCEL_ZONE_FRACTION = 0.15f
private const val EPISODE_SWIPE_THRESHOLD_FRACTION = 0.14f
private const val ADJUST_TRAVEL_RATIO = 0.75f
