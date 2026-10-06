package dev.piko.ui.screens.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.PointerEventPass

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
    /** [cancelled]：手指往上挪开了，松手不跳，见 SEEK_CANCEL_DISTANCE。 */
    data class Seek(val startPositionMillis: Long, val deltaMillis: Long, val cancelled: Boolean = false) : PlayerGesture {
        fun targetMillis(durationMillis: Long): Long =
            if (cancelled) startPositionMillis
            else (startPositionMillis + deltaMillis).coerceIn(0L, durationMillis.coerceAtLeast(0L))
    }

    data class Adjust(val kind: VerticalAdjust, val fraction: Float) : PlayerGesture
}

/**
 * 覆盖整个播放区域的手势层。
 *
 * 单击显隐控件，双击两侧快退/快进（之后读数还在时单点即可接着进退）、中间播放暂停，长按加速，横滑 seek，
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
    // 为 true 时两侧的单点也交给 onDoubleTap，不等第二下：双击进退之后，读数还在就接着点
    seekTapActive: () -> Boolean = { false },
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
    val seekTapActive by rememberUpdatedState(seekTapActive)
    val brightnessControl by rememberUpdatedState(brightness)
    val volumeControl by rememberUpdatedState(volume)

    var gesture by remember { mutableStateOf<PlayerGesture?>(null) }
    var dragTotal by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    var adjustBaseValue by remember { mutableFloatStateOf(0f) }
    var boosting by remember { mutableStateOf(false) }

    // 系统返回手势占着的左右两条：从这里起手的拖动交给系统，不当作进退、调音量亮度。
    // 三键导航与桌面上宽度为 0，不受影响。点按不在此列：系统只拦从边缘起手的滑动
    val gestureInsets = WindowInsets.systemGestures
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val edgeLeft by rememberUpdatedState(gestureInsets.getLeft(density, layoutDirection))
    val edgeRight by rememberUpdatedState(gestureInsets.getRight(density, layoutDirection))
    // 按下点的横坐标。onDragStart 给的是越过 slop 时的位置，判断是不是从边缘起手要看按下点
    var pressX by remember { mutableFloatStateOf(0f) }

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
                // 不用 detectTapGestures：它只分单击与双击，进退中连点三下会拆成一次双击加一次单击，
                // 多出的单击把控件唤出来，想连进几步只能两下两下地点
                fun zoneAt(x: Float) = when {
                    x < size.width * SIDE_ZONE_FRACTION -> DoubleTapZone.Rewind
                    x > size.width * (1f - SIDE_ZONE_FRACTION) -> DoubleTapZone.Forward
                    else -> DoubleTapZone.PlayPause
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val zone = zoneAt(down.position.x)
                    // 在按下时就判定：等抬起时读数可能刚好收起，这一下就变成了等双击
                    val continuesSeek = zone != DoubleTapZone.PlayPause && seekTapActive()

                    // 拖动层越过 slop 后消费事件，这里随之得到 null，这一下不算点按
                    var cancelled = false
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation().also { if (it == null) cancelled = true }
                    }
                    if (cancelled) return@awaitEachGesture
                    if (up == null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        boosting = true
                        speedBoost(true)
                        // finally：按着时锁定画面，pointerInput 以 isLocked 为 key 重启，这个协程被取消，
                        // 等不到松开，不收尾的话临时倍速留着，拖动手势也一直被当成长按里的挪动而忽略
                        try {
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            if (boosting) {
                                boosting = false
                                speedBoost(false)
                            }
                        }
                        return@awaitEachGesture
                    }
                    up.consume()

                    if (continuesSeek) {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        doubleTap(zone)
                        return@awaitEachGesture
                    }
                    val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                    if (secondDown == null) {
                        toggleControls()
                        return@awaitEachGesture
                    }
                    secondDown.consume()
                    val secondUp = waitForUpOrCancellation()
                    if (secondUp == null) {
                        toggleControls()
                    } else {
                        secondUp.consume()
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        doubleTap(zoneAt(secondUp.position.x))
                    }
                }
            }
            // 只观察不消费，Initial 阶段先于下面的拖动检测拿到按下点
            .pointerInput(Unit) {
                awaitEachGesture {
                    pressX = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position.x
                }
            }
            .pointerInput(isLocked) {
                if (isLocked) return@pointerInput
                // 方向只判一次；竖滑而平台没有对应电平时，这次拖动整个忽略
                var directionDecided = false
                // 从系统返回手势区起手的这次拖动整个不理会，也不消费，系统照常接走
                var yieldedToSystem = false
                detectDragGestures(
                    onDragStart = { offset ->
                        yieldedToSystem = pressX < edgeLeft || pressX > size.width - edgeRight
                        dragOrigin = offset
                        dragTotal = Offset.Zero
                        directionDecided = false
                        publish(null)
                    },
                    onDragEnd = {
                        (gesture as? PlayerGesture.Seek)?.takeUnless { it.cancelled }?.let { seek -> seekTo(seek.targetMillis(duration)) }
                        publish(null)
                    },
                    onDragCancel = { publish(null) },
                    onDrag = { change, dragAmount ->
                        // 长按加速时手指难免移动，这时的位移不算拖动手势
                        if (boosting || yieldedToSystem) return@detectDragGestures
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
                            // 照 Animeko 的做法：划过整个宽度固定是 SEEK_FULL_WIDTH_MILLIS，与片长无关，同样一寸总是走同样多的秒数。
                            // 试过按片长等比（划满一屏即整部片长）：二十几分钟的片子随手一划就是几分钟，进度条一碰就飞；
                            // 更早按片长缩放量程再按手速加速，同样一寸走多远随片子与手速变，估不准落点。
                            // 往上挪开 SEEK_CANCEL_DISTANCE 即取消，挪回来又恢复
                            is PlayerGesture.Seek -> {
                                val delta = (dragTotal.x / size.width.coerceAtLeast(1) * SEEK_FULL_WIDTH_MILLIS).toLong()
                                val cancelled = -dragTotal.y > SEEK_CANCEL_DISTANCE.toPx()
                                if (cancelled != active.cancelled) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                publish(active.copy(deltaMillis = delta, cancelled = cancelled))
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

// 划过整个宽度跳的时长。Animeko 取 97 秒，为的是划满一屏刚好跳过番剧 90 秒的片头片尾；网盘里放的多是电影，
// 两个小时的片子一屏只走一分半太慢，取 5 分钟
private const val SEEK_FULL_WIDTH_MILLIS = 300_000L

// 横滑途中手指往上挪开多远取消这次进退，同取 Animeko 的值
private val SEEK_CANCEL_DISTANCE = 144.dp

// 竖向划过手势层高度的 75% 对应亮度或音量的全量程
private const val ADJUST_TRAVEL_RATIO = 0.75f
