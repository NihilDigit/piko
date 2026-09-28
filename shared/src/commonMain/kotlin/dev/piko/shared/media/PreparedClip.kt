package dev.piko.shared.media

import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.proxy.ProxyStream
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.StreamRole
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile

/**
 * 随机片段里的一段，备好了代理会话：全片 [clipStartMs] 起的一段，交给播放器从 [startOnPlayer] 起播。
 *
 * 两种来源，见 PikoMediaRepository.prepareClip：
 * 转码流的一截（[sliced]），播放器看到的是一个从头顺序播的短文件，时钟从 0 起，对应全片的 [clipStartMs]；
 * 或者原画整条，播放器从 [clipStartMs] 起播，时钟就是全片的时刻。
 */
class PreparedClip internal constructor(
    private val stream: ProxyStream?,
    /** 原画没开起代理会话时退回的直链。切片没有直链可退，截出来的那一截只在代理里。 */
    private val fallbackUrl: String?,
    val sliced: Boolean,
    clipStartMs: Long,
    /** 这条流每毫秒视频的平均字节数。预取与预读的深度都按它折算。 */
    private val bytesPerMs: Double,
    /** 按磁盘上的记录重建，没查详情；开头与末尾多半也在盘上，见 ClipCache。 */
    val fromDisk: Boolean = false,
    /**
     * 读切片开头的时间戳，得出它实际从全片哪一刻开始。切片按平均码率算起点字节，码率不均时
     * 与原定的起点差出十几秒，看完整就接不上眼前的画面。null 是起点已经准了（原画、盘上记着的）。
     */
    private val resolveStart: (suspend () -> Long?)? = null,
    /** 切片的开头与末尾都取到了，调用方据此记下这一段与它的实际起点，见 ClipCache。 */
    private val onWarmed: (suspend (clipStartMs: Long) -> Unit)? = null,
) : AutoCloseable {
    /** 这一段从全片哪一刻开始。切片在 [prefetch] 读过时间戳后改成实际的起点。 */
    @Volatile
    var clipStartMs: Long = clipStartMs
        private set

    val url: String? get() = stream?.url ?: fallbackUrl.takeUnless { sliced }

    /** 播放器从哪里起播，也是这一段在播放器时钟上的起点。 */
    val startOnPlayer: Long get() = if (sliced) 0L else clipStartMs

    /** 代理实际读的那条流的字节数，切片即切片的长度。没有代理会话时为 null。 */
    val streamBytes: Long? get() = stream?.size

    var role: StreamRole
        get() = stream?.role ?: StreamRole.FOREGROUND
        set(value) {
            stream?.role = value
        }

    /** 有人正等着这一段：拖动后、卡在缓冲上、翻到时还没出画面。见 ProxyStream.urgent。 */
    var urgent: Boolean
        get() = stream?.urgent ?: false
        set(value) {
            stream?.urgent = value
        }

    /** 播放器时钟上的位置对应全片的哪一刻。 */
    fun videoMs(playerMs: Long): Long = playerMs - startOnPlayer + clipStartMs

    /**
     * 开播这一段要读的字节，以 [role] 预取它们，全部到手才返回。
     *
     * 切片只要开头 [SLICE_PREFETCH_SECONDS] 秒：切片从关键帧切起，TS 顺序播，这几秒就是开播与刚开始看的那一截。
     * 刷的时候大多几秒就翻走，每段只取这么多，同样的带宽能多备几段；真看起来的段再往后取，
     * 见 [extendReadAhead]。原先取 13 秒再乘余量，一段 6 MB 上下，快翻时八段一齐取，前面的迟迟取不完。
     * 再加上切片末尾：FFmpeg 打开 TS 时读末尾的时间戳估时长。
     * 这两截落盘（见 ClipCache），下次同一段从盘上读，预取立即完成。
     *
     * 原画要文件头（容器头，faststart 的 MP4 连索引在内）、文件尾（MKV 的 Cues、非 faststart 的 MP4 的 moov），
     * 以及起点起至少 [PREFETCH_SECONDS] 秒。起点的字节位置按时长比例折算，码率不均时会偏，
     * 实测一个 4.3 GB 的文件偏了 9 MB，约 0.2%，所以起点前后按比例再留一截，但有上限：
     * 按比例留，4 GB 的文件前后各 12 MB，几段一起就占满账号的连接。估偏了只是轮到它时补读一次。
     *
     * 原画比转码的开头低一档：它一段六七 MB 起，同一档里排在前面，有转码的段就被它拖慢。
     * 不改用一次只取一段原画：排在最前的那段直链坏了，SDK 换主机重试近二十秒才判定失败，
     * 其余原画全堵在它后面，等待页停了十九秒（2026-09-28）。低一档只是让出连接，谁也不等谁
     */
    suspend fun prefetch(role: StreamRole) {
        val proxy = stream ?: return
        if (!sliced) {
            proxy.prefetch(originalRanges(proxy.size), role, priority = PikPakStreamReader.WARM_PRIORITY - 1)
            return
        }
        proxy.prefetch(sliceRanges(proxy.size, bytesPerMs), role)
        resolveStart?.let { resolve ->
            // 读不出来就沿用按码率估的起点：偏几秒，不值得为此让这一段放不了
            try {
                resolve()?.let { clipStartMs = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PikoLog.w("Clips", "读切片时间戳失败，沿用估算的起点", e)
            }
        }
        onWarmed?.invoke(clipStartMs)
    }

    private fun originalRanges(streamBytes: Long): List<LongRange> = buildList {
        add(0L until EDGE_BYTES)
        add((streamBytes - EDGE_BYTES).coerceAtLeast(0) until streamBytes)
        val estimate = (bytesPerMs * clipStartMs).toLong()
        val drift = minOf((streamBytes * DRIFT_FRACTION).toLong(), MAX_DRIFT_BYTES)
        val ahead = maxOf((bytesPerMs * PREFETCH_SECONDS * 1000 * BITRATE_MARGIN).toLong(), MIN_AHEAD_BYTES)
        add((estimate - drift - MIN_BEFORE_BYTES).coerceAtLeast(0) until estimate + drift + ahead)
    }

    /**
     * 代理这一层不往读位置之后多取，只剩 SDK 的下限一块。还没真看起来的段（刚翻到的、停在第一帧的前后两段）
     * 读的都是预取好的开头，不必再往后要；要了就以前台预读的档位压过别段开头的预取。
     * 默认深度 32 MiB 是给整部片子的。
     */
    fun holdReadAhead() {
        stream?.readAheadLimit = 0
    }

    /**
     * 真在看这一段了：往后读 [seconds] 秒，以前台预读的档位排在所有段的预取之前。
     * 与播放器的缓冲时长一起放开，见 ClipPager 的升档。
     */
    fun extendReadAhead(seconds: Int) {
        stream?.readAheadLimit = maxOf((bytesPerMs * seconds * 1000 * BITRATE_MARGIN).toLong(), MIN_AHEAD_BYTES)
    }

    override fun close() {
        stream?.close()
    }

    internal companion object {
        /** 切片里开播要的两截：开头与末尾，见 [prefetch]。落盘的也是这两截，所以由这里一处算。 */
        fun sliceRanges(size: Long, bytesPerMs: Double): List<LongRange> {
            val headEnd = maxOf((bytesPerMs * SLICE_PREFETCH_SECONDS * 1000 * BITRATE_MARGIN).toLong(), MIN_SLICE_HEAD_BYTES)
                .coerceAtMost(size)
            val tailStart = (size - SLICE_TAIL_BYTES).coerceAtLeast(headEnd)
            return listOfNotNull(0L until headEnd, (tailStart until size).takeUnless { it.isEmpty() })
        }

        const val KEYFRAME_INTERVAL_MS = 5_000L
        const val PREFETCH_SECONDS = 2
        const val SLICE_PREFETCH_SECONDS = 5

        // 平均码率折算，动作场面的码率可以高出一截
        const val BITRATE_MARGIN = 1.5
        const val MIN_AHEAD_BYTES = 1L * 1024 * 1024

        // 低码率的切片 5 秒不到半兆。SDK 按 256 KiB 一块取，再往下省不出什么
        const val MIN_SLICE_HEAD_BYTES = 512L * 1024

        // 与代理读文件头时顺手取的文件尾一样长，见 ProxySession.requestTail
        const val SLICE_TAIL_BYTES = 512L * 1024
        const val EDGE_BYTES = 2L * 1024 * 1024
        const val MIN_BEFORE_BYTES = 1L * 1024 * 1024
        const val DRIFT_FRACTION = 0.003
        const val MAX_DRIFT_BYTES = 2L * 1024 * 1024
    }
}
