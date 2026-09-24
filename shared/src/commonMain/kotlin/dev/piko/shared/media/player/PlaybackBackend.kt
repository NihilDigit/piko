package dev.piko.shared.media.player

import kotlinx.coroutines.flow.Flow

/**
 * 播放后端的最小接口：共用的 [PlayerScreenState] 只通过它控制播放器。
 *
 * Android 由 libmpv 适配层实现，Desktop 由 MediaMP 的 mpv 后端实现。控件与画面表面
 * 仍由各端自己接，这里只管取流策略需要的读数与命令。
 *
 * 读数属性要由 Compose 的 State 支撑，共用层和视图层直接读，读取粒度与重组范围才对得上。
 */
interface PlaybackBackend {
    val positionMillis: Long

    /** 后端还不知道时长时为 0。 */
    val durationMillis: Long

    /** 已缓冲区间的终点（绝对时间），不支持时为 0。 */
    val bufferedPositionMillis: Long

    /** 意图中的播放态，即 playWhenReady：缓冲中也算在播。 */
    val isPlaying: Boolean

    /** 在加载或缓冲，画面暂时不会前进。 */
    val isBuffering: Boolean

    val speed: Float

    /** 为 false 时控件隐藏倍速入口。 */
    val supportsSpeed: Boolean

    /** 当前画面比例模式，后端不支持时为 null。 */
    val aspectRatio: PlayerAspectRatio?

    /** 显示宽高比（已计入旋转），画面参数未知时为 null。 */
    val videoAspect: Float?

    /**
     * 播放器自身的软件音量，0 到 1，1 是原始响度（mpv 的 100）。不放大：超过 100 会削波。
     * 后端不支持时为 null。Android 的音量手势调的是系统媒体音量，不走这里；桌面没有那一路，只能调它。
     */
    val volume: Float?

    /** 一次性事件。错误与播放结束不能用状态表达：重连期间同一个错误会被反复读到。 */
    val events: Flow<PlaybackBackendEvent>

    /** 当前文件的音轨，含外挂的。换文件后清空，重新读到之前为空。 */
    val audioTracks: List<MediaTrack> get() = emptyList()

    /** 当前文件的字幕轨，内封与外挂都在里面。 */
    val subtitleTracks: List<MediaTrack> get() = emptyList()

    val selectedAudioTrackId: String? get() = null

    /** 为 null 表示字幕关闭。 */
    val selectedSubtitleTrackId: String? get() = null

    /**
     * [subtitles] 是随文件一起加载的外挂字幕，加载后出现在 [subtitleTracks] 里。
     * 选哪条由后端按语言偏好决定，调用方有偏好时在 Ready 之后再调 [selectSubtitleTrack]。
     */
    suspend fun open(
        target: PlaybackTarget,
        startMillis: Long,
        playWhenReady: Boolean = true,
        subtitles: List<ExternalSubtitle> = emptyList(),
    )

    fun selectAudioTrack(id: String) = Unit

    /** [id] 为 null 时关闭字幕。 */
    fun selectSubtitleTrack(id: String?) = Unit

    fun stop()

    fun play()

    fun pause()

    fun seekTo(positionMillis: Long)

    fun setSpeed(speed: Float)

    fun setAspectRatio(mode: PlayerAspectRatio)

    fun setVolume(volume: Float)
}

/**
 * 一条音轨或字幕轨。[id] 是后端内部的编号，只在同一个文件内有效，换集后要按语言与标题重新找。
 * [title] 与 [language] 是容器里写的原文，可能为空，也可能是「jpn」这类代码；显示名见 [trackDisplayName]。
 */
data class MediaTrack(
    val id: String,
    val title: String?,
    val language: String?,
    val isExternal: Boolean = false,
)

/** 随视频一起加载的外挂字幕。[url] 是后端能直接读的地址，[title] 用来在列表里区分。 */
data class ExternalSubtitle(val url: String, val title: String, val language: String?)

sealed interface PlaybackTarget {
    /** 本机文件的绝对路径。 */
    data class LocalFile(val path: String) : PlaybackTarget

    data class Url(val url: String) : PlaybackTarget
}

sealed interface PlaybackBackendEvent {
    /** 本次 open 的第一帧已经出来。 */
    data object Ready : PlaybackBackendEvent

    data object Ended : PlaybackBackendEvent

    /** 当前文件被迫中止。[detail] 是后端给出的原因，可能是英文。 */
    data class Error(val detail: String?) : PlaybackBackendEvent
}

enum class PlayerAspectRatio { Fit, Crop, Stretch }
