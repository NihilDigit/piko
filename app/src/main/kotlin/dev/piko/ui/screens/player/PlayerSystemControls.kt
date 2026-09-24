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

/*
 * 播放器的系统胶水：亮度、媒体音量、横竖屏与沉浸式系统栏。
 *
 * 亮度与音量走窗口属性和 AudioManager，而不是播放器后端：libmpv 的音量只是软件增益，
 * 系统音量键调的是另一路，两者叠加会出现「系统满格却很小声」。
 */

/**
 * 窗口级亮度控制。离开播放器时自动还原。
 */
internal class WindowBrightness(private val window: Window?) : PlayerLevelControl {
    override fun current(): Float {
        val override = window?.attributes?.screenBrightness ?: -1f
        if (override >= 0f) return override.coerceIn(0f, 1f)
        return runCatching {
            val resolver = window?.context?.contentResolver ?: return@runCatching 0.5f
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS).toFloat() / 255f
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
    }

    override fun set(fraction: Float): Float {
        val w = window ?: return fraction
        w.attributes = w.attributes.apply {
            // 0 会让部分机型直接关背光，留一点下限
            screenBrightness = fraction.coerceIn(0.02f, 1f)
        }
        return fraction
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
internal class MediaVolume(context: Context) : PlayerLevelControl {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val max: Int = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)

    override fun current(): Float =
        ((audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max).coerceIn(0f, 1f)

    override fun set(fraction: Float): Float {
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
internal fun rememberMediaVolume(): MediaVolume {
    val context = LocalContext.current
    return remember(context) { MediaVolume(context) }
}

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
