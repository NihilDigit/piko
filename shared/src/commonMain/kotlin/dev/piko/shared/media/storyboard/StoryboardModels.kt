package dev.piko.shared.media.storyboard

import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.shared.media.PlayableMediaKind
import io.github.nihildigit.pikpak.MediaVariant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 经过严格 `<= 480P` MPEG-TS 门控筛选出的雪碧图视频源。
 */
data class StoryboardSource(
    val fileId: String,
    val gcid: String,
    val mediaId: String,
    val url: String,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    val streamSizeBytes: Long? = null,
)

/**
 * 单个雪碧图采样槽位计划（已按 188 字节 TS 包边界对齐）。
 */
data class StoryboardSlotPlan(
    val slotIndex: Int,
    val estimatedMs: Long,
    val byteOffset: Long,
    val probeLengthBytes: Int,
)

/**
 * 已解码的一帧紧凑雪碧图（含真实 MPEG-TS `PTS` 毫秒时间戳与紧凑 JPEG 字节）。
 */
data class StoryboardFrame(
    val slotIndex: Int,
    val ptsMs: Long,
    val jpegBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoryboardFrame) return false
        return slotIndex == other.slotIndex && ptsMs == other.ptsMs && jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int {
        var result = slotIndex
        result = 31 * result + ptsMs.hashCode()
        result = 31 * result + jpegBytes.contentHashCode()
        return result
    }
}

/**
 * 持久化到 [StoryboardCache] 的单视频雪碧图包（支持部分持久化与完整持久化）。
 */
data class StoryboardBundle(
    val gcid: String,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val completed: Boolean,
    val lastAccessEpochMillis: Long,
    val frames: List<StoryboardFrame>,
)

/**
 * 按真实 `PTS` 升序排列的雪碧图时间线，提供 `O(log N)` 毫秒级二分查帧：
 * 每帧 `i` 覆盖进度条区间 `[pts_i, pts_{i+1})`（首帧兼管 `[0, pts_1)`，末帧覆盖至片尾）。
 */
data class StoryboardTimeline(
    val durationMillis: Long,
    val totalSlots: Int,
    val frames: List<StoryboardFrame>,
    val isComplete: Boolean = false,
) {
    /**
     * 在 `frames` 中执行 `O(log N)` 二分查找，返回覆盖 [positionMillis] 的帧索引；无帧时返回 `-1`。
     */
    fun findFrameIndex(positionMillis: Long): Int {
        if (frames.isEmpty()) return -1
        val target = positionMillis.coerceAtLeast(0L)
        if (target <= frames.first().ptsMs) return 0
        if (target >= frames.last().ptsMs) return frames.lastIndex
        var lo = 0
        var hi = frames.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val pts = frames[mid].ptsMs
            when {
                pts == target -> return mid
                pts < target -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        // hi is the greatest index with frames[hi].ptsMs < target, so target lies in [pts_hi, pts_{hi+1})
        return hi.coerceIn(0, frames.lastIndex)
    }

    fun findFrame(positionMillis: Long): StoryboardFrame? {
        val idx = findFrameIndex(positionMillis)
        return if (idx in frames.indices) frames[idx] else null
    }
}

/**
 * 雪碧图 Range 拉取优先级档位：
 * - [URGENT]：偏移 0 流头探测、Priority 0 用户悬停/「速览」焦点槽位与 Priority 1 Wave 1 锚点（高优通道，不被 mpv 投机预读饿死）；
 * - [BACKGROUND]：Priority 2 Wave 2 后台补全槽位（低优礼让前景播放，优先于暂停态后台读）。
 */
enum class StoryboardFetchPriority {
    URGENT,
    BACKGROUND,
}

/**
 * 单次流式 Range 读取返回的元数据摘要。
 */
data class StoryboardRangeProbe(
    val streamSizeBytes: Long? = null,
    val bytesConsumed: Int = 0,
    val reachedEof: Boolean = false,
)

/**
 * 流式 MPEG-TS Range 拉取接口：边收网络分块（或直读本地磁盘缓存）边回调 [onChunk]，
 * 回调返回 `true` 时立即中止/关闭 HTTP Range 流，零多余尾部流量。
 */
fun interface StoryboardStreamFetcher {
    suspend fun streamRange(
        offset: Long,
        maxLength: Int,
        priority: StoryboardFetchPriority,
        onChunk: (buffer: ByteArray, length: Int) -> Boolean,
    ): StoryboardRangeProbe?
}

/**
 * 复用单一持久化解码器（HEVC / H.264）将 Annex B IDR 帧（带参数集）直接解码缩放为紧凑 JPEG（<= 384x216）。
 * 严禁每帧重建 `avformat_open_input` 或 `av_seek_frame`；切换视频或关闭播放器时调用 [close] 确定性释放。
 */
interface StoryboardIdrDecoder : AutoCloseable {
    suspend fun decodeToJpeg(
        csdAnnexB: ByteArray,
        idrAccessUnitAnnexB: ByteArray,
        width: Int,
        height: Int,
        maxWidthPx: Int = DEFAULT_MAX_WIDTH_PX,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ): ByteArray?

    suspend fun decodeToJpeg(
        csdAnnexB: ByteArray,
        idrAccessUnitAnnexB: ByteArray,
        width: Int,
        height: Int,
        codec: TsVideoCodec,
        maxWidthPx: Int = DEFAULT_MAX_WIDTH_PX,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ): ByteArray? = if (codec == TsVideoCodec.HEVC) {
        decodeToJpeg(csdAnnexB, idrAccessUnitAnnexB, width, height, maxWidthPx, jpegQuality)
    } else {
        null
    }

    companion object {
        const val DEFAULT_MAX_WIDTH_PX = 384
        const val DEFAULT_JPEG_QUALITY = 80
    }
}

/**
 * 严格门控解析器：
 * - 仅当视频具备 `height <= 480` 的 PikPak MPEG-TS 转码档时返回 [StoryboardSource]（优先选最低可用 `<= 480P`）；
 * - 纯原画视频、最低转码档为 `720P` / `1080P` 的视频、或本地文件一律返回 `null`（悬停仅显示时间，隐藏「速览」入口，零额外流量）。
 */
object StoryboardSourceResolver {
    const val MAX_ALLOWED_HEIGHT = 480

    private val RESOLUTION_REGEX = Regex("([0-9]{3,4})[pP]")

    fun resolve(
        isLocalPlayback: Boolean,
        mediaInfo: PlayableMediaInfo?,
        durationMillis: Long,
        unreadableMediaIds: Set<String> = emptySet(),
        knownStreamSizeLookup: ((mediaId: String) -> Long?)? = null,
    ): StoryboardSource? {
        if (isLocalPlayback || mediaInfo == null || durationMillis <= 0L) return null
        if (mediaInfo.kind != PlayableMediaKind.Video) return null
        if (mediaInfo.gcid.isBlank()) return null

        val eligible = mediaInfo.availableVariants.mapNotNull { variant ->
            val height = eligibleHeightOrNull(variant, unreadableMediaIds) ?: return@mapNotNull null
            variant to height
        }
        val (best, bestHeight) = eligible.minByOrNull { (_, h) -> h } ?: return null
        val bestWidth = best.video?.width?.takeIf { it > 0 } ?: ((bestHeight * 16) / 9)
        return StoryboardSource(
            fileId = mediaInfo.fileId,
            gcid = mediaInfo.gcid,
            mediaId = best.mediaId,
            url = best.link.url,
            width = bestWidth,
            height = bestHeight,
            durationMillis = durationMillis,
            streamSizeBytes = knownStreamSizeLookup?.invoke(best.mediaId),
        )
    }

    internal fun eligibleHeightOrNull(variant: MediaVariant, unreadableMediaIds: Set<String> = emptySet()): Int? {
        if (variant.isOrigin) return null
        if (variant.video == null) return null
        if (variant.link.url.isBlank()) return null
        if (variant.mediaId.isBlank() || variant.mediaId in unreadableMediaIds) return null
        val height = variant.video?.height?.takeIf { it > 0 }
            ?: parseHeight(variant.resolutionName)
            ?: parseHeight(variant.mediaName)
            ?: return null
        return height.takeIf { it in 1..MAX_ALLOWED_HEIGHT }
    }

    private fun parseHeight(label: String): Int? {
        if (label.isBlank()) return null
        return RESOLUTION_REGEX.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

/**
 * 全片自适应雪碧图采样规划器：
 * - 按时长计算槽位数（`<=10min`: ~5s，`10-30min`: ~12s，`30-90min`: ~25s，`>90min`: ~35s，夹在 `48..180` 槽位）；
 * - 按已知流大小与时长推算 `<= 480P` 转码流字节速率，将单槽首探自适应定容为覆盖约 5s GOP + IDR 帧（`192..384 KiB`），
 *   单次 HTTP Range 往返即可命中绝大多数关键帧；
 * - 生成 Wave 1（12–16 个均匀锚点）、Wave 2（均匀二分插值填满剩余槽位）与交互优先排序。
 */
object StoryboardPlanner {
    const val TS_PACKET_BYTES = 188L
    const val MIN_SLOTS = 48
    const val MAX_SLOTS = 180
    /** 文件头（偏移 0）探测基准块大小，保持 `<= 64 KiB` 以命中 SDK `MICRO_PROBE_PRIORITY = 12`。 */
    const val DEFAULT_PROBE_BYTES = 64 * 1024
    const val MIN_ADAPTIVE_PROBE_BYTES = 192 * 1024
    const val MAX_ADAPTIVE_PROBE_BYTES = 384 * 1024
    /** 首块尾部已见到正在组装的 IDR PES 时，仅补拉紧凑尾块即可收齐完整关键帧。 */
    const val TAIL_CONTINUATION_BYTES = 128 * 1024
    /** 长 GOP 续块探测总预算上限（兼容旧版整块拉取路径：首块 + 续块累计不超过 512 KiB）。 */
    const val EXPANDED_PROBE_BYTES = 512 * 1024
    /** 偏移 0 流式头 + 首帧探测上限（实际在收齐流头与第 0 帧后立即中止，通常仅读 32–96 KiB）。 */
    const val HEAD_STREAM_SCAN_BYTES = 512 * 1024
    /** 流式增量扫描单次 Range 上限区间（覆盖实测 10s GOP + VBR 峰值，抽到首帧立即中止不读尾块）。 */
    const val MIN_STREAM_SCAN_BYTES = 512 * 1024
    const val MAX_STREAM_SCAN_BYTES = 1280 * 1024
    /** 流式增量扫描极长 VBR GOP 续块总预算上限（2 MiB）。 */
    const val EXPANDED_STREAM_SCAN_BYTES = 2048 * 1024
    const val WAVE1_MIN_ANCHORS = 12
    const val WAVE1_MAX_ANCHORS = 16

    private const val ADAPTIVE_GOP_SECONDS = 5.0
    private const val ADAPTIVE_IDR_HEADROOM_BYTES = 32 * 1024L
    private const val STREAM_SCAN_GOP_SECONDS = 13.0
    private const val STREAM_SCAN_IDR_HEADROOM_BYTES = 48 * 1024L
    private const val PROBE_GRANULARITY_BYTES = 16 * 1024L

    fun slotCountForDuration(durationMillis: Long): Int {
        if (durationMillis <= 0L) return 0
        val durationSec = (durationMillis / 1_000L).coerceAtLeast(1L)
        if (durationSec < MIN_SLOTS.toLong()) {
            return durationSec.toInt().coerceIn(1, MIN_SLOTS)
        }
        val targetStepSec = when {
            durationSec <= 600L -> 5.0
            durationSec <= 1_800L -> 12.0
            durationSec <= 5_400L -> 25.0
            else -> 35.0
        }
        return (durationSec / targetStepSec).roundToInt().coerceIn(MIN_SLOTS, MAX_SLOTS)
    }

    /**
     * 按转码流平均字节速率（`streamSizeBytes / durationSec`）计算覆盖约 5s GOP + IDR 帧的单次探测字节数，
     * 向上对齐至 16 KiB 并夹在 `192 KiB..384 KiB` 区间（供兼容整块拉取路径使用）。
     */
    fun adaptiveProbeBytes(durationMillis: Long, streamSizeBytes: Long): Int {
        if (durationMillis <= 0L || streamSizeBytes <= 0L) return MIN_ADAPTIVE_PROBE_BYTES
        val durationSec = (durationMillis.toDouble() / 1_000.0).coerceAtLeast(1.0)
        val bytesPerSec = streamSizeBytes.toDouble() / durationSec
        val rawTarget = (bytesPerSec * ADAPTIVE_GOP_SECONDS).toLong() + ADAPTIVE_IDR_HEADROOM_BYTES
        val aligned = ((rawTarget + PROBE_GRANULARITY_BYTES - 1L) / PROBE_GRANULARITY_BYTES) * PROBE_GRANULARITY_BYTES
        return aligned.coerceIn(MIN_ADAPTIVE_PROBE_BYTES.toLong(), MAX_ADAPTIVE_PROBE_BYTES.toLong()).toInt()
    }

    /**
     * 按转码流平均字节速率计算流式增量扫描单次 Range 上限（覆盖 10s 长 GOP + 30% VBR 峰值 + IDR 头余量），
     * 向上对齐至 16 KiB 并夹在 `512 KiB..1280 KiB`；由于流式扫描在抽到首个完整 IDR 后立即中止 HTTP 流，
     * 实际流量仅与首个 IDR 落点成正比，单次往返即可覆盖绝大多数 10s GOP 而不浪费尾部字节。
     */
    fun adaptiveStreamScanBytes(durationMillis: Long, streamSizeBytes: Long): Int {
        if (durationMillis <= 0L || streamSizeBytes <= 0L) return MIN_STREAM_SCAN_BYTES
        val durationSec = (durationMillis.toDouble() / 1_000.0).coerceAtLeast(1.0)
        val bytesPerSec = streamSizeBytes.toDouble() / durationSec
        val rawTarget = (bytesPerSec * STREAM_SCAN_GOP_SECONDS).toLong() + STREAM_SCAN_IDR_HEADROOM_BYTES
        val aligned = ((rawTarget + PROBE_GRANULARITY_BYTES - 1L) / PROBE_GRANULARITY_BYTES) * PROBE_GRANULARITY_BYTES
        return aligned.coerceIn(MIN_STREAM_SCAN_BYTES.toLong(), MAX_STREAM_SCAN_BYTES.toLong()).toInt()
    }

    fun buildSlots(
        durationMillis: Long,
        streamSizeBytes: Long,
        probeBytes: Int = adaptiveProbeBytes(durationMillis, streamSizeBytes),
    ): List<StoryboardSlotPlan> {
        val count = slotCountForDuration(durationMillis)
        if (count <= 0 || streamSizeBytes < TS_PACKET_BYTES * 2L) return emptyList()
        val effectiveProbe = probeBytes.coerceIn(16 * 1024, EXPANDED_PROBE_BYTES)
        val maxOffset = ((streamSizeBytes - TS_PACKET_BYTES).coerceAtLeast(0L) / TS_PACKET_BYTES) * TS_PACKET_BYTES
        return List(count) { idx ->
            val fraction = if (count == 1) 0.0 else idx.toDouble() / count.toDouble()
            val estMs = (durationMillis.toDouble() * fraction).toLong().coerceIn(0L, durationMillis)
            val rawOffset = (streamSizeBytes.toDouble() * fraction).toLong()
            val alignedOffset = ((rawOffset / TS_PACKET_BYTES) * TS_PACKET_BYTES).coerceIn(0L, maxOffset)
            val len = minOf(effectiveProbe.toLong(), streamSizeBytes - alignedOffset).toInt().coerceAtLeast(TS_PACKET_BYTES.toInt())
            StoryboardSlotPlan(
                slotIndex = idx,
                estimatedMs = estMs,
                byteOffset = alignedOffset,
                probeLengthBytes = len,
            )
        }
    }

    /**
     * Wave 1：全片均匀分布的 12–16 个锚点槽位（始终覆盖开头与末尾，~0.7–1.0 MiB）。
     */
    fun wave1Indices(slotCount: Int): List<Int> {
        if (slotCount <= 0) return emptyList()
        if (slotCount <= WAVE1_MAX_ANCHORS) return (0 until slotCount).toList()
        val anchors = when {
            slotCount >= 96 -> WAVE1_MAX_ANCHORS
            slotCount >= 64 -> 14
            else -> WAVE1_MIN_ANCHORS
        }.coerceIn(WAVE1_MIN_ANCHORS, minOf(WAVE1_MAX_ANCHORS, slotCount))
        val out = LinkedHashSet<Int>(anchors)
        for (i in 0 until anchors) {
            val idx = if (i == anchors - 1) {
                slotCount - 1
            } else {
                ((i.toDouble() * (slotCount - 1)) / (anchors - 1)).roundToInt().coerceIn(0, slotCount - 1)
            }
            out += idx
        }
        return out.toList()
    }

    /**
     * Wave 2：将剩余槽位按区间中点递归细分排布，使全片缩略图密度均匀加密，而非从左到右线性堆叠。
     */
    fun wave2Indices(slotCount: Int, wave1: List<Int> = wave1Indices(slotCount)): List<Int> {
        if (slotCount <= 0) return emptyList()
        val taken = wave1.toHashSet()
        val sortedAnchors = wave1.sorted()
        val result = ArrayList<Int>((slotCount - taken.size).coerceAtLeast(0))
        val queue = ArrayDeque<Pair<Int, Int>>()
        if (sortedAnchors.isEmpty()) {
            queue.addLast(0 to (slotCount - 1))
        } else {
            if (sortedAnchors.first() > 0) queue.addLast(0 to (sortedAnchors.first() - 1))
            for (i in 0 until sortedAnchors.lastIndex) {
                val lo = sortedAnchors[i] + 1
                val hi = sortedAnchors[i + 1] - 1
                if (lo <= hi) queue.addLast(lo to hi)
            }
            if (sortedAnchors.last() < slotCount - 1) queue.addLast((sortedAnchors.last() + 1) to (slotCount - 1))
        }
        while (queue.isNotEmpty()) {
            val (lo, hi) = queue.removeFirst()
            if (lo > hi) continue
            val mid = (lo + hi) ushr 1
            if (taken.add(mid)) {
                result += mid
            }
            if (lo <= mid - 1) queue.addLast(lo to (mid - 1))
            if (mid + 1 <= hi) queue.addLast((mid + 1) to hi)
        }
        return result
    }

    /**
     * 交互覆盖排序：用户悬停进度条或打开「速览」时，将待解码槽位按距离 [targetMs] 从近到远立刻重排。
     */
    fun prioritizeAround(
        pendingSlots: List<StoryboardSlotPlan>,
        targetMs: Long,
    ): List<StoryboardSlotPlan> = pendingSlots.sortedWith(
        compareBy<StoryboardSlotPlan> { abs(it.estimatedMs - targetMs) }.thenBy { it.slotIndex },
    )
}
