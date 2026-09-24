package dev.piko.ui.screens.player

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.abs

/**
 * 竖滑手势与上下方向键调节的一路电平，取值 0 到 1。由平台实现：Android 的亮度是窗口属性、
 * 音量是系统媒体音量；桌面没有可调的屏幕亮度，音量是播放器自身的软件音量。
 */
interface PlayerLevelControl {
    fun current(): Float

    /** 设置并返回实际落到的值。系统音量只有十几档，HUD 显示实际档位才不会与音量键对不上。 */
    fun set(fraction: Float): Float
}

internal enum class VerticalAdjust {
    Brightness,
    Volume,
}

/**
 * 双击落点分区。两侧各三成五是快退与快进，中间三成是播放/暂停：快进快退靠的是
 * 手指落在哪半边，判据放宽到 35% 仍然分得清，而中间留出一块专门给播放控制。
 */
internal enum class DoubleTapZone { Rewind, PlayPause, Forward }

internal sealed interface PlayerGesture {
    data class Seek(val startPositionMillis: Long, val deltaMillis: Long) : PlayerGesture {
        fun targetMillis(durationMillis: Long): Long =
            (startPositionMillis + deltaMillis).coerceIn(0L, durationMillis.coerceAtLeast(0L))
    }

    data class Adjust(val kind: VerticalAdjust, val fraction: Float) : PlayerGesture
}

/**
 * 覆盖整个播放区域的手势层。
 *
 * 单击显隐控件，双击两侧快退/快进、中间播放暂停，长按加速，横滑 seek，
 * 左右半屏竖滑调亮度/音量；平台没有亮度时整个宽度都调音量，两样都没有时竖滑不起作用。
 * 锁定时只保留单击，其余手势一律不识别。鼠标的点击与拖动也走这里，不另写一套。
 *
 * 方向判定只做一次：detectDragGestures 已经等过系统 touchSlop，越过 slop 的那一刻
 * 按位移的主方向锁定，之后不再切换。旧实现在 slop 之后又叠了 24px 的固定阈值，
 * 这个像素值随屏幕密度变化，高密度屏上手势起步明显发粘。
 */
@Composable
internal fun PlayerGestureLayer(
    isLocked: Boolean,
    durationMillis: Long,
    positionProvider: () -> Long,
    brightness: PlayerLevelControl?,
    volume: PlayerLevelControl?,
    onGestureChange: (PlayerGesture?) -> Unit,
    onToggleControls: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onDoubleTap: (zone: DoubleTapZone) -> Unit,
    onSpeedBoost: (active: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    // pointerInput 只以 isLocked 为 key，其余入参走 rememberUpdatedState，
    // 否则播放位置每变一次都会重建手势检测协程，拖拽会被打断
    val duration by rememberUpdatedState(durationMillis)
    val readPosition by rememberUpdatedState(positionProvider)
    val gestureChanged by rememberUpdatedState(onGestureChange)
    val toggleControls by rememberUpdatedState(onToggleControls)
    val seekTo by rememberUpdatedState(onSeekTo)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val speedBoost by rememberUpdatedState(onSpeedBoost)
    val brightnessControl by rememberUpdatedState(brightness)
    val volumeControl by rememberUpdatedState(volume)

    var gesture by remember { mutableStateOf<PlayerGesture?>(null) }
    var dragTotal by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    var adjustBaseValue by remember { mutableFloatStateOf(0f) }
    var boosting by remember { mutableStateOf(false) }

    fun publish(next: PlayerGesture?) {
        gesture = next
        gestureChanged(next)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isLocked) {
                if (isLocked) {
                    detectTapGestures(onTap = { toggleControls() })
                    return@pointerInput
                }
                detectTapGestures(
                    onTap = { toggleControls() },
                    onDoubleTap = { offset ->
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        doubleTap(
                            when {
                                offset.x < size.width * SIDE_ZONE_FRACTION -> DoubleTapZone.Rewind
                                offset.x > size.width * (1f - SIDE_ZONE_FRACTION) -> DoubleTapZone.Forward
                                else -> DoubleTapZone.PlayPause
                            },
                        )
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        boosting = true
                        speedBoost(true)
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (boosting) {
                            boosting = false
                            speedBoost(false)
                        }
                    },
                )
            }
            .pointerInput(isLocked) {
                if (isLocked) return@pointerInput
                // 方向只判一次；竖滑而平台没有对应电平时，这次拖动整个忽略
                var directionDecided = false
                detectDragGestures(
                    onDragStart = { offset ->
                        dragOrigin = offset
                        dragTotal = Offset.Zero
                        directionDecided = false
                        publish(null)
                    },
                    onDragEnd = {
                        (gesture as? PlayerGesture.Seek)?.let { seek -> seekTo(seek.targetMillis(duration)) }
                        publish(null)
                    },
                    onDragCancel = { publish(null) },
                    onDrag = { change, dragAmount ->
                        // 长按加速时手指难免移动，这时的位移不算拖动手势
                        if (boosting) return@detectDragGestures
                        change.consume()
                        dragTotal += dragAmount

                        if (!directionDecided) {
                            directionDecided = true
                            val started = if (abs(dragTotal.x) >= abs(dragTotal.y)) {
                                PlayerGesture.Seek(readPosition(), 0L)
                            } else {
                                val leftSide = dragOrigin.x < size.width / 2
                                val kind = if (leftSide && brightnessControl != null) {
                                    VerticalAdjust.Brightness
                                } else {
                                    VerticalAdjust.Volume
                                }
                                levelFor(kind, brightnessControl, volumeControl)?.let { control ->
                                    adjustBaseValue = control.current()
                                    PlayerGesture.Adjust(kind, adjustBaseValue)
                                }
                            }
                            if (started != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                publish(started)
                            }
                        }

                        when (val active = gesture) {
                            is PlayerGesture.Seek -> {
                                val delta = dragTotal.x / size.width * SEEK_FULL_SWEEP_MILLIS
                                publish(active.copy(deltaMillis = delta.toLong()))
                            }

                            is PlayerGesture.Adjust -> {
                                val requested = (adjustBaseValue - dragTotal.y / (size.height * ADJUST_TRAVEL_RATIO))
                                    .coerceIn(0f, 1f)
                                val applied = levelFor(active.kind, brightnessControl, volumeControl)?.set(requested)
                                    ?: requested
                                if (applied != active.fraction && (applied == 0f || applied == 1f)) {
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                publish(active.copy(fraction = applied))
                            }

                            null -> Unit
                        }
                    },
                )
            },
    )
}

private fun levelFor(
    kind: VerticalAdjust,
    brightness: PlayerLevelControl?,
    volume: PlayerLevelControl?,
): PlayerLevelControl? = if (kind == VerticalAdjust.Brightness) brightness else volume

internal const val SIDE_ZONE_FRACTION = 0.35f

// 横向划过整个手势层宽度对应的时长。按宽度比例而不是按像素换算，
// 同一手势在不同密度、不同朝向下的幅度一致
private const val SEEK_FULL_SWEEP_MILLIS = 180_000f

// 竖向划过手势层高度的 75% 对应亮度或音量的全量程
private const val ADJUST_TRAVEL_RATIO = 0.75f
