package dev.piko.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 播放器的手势层与系统胶水：亮度、媒体音量、横竖屏与沉浸式系统栏。
 *
 * 亮度与音量走窗口属性和 AudioManager，而不是播放器后端：ExoPlayer 与 libmpv
 * 的音量都只是软件增益，系统音量键调的是另一路，两者叠加会出现「系统满格却很小声」。
 */

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
 * 窗口级亮度控制。离开播放器时自动还原。
 */
internal class WindowBrightness(private val window: Window?) {
    fun current(): Float {
        val override = window?.attributes?.screenBrightness ?: -1f
        if (override >= 0f) return override.coerceIn(0f, 1f)
        return runCatching {
            val resolver = window?.context?.contentResolver ?: return@runCatching 0.5f
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS).toFloat() / 255f
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
    }

    fun set(fraction: Float) {
        val w = window ?: return
        w.attributes = w.attributes.apply {
            // 0 会让部分机型直接关背光，留一点下限
            screenBrightness = fraction.coerceIn(0.02f, 1f)
        }
    }

    fun release() {
        val w = window ?: return
        w.attributes = w.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
}

/**
 * 系统媒体音量控制。
 */
internal class MediaVolume(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val max: Int = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)

    fun current(): Float =
        ((audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max).coerceIn(0f, 1f)

    /** 设置音量，返回实际落到的档位比例。系统音量只有十几档，HUD 显示实际档位才不会与音量键对不上。 */
    fun set(fraction: Float): Float {
        val index = (fraction.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
        runCatching {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        }
        return index.toFloat() / max
    }
}

internal fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
internal fun rememberWindowBrightness(): WindowBrightness {
    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window }
    val brightness = remember(window) { WindowBrightness(window) }
    DisposableEffect(brightness) {
        onDispose { brightness.release() }
    }
    return brightness
}

@Composable
internal fun rememberMediaVolume(context: Context): MediaVolume =
    remember(context) { MediaVolume(context) }

/**
 * 控制屏幕沉浸式全屏与横竖屏旋转
 */
internal class ScreenOrientationController(private val activity: Activity?) {
    private val initialRequestedOrientation =
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    fun setLandscape() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
    }

    fun setPortrait() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        showSystemBars()
    }

    fun resetOrientation() {
        activity?.requestedOrientation = initialRequestedOrientation
        showSystemBars()
    }

    fun toggleOrientation(isCurrentlyLandscape: Boolean) {
        if (isCurrentlyLandscape) {
            setPortrait()
        } else {
            setLandscape()
        }
    }

    fun hideSystemBars() {
        val window = activity?.window ?: return
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    fun showSystemBars() {
        val window = activity?.window ?: return
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
internal fun rememberOrientationController(): ScreenOrientationController {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val controller = remember(activity) { ScreenOrientationController(activity) }
    DisposableEffect(controller) {
        onDispose {
            controller.resetOrientation()
            controller.showSystemBars()
        }
    }
    return controller
}

/**
 * 覆盖整个播放区域的手势层。
 *
 * 单击显隐控件，双击两侧快退/快进、中间播放暂停，长按加速，横滑 seek，
 * 左右半屏竖滑调亮度/音量。锁定时只保留单击，其余手势一律不识别。
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
    brightness: WindowBrightness,
    volume: MediaVolume,
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
                detectDragGestures(
                    onDragStart = { offset ->
                        dragOrigin = offset
                        dragTotal = Offset.Zero
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

                        if (gesture == null) {
                            val horizontal = abs(dragTotal.x) >= abs(dragTotal.y)
                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            if (horizontal) {
                                publish(PlayerGesture.Seek(readPosition(), 0L))
                            } else {
                                val leftSide = dragOrigin.x < size.width / 2
                                val kind = if (leftSide) VerticalAdjust.Brightness else VerticalAdjust.Volume
                                adjustBaseValue = if (leftSide) brightness.current() else volume.current()
                                publish(PlayerGesture.Adjust(kind, adjustBaseValue))
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
                                val applied = if (active.kind == VerticalAdjust.Brightness) {
                                    brightness.set(requested)
                                    requested
                                } else {
                                    volume.set(requested)
                                }
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

internal const val SIDE_ZONE_FRACTION = 0.35f

// 横向划过整个手势层宽度对应的时长。按宽度比例而不是按像素换算，
// 同一手势在不同密度、不同朝向下的幅度一致
private const val SEEK_FULL_SWEEP_MILLIS = 180_000f

// 竖向划过手势层高度的 75% 对应亮度或音量的全量程
private const val ADJUST_TRAVEL_RATIO = 0.75f
