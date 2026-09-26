package dev.piko.desktop.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.desktop.isMacOs
import dev.piko.shared.media.player.ExternalSubtitle
import dev.piko.shared.media.player.MPV_SUBTITLE_LANGUAGES
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.MpvTrackSnapshot
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.mpvSubtitleAddCommands
import dev.piko.shared.media.player.readMpvTracks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.isLoadingOrBuffering
import org.openani.mediamp.isMediaLoaded
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.mpv.JvmMpvMediampPlayer
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.source.UriMediaData
import java.io.File

/**
 * MediaMP（mpv 后端）到共用播放接口的转接。
 *
 * 能力按后端注册，取不到的特性在这里降级：倍速取不到就报 supportsSpeed = false，
 * 画面比例与音量取不到就报 null，控件据此隐藏入口。
 */
internal class MediampPlaybackBackend(
    val player: MediampPlayer,
    scope: CoroutineScope,
) : PlaybackBackend {
    private val bufferingFeature = player.features[Buffering.Key]
    private val speedFeature = player.features[PlaybackSpeed.Key]
    private val aspectRatioFeature = player.features[VideoAspectRatio.Key]
    private val audioFeature = player.features[AudioLevelController.Key]
    private val metadataFeature = player.features[MediaMetadata.Key]
    private val mpv: MPVHandle? = mpvHandleOf(player)

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

    // MediaMP 的音量以 1 为 mpv 的 100，上限是 2；共用接口不放大，只用到 1
    private var currentVolume by mutableStateOf(audioFeature?.volume?.value?.coerceIn(0f, 1f))
    override val volume: Float? get() = currentVolume

    override var audioTracks by mutableStateOf<List<MediaTrack>>(emptyList())
        private set
    override var subtitleTracks by mutableStateOf<List<MediaTrack>>(emptyList())
        private set
    override var selectedAudioTrackId by mutableStateOf<String?>(null)
        private set
    override var selectedSubtitleTrackId by mutableStateOf<String?>(null)
        private set

    // 当前文件的外挂字幕，Ready 之后再挂：MediaMP 打开文件时不看 MediaExtraFiles
    private var pendingSubtitles: List<ExternalSubtitle> = emptyList()

    // MediaMP 读到的轨道，只在取不到 mpv 句柄时用来兜底
    private var audioCandidates: List<AudioTrack> = emptyList()
    private var subtitleCandidates: List<SubtitleTrack> = emptyList()

    private val _events = MutableSharedFlow<PlaybackBackendEvent>(extraBufferCapacity = 16)
    override val events: Flow<PlaybackBackendEvent> = _events.asSharedFlow()

    // 只在 scope 的调度器（Compose 主线程）上读写
    private var awaitingReady = false

    init {
        mpv?.setPropertyString("slang", MPV_SUBTITLE_LANGUAGES)
        // libass 缺字时经 DirectWrite 找回退字体，传的语言是空串，非中文系统上给汉字挑的多是日文字体：
        // 繁体字在里面有，「这」「们」这类简体字没有，回退失败后落到 sub-font。默认的 sans-serif 也不含中文，
        // 简体字幕就缺字。换成雅黑，简繁都全，Windows 各语言版都自带
        mpv?.setPropertyString("sub-font", SUBTITLE_FALLBACK_FONT)
        scope.launch { player.currentPositionMillis.collect { positionMillis = it } }
        metadataFeature?.let { feature ->
            // MediaMP 自己读的轨道丢了音轨语言与外挂标志，只拿它的变化当通知，列表从 mpv 重读
            feature.audioTracks?.let { group ->
                scope.launch { group.candidates.collect { audioCandidates = it; refreshTracks(feature) } }
                scope.launch { group.selected.collect { refreshTracks(feature) } }
            }
            feature.subtitleTracks?.let { group ->
                scope.launch { group.candidates.collect { subtitleCandidates = it; refreshTracks(feature) } }
                scope.launch { group.selected.collect { refreshTracks(feature) } }
            }
        }
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
                    attachPendingSubtitles()
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
        audioFeature?.let { feature -> scope.launch { feature.volume.collect { currentVolume = it.coerceIn(0f, 1f) } } }
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

    override suspend fun open(
        target: PlaybackTarget,
        startMillis: Long,
        playWhenReady: Boolean,
        subtitles: List<ExternalSubtitle>,
    ) {
        val uri = when (target) {
            is PlaybackTarget.LocalFile -> File(target.path).toURI().toString()
            is PlaybackTarget.Url -> target.url
        }
        awaitingReady = true
        pendingSubtitles = subtitles
        player.setMediaData(UriMediaData(uri), playWhenReady = playWhenReady, startPositionMillis = startMillis)
    }

    override fun selectAudioTrack(id: String) {
        val handle = mpv
        if (handle != null) {
            handle.setPropertyString("aid", id)
        } else {
            val track = audioCandidates.firstOrNull { it.internalId == id } ?: return
            metadataFeature?.audioTracks?.select(track)
        }
    }

    override fun selectSubtitleTrack(id: String?) {
        val handle = mpv
        if (handle != null) {
            handle.setPropertyString("sid", id ?: "no")
        } else {
            val group = metadataFeature?.subtitleTracks ?: return
            val track = id?.let { wanted -> subtitleCandidates.firstOrNull { it.internalId == wanted } ?: return }
            // 关字幕是 select(null)：MediaMP 的实现按 null 写 sid=no，只是接口的类型参数不可空
            @Suppress("UNCHECKED_CAST")
            (group as org.openani.mediamp.metadata.TrackGroup<SubtitleTrack?>).select(track)
        }
    }

    private fun refreshTracks(feature: MediaMetadata) {
        val snapshot = mpv?.let { handle -> readMpvTracks { handle.getPropertyString(it) } }
            ?: snapshotOf(feature, audioCandidates, subtitleCandidates)
        audioTracks = snapshot.audio
        subtitleTracks = snapshot.subtitles
        selectedAudioTrackId = snapshot.selectedAudioId
        selectedSubtitleTrackId = snapshot.selectedSubtitleId
    }

    private fun attachPendingSubtitles() {
        val subtitles = pendingSubtitles
        pendingSubtitles = emptyList()
        val handle = mpv ?: return
        if (subtitles.isEmpty()) return
        val hasSelected = readMpvTracks { handle.getPropertyString(it) }.selectedSubtitleId != null
        mpvSubtitleAddCommands(subtitles, hasSelected).forEach { handle.command(*it) }
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

    override fun setVolume(volume: Float) {
        val feature = audioFeature ?: return
        val clamped = volume.coerceIn(0f, 1f)
        feature.setVolume(clamped)
        // 读数经协程回报会慢一拍，连按方向键时下一次要在这次的值上累加，先行写入
        currentVolume = clamped
        // 静音时调音量，意图是要听见
        if (volume > 0f && feature.isMute.value) feature.setMute(false)
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

/**
 * MediaMP 的 mpv 句柄。它把句柄的 getter 标成了 internal，而 0.5.0 打开文件时不理会 MediaExtraFiles，
 * 外挂字幕只能自己 sub-add，所以经反射取。版本锁在 0.5.0（原因见 CLAUDE.md），升级时这里要重新核对。
 * 取不到时为 null：内嵌轨道照常经 MediaMetadata 可用，只是没有外挂字幕。
 */
private fun mpvHandleOf(player: MediampPlayer): MPVHandle? = runCatching {
    JvmMpvMediampPlayer::class.java.getMethod("getHandle" + "$" + "mediamp_mpv").invoke(player) as? MPVHandle
}.getOrNull()

// macOS 的 libass 经 CoreText 找字体，苹方同样简繁都全，系统自带
private val SUBTITLE_FALLBACK_FONT = if (isMacOs) "PingFang SC" else "Microsoft YaHei"

/** 句柄取不到时退回 MediaMP 读的轨道：音轨没有语言，也分不出外挂。 */
private fun snapshotOf(feature: MediaMetadata, audio: List<AudioTrack>, subtitles: List<SubtitleTrack>) = MpvTrackSnapshot(
    audio = audio.map { MediaTrack(it.internalId, it.name, null) },
    subtitles = subtitles.map { MediaTrack(it.internalId, it.labels.firstOrNull()?.value, it.language) },
    selectedAudioId = feature.audioTracks?.selected?.value?.internalId,
    selectedSubtitleId = feature.subtitleTracks?.selected?.value?.internalId,
)
