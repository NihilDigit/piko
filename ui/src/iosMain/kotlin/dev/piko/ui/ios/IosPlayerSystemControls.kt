@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.piko.ui.ios

import dev.piko.ui.screens.player.PlayerLevelControl
import platform.Foundation.NSNumber
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIScreen
import kotlin.math.roundToLong

/**
 * 屏幕亮度手势控制器：直接读写 `UIScreen.mainScreen.brightness`，
 * 下限保留 `0.02f` 防止误滑黑屏，并在离开播放器时恢复初始亮度。
 */
internal class IosScreenBrightnessControl : PlayerLevelControl {
    private val initialBrightness: Double = UIScreen.mainScreen.brightness
    private var touched = false

    override fun current(): Float = UIScreen.mainScreen.brightness.toFloat().coerceIn(MIN_BRIGHTNESS, 1f)

    override fun set(fraction: Float): Float {
        val clamped = fraction.coerceIn(MIN_BRIGHTNESS, 1f)
        touched = true
        UIScreen.mainScreen.brightness = clamped.toDouble()
        return clamped
    }

    fun restore() {
        if (touched) {
            UIScreen.mainScreen.brightness = initialBrightness
            touched = false
        }
    }

    private companion object {
        const val MIN_BRIGHTNESS = 0.02f
    }
}

/**
 * 播放器软件音量手势控制器：直接调节 `AVPlayer.volume`（0..1），
 * 避免依赖私有 `MPVolumeView` 滑块遍历。
 */
internal class IosPlayerVolumeControl(
    private val backend: IosPlaybackBackend,
) : PlayerLevelControl {
    override fun current(): Float = backend.volume.coerceIn(0f, 1f)

    override fun set(fraction: Float): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        backend.setVolume(clamped)
        return clamped
    }
}

/**
 * iOS 锁屏与控制中心媒体会话桥（`MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`）：
 * 同步当前媒体元数据与播放进度，并响应耳机线控、锁屏与控制中心的播放控制指令。
 */
internal class IosNowPlayingController(
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onTogglePlayPause: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onSeekBy: (Long) -> Unit,
    private val seekStepSecondsProvider: () -> Int,
) {
    private val center = MPRemoteCommandCenter.sharedCommandCenter()
    private val targets = mutableListOf<Pair<platform.MediaPlayer.MPRemoteCommand, Any>>()
    private var disposed = false

    init {
        bind(center.playCommand) {
            onPlay()
            MPRemoteCommandHandlerStatusSuccess
        }
        bind(center.pauseCommand) {
            onPause()
            MPRemoteCommandHandlerStatusSuccess
        }
        bind(center.togglePlayPauseCommand) {
            onTogglePlayPause()
            MPRemoteCommandHandlerStatusSuccess
        }
        bind(center.previousTrackCommand) {
            onPrevious()
            MPRemoteCommandHandlerStatusSuccess
        }
        bind(center.nextTrackCommand) {
            onNext()
            MPRemoteCommandHandlerStatusSuccess
        }
        bind(center.skipForwardCommand) {
            onSeekBy(seekStepSecondsProvider().toLong() * 1_000L)
            MPRemoteCommandHandlerStatusSuccess
        }
        bind(center.skipBackwardCommand) {
            onSeekBy(-seekStepSecondsProvider().toLong() * 1_000L)
            MPRemoteCommandHandlerStatusSuccess
        }
        bind(center.changePlaybackPositionCommand) { event ->
            val posEvent = event as? MPChangePlaybackPositionCommandEvent
            if (posEvent != null && posEvent.positionTime.isFinite() && posEvent.positionTime >= 0.0) {
                onSeekTo((posEvent.positionTime * 1_000.0).roundToLong())
            }
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    fun update(
        title: String,
        artist: String,
        durationMillis: Long,
        positionMillis: Long,
        isPlaying: Boolean,
        speed: Float,
        hasPrevious: Boolean,
        hasNext: Boolean,
        seekStepSeconds: Int,
    ) {
        if (disposed) return
        center.previousTrackCommand.enabled = hasPrevious
        center.nextTrackCommand.enabled = hasNext
        val step = seekStepSeconds.coerceAtLeast(1)
        center.skipForwardCommand.preferredIntervals = listOf(NSNumber(int = step))
        center.skipBackwardCommand.preferredIntervals = listOf(NSNumber(int = step))

        val info = LinkedHashMap<Any?, Any>()
        info[MPMediaItemPropertyTitle] = title
        info[MPMediaItemPropertyArtist] = artist
        if (durationMillis > 0L) {
            info[MPMediaItemPropertyPlaybackDuration] = durationMillis.toDouble() / 1_000.0
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = positionMillis.coerceAtLeast(0L).toDouble() / 1_000.0
        info[MPNowPlayingInfoPropertyPlaybackRate] = if (isPlaying) speed.toDouble() else 0.0
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    fun clear() {
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        for ((command, token) in targets) {
            command.removeTarget(token)
        }
        targets.clear()
        clear()
    }

    private fun bind(
        command: platform.MediaPlayer.MPRemoteCommand,
        handler: (platform.MediaPlayer.MPRemoteCommandEvent?) -> Long,
    ) {
        command.enabled = true
        val token = command.addTargetWithHandler(handler)
        targets += command to token
    }
}
