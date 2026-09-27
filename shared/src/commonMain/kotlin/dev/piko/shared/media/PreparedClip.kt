package dev.piko.shared.media

import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.proxy.ProxyStream
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

    /** 播放器时钟上的位置对应全片的哪一刻。 */
    fun videoMs(playerMs: Long): Long = playerMs - startOnPlayer + clipStartMs

    /**
     * 开播这一段要读的字节，以 [role] 预取它们，全部到手才返回。
     *
     * 切片只要开头：TS 顺序播，第一个关键帧之前的丢掉，所以要取到关键帧再往后 [SLICE_PREFETCH_SECONDS] 秒；
     * PikPak 的转码实测每 5 秒一个关键帧。取得比出第一帧所需的深：取好了才进翻页器，进去了就该放得起来，
     * 只够首帧的话一开播就得现取，正赶上跟别的段抢带宽。再加上切片末尾：FFmpeg 打开 TS 时读末尾的时间戳估时长。
     * 这两截落盘（见 ClipCache），下次同一段从盘上读，预取立即完成。
     *
     * 原画要文件头（容器头，faststart 的 MP4 连索引在内）、文件尾（MKV 的 Cues、非 faststart 的 MP4 的 moov），
     * 以及起点起至少 [PREFETCH_SECONDS] 秒。起点的字节位置按时长比例折算，码率不均时会偏，
     * 实测一个 4.3 GB 的文件偏了 9 MB，约 0.2%，所以起点前后按比例再留一截，但有上限：
     * 按比例留，4 GB 的文件前后各 12 MB，几段一起就占满账号的连接。估偏了只是轮到它时补读一次。
     */
    suspend fun prefetch(role: StreamRole) {
        val proxy = stream ?: return
        if (!sliced) {
            proxy.prefetch(originalRanges(proxy.size), role)
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
     * 播放时往后读多深：按平均码率够放 [READ_AHEAD_SECONDS] 秒。默认深度 32 MiB 是给整部片子的，
     * 一段只放 30 秒，多读的都在抢下一段的连接。
     */
    fun limitReadAhead() {
        stream?.readAheadLimit = maxOf((bytesPerMs * READ_AHEAD_SECONDS * 1000 * BITRATE_MARGIN).toLong(), MIN_AHEAD_BYTES)
    }

    override fun close() {
        stream?.close()
    }

    internal companion object {
        /** 切片里开播要的两截：开头与末尾，见 [prefetch]。落盘的也是这两截，所以由这里一处算。 */
        fun sliceRanges(size: Long, bytesPerMs: Double): List<LongRange> {
            val headEnd = maxOf((bytesPerMs * (KEYFRAME_INTERVAL_MS + SLICE_PREFETCH_SECONDS * 1000) * BITRATE_MARGIN).toLong(), MIN_AHEAD_BYTES)
                .coerceAtMost(size)
            val tailStart = (size - SLICE_TAIL_BYTES).coerceAtLeast(headEnd)
            return listOfNotNull(0L until headEnd, (tailStart until size).takeUnless { it.isEmpty() })
        }

        const val KEYFRAME_INTERVAL_MS = 5_000L
        const val PREFETCH_SECONDS = 2
        const val SLICE_PREFETCH_SECONDS = 8
        // 平均码率折算，动作场面的码率可以高出一截
        const val BITRATE_MARGIN = 1.5
        const val MIN_AHEAD_BYTES = 1L * 1024 * 1024

        // 与代理读文件头时顺手取的文件尾一样长，见 ProxySession.requestTail
        const val SLICE_TAIL_BYTES = 512L * 1024
        const val EDGE_BYTES = 2L * 1024 * 1024
        const val MIN_BEFORE_BYTES = 1L * 1024 * 1024
        const val DRIFT_FRACTION = 0.003
        const val MAX_DRIFT_BYTES = 2L * 1024 * 1024
        const val READ_AHEAD_SECONDS = 10
    }
}
