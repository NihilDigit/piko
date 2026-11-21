package dev.piko.shared.media.storyboard

import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.time.Clock

/**
 * 播放器实时状态快照，用于控制雪碧图三阶段触发与动态并发退让策略。
 */
data class StoryboardPlaybackGate(
    val firstFrameReady: Boolean = false,
    val bufferedAheadMs: Long = 0L,
    val isPlaying: Boolean = false,
    /** 正在准备、重连、拖动定位或网络卡顿缓冲时为 true，后台雪碧图抓取立即暂停让路。 */
    val isPlaybackBusy: Boolean = true,
    /** 后端是否已上报可信缓冲读数：`true` 时 `bufferedAheadMs == 0L` 视为真实耗尽并立即退让；`false` 时视为未上报。 */
    val hasBufferReading: Boolean = bufferedAheadMs > 0L,
)

/**
 * 单视频全片雪碧图引擎（仅对 `<= 480P` MPEG-TS 转码流启用）：
 * - Stage (a) 磁盘缓存命中（按 `gcid`）：开播即并行读入（`< 5ms`，0 字节网络流量）；对旧版稀疏 `.sb2`（时长 `>= 3m` 且帧数不足计划槽位的 65%）自动在内存中降级为部分缓存并后台补齐；
 * - Stage (b) 事件驱动启动与统一动态并发调度：首帧就绪且暂停/缓冲健康（`>= 3s` 零等待启动，未上报缓冲余量时平稳播放 `>= 800ms` 启动）后，
 *   由单队列优先级调度器（Priority 0 用户焦距附近 16 槽 > Priority 1 Wave 1 锚点 > Priority 2 Wave 2 其余槽位）
 *   驱动动态并发（暂停或交互优先 6 路并发、播放中缓冲充裕时 Wave 1 4 路、Wave 2 3 路，缓冲低于 3s 或卡顿立即退让为 0）；
 * - 偏移 0 流头探测沿用 `<= 64 KiB`（命中 SDK `MICRO_PROBE_PRIORITY = 12`），中段槽位按转码流字节速率单次自适应定容为 `192..384 KiB` 覆盖约 5s GOP + IDR 帧，
 *   仅在跨界截断（补拉紧凑尾块）或超长 GOP（扩展至 512 KiB 上限）时补拉单次续块并在内存中拼接，零重复流量；
 * - 复用单个持久化 [StoryboardIdrDecoder]，仅在 2–5ms 解码阶段串行加锁，网络拉取 100% 并行重叠。
 */
class StoryboardEngine(
    val source: StoryboardSource,
    private val cache: StoryboardCache,
    private val resolveStreamSize: suspend () -> Long?,
    private val fetchRange: suspend (offset: Long, length: Int) -> ByteArray?,
    private val decoderFactory: () -> StoryboardIdrDecoder?,
    private val playbackGate: StateFlow<StoryboardPlaybackGate>,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : AutoCloseable {
    private val initialSlotCount = StoryboardPlanner.slotCountForDuration(source.durationMillis)
    private val boostCoalesceThresholdMs = run {
        val slotStep = if (initialSlotCount > 0) source.durationMillis / initialSlotCount else 0L
        maxOf(BOOST_COALESCE_MIN_DELTA_MS, slotStep / 2L)
    }
    private val stateLock = Mutex()
    private val decodeLock = Mutex()
    private val persistLock = Mutex()
    private val decodedBySlot = LinkedHashMap<Int, StoryboardFrame>()
    private val failedSlots = HashSet<Int>()

    private val _timeline = MutableStateFlow(
        StoryboardTimeline(
            durationMillis = source.durationMillis,
            totalSlots = initialSlotCount,
            frames = emptyList(),
            isComplete = false,
        ),
    )
    val timeline: StateFlow<StoryboardTimeline> = _timeline.asStateFlow()

    private val _plannedSlots = MutableStateFlow<List<StoryboardSlotPlan>>(emptyList())
    val plannedSlots: StateFlow<List<StoryboardSlotPlan>> = _plannedSlots.asStateFlow()

    private val priorityTargetMs = MutableStateFlow<Long?>(null)
    private val hasUserBoost = MutableStateFlow(false)
    internal val activePriorityTargetMs: Long? get() = priorityTargetMs.value
    private var workerJob: Job? = null
    private var dirtyNewFrames = 0
    private var continuationFetches = 0

    fun start() {
        if (workerJob != null) return
        workerJob = scope.launch(Dispatchers.IO) {
            runEngine()
        }
    }

    /**
     * Stage (c) 用户交互覆盖：悬停进度条或打开「速览」时调用，立即打破冷启动等待并将目标时刻附近槽位抢占至最高优先级。
     * 高频微幅移动（不足半个槽位间隔）自动合并，避免频繁重排优先级队列。
     */
    fun boostAround(targetMillis: Long) {
        val maxMs = source.durationMillis.coerceAtLeast(0L)
        val clamped = targetMillis.coerceIn(0L, maxMs)
        val prev = priorityTargetMs.value
        if (prev != null) {
            val atBoundary = (clamped == 0L && prev != 0L) || (clamped == maxMs && prev != maxMs)
            if (!atBoundary && abs(clamped - prev) < boostCoalesceThresholdMs) {
                return
            }
        }
        priorityTargetMs.value = clamped
        if (!hasUserBoost.value) {
            hasUserBoost.value = true
        }
    }

    private suspend fun runEngine() {
        // Stage (a): 磁盘缓存命中即刻加载（< 5ms，0 网络）
        val cachedBundle = runCatching { cache.read(source.gcid) }.getOrNull()
        var effectiveComplete = false
        if (cachedBundle != null && cachedBundle.frames.isNotEmpty()) {
            val sparseCompletedCache = isSparseCompletedCache(
                bundle = cachedBundle,
                durationMillis = source.durationMillis,
                expectedSlots = initialSlotCount,
            )
            effectiveComplete = cachedBundle.completed && !sparseCompletedCache
            stateLock.withLock {
                for (frame in cachedBundle.frames) {
                    decodedBySlot[frame.slotIndex] = frame
                }
                publishTimelineLocked(isComplete = effectiveComplete)
            }
            if (effectiveComplete) {
                PikoLog.d(TAG, "Storyboard disk cache hit (complete, ${cachedBundle.frames.size} frames): ${logFile(source.fileId, source.mediaId)}")
                return
            }
            if (sparseCompletedCache) {
                PikoLog.i(
                    TAG,
                    "Storyboard disk cache sparse (${cachedBundle.frames.size}/$initialSlotCount frames), self-healing in background: ${logFile(source.fileId, source.mediaId)}",
                )
            } else {
                PikoLog.d(TAG, "Storyboard disk cache hit (partial, ${cachedBundle.frames.size} frames), continuing fill: ${logFile(source.fileId, source.mediaId)}")
            }
        }

        // Stage (b): 事件驱动等待首帧就绪且暂停/缓冲健康，或被 Stage (c) 交互零延迟打破
        awaitStartupTrigger()
        val engineStartMs = nowMillis()

        val streamSize = source.streamSizeBytes?.takeIf { it > 0L } ?: resolveStreamSize() ?: return
        val slots = StoryboardPlanner.buildSlots(source.durationMillis, streamSize)
        if (slots.isEmpty()) return
        _plannedSlots.value = slots
        if (cachedBundle != null && !effectiveComplete) {
            reconcileCachedSlots(slots)
        }

        // 打开一次持久化单解码器，全程复用，退出时确定性释放
        val decoder = decoderFactory() ?: return
        try {
            // 探测流头（首块保持 <= 64 KiB 命中 MICRO_PROBE_PRIORITY=12，仅缺失参数集或首帧跨界时单次补拉续块拼接）
            awaitPlaybackIdleIfBackground()
            val headPrimaryLen = minOf(HEAD_PROBE_BYTES.toLong(), streamSize).toInt()
            val headPrimary = fetchRange(0L, headPrimaryLen) ?: return
            if (headPrimary.isEmpty()) return
            var headBytes = headPrimary
            var header = TsIdrExtractor.parseHeader(
                bytes = headBytes,
                fallbackWidth = source.width,
                fallbackHeight = source.height,
            )
            val headTargetCap = minOf(
                streamSize,
                maxOf(StoryboardPlanner.MIN_ADAPTIVE_PROBE_BYTES.toLong(), slots.first().probeLengthBytes.toLong()),
            )
            var headContinuationFetched = false
            if (header == null && headTargetCap > headBytes.size.toLong()) {
                val contOffset = headBytes.size.toLong()
                val contLen = (headTargetCap - contOffset).toInt()
                if (contLen > 0) {
                    awaitPlaybackIdleIfBackground()
                    val contBytes = fetchRange(contOffset, contLen)
                    if (contBytes != null && contBytes.isNotEmpty()) {
                        headContinuationFetched = true
                        recordContinuationFetch()
                        headBytes = concatBytes(headBytes, contBytes)
                        header = TsIdrExtractor.parseHeader(
                            bytes = headBytes,
                            fallbackWidth = source.width,
                            fallbackHeight = source.height,
                        )
                    }
                }
            }
            val resolvedHeader = header ?: return

            // 顺手解码第 0 槽位（0 额外网络往返；若首 64 KiB 未含完整首帧，按需补拉紧凑续块拼接）
            val needSlot0 = stateLock.withLock { 0 !in decodedBySlot }
            if (needSlot0) {
                val outcome0 = decodeChunkIntoSlot(
                    slotIndex = 0,
                    bytes = headBytes,
                    header = resolvedHeader,
                    decoder = decoder,
                    allowTailDrain = headBytes.size.toLong() >= streamSize,
                )
                if (outcome0 !is ChunkDecodeOutcome.Decoded && !headContinuationFetched && headTargetCap > headBytes.size.toLong()) {
                    val contOffset = headBytes.size.toLong()
                    val contLen = continuationLengthBytes(
                        outcome = outcome0,
                        remainingBudget = headTargetCap - contOffset,
                    )
                    if (contLen > 0) {
                        awaitPlaybackIdleIfBackground()
                        val contBytes = fetchRange(contOffset, contLen)
                        if (contBytes != null && contBytes.isNotEmpty()) {
                            recordContinuationFetch()
                            val combined = concatBytes(headBytes, contBytes)
                            decodeChunkIntoSlot(
                                slotIndex = 0,
                                bytes = combined,
                                header = resolvedHeader,
                                decoder = decoder,
                                allowTailDrain = combined.size.toLong() >= streamSize,
                            )
                        }
                    }
                }
            }

            val wave1 = StoryboardPlanner.wave1Indices(slots.size)
            val wave2 = StoryboardPlanner.wave2Indices(slots.size, wave1)

            // 统一优先级 + 动态并发调度器（覆盖 Priority 0 交互抢占、Priority 1 Wave 1、Priority 2 Wave 2）
            runUnifiedScheduler(
                wave1 = wave1,
                wave2 = wave2,
                allSlots = slots,
                streamSize = streamSize,
                header = resolvedHeader,
                decoder = decoder,
                engineStartMs = engineStartMs,
            )

            val summary = stateLock.withLock {
                val processedAll = slots.isNotEmpty() && decodedBySlot.size + failedSlots.size >= slots.size
                val minCompletedFrames = maxOf(1, (slots.size * MIN_COMPLETION_NUMERATOR) / MIN_COMPLETION_DENOMINATOR)
                val done = processedAll && (failedSlots.isEmpty() || decodedBySlot.size >= minCompletedFrames)
                CompletionSummary(
                    allDone = done,
                    hasFrames = decodedBySlot.isNotEmpty(),
                    frames = decodedBySlot.size,
                    failed = failedSlots.size,
                    continuations = continuationFetches,
                )
            }
            if (summary.allDone) {
                // 先将完整缓存落盘，再对外发布 isComplete=true，确保调用方观察到完成态后立即 close() 也不会打断落盘。
                persistSnapshotIfDirty(completed = true, header = resolvedHeader, force = true)
                stateLock.withLock {
                    publishTimelineLocked(isComplete = true)
                }
                PikoLog.i(
                    TAG,
                    "Storyboard complete: ${logFile(source.fileId, source.mediaId)}, " +
                        "frames=${summary.frames}, totalSlots=${slots.size}, failed=${summary.failed}, " +
                        "elapsedMs=${(nowMillis() - engineStartMs).coerceAtLeast(0L)}, continuations=${summary.continuations}",
                )
            } else {
                persistSnapshotIfDirty(completed = false, header = resolvedHeader, force = summary.hasFrames)
                if (summary.failed > 0) {
                    PikoLog.d(
                        TAG,
                        "Storyboard incomplete due to slot failures: ${logFile(source.fileId, source.mediaId)}, " +
                            "frames=${summary.frames}, totalSlots=${slots.size}, failed=${summary.failed}",
                    )
                }
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                persistSnapshotIfDirty(completed = false, header = null)
            }
            throw cancel
        } finally {
            runCatching { decoder.close() }
        }
    }

    /**
     * 若旧版稀疏缓存的槽位编号与当前槽位规划偏移过大（例如早期版本总槽位数不同），
     * 按真实 `PTS` 将已缓存帧重映射到最接近的当前槽位，避免自愈时漏补片头或片尾空洞。
     */
    private suspend fun reconcileCachedSlots(slots: List<StoryboardSlotPlan>) {
        if (slots.isEmpty()) return
        val slotStepMs = (source.durationMillis / slots.size.coerceAtLeast(1)).coerceAtLeast(1_000L)
        val maxDriftMs = maxOf(slotStepMs * 3L, 60_000L)
        stateLock.withLock {
            val needsRemap = decodedBySlot.any { (slotIdx, frame) ->
                slotIdx !in slots.indices || abs(slots[slotIdx].estimatedMs - frame.ptsMs) > maxDriftMs
            }
            if (!needsRemap) return@withLock
            val remapped = LinkedHashMap<Int, StoryboardFrame>(decodedBySlot.size)
            for (frame in decodedBySlot.values) {
                val nearest = slots.minByOrNull { abs(it.estimatedMs - frame.ptsMs) } ?: continue
                val existing = remapped[nearest.slotIndex]
                if (existing == null || abs(nearest.estimatedMs - frame.ptsMs) < abs(nearest.estimatedMs - existing.ptsMs)) {
                    remapped[nearest.slotIndex] = frame.copy(slotIndex = nearest.slotIndex)
                }
            }
            decodedBySlot.clear()
            decodedBySlot.putAll(remapped)
            publishTimelineLocked(isComplete = _timeline.value.isComplete)
        }
    }

    private fun isBufferCriticallyLow(gate: StoryboardPlaybackGate): Boolean =
        gate.isPlaying && gate.hasBufferReading && gate.bufferedAheadMs < CRITICAL_BUFFER_AHEAD_MS

    private fun shouldYieldForBackground(gate: StoryboardPlaybackGate): Boolean =
        gate.isPlaybackBusy || isBufferCriticallyLow(gate)

    private suspend fun awaitStartupTrigger() {
        var smoothPlayingSinceMs: Long? = null
        while (true) {
            if (hasUserBoost.value) return
            val gate = playbackGate.value
            val now = nowMillis()
            if (gate.firstFrameReady) {
                // 首帧就绪且暂停时零等待立即启动（典型场景：暂停后直接悬停或打开「速览」）
                if (!gate.isPlaying) return
                val criticallyLow = isBufferCriticallyLow(gate)
                // 缓冲余量已达健康线（>= 3s）立即启动，无需空等冷启动延迟
                if (!gate.isPlaybackBusy && gate.hasBufferReading && gate.bufferedAheadMs >= HEALTHY_BUFFER_AHEAD_MS) return
                if (!gate.isPlaybackBusy && !criticallyLow) {
                    val since = smoothPlayingSinceMs ?: now.also { smoothPlayingSinceMs = it }
                    val remaining = SMOOTH_PLAYBACK_TRIGGER_MS - (now - since)
                    if (remaining <= 0L) return
                    withTimeoutOrNull(remaining) {
                        combine(playbackGate, hasUserBoost) { g, boosted -> g to boosted }
                            .drop(1)
                            .first { (g, boosted) ->
                                boosted ||
                                    !g.firstFrameReady ||
                                    !g.isPlaying ||
                                    g.isPlaybackBusy ||
                                    isBufferCriticallyLow(g) ||
                                    (g.hasBufferReading && g.bufferedAheadMs >= HEALTHY_BUFFER_AHEAD_MS)
                            }
                    }
                    continue
                }
            }
            smoothPlayingSinceMs = null
            combine(playbackGate, hasUserBoost) { g, boosted -> g to boosted }
                .first { (g, boosted) ->
                    boosted || (g.firstFrameReady && (!g.isPlaying || (!g.isPlaybackBusy && !isBufferCriticallyLow(g))))
                }
        }
    }

    private suspend fun awaitPlaybackIdleIfBackground() {
        while (shouldYieldForBackground(playbackGate.value)) {
            // 用户主动交互时仅短暂礼让，避免卡顿重连期间彻底无响应；后台抓取则事件挂起直至恢复
            if (hasUserBoost.value) {
                delay(USER_BOOST_YIELD_MS)
                return
            }
            combine(playbackGate, hasUserBoost) { g, boosted -> !shouldYieldForBackground(g) || boosted }
                .first { it }
        }
    }

    private fun desiredConcurrency(
        gate: StoryboardPlaybackGate,
        boosted: Boolean,
        wave1Active: Boolean,
    ): Int {
        val criticallyLow = isBufferCriticallyLow(gate)
        return when {
            gate.isPlaybackBusy -> if (boosted) BUSY_BOOSTED_CONCURRENCY else 0
            !gate.isPlaying || boosted -> PAUSED_OR_BOOSTED_CONCURRENCY
            criticallyLow -> 0
            gate.hasBufferReading && gate.bufferedAheadMs < COMFORTABLE_BUFFER_AHEAD_MS -> LOW_BUFFER_PLAYING_CONCURRENCY
            wave1Active -> WAVE1_PLAYING_CONCURRENCY
            else -> WAVE2_PLAYING_CONCURRENCY
        }
    }

    private suspend fun runUnifiedScheduler(
        wave1: List<Int>,
        wave2: List<Int>,
        allSlots: List<StoryboardSlotPlan>,
        streamSize: Long,
        header: TsStoryboardHeader,
        decoder: StoryboardIdrDecoder,
        engineStartMs: Long,
    ) {
        val pendingBySlot = LinkedHashMap<Int, StoryboardSlotPlan>(allSlots.size)
        val wave1Queue = ArrayDeque<Int>(wave1.size)
        val wave2Queue = ArrayDeque<Int>(wave2.size)
        val wave1Set = HashSet<Int>(wave1.size)

        stateLock.withLock {
            for (idx in wave1) {
                if (idx in allSlots.indices && idx !in decodedBySlot && idx !in failedSlots) {
                    pendingBySlot[idx] = allSlots[idx]
                    wave1Queue.addLast(idx)
                    wave1Set += idx
                }
            }
            for (idx in wave2) {
                if (idx in allSlots.indices && idx !in decodedBySlot && idx !in failedSlots && idx !in pendingBySlot) {
                    pendingBySlot[idx] = allSlots[idx]
                    wave2Queue.addLast(idx)
                }
            }
        }
        if (pendingBySlot.isEmpty()) return

        val queueLock = Mutex()
        val boostQueue = ArrayDeque<Int>(USER_BOOST_WINDOW_SLOTS)
        var lastBoostSeen: Long? = null
        val wave1Remaining = MutableStateFlow(wave1Set.size)
        val queueExhausted = MutableStateFlow(false)
        var wave1Logged = wave1Set.isEmpty()

        suspend fun pollNext(): StoryboardSlotPlan? = queueLock.withLock {
            fun takePending(idx: Int): StoryboardSlotPlan? {
                val taken = pendingBySlot.remove(idx) ?: return null
                if (pendingBySlot.isEmpty()) {
                    queueExhausted.value = true
                }
                return taken
            }

            val boost = priorityTargetMs.value
            if (boost != null && boost != lastBoostSeen && pendingBySlot.isNotEmpty()) {
                lastBoostSeen = boost
                boostQueue.clear()
                val nearest = pendingBySlot.values
                    .sortedWith(compareBy<StoryboardSlotPlan> { abs(it.estimatedMs - boost) }.thenBy { it.slotIndex })
                    .take(USER_BOOST_WINDOW_SLOTS)
                for (slot in nearest) {
                    boostQueue.addLast(slot.slotIndex)
                }
            }
            // Priority 0: 用户焦点附近 ~16 个槽位（可抢占未完成的 Wave 1 与 Wave 2）
            while (boostQueue.isNotEmpty()) {
                val idx = boostQueue.removeFirst()
                takePending(idx)?.let { return@withLock it }
            }
            // Priority 1: Wave 1 全片均匀锚点
            while (wave1Queue.isNotEmpty()) {
                val idx = wave1Queue.removeFirst()
                takePending(idx)?.let { return@withLock it }
            }
            // Priority 2: Wave 2 其余二分细分槽位
            while (wave2Queue.isNotEmpty()) {
                val idx = wave2Queue.removeFirst()
                takePending(idx)?.let { return@withLock it }
            }
            queueExhausted.value = true
            null
        }

        suspend fun awaitWorkerPermit(workerId: Int): Boolean {
            while (true) {
                if (queueExhausted.value) return false
                val gate = playbackGate.value
                val boosted = hasUserBoost.value
                val allowed = desiredConcurrency(
                    gate = gate,
                    boosted = boosted,
                    wave1Active = wave1Remaining.value > 0,
                )
                if (workerId < allowed) {
                    if (gate.isPlaybackBusy && boosted) {
                        delay(USER_BOOST_YIELD_MS)
                        return !queueExhausted.value
                    }
                    return true
                }
                combine(playbackGate, hasUserBoost, wave1Remaining, queueExhausted) { g, isBoosted, w1, exhausted ->
                    exhausted || workerId < desiredConcurrency(g, isBoosted, w1 > 0)
                }.first { it }
            }
        }

        suspend fun onSlotFinished(slotIndex: Int) {
            if (slotIndex !in wave1Set) return
            val remaining = wave1Remaining.updateAndGet { (it - 1).coerceAtLeast(0) }
            if (remaining != 0) return
            val shouldLog = queueLock.withLock {
                if (wave1Logged) {
                    false
                } else {
                    wave1Logged = true
                    true
                }
            }
            if (shouldLog) {
                val (frames, contCount) = stateLock.withLock { decodedBySlot.size to continuationFetches }
                PikoLog.i(
                    TAG,
                    "Storyboard wave1 complete: ${logFile(source.fileId, source.mediaId)}, " +
                        "frames=$frames, totalSlots=${allSlots.size}, " +
                        "elapsedMs=${(nowMillis() - engineStartMs).coerceAtLeast(0L)}, continuations=$contCount",
                )
                persistSnapshotIfDirty(completed = false, header = header)
            }
        }

        coroutineScope {
            val workers = (0 until MAX_CONCURRENCY).map { workerId ->
                async {
                    while (true) {
                        if (!awaitWorkerPermit(workerId)) break
                        val slot = pollNext() ?: break
                        try {
                            processSlot(slot, streamSize, header, decoder)
                        } finally {
                            onSlotFinished(slot.slotIndex)
                        }
                        maybePersistBatch(header)
                    }
                }
            }
            workers.awaitAll()
        }
    }

    private suspend fun processSlot(
        slot: StoryboardSlotPlan,
        streamSize: Long,
        header: TsStoryboardHeader,
        decoder: StoryboardIdrDecoder,
    ) {
        val primaryLen = minOf(
            slot.probeLengthBytes.toLong(),
            streamSize - slot.byteOffset,
        ).toInt()
        if (primaryLen <= 0) {
            stateLock.withLock { failedSlots += slot.slotIndex }
            return
        }
        val primaryBytes = fetchRange(slot.byteOffset, primaryLen)
        if (primaryBytes != null && primaryBytes.isNotEmpty()) {
            val primaryEnd = slot.byteOffset + primaryBytes.size
            val outcome = decodeChunkIntoSlot(
                slotIndex = slot.slotIndex,
                bytes = primaryBytes,
                header = header,
                decoder = decoder,
                allowTailDrain = primaryEnd >= streamSize,
            )
            if (outcome is ChunkDecodeOutcome.Decoded) {
                return
            }
            // 仅在未抽到完整 IDR 时发起单次续块：块尾已见跨界 IDR PES 仅补拉精确/紧凑尾块，超长 GOP 扩展至 512 KiB 上限，零重复流量。
            if (outcome !is ChunkDecodeOutcome.DecodeFailed) {
                val maxExpandedEnd = minOf(
                    slot.byteOffset + StoryboardPlanner.EXPANDED_PROBE_BYTES.toLong(),
                    streamSize,
                )
                val contOffset = slot.byteOffset + primaryBytes.size
                val remainingBudget = (maxExpandedEnd - contOffset).coerceAtLeast(0L)
                val contLen = continuationLengthBytes(outcome, remainingBudget)
                if (contLen > 0) {
                    awaitPlaybackIdleIfBackground()
                    val contBytes = fetchRange(contOffset, contLen)
                    if (contBytes != null && contBytes.isNotEmpty()) {
                        recordContinuationFetch()
                        val combined = concatBytes(primaryBytes, contBytes)
                        val combinedEnd = slot.byteOffset + combined.size
                        val contOutcome = decodeChunkIntoSlot(
                            slotIndex = slot.slotIndex,
                            bytes = combined,
                            header = header,
                            decoder = decoder,
                            allowTailDrain = combinedEnd >= streamSize,
                        )
                        if (contOutcome is ChunkDecodeOutcome.Decoded) {
                            return
                        }
                    }
                }
            }
        }
        stateLock.withLock {
            failedSlots += slot.slotIndex
        }
    }

    private fun continuationLengthBytes(
        outcome: ChunkDecodeOutcome,
        remainingBudget: Long,
    ): Int {
        if (remainingBudget <= 0L) return 0
        return when (outcome) {
            is ChunkDecodeOutcome.TruncatedAtTail -> {
                val declared = outcome.declaredRemainingBytes
                val desired = if (declared != null && declared > 0) {
                    // 计入 TS 188/184 封装开销与末尾终结包余量，尽量命中 <= 64 KiB 轻探测通道
                    val framed = ((declared.toLong() * StoryboardPlanner.TS_PACKET_BYTES) + 183L) / 184L +
                        StoryboardPlanner.TS_PACKET_BYTES * 8L
                    framed.coerceIn(MIN_TAIL_CONTINUATION_BYTES, StoryboardPlanner.TAIL_CONTINUATION_BYTES.toLong())
                } else {
                    StoryboardPlanner.TAIL_CONTINUATION_BYTES.toLong()
                }
                minOf(remainingBudget, desired).toInt()
            }
            ChunkDecodeOutcome.NotFound -> remainingBudget.toInt()
            ChunkDecodeOutcome.Decoded, ChunkDecodeOutcome.DecodeFailed -> 0
        }
    }

    private suspend fun decodeChunkIntoSlot(
        slotIndex: Int,
        bytes: ByteArray,
        header: TsStoryboardHeader,
        decoder: StoryboardIdrDecoder,
        allowTailDrain: Boolean,
    ): ChunkDecodeOutcome {
        val extracted = when (
            val result = TsIdrExtractor.extractFirstIdrDetailed(
                bytes = bytes,
                header = header,
                durationMillis = source.durationMillis,
                allowTailDrain = allowTailDrain,
            )
        ) {
            is TsIdrExtractionResult.Found -> result.idr
            is TsIdrExtractionResult.TruncatedAtTail -> return ChunkDecodeOutcome.TruncatedAtTail(result.declaredRemainingBytes)
            TsIdrExtractionResult.NotFound -> return ChunkDecodeOutcome.NotFound
        }
        val jpeg = decodeLock.withLock {
            decoder.decodeToJpeg(
                csdAnnexB = header.csdAnnexB,
                idrAccessUnitAnnexB = extracted.accessUnitAnnexB,
                width = header.width,
                height = header.height,
            )
        } ?: return ChunkDecodeOutcome.DecodeFailed
        if (jpeg.isEmpty()) return ChunkDecodeOutcome.DecodeFailed
        stateLock.withLock {
            decodedBySlot[slotIndex] = StoryboardFrame(
                slotIndex = slotIndex,
                ptsMs = extracted.ptsMs,
                jpegBytes = jpeg,
            )
            dirtyNewFrames++
            publishTimelineLocked(isComplete = _timeline.value.isComplete)
        }
        return ChunkDecodeOutcome.Decoded
    }

    private suspend fun recordContinuationFetch() {
        stateLock.withLock {
            continuationFetches++
        }
    }

    private suspend fun maybePersistBatch(header: TsStoryboardHeader) {
        val shouldPersist = stateLock.withLock { dirtyNewFrames >= PERSIST_BATCH_INTERVAL }
        if (shouldPersist) {
            persistSnapshotIfDirty(completed = false, header = header)
        }
    }

    private fun publishTimelineLocked(isComplete: Boolean) {
        // 按真实 PTS 升序排布，并对相同 PTS 去重（相邻字节探测偶尔命中同一 GOP 关键帧）
        val sorted = decodedBySlot.values
            .sortedWith(compareBy<StoryboardFrame> { it.ptsMs }.thenBy { it.slotIndex })
        val deduped = ArrayList<StoryboardFrame>(sorted.size)
        var lastPts = Long.MIN_VALUE
        for (frame in sorted) {
            if (frame.ptsMs != lastPts) {
                deduped += frame
                lastPts = frame.ptsMs
            }
        }
        _timeline.value = StoryboardTimeline(
            durationMillis = source.durationMillis,
            totalSlots = _plannedSlots.value.size.takeIf { it > 0 } ?: initialSlotCount,
            frames = deduped,
            isComplete = isComplete,
        )
    }

    private suspend fun persistSnapshotIfDirty(
        completed: Boolean,
        header: TsStoryboardHeader?,
        force: Boolean = false,
    ) = persistLock.withLock {
        val bundle = stateLock.withLock {
            if (!force && dirtyNewFrames <= 0) return@withLock null
            val frames = _timeline.value.frames
            if (frames.isEmpty()) return@withLock null
            dirtyNewFrames = 0
            StoryboardBundle(
                gcid = source.gcid,
                durationMillis = source.durationMillis,
                width = header?.width ?: source.width,
                height = header?.height ?: source.height,
                completed = completed || _timeline.value.isComplete,
                lastAccessEpochMillis = nowMillis(),
                frames = frames,
            )
        } ?: return@withLock
        runCatching { cache.write(bundle) }
            .onFailure { err ->
                if (err is CancellationException) throw err
                PikoLog.w(TAG, "Failed to persist storyboard bundle", err)
            }
    }

    override fun close() {
        workerJob?.cancel()
        workerJob = null
    }

    private sealed interface ChunkDecodeOutcome {
        data object Decoded : ChunkDecodeOutcome
        data class TruncatedAtTail(val declaredRemainingBytes: Int?) : ChunkDecodeOutcome
        data object NotFound : ChunkDecodeOutcome
        data object DecodeFailed : ChunkDecodeOutcome
    }

    private data class CompletionSummary(
        val allDone: Boolean,
        val hasFrames: Boolean,
        val frames: Int,
        val failed: Int,
        val continuations: Int,
    )

    private fun concatBytes(first: ByteArray, second: ByteArray): ByteArray =
        ByteArray(first.size + second.size).also { out ->
            first.copyInto(out, 0)
            second.copyInto(out, first.size)
        }

    internal companion object {
        const val TAG = "Storyboard"
        /** 偏移 0 流头探测保持 <= 64 KiB，命中 SDK `MICRO_PROBE_PRIORITY = 12`。 */
        const val HEAD_PROBE_BYTES = StoryboardPlanner.DEFAULT_PROBE_BYTES
        const val MIN_TAIL_CONTINUATION_BYTES = 16L * 1024L
        /** 旧版稀疏 `.sb2` 自愈门限：片长 >= 3 分钟、帧数不足 65% 且实际 PTS 覆盖存在明显空洞时降级补齐。 */
        const val SPARSE_CACHE_HEAL_MIN_DURATION_MS = 180_000L
        const val SPARSE_CACHE_HEAL_MIN_NUMERATOR = 65
        const val SPARSE_CACHE_HEAL_MIN_DENOMINATOR = 100
        const val SPARSE_CACHE_MIN_GAP_MS = 25_000L
        /** 前方缓冲低于 3 秒时后台雪碧图完全停发，优先保证前景播放不卡顿。 */
        const val CRITICAL_BUFFER_AHEAD_MS = 3_000L
        /** 起播后前方缓冲达到 3 秒视为健康，零等待立刻启动后台预取。 */
        const val HEALTHY_BUFFER_AHEAD_MS = 3_000L
        /** 前方缓冲达 6 秒以上（或后端未上报缓冲余量且播放平稳）放宽至 Wave 1 4 路 / Wave 2 3 路；3–6 秒限单路。 */
        const val COMFORTABLE_BUFFER_AHEAD_MS = 6_000L
        const val SMOOTH_PLAYBACK_TRIGGER_MS = 800L
        const val USER_BOOST_YIELD_MS = 120L
        const val BOOST_COALESCE_MIN_DELTA_MS = 1_000L
        const val MAX_CONCURRENCY = 6
        const val PAUSED_OR_BOOSTED_CONCURRENCY = 6
        const val LOW_BUFFER_PLAYING_CONCURRENCY = 1
        const val WAVE1_PLAYING_CONCURRENCY = 4
        const val WAVE2_PLAYING_CONCURRENCY = 3
        const val BUSY_BOOSTED_CONCURRENCY = 2
        const val USER_BOOST_WINDOW_SLOTS = 16
        const val PERSIST_BATCH_INTERVAL = 16
        const val MIN_COMPLETION_NUMERATOR = 3
        const val MIN_COMPLETION_DENOMINATOR = 4

        internal fun isSparseCompletedCache(
            bundle: StoryboardBundle,
            durationMillis: Long,
            expectedSlots: Int,
        ): Boolean {
            if (!bundle.completed) return false
            if (durationMillis < SPARSE_CACHE_HEAL_MIN_DURATION_MS) return false
            if (expectedSlots <= 0) return false
            val minRequired = (expectedSlots * SPARSE_CACHE_HEAL_MIN_NUMERATOR) / SPARSE_CACHE_HEAL_MIN_DENOMINATOR
            if (bundle.frames.size >= minRequired) return false
            val sortedPts = bundle.frames.map { it.ptsMs.coerceIn(0L, durationMillis) }.sorted()
            if (sortedPts.isEmpty()) return true
            val slotStepMs = (durationMillis / expectedSlots.toLong()).coerceAtLeast(1_000L)
            val maxAllowedGapMs = maxOf(slotStepMs * 2L, SPARSE_CACHE_MIN_GAP_MS)
            var maxGap = maxOf(sortedPts.first(), durationMillis - sortedPts.last())
            for (i in 1 until sortedPts.size) {
                val gap = sortedPts[i] - sortedPts[i - 1]
                if (gap > maxGap) maxGap = gap
            }
            return maxGap > maxAllowedGapMs
        }
    }
}
