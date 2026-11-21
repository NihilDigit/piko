package dev.piko.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import dev.piko.shared.media.MediaService
import dev.piko.shared.media.player.PlayerScreenState
import dev.piko.shared.media.storyboard.StoryboardCache
import dev.piko.shared.media.storyboard.StoryboardEngine
import dev.piko.shared.media.storyboard.StoryboardPlanner
import dev.piko.shared.media.storyboard.StoryboardPlaybackGate
import dev.piko.shared.media.storyboard.StoryboardSource
import dev.piko.ui.platform.PikoPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「速览」全片时间轴条中的一个槽位视图项：已解码时使用真实 MPEG-TS `PTS`，加载中回退计划估算时刻。
 */
@Immutable
data class StoryboardDisplaySlot(
    val slotIndex: Int,
    val timestampMillis: Long,
    val bitmap: ImageBitmap?,
    val isDecoded: Boolean,
)

/**
 * 进度条毫秒级悬停预览与「速览」时间轴条共用的全片雪碧图缓存（仅对存在 `<= 480P` MPEG-TS 转码档的视频创建）：
 * - 由 [StoryboardEngine] 按三阶段策略（磁盘缓存 `< 5ms` 命中 -> Wave 1 锚点 -> Wave 2 后台低优 -> 交互优先覆盖）驱动；
 * - 悬停查帧走 [StoryboardTimeline.findFrameIndex] `O(log N)` 真实 `PTS` 二分查找（`< 1ms`，0 网络，120fps 顺滑）；
 * - 内存仅缓存紧凑 `384x216` 解码位图，切视频或关闭播放器时确定性关闭解码器并释放全部内存。
 */
@Stable
class ScrubFrameCache(
    val durationMillis: Long,
    private val engine: StoryboardEngine,
    private val decodeBitmap: (ByteArray) -> ImageBitmap?,
    private val scope: CoroutineScope,
    private val batchWindowMillis: Long = UI_BATCH_WINDOW_MILLIS,
) {
    private val bitmapsByPts = LinkedHashMap<Long, ImageBitmap>()
    private var collectorJob: Job? = null

    var timeline by mutableStateOf(engine.timeline.value)
        private set

    /** 每次有新雪碧图批次解码入库时递增，供悬停气泡与「速览」局部刷新。 */
    var revision by mutableIntStateOf(0)
        private set

    val totalSlots: Int get() = timeline.totalSlots
    val loadedCount: Int get() = timeline.frames.size
    val isComplete: Boolean get() = timeline.isComplete

    init {
        collectorJob = scope.launch {
            engine.timeline.collect { next ->
                // 在主线程先拍下已解码 PTS 快照，避免在 Dispatchers.Default 跨线程读取非线程安全 LinkedHashMap
                val decodedPtsSnapshot = bitmapsByPts.keys.toHashSet()
                val newlyDecoded = withContext(Dispatchers.Default) {
                    val out = ArrayList<Pair<Long, ImageBitmap>>()
                    for (frame in next.frames) {
                        if (frame.ptsMs !in decodedPtsSnapshot) {
                            decodeBitmap(frame.jpegBytes)?.let { bmp ->
                                out += frame.ptsMs to bmp
                            }
                        }
                    }
                    out
                }
                for ((pts, bmp) in newlyDecoded) {
                    if (pts !in bitmapsByPts) {
                        if (bitmapsByPts.size >= MAX_BITMAP_ENTRIES) {
                            val oldest = bitmapsByPts.keys.firstOrNull()
                            if (oldest != null) bitmapsByPts.remove(oldest)
                        }
                        bitmapsByPts[pts] = bmp
                    }
                }
                val changed = newlyDecoded.isNotEmpty() || next != timeline
                timeline = next
                if (changed) {
                    revision += 1
                }
                // 首帧与完成态零延迟立即刷新；流式补齐期间以约 60ms 微批合并突发帧，避免每解码 1 帧就重排重组整个胶片条。
                if (!next.isComplete && batchWindowMillis > 0L && next.frames.isNotEmpty() && changed) {
                    delay(batchWindowMillis)
                }
            }
        }
        engine.start()
    }

    /**
     * `O(log N)` 按真实 `PTS` 二分查找覆盖 [positionMillis] 的雪碧图帧（区间 `[pts_i, pts_{i+1})`）：
     * 纯内存查找，`< 1ms`，0 网络往返。
     */
    fun frameAt(positionMillis: Long): ImageBitmap? {
        val frame = timeline.findFrame(positionMillis) ?: return null
        return bitmapsByPts[frame.ptsMs] ?: decodeBitmap(frame.jpegBytes)?.also { bmp ->
            if (bitmapsByPts.size >= MAX_BITMAP_ENTRIES) {
                val oldest = bitmapsByPts.keys.firstOrNull()
                if (oldest != null) bitmapsByPts.remove(oldest)
            }
            bitmapsByPts[frame.ptsMs] = bmp
        }
    }

    /** 通知引擎用户正在悬停进度条或浏览「速览」，立即打破冷启动等待并优先抓取 [positionMillis] 附近槽位。 */
    fun notifyUserFocus(positionMillis: Long) {
        engine.boostAround(positionMillis)
    }

    /**
     * 生成「速览」时间轴条所需的从左到右全片槽位列表（与进度条悬停共用同一套真实 `PTS` 雪碧图）：
     * - 已全部完成时严格按真实 `PTS` 升序排列；
     * - 加载途中合并已解码真实 `PTS` 帧与未就绪槽位占位，确保全片可即刻翻阅并平滑浮现。
     */
    fun displaySlots(): List<StoryboardDisplaySlot> {
        val currentTimeline = timeline
        if (currentTimeline.isComplete && currentTimeline.frames.isNotEmpty()) {
            return currentTimeline.frames.mapIndexed { idx, frame ->
                StoryboardDisplaySlot(
                    slotIndex = idx,
                    timestampMillis = frame.ptsMs,
                    bitmap = bitmapsByPts[frame.ptsMs],
                    isDecoded = true,
                )
            }
        }
        val planned = engine.plannedSlots.value
        val bySlot = currentTimeline.frames.associateBy { it.slotIndex }
        if (planned.isNotEmpty()) {
            return planned.map { slot ->
                val frame = bySlot[slot.slotIndex]
                if (frame != null) {
                    StoryboardDisplaySlot(
                        slotIndex = slot.slotIndex,
                        timestampMillis = frame.ptsMs,
                        bitmap = bitmapsByPts[frame.ptsMs],
                        isDecoded = true,
                    )
                } else {
                    StoryboardDisplaySlot(
                        slotIndex = slot.slotIndex,
                        timestampMillis = slot.estimatedMs,
                        bitmap = null,
                        isDecoded = false,
                    )
                }
            }.sortedBy { it.timestampMillis }
        }
        val count = StoryboardPlanner.slotCountForDuration(durationMillis).coerceAtLeast(1)
        if (currentTimeline.frames.isNotEmpty() && currentTimeline.frames.size >= count) {
            return currentTimeline.frames.mapIndexed { idx, frame ->
                StoryboardDisplaySlot(
                    slotIndex = idx,
                    timestampMillis = frame.ptsMs,
                    bitmap = bitmapsByPts[frame.ptsMs],
                    isDecoded = true,
                )
            }
        }
        return List(count) { idx ->
            val estMs = if (count == 1) 0L else (durationMillis.toDouble() * idx / count).toLong().coerceIn(0L, durationMillis)
            val frame = bySlot[idx]
            StoryboardDisplaySlot(
                slotIndex = idx,
                timestampMillis = frame?.ptsMs ?: estMs,
                bitmap = frame?.let { bitmapsByPts[it.ptsMs] },
                isDecoded = frame != null,
            )
        }.sortedBy { it.timestampMillis }
    }

    /** 定位包含 [positionMillis] 的显示槽位索引（`O(log N)` 二分，用于高亮当前播放所在槽位）。 */
    fun findDisplaySlotIndex(slots: List<StoryboardDisplaySlot>, positionMillis: Long): Int {
        if (slots.isEmpty()) return 0
        val target = positionMillis.coerceAtLeast(0L)
        if (target <= slots.first().timestampMillis) return 0
        if (target >= slots.last().timestampMillis) return slots.lastIndex
        var lo = 0
        var hi = slots.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val ts = slots[mid].timestampMillis
            when {
                ts == target -> return mid
                ts < target -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return hi.coerceIn(0, slots.lastIndex)
    }

    fun clear() {
        collectorJob?.cancel()
        collectorJob = null
        engine.close()
        bitmapsByPts.clear()
    }

    private companion object {
        const val MAX_BITMAP_ENTRIES = 200
        const val UI_BATCH_WINDOW_MILLIS = 60L
    }
}

/** 进度条悬停/拖动与「速览」共享的全片雪碧图缓存；无 `<= 480P` 转码档的视频恒为 `null`。 */
internal val LocalScrubFrameCache = compositionLocalOf<ScrubFrameCache?> { null }

/** 「自动」画质下当前实际落地的档位名（如 `ORIGINAL_QUALITY` 或 `"720P"`），供画质胶囊与弹窗统一展示。 */
internal val LocalAutoResolvedQuality = compositionLocalOf<String?> { null }

/**
 * 为当前播放视频创建并管理全片雪碧图 [ScrubFrameCache] 生命周期：
 * - 严格门控：仅当 [state.storyboardSource] 非空（即存在 `<= 480P` PikPak MPEG-TS 转码档）时启用；
 *   纯原画、最低档 `>= 720P` 或本地文件均直接返回 `null`（悬停仅显示时间，隐藏「速览」，零流量）。
 * - `remember` 仅以 `(fileId, gcid, mediaId)` 为键（不纳入 `durationMillis`），避免 mpv 打开后微调容器时长导致引擎与原生解码器重建；
 * - 切换视频或退出播放器时确定性关闭持久化单解码器并清空位图。
 */
@Composable
fun rememberScrubFrameCache(
    state: PlayerScreenState,
    storyboardCache: StoryboardCache,
    mediaRepository: MediaService,
    platform: PikoPlatform,
): ScrubFrameCache? {
    val source: StoryboardSource? = state.storyboardSource
    val scope = rememberCoroutineScope()
    val gate = remember(source?.fileId, source?.gcid, source?.mediaId) {
        MutableStateFlow(StoryboardPlaybackGate())
    }

    LaunchedEffect(source?.fileId, source?.gcid, source?.mediaId, state) {
        if (source == null) return@LaunchedEffect
        snapshotFlow {
            val ahead = state.storyboardBufferedAheadMillis
            val quantizedAhead = when {
                ahead == null -> 0L
                ahead >= GATE_BUFFER_QUANTUM_MS -> (ahead / GATE_BUFFER_QUANTUM_MS) * GATE_BUFFER_QUANTUM_MS
                else -> ahead
            }
            StoryboardPlaybackGate(
                firstFrameReady = state.isFirstFrameReady,
                bufferedAheadMs = quantizedAhead,
                isPlaying = state.isPlaying,
                isPlaybackBusy = state.isPlaybackBusyForStoryboard,
                hasBufferReading = ahead != null,
            )
        }.collect { gate.value = it }
    }

    val cache = remember(source?.fileId, source?.gcid, source?.mediaId) {
        if (source == null) {
            null
        } else {
            val session = mediaRepository.openStoryboardSession(source)
            var resolvedSize = source.streamSizeBytes?.takeIf { it > 0L } ?: 0L
            val engine = StoryboardEngine(
                source = source,
                cache = storyboardCache,
                resolveStreamSize = {
                    val size = mediaRepository.resolveStoryboardStreamSize(source)
                    if (size != null && size > 0L) resolvedSize = size
                    size
                },
                fetchRange = { offset, length ->
                    val size = resolvedSize.takeIf { it > 0L }
                        ?: mediaRepository.resolveStoryboardStreamSize(source)?.also { resolvedSize = it }
                        ?: return@StoryboardEngine null
                    mediaRepository.fetchStoryboardRange(source, size, offset, length)
                },
                decoderFactory = { platform.createStoryboardIdrDecoder() },
                playbackGate = gate,
                scope = scope,
                streamFetcher = session,
                onTransportGateChange = session::updateTransportGate,
                onClose = session::close,
            )
            ScrubFrameCache(
                durationMillis = source.durationMillis,
                engine = engine,
                decodeBitmap = platform::decodeEncodedImageBitmap,
                scope = scope,
            )
        }
    }

    DisposableEffect(cache) {
        onDispose { cache?.clear() }
    }
    return cache
}

private const val GATE_BUFFER_QUANTUM_MS = 250L
