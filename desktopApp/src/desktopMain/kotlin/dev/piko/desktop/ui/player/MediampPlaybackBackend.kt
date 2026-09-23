package dev.piko.desktop.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.player.PlayerAspectRatio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.isLoadingOrBuffering
import org.openani.mediamp.isMediaLoaded
import org.openani.mediamp.playUri
import java.io.File

/**
 * MediaMP（mpv 后端）到共用播放接口的转接。
 *
 * 能力按后端注册，取不到的特性在这里降级：倍速取不到就报 supportsSpeed = false，
 * 画面比例取不到就报 null，控件据此隐藏入口。
 */
internal class MediampPlaybackBackend(
    val player: MediampPlayer,
    scope: CoroutineScope,
) : PlaybackBackend {
    private val bufferingFeature = player.features[Buffering.Key]
    private val speedFeature = player.features[PlaybackSpeed.Key]
    private val aspectRatioFeature = player.features[VideoAspectRatio.Key]

    override var positionMillis by mutableLongStateOf(0L)
        private set
    override var durationMillis by mutableLongStateOf(0L)
        private set
    override var bufferedPositionMillis by mutableLongStateOf(0L)
        private set
    override var isPlaying by mutableStateOf(false)
        private set
    override var isBuffering by mutableStateOf(false)
        private set

    // 不能写成 private set 的属性：生成的 setter 与接口的 setSpeed/setAspectRatio 签名相撞
    private var currentSpeed by mutableFloatStateOf(speedFeature?.value ?: 1f)
    override val speed: Float get() = currentSpeed
    override val supportsSpeed: Boolean = speedFeature != null
    private var currentAspectRatio by mutableStateOf(
        aspectRatioFeature?.mode?.value?.toShared(),
    )
    override val aspectRatio: PlayerAspectRatio? get() = currentAspectRatio
    override var videoAspect by mutableStateOf<Float?>(null)
        private set

    private val _events = MutableSharedFlow<PlaybackBackendEvent>(extraBufferCapacity = 16)
    override val events: Flow<PlaybackBackendEvent> = _events.asSharedFlow()

    // 只在 scope 的调度器（Compose 主线程）上读写
    private var awaitingReady = false

    init {
        scope.launch { player.currentPositionMillis.collect { positionMillis = it } }
        scope.launch {
            player.mediaProperties.collect { properties ->
                durationMillis = properties?.durationMillis ?: 0L
                val width = properties?.videoWidth ?: 0
                val height = properties?.videoHeight ?: 0
                videoAspect = if (width > 0 && height > 0) width.toFloat() / height else null
            }
        }
        scope.launch {
            player.state.collect { state ->
                isPlaying = state.playWhenReady
                isBuffering = state.isLoadingOrBuffering
                if (awaitingReady && state.isMediaLoaded && !state.isLoadingOrBuffering) {
                    awaitingReady = false
                    _events.emit(PlaybackBackendEvent.Ready)
                }
            }
        }
        bufferingFeature?.let { feature ->
            scope.launch {
                combine(feature.bufferedPercentage, player.mediaProperties) { percent, properties ->
                    (properties?.durationMillis ?: 0L) * percent / 100
                }.collect { bufferedPositionMillis = it }
            }
        }
        speedFeature?.let { feature -> scope.launch { feature.valueFlow.collect { currentSpeed = it } } }
        aspectRatioFeature?.let { feature -> scope.launch { feature.mode.collect { currentAspectRatio = it.toShared() } } }
        scope.launch {
            player.events.collect { event ->
                when (event) {
                    is PlaybackEvent.ErrorOccurred -> _events.emit(PlaybackBackendEvent.Error(event.error.message))
                    is PlaybackEvent.MediaEnded -> _events.emit(PlaybackBackendEvent.Ended)
                    else -> Unit
                }
            }
        }
    }

    override suspend fun open(target: PlaybackTarget, startMillis: Long, playWhenReady: Boolean) {
        val uri = when (target) {
            is PlaybackTarget.LocalFile -> File(target.path).toURI().toString()
            is PlaybackTarget.Url -> target.url
        }
        awaitingReady = true
        player.playUri(uri, playWhenReady = playWhenReady, startPositionMillis = startMillis)
    }

    override fun stop() {
        awaitingReady = false
        player.stopPlayback()
    }

    override fun play() = player.resume()

    override fun pause() = player.pause()

    override fun seekTo(positionMillis: Long) = player.seekTo(positionMillis)

    override fun setSpeed(speed: Float) {
        speedFeature?.set(speed)
    }

    override fun setAspectRatio(mode: PlayerAspectRatio) {
        aspectRatioFeature?.setMode(
            when (mode) {
                PlayerAspectRatio.Fit -> AspectRatioMode.FIT
                PlayerAspectRatio.Crop -> AspectRatioMode.CROP
                PlayerAspectRatio.Stretch -> AspectRatioMode.STRETCH
            },
        )
    }
}

private fun AspectRatioMode.toShared(): PlayerAspectRatio = when (this) {
    AspectRatioMode.FIT -> PlayerAspectRatio.Fit
    AspectRatioMode.CROP -> PlayerAspectRatio.Crop
    AspectRatioMode.STRETCH -> PlayerAspectRatio.Stretch
}
