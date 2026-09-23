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

    /** 一次性事件。错误与播放结束不能用状态表达：重连期间同一个错误会被反复读到。 */
    val events: Flow<PlaybackBackendEvent>

    suspend fun open(target: PlaybackTarget, startMillis: Long, playWhenReady: Boolean = true)

    fun stop()

    fun play()

    fun pause()

    fun seekTo(positionMillis: Long)

    fun setSpeed(speed: Float)

    fun setAspectRatio(mode: PlayerAspectRatio)
}

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
