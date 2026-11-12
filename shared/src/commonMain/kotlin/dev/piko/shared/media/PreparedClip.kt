package dev.piko.shared.media

import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.proxy.ProxyStream
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.PlaybackBufferSnapshot
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

private val InactiveClipReactivation: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

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
    /** 这条流每毫秒视频的平均字节数。预取与缓冲预算深度都按它折算。 */
    private val bytesPerMs: Double,
    /** 切片允许扩展到的最大窗口时长（毫秒）；预取后按实测切片首尾 PTS 校准，原画整流不设切片上限，为 null。 */
    maxWindowMs: Long? = null,
    /** 按磁盘上的记录重建，没查详情；开头与末尾多半也在盘上，见 ClipCache。 */
    val fromDisk: Boolean = false,
    /** 本地原文件直通，不创建代理或 TS 切片。 */
    val localPath: String? = null,
    /**
     * 可选的预取后起点校准回调：读取切片开头的时间戳修正 [clipStartMs]。
     * 常规切片路径已在 `openClipSlice` 扫描验证关键帧时直接完成内存 PTS 校准并传 null；仅在需要预取后二次校准的场景传入。
     */
    private val resolveStart: (suspend () -> Long?)? = null,
    /**
     * 可选的预取后窗口时长校准回调：读取已预取切片流尾 PTS 修正 [maxWindowMs]，无额外流尾校准时为 null。
     */
    private val resolveWindowDuration: (suspend () -> Long?)? = null,
    /** 切片的开头与末尾都取到了，调用方据此记下这一段与它的实际起点和实测时长，见 ClipCache。 */
    private val onWarmed: (suspend (clipStartMs: Long, actualWindowMs: Long) -> Unit)? = null,
) : AutoCloseable {
    private val lifetimeLock = PikoLock()
    private val players = mutableMapOf<Any, () -> Unit>()
    private var closing = false

    /** 原生播放器卸载输入前，代理地址及其字节来源仍须存在。 */
    internal fun retainPlayback(onWithdrawn: () -> Unit): AutoCloseable {
        val key = Any()
        withPikoLock(lifetimeLock) {
            if (closing) throw CancellationException("clip retired")
            players[key] = onWithdrawn
        }
        return AutoCloseable {
            val release = withPikoLock(lifetimeLock) {
                players.remove(key) != null && closing && players.isEmpty()
            }
            if (release) stream?.close()
        }
    }

    private var demand: TransferDemand? = null

    internal fun bindDemand(owner: TransferDemand) {
        demand = owner
        stream?.bindDemand(owner)
    }

    internal val estimatedBytesPerSecond: Long?
        get() = (bytesPerMs * 1000.0).toLong().takeIf { it > 0L }

    internal fun readAheadBytesFor(seconds: Int, minBytes: Long = MIN_AHEAD_BYTES): Long =
        maxOf((bytesPerMs * seconds * 1000 * BITRATE_MARGIN).toLong(), minBytes)

    fun reportPlaybackBuffer(snapshot: PlaybackBufferSnapshot) {
        demand?.reportPlaybackBuffer(
            snapshot.copy(mediaBytesPerSecond = snapshot.mediaBytesPerSecond ?: estimatedBytesPerSecond),
        )
    }

    /** 这一段从全片哪一刻开始。切片在 [prefetch] 读过时间戳后改成实际的起点。 */
    @Volatile
    var clipStartMs: Long = clipStartMs
        private set

    @Volatile
    var maxWindowMs: Long? = maxWindowMs
        private set

    val url: String? get() = stream?.url ?: fallbackUrl.takeUnless { sliced }

    /** 播放器从哪里起播，也是这一段在播放器时钟上的起点。 */
    val startOnPlayer: Long get() = if (sliced) 0L else clipStartMs

    /** 代理实际读的那条流的字节数，切片即切片的长度。没有代理会话时为 null。 */
    val streamBytes: Long? get() = stream?.size

    /** 当前片段底层缓存已从网络收到的累计字节数（单调递增）。 */
    val receivedBytes: Long get() = stream?.receivedBytes ?: 0L

    /** 底层云端文件是否正在执行冷存唤醒轮询。 */
    val isReactivating: StateFlow<Boolean> get() = stream?.isReactivating ?: InactiveClipReactivation

    /** 播放器时钟上的位置对应全片的哪一刻。 */
    fun videoMs(playerMs: Long): Long = playerMs - startOnPlayer + clipStartMs

    /**
     * 开播这一段要读的字节，经会话绑定的 [TransferDemand] 预取它们，全部到手才返回。
     *
     * 切片只要开头 [SLICE_PREFETCH_SECONDS] 秒与末尾 [SLICE_TAIL_BYTES]：切片从关键帧切起，TS 顺序播，
     * 开头几秒就是开播与刚开始看的那一截，末尾供 FFmpeg 读流尾时间戳估算总时长；真看起来的段再通过
     * [TransferDemand] 的缓冲预算往后扩展预读。这两截落盘（见 ClipCache），下次同一段从盘上直出。
     *
     * 原画要文件头、文件尾以及起点附近至少 [PREFETCH_SECONDS] 秒，优先级比转码开头低一档，免得大体积原画拖慢转码段。
     */
    suspend fun prefetch(role: StreamRole = StreamRole.BACKGROUND) {
        val proxy = stream ?: return
        if (!sliced) {
            proxy.prefetch(originalRanges(proxy.size), role, priority = PikPakStreamReader.WARM_PRIORITY - 1)
            return
        }
        proxy.prefetch(sliceRanges(proxy.size, bytesPerMs), role)
        resolveStart?.let { resolve ->
            try {
                resolve()?.let { clipStartMs = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PikoLog.w(TAG, "Failed to resolve clip start PTS, keeping estimated start: clipStartMs=$clipStartMs, streamBytes=${proxy.size}", e)
            }
        }
        resolveWindowDuration?.let { resolveDur ->
            try {
                resolveDur()?.takeIf { it > 0L }?.let { actual ->
                    val currentCap = maxWindowMs
                    maxWindowMs = if (currentCap != null) minOf(currentCap, actual) else actual
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PikoLog.w(TAG, "Failed to resolve clip tail window duration, keeping policy cap: clipStartMs=$clipStartMs, maxWindowMs=$maxWindowMs, streamBytes=${proxy.size}", e)
            }
        }
        onWarmed?.invoke(clipStartMs, maxWindowMs ?: 0L)
    }

    private fun originalRanges(streamBytes: Long): List<LongRange> = buildList {
        add(0L until EDGE_BYTES)
        add((streamBytes - EDGE_BYTES).coerceAtLeast(0) until streamBytes)
        val estimate = (bytesPerMs * clipStartMs).toLong()
        val drift = minOf((streamBytes * DRIFT_FRACTION).toLong(), MAX_DRIFT_BYTES)
        val ahead = maxOf((bytesPerMs * PREFETCH_SECONDS * 1000 * BITRATE_MARGIN).toLong(), MIN_AHEAD_BYTES)
        add((estimate - drift - MIN_BEFORE_BYTES).coerceAtLeast(0) until estimate + drift + ahead)
    }

    override fun close() {
        val owners = withPikoLock(lifetimeLock) {
            if (closing) return
            closing = true
            players.values.toList()
        }
        demand?.close()
        stream?.withdrawTransport()
        if (owners.isEmpty()) stream?.close() else owners.forEach { it() }
    }

    internal companion object {
        private const val TAG = "Clips"

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

        // 预取切片末尾 512 KiB，覆盖 FFmpeg 打开 MPEG-TS 时读取流尾 PTS 估算总时长的窗口
        const val SLICE_TAIL_BYTES = 512L * 1024
        const val EDGE_BYTES = 2L * 1024 * 1024
        const val MIN_BEFORE_BYTES = 1L * 1024 * 1024
        const val DRIFT_FRACTION = 0.003
        const val MAX_DRIFT_BYTES = 2L * 1024 * 1024
    }
}
