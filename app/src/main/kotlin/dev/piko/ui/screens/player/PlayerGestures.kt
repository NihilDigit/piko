package dev.piko.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

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

    fun setLandscape() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
    }

    fun setPortrait() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        showSystemBars()
    }

    fun resetOrientation() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
