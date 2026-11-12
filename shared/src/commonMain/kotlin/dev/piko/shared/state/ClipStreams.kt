package dev.piko.shared.state

import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.media.CLIP_SCAN_READ_AHEAD_BYTES
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.PreparedClip
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.shared.media.proxy.ProxyByteSource
import io.github.nihildigit.pikpak.BufferBudget
import io.github.nihildigit.pikpak.ConsumerIntent
import io.github.nihildigit.pikpak.PlaybackBufferSnapshot
import io.github.nihildigit.pikpak.ServiceIntent
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** 当前屏幕焦点片段的播放阶段。 */
enum class FocusPlaybackState {
    /** 正在等待首帧、缓冲恢复或定位落点。 */
    Waiting,

    /** 画面已在稳定推进播放。 */
    Playing,

    /** 用户在当前焦点页主动暂停。 */
    Paused,
}

/**
 * 信息流屏幕的单一权威展示需求快照：
 * 统一表达当前焦点页、冷启动/底部等待页正在等待的首个候补段，以及相邻预渲染页集合。
 */
data class ClipDisplayDemand(
    val focusClip: Clip? = null,
    val focusState: FocusPlaybackState = FocusPlaybackState.Waiting,
    val awaitingClip: Clip? = null,
    val prerenderingNeighbours: Set<Clip> = emptySet(),
)

/**
 * 信息流各片段的流会话与预取调度器（仅在主线程调用，内部字典不加锁）。
 *
 * 每个 [Clip] 自始至终持有同一个 [TransferDemand]（跨详情查询、关键帧扫描、首尾预取与代理读取原地升降级）；
 * 所有条目的 [ConsumerIntent] 均由单一权威 [ClipDisplayDemand] 快照与挂起态纯函数投影，不保留平行的前后台/急迫布尔开关。
 */
class ClipStreams internal constructor(
    private val repository: PikoMediaRepository,
    private val onReady: (Clip) -> Unit,
    private val onDead: (Clip) -> Unit,
    coroutineContext: kotlin.coroutines.CoroutineContext = Dispatchers.Main.immediate,
    private val prepare: suspend (Clip, StreamRole, TransferDemand, ((ProxyByteSource) -> Unit)?) -> Result<PreparedClip> = { clip, role, owner, onByteSource ->
        repository.prepareClip(clip.fileId, clip.startMs, clip.videoDurationMs, role, owner, onByteSource)
    },
) {
    internal constructor(
        repository: PikoMediaRepository,
        onReady: (Clip) -> Unit,
        onDead: (Clip) -> Unit,
        coroutineContext: kotlin.coroutines.CoroutineContext,
        prepare: suspend (Clip, StreamRole, TransferDemand) -> Result<PreparedClip>,
    ) : this(repository, onReady, onDead, coroutineContext, { clip, role, owner, _ -> prepare(clip, role, owner) })

    private data class BoostWindow(val seconds: Int, val minBytes: Long)

    private class Entry(val demand: TransferDemand) {
        lateinit var result: Deferred<PreparedClip?>
        @Volatile var byteSource: ProxyByteSource? = null
        @Volatile var priorSourceBytes: Long = 0L
        var prepared: PreparedClip? = null
        var fetch: Job? = null
        var wanted = false
        var ready = false
        var closed = false
        var boostWindow: BoostWindow? = null
        var feedbackOwner: Any? = null
        var nativeGeneration = Long.MIN_VALUE
        var epoch = 0L
        var lastPlaybackSample: PlaybackBufferSnapshot? = null

        fun attachSource(source: ProxyByteSource) {
            val prev = byteSource
            if (prev != null && prev !== source) {
                priorSourceBytes += prev.receivedBytes
            }
            byteSource = source
        }

        fun receivedBytes(): Long =
            priorSourceBytes + maxOf(byteSource?.receivedBytes ?: 0L, prepared?.receivedBytes ?: 0L)

        fun isReactivating(): Boolean =
            prepared?.isReactivating?.value == true || byteSource?.isReactivating?.value == true
    }

    private val scope = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
    private val preparing = PreparationQueue(backgroundSlots = 4)
    private val prefetching = PreparationQueue(backgroundSlots = 2)
    private val streams = mutableMapOf<Clip, Entry>()
    private val ripenedOrder = ArrayList<Clip>()
    private var retiredBytes = 0L
    private var display = ClipDisplayDemand()
    private val suspended = MutableStateFlow(false)

    val displayDemand: ClipDisplayDemand get() = display

    fun setSuspended(value: Boolean) {
        if (suspended.value == value) return
        suspended.value = value
        for ((clip, entry) in streams) {
            if (value) {
                entry.fetch?.cancel()
                entry.fetch = null
            }
            projectIntent(clip, entry)
        }
        if (!value) {
            ripen(streams.filterValues { it.wanted && !it.ready }.keys.toList())
        }
    }

    /** 原子更新信息流展示需求集合，并对全部活跃条目原地重投影 [ConsumerIntent]。 */
    fun updateDisplayDemand(next: ClipDisplayDemand) {
        if (display == next) return
        display = next
        reprojectAll()
    }

    /** 冷启动首段尚未刷出或停在底部等待页时，声明界面正在等待的候补片段。 */
    fun setAwaitingClip(clip: Clip?) {
        if (display.awaitingClip == clip) return
        display = display.copy(awaitingClip = clip)
        reprojectAll()
    }

    /** 更新当前焦点页的播放阶段（等待首帧/缓冲、稳定播放、用户暂停）。 */
    fun updateFocusState(clip: Clip, state: FocusPlaybackState) {
        if (display.focusClip != clip) {
            display = display.copy(focusClip = clip, focusState = state)
            reprojectAll()
            return
        }
        if (display.focusState == state) return
        display = display.copy(focusState = state)
        streams[clip]?.let { projectIntent(clip, it) }
    }

    /**
     * 将有序片段集合投影为「首项焦点页 + 其余相邻预渲染页」。
     * 供无头基准工具与测试以集合形式一步声明前台焦点与邻居。
     */
    fun bringToFront(clips: Set<Clip>) {
        val nextFocus = clips.firstOrNull()
        val nextNeighbours = if (clips.size > 1 && nextFocus != null) clips - nextFocus else emptySet()
        val preservedState = if (nextFocus != null && nextFocus == display.focusClip) {
            display.focusState
        } else {
            val existing = nextFocus?.let { streams[it] }
            if (existing != null && existing.result.isCompleted) FocusPlaybackState.Playing else FocusPlaybackState.Waiting
        }
        updateDisplayDemand(
            display.copy(
                focusClip = nextFocus,
                focusState = preservedState,
                prerenderingNeighbours = nextNeighbours,
            ),
        )
    }

    fun get(clip: Clip, forPlayer: Boolean = false, urgent: Boolean = false): Deferred<PreparedClip?> {
        val existing = streams[clip]
        val needsUrgent = urgent || (forPlayer && display.focusClip == null && existing?.result?.isCompleted != true)
        if (needsUrgent && display.focusClip != clip) {
            display = display.copy(focusClip = clip, focusState = FocusPlaybackState.Waiting)
            reprojectAll()
        } else if (urgent && display.focusClip == clip && display.focusState != FocusPlaybackState.Waiting) {
            display = display.copy(focusState = FocusPlaybackState.Waiting)
        } else if (forPlayer && display.focusClip != null && display.focusClip != clip && clip !in display.prerenderingNeighbours) {
            display = display.copy(prerenderingNeighbours = display.prerenderingNeighbours + clip)
        }
        existing?.let {
            projectIntent(clip, it)
            return it.result
        }
        val initialDemand = repository.openTransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.PREFETCH,
                bufferBudget = BufferBudget.NONE,
            ),
        )
        val entry = Entry(initialDemand)
        streams[clip] = entry
        projectIntent(clip, entry)
        entry.result = scope.async(start = CoroutineStart.LAZY) {
            preparing.run(entry.demand.priorityFlow) {
                val role = if (entry.demand.intent.service == ServiceIntent.WAITING || entry.demand.intent.service == ServiceIntent.PLAYING) {
                    StreamRole.FOREGROUND
                } else {
                    StreamRole.BACKGROUND
                }
                val prepared = prepare(
                    clip,
                    role,
                    entry.demand,
                    entry::attachSource,
                ).getOrElse { error ->
                    if (!entry.closed && error !is CancellationException) {
                        PikoLog.w(
                            TAG,
                            "Failed to prepare clip stream for ${logFile(clip.fileId, clip.name)} " +
                                "(startMs=${clip.startMs}, service=${entry.demand.intent.service}, priority=${entry.demand.priority})",
                            error,
                        )
                    }
                    return@run null
                }
                if (entry.closed) {
                    prepared.close()
                    return@run null
                }
                entry.prepared = prepared
                projectIntent(clip, entry)
                prepared
            }
        }
        entry.result.start()
        return entry.result
    }

    fun ripen(upcoming: List<Clip>) {
        ripenedOrder.clear()
        ripenedOrder.addAll(upcoming)
        for (clip in upcoming) {
            get(clip)
            val entry = streams.getValue(clip)
            entry.wanted = true
        }
        reprojectAll()
        for (clip in upcoming) {
            val entry = streams[clip] ?: continue
            if (entry.ready || entry.fetch?.isActive == true || suspended.value) continue
            entry.fetch = scope.launch {
                try {
                    val prepared = entry.result.await()
                    if (prepared == null) {
                        if (!entry.closed) {
                            streams.remove(clip)
                            ripenedOrder.remove(clip)
                            close(entry)
                            reprojectAll()
                            onDead(clip)
                        }
                        return@launch
                    }
                    suspended.first { !it }
                    prefetching.run(entry.demand.priorityFlow) {
                        suspended.first { !it }
                        val role = if (entry.demand.intent.service == ServiceIntent.WAITING) {
                            StreamRole.FOREGROUND
                        } else {
                            StreamRole.BACKGROUND
                        }
                        withContext(entry.demand + Dispatchers.Default) { prepared.prefetch(role) }
                    }
                    entry.ready = true
                    reprojectAll()
                    if (!entry.closed) onReady(clip)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!entry.closed) {
                        PikoLog.w(
                            TAG,
                            "Failed to prefetch clip stream for ${logFile(clip.fileId, clip.name)} " +
                                "(startMs=${clip.startMs}, receivedBytes=${entry.receivedBytes()})",
                            e,
                        )
                        streams.remove(clip)
                        ripenedOrder.remove(clip)
                        close(entry)
                        reprojectAll()
                        onDead(clip)
                    }
                }
            }
        }
    }

    fun boost(clip: Clip, seconds: Int, minBytes: Long = PreparedClip.MIN_AHEAD_BYTES) {
        get(clip)
        val entry = streams.getValue(clip)
        entry.boostWindow = BoostWindow(seconds, minBytes)
        projectIntent(clip, entry)
    }

    fun holdAllBut(clip: Clip) {
        for ((other, entry) in streams) {
            if (other != clip && entry.boostWindow != null) {
                entry.boostWindow = null
                projectIntent(other, entry)
            }
        }
    }

    fun bindPlayback(clip: Clip, owner: Any) {
        get(clip, forPlayer = true)
        val entry = streams.getValue(clip)
        if (entry.feedbackOwner === owner) return
        entry.feedbackOwner = owner
        entry.nativeGeneration = Long.MIN_VALUE
        entry.epoch++
        entry.lastPlaybackSample = PlaybackBufferSnapshot(
            generation = entry.epoch,
            bufferedDuration = null,
            sampledAt = TimeSource.Monotonic.markNow(),
            mediaBytesPerSecond = entry.prepared?.estimatedBytesPerSecond,
        )
        projectIntent(clip, entry)
    }

    fun reportPlaybackBuffer(clip: Clip, owner: Any, snapshot: PlaybackBufferSnapshot) {
        val entry = streams[clip] ?: return
        if (entry.feedbackOwner !== owner || snapshot.generation < entry.nativeGeneration) return
        val wasUnset = entry.nativeGeneration == Long.MIN_VALUE
        if (snapshot.generation > entry.nativeGeneration) {
            entry.nativeGeneration = snapshot.generation
            if (!wasUnset) {
                entry.epoch++
            }
        }
        val remapped = snapshot.copy(
            generation = entry.epoch,
            mediaBytesPerSecond = snapshot.mediaBytesPerSecond ?: entry.prepared?.estimatedBytesPerSecond,
        )
        entry.lastPlaybackSample = remapped
        if (!wasUnset && entry.demand.intent.epoch != entry.epoch) {
            projectIntent(clip, entry)
        } else {
            entry.demand.reportPlaybackBuffer(remapped)
            entry.prepared?.reportPlaybackBuffer(remapped)
        }
    }

    fun beginSeek(clip: Clip, backend: PlaybackBackend) {
        bindPlayback(clip, backend)
        val entry = streams[clip] ?: return
        entry.nativeGeneration = backend.playbackGeneration + 1L
        entry.epoch++
        entry.lastPlaybackSample = PlaybackBufferSnapshot(
            generation = entry.epoch,
            bufferedDuration = null,
            sampledAt = TimeSource.Monotonic.markNow(),
            mediaBytesPerSecond = entry.prepared?.estimatedBytesPerSecond,
        )
        if (display.focusClip == clip && display.focusState != FocusPlaybackState.Waiting) {
            display = display.copy(focusState = FocusPlaybackState.Waiting)
        }
        projectIntent(clip, entry)
    }

    private fun effectivePrimaryClip(): Pair<Clip?, FocusPlaybackState> {
        display.focusClip?.let { return it to display.focusState }
        val awaiting = display.awaitingClip?.takeIf { streams[it]?.ready != true }
            ?: ripenedOrder.firstOrNull { streams[it]?.ready != true }
        return awaiting to FocusPlaybackState.Waiting
    }

    private fun reprojectAll() {
        for ((clip, entry) in streams) {
            projectIntent(clip, entry)
        }
    }

    private fun projectIntent(clip: Clip, entry: Entry) {
        if (entry.closed || entry.demand.isClosed) return
        val (primaryClip, primaryState) = effectivePrimaryClip()
        val isPrimary = clip == primaryClip
        val service = when {
            suspended.value -> ServiceIntent.PAUSED
            isPrimary -> when (primaryState) {
                FocusPlaybackState.Waiting -> ServiceIntent.WAITING
                FocusPlaybackState.Playing -> ServiceIntent.PLAYING
                FocusPlaybackState.Paused -> ServiceIntent.PAUSED
            }
            else -> ServiceIntent.PREFETCH
        }
        val prep = entry.prepared
        val budget = when {
            suspended.value || service == ServiceIntent.PAUSED -> BufferBudget.NONE
            prep == null -> if (service == ServiceIntent.WAITING) {
                BufferBudget(maxReadAheadBytes = CLIP_SCAN_READ_AHEAD_BYTES)
            } else {
                BufferBudget.NONE
            }
            isPrimary && (service == ServiceIntent.WAITING || service == ServiceIntent.PLAYING) -> {
                val boost = entry.boostWindow
                if (boost != null) {
                    BufferBudget(
                        targetBuffer = boost.seconds.seconds,
                        maxReadAheadBytes = prep.readAheadBytesFor(boost.seconds, boost.minBytes),
                    )
                } else {
                    BufferBudget.NONE
                }
            }
            else -> BufferBudget.NONE
        }
        val previous = entry.demand.intent
        val sample = entry.lastPlaybackSample?.takeIf { it.generation == entry.epoch }
        entry.demand.submitIntent(
            ConsumerIntent(
                epoch = entry.epoch,
                service = service,
                bufferBudget = budget,
                playbackSample = sample,
            ),
        )
        if (previous.bufferBudget.maxReadAheadBytes > 0L && budget.maxReadAheadBytes == 0L) {
            entry.demand.withdrawSpeculation()
        }
    }

    fun receivedBytes(clip: Clip): Long = streams[clip]?.receivedBytes() ?: 0L

    fun isReactivating(clip: Clip): Boolean = streams[clip]?.isReactivating() ?: false

    fun totalReceivedBytes(): Long = retiredBytes + streams.values.sumOf { it.receivedBytes() }

    fun anyReactivating(): Boolean = streams.values.any { it.isReactivating() }

    fun sync(window: Set<Clip>) {
        (streams.keys - window).forEach { clip ->
            ripenedOrder.remove(clip)
            streams.remove(clip)?.let(::close)
        }
        val cleanedFocus = display.focusClip?.takeIf { it in window }
        val cleanedAwaiting = display.awaitingClip?.takeIf { it in window }
        val cleanedNeighbours = display.prerenderingNeighbours.intersect(window)
        if (cleanedFocus != display.focusClip || cleanedAwaiting != display.awaitingClip || cleanedNeighbours != display.prerenderingNeighbours) {
            display = ClipDisplayDemand(
                focusClip = cleanedFocus,
                focusState = display.focusState,
                awaitingClip = cleanedAwaiting,
                prerenderingNeighbours = cleanedNeighbours,
            )
            reprojectAll()
        }
    }

    fun closeAll() {
        streams.values.forEach(::close)
        streams.clear()
        ripenedOrder.clear()
        retiredBytes = 0L
        display = ClipDisplayDemand()
    }

    private fun close(entry: Entry) {
        if (entry.closed) return
        entry.closed = true
        retiredBytes += entry.receivedBytes()
        entry.fetch?.cancel()
        entry.result.cancel()
        entry.demand.close()
        entry.prepared?.close()
    }

    private companion object {
        const val TAG = "ClipFeed"
    }
}
