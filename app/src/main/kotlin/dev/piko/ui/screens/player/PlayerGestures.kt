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

/**
 * Gestures and system level controls for media playback: brightness, volume, and orientation.
 *
 * Documentation References:
 * - Android Audio Focus & Stream: android-docs-mirror/pages/media/optimize/audio-focus.md
 * - Android Immersive System Bars: android-docs-mirror/pages/develop/ui/views/layout/edge-to-edge.md
 * - Material 3 Interaction & Haptics: m3-material-mirror/pages/foundations.md
 */
internal enum class VerticalAdjust {
    Brightness,
    Volume,
}

internal sealed interface PlayerGesture {
    data class Seek(val startPositionMillis: Long, val deltaMillis: Long) : PlayerGesture
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

    fun set(fraction: Float) {
        val index = (fraction.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
        runCatching {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        }
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

/**
 * 覆盖整个播放区域的手势层。
 *
 * 单击显隐控件，双击左右半屏快退/快进，长按加速，横滑 seek，左右半屏竖滑调亮度/音量。
 * 锁定时只保留单击，其余手势一律不识别。
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
    onDoubleTapSeek: (forward: Boolean) -> Unit,
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
    val doubleTapSeek by rememberUpdatedState(onDoubleTapSeek)
    val speedBoost by rememberUpdatedState(onSpeedBoost)

    var gesture by remember { mutableStateOf<PlayerGesture?>(null) }
    var touchOrigin by remember { mutableStateOf(Offset.Zero) }
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
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        doubleTapSeek(offset.x > size.width / 2)
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
                        touchOrigin = offset
                        publish(null)
                    },
                    onDragEnd = {
                        (gesture as? PlayerGesture.Seek)?.let { seek ->
                            val target = (seek.startPositionMillis + seek.deltaMillis)
                                .coerceIn(0L, duration.coerceAtLeast(1L))
                            seekTo(target)
                        }
                        publish(null)
                    },
                    onDragCancel = { publish(null) },
                    onDrag = { change, _ ->
                        change.consume()
                        val dx = change.position.x - touchOrigin.x
                        val dy = change.position.y - touchOrigin.y

                        if (gesture == null) {
                            if (abs(dx) > GESTURE_SLOP_PX && abs(dx) > abs(dy)) {
                                publish(PlayerGesture.Seek(readPosition(), 0L))
                            } else if (abs(dy) > GESTURE_SLOP_PX && abs(dy) > abs(dx)) {
                                val leftSide = touchOrigin.x < size.width / 2
                                val kind = if (leftSide) VerticalAdjust.Brightness else VerticalAdjust.Volume
                                adjustBaseValue = if (leftSide) brightness.current() else volume.current()
                                publish(PlayerGesture.Adjust(kind, adjustBaseValue))
                            }
                        }

                        when (val active = gesture) {
                            is PlayerGesture.Seek -> {
                                publish(active.copy(deltaMillis = (dx * SEEK_MILLIS_PER_PX).toLong()))
                            }

                            is PlayerGesture.Adjust -> {
                                val fraction = (adjustBaseValue - dy / (size.height * ADJUST_TRAVEL_RATIO))
                                    .coerceIn(0f, 1f)
                                if (active.kind == VerticalAdjust.Brightness) {
                                    brightness.set(fraction)
                                } else {
                                    volume.set(fraction)
                                }
                                publish(active.copy(fraction = fraction))
                            }

                            null -> Unit
                        }
                    },
                )
            },
    )
}

private const val GESTURE_SLOP_PX = 24f
private const val SEEK_MILLIS_PER_PX = 120f
private const val ADJUST_TRAVEL_RATIO = 0.75f

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
