package dev.piko.shared.media.player

import kotlin.math.ln
import kotlin.math.pow

/**
 * 参与自适应画质决策的一档候选流（原画或转码档）。
 *
 * [bytesPerSec] 为 1x 常速下的平均字节消费率；高倍速播放时有效需求自动按 `bytesPerSec * speed` 线性放大。
 * [mediaId] 为转码档 ID，原画为 `null`（查本地缓存覆盖时按 `mediaId ?: name` 匹配）。
 */
internal data class AbrRendition(
    val name: String,
    val bytesPerSec: Long,
    val height: Int,
    val mediaId: String? = null,
)

/**
 * 代理层上报的活跃网络传输累计值：仅包含真正从网络拉取字节的阻塞时段，排除缓冲满载后的空闲窗口。
 */
internal data class ActiveTransferSample(
    val activeBytes: Long = 0L,
    val activeNanos: Long = 0L,
)

/**
 * ABR 每周期（1s）输入的播放观测快照。
 *
 * [candidates] 按 `bytesPerSec` 从高到低排列（索引 0 最高画质，末位最低画质）。
 */
internal data class AbrObservation(
    val nowMillis: Long,
    val candidates: List<AbrRendition>,
    val currentName: String,
    val playbackSpeed: Float,
    val bufferSeconds: Double?,
    val isBuffering: Boolean,
    val isPlaying: Boolean,
    /** 各档位在本地持久化缓存中的覆盖率（`0f..1f`），键可为 `mediaId` 或档位 `name`（如 `ORIGINAL_QUALITY`）。 */
    val cachedCoverageByMediaId: Map<String, Float> = emptyMap(),
    /** 当前档位前方播放窗口是否已命中本地磁盘缓存。 */
    val currentWindowCached: Boolean = false,
    /** 前方播放窗口已命中本地磁盘缓存的候选档位键集合（`mediaId` 或 `name`）。 */
    val windowCachedRenditions: Set<String> = emptySet(),
)

internal data class AbrDecision(
    val targetName: String,
    val forSpeed: Boolean,
    val reason: String,
)

/**
 * Shaka Player 风格的指数加权移动平均器（带零点修正因子）：
 * 按每次活跃传输的时间权重更新，半衰期越短对突变越敏感。
 */
internal class ShakaEwma(halfLifeSeconds: Double) {
    private val alpha: Double = 0.5.pow(1.0 / halfLifeSeconds)
    private var estimate: Double = 0.0
    private var totalWeight: Double = 0.0

    val hasEstimate: Boolean get() = totalWeight > 0.0

    fun sample(weightSeconds: Double, value: Double) {
        if (weightSeconds <= 0.0 || !value.isFinite() || value <= 0.0) return
        val adjAlpha = alpha.pow(weightSeconds)
        estimate = value * (1.0 - adjAlpha) + adjAlpha * estimate
        totalWeight += weightSeconds
    }

    fun getEstimate(): Double {
        if (totalWeight <= 0.0) return 0.0
        val zeroFactor = 1.0 - alpha.pow(totalWeight)
        return if (zeroFactor <= 1e-9) estimate else estimate / zeroFactor
    }

    fun reset() {
        estimate = 0.0
        totalWeight = 0.0
    }
}

/**
 * Shaka Player Dual-EWMA 活跃突发带宽估算器（`fastHalfLife = 2.0s`、`slowHalfLife = 5.0s`、`min(fast, slow)`）：
 * - 仅接收 `>= 32 KiB` 的活跃网络突发（`activeBytes / activeNanos`），完全屏蔽低画质满缓冲时的空闲间隔；
 * - 弱网骤降时 `fast` 迅速拉低 `min(fast, slow)` 防止卡顿；网络恢复时 `slow` 经数次活跃突发平滑抬升并采用最小有效权重，
 *   避免 50 MB/s 链路下 256 KiB 短突发仅占 5ms 导致 `slow` 长期滞后而被困在低画质。
 */
internal class DualEwmaBandwidthEstimator(
    fastHalfLifeSeconds: Double = FAST_HALF_LIFE_SECONDS,
    slowHalfLifeSeconds: Double = SLOW_HALF_LIFE_SECONDS,
) {
    private val fast = ShakaEwma(fastHalfLifeSeconds)
    private val slow = ShakaEwma(slowHalfLifeSeconds)

    var lastSampleTimeMillis: Long = Long.MIN_VALUE
        private set

    val hasEstimate: Boolean get() = fast.hasEstimate && slow.hasEstimate

    val estimatedBytesPerSec: Long?
        get() = if (!hasEstimate) null else minOf(fast.getEstimate(), slow.getEstimate()).toLong().coerceAtLeast(1L)

    fun recordActiveBurst(deltaBytes: Long, deltaNanos: Long, nowMillis: Long): Boolean {
        if (deltaBytes < MIN_BURST_BYTES || deltaNanos < MIN_BURST_NANOS) return false
        val durationSec = (deltaNanos / 1_000_000_000.0).coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
        val throughputBps = deltaBytes / durationSec
        // 高速链路单次 256 KiB 突发耗时极短（数毫秒），按字节占比赋予下限权重以防 slow EWMA 长期滞后
        val sizeScale = (deltaBytes.toDouble() / WEIGHT_FLOOR_REF_BYTES).coerceIn(0.05, 1.0)
        val weightSec = maxOf(durationSec, MIN_BURST_WEIGHT_SECONDS * sizeScale).coerceAtMost(MAX_DURATION_SECONDS)
        fast.sample(weightSec, throughputBps)
        slow.sample(weightSec, throughputBps)
        lastSampleTimeMillis = nowMillis
        return true
    }

    fun reset() {
        fast.reset()
        slow.reset()
        lastSampleTimeMillis = Long.MIN_VALUE
    }

    companion object {
        const val FAST_HALF_LIFE_SECONDS = 2.0
        const val SLOW_HALF_LIFE_SECONDS = 5.0
        const val MIN_BURST_BYTES = 32L * 1024L
        const val MIN_BURST_NANOS = 2_000_000L
        private const val MIN_DURATION_SECONDS = 0.002
        private const val MAX_DURATION_SECONDS = 10.0
        private const val MIN_BURST_WEIGHT_SECONDS = 0.5
        private const val WEIGHT_FLOOR_REF_BYTES = 256.0 * 1024.0
    }
}

/**
 * Shaka Dual-EWMA（活跃传输测速）+ DASH.js BOLA（缓冲占用李雅普诺夫优化）+ 本地缓存亲和性（Cache-Aware）混合自适应码率控制器：
 * - **永不陷入低码率陷阱**：测速仅统计活跃网络突发，480P 满缓冲限速播放期间突发仍能暴露 50 MB/s 恢复链路；
 *   若传输遥测稀疏但缓冲持续充盈（`>= 14s` 且无卡顿），BOLA 效用函数同样自然授权升档；
 * - **永不乒乓震荡**：稳态弱网下活跃突发实时刷新真实上限，吞吐规则否决超预算升档；升档需 20s 稳定窗口与 12s+ 缓冲；
 * - **缓存亲和性（零流量重播 & 防缓存碎片化）**：
 *   - 初始选档与稳态切换时，若某档位已有可观本地覆盖（`>= 25%`）或前方窗口已在盘上，优先复用已缓存档位，
 *     避免反复切走把本地缓存打碎或重播时白白消耗流量；
 *   - 仅当真实卡顿/缺口风险（未缓存空洞遇弱网）或用户手动选档时打破缓存亲和性。
 * - **高倍速弹性**：持续 `>= 2.0x`（>= 1.2s）按倍速放大后的有效码率即时降档，回落 `<= 1.5x` 满 4s 快速恢复高画质。
 */
internal class AdaptiveBitrateController(
    val estimator: DualEwmaBandwidthEstimator = DualEwmaBandwidthEstimator(),
) {
    private var lastSwitchMillis: Long = Long.MIN_VALUE
    private var sessionStartMillis: Long = Long.MIN_VALUE
    private var steppedDownForSpeed: Boolean = false
    private var consecutiveBufferingSecs: Int = 0
    private var consecutiveStableSecs: Int = 0
    private var highSpeedSinceMillis: Long? = null
    private var normalSpeedSinceMillis: Long? = null

    val estimatedBytesPerSec: Long? get() = estimator.estimatedBytesPerSec

    fun resetSession(nowMillis: Long = Long.MIN_VALUE, clearBandwidth: Boolean = false) {
        if (clearBandwidth) estimator.reset()
        lastSwitchMillis = nowMillis
        sessionStartMillis = nowMillis
        steppedDownForSpeed = false
        consecutiveBufferingSecs = 0
        consecutiveStableSecs = 0
        highSpeedSinceMillis = null
        normalSpeedSinceMillis = null
    }

    /** 首帧就绪时刷新开播暖机起点与降档冷却，同时保留高倍速降档记忆以支持回常速恢复。 */
    fun notePlaybackReady(nowMillis: Long) {
        lastSwitchMillis = nowMillis
        sessionStartMillis = nowMillis
        consecutiveBufferingSecs = 0
        consecutiveStableSecs = 0
    }

    fun noteManualSwitch(nowMillis: Long) {
        lastSwitchMillis = nowMillis
        steppedDownForSpeed = false
        consecutiveBufferingSecs = 0
        consecutiveStableSecs = 0
    }

    fun noteAutoSwitch(nowMillis: Long, forSpeed: Boolean) {
        lastSwitchMillis = nowMillis
        steppedDownForSpeed = forSpeed
        consecutiveBufferingSecs = 0
        consecutiveStableSecs = 0
    }

    fun recordActiveBurst(deltaBytes: Long, deltaNanos: Long, nowMillis: Long): Boolean =
        estimator.recordActiveBurst(deltaBytes, deltaNanos, nowMillis)

    /**
     * 首次切入「自动」时按本地缓存覆盖率、实测带宽与倍速选出起始画质（`candidates` 按高到低排列）。
     * 重播或已有较完整本地缓存时优先命中已缓存档位，实现零额外流量秒开。
     */
    fun pickInitial(
        candidates: List<AbrRendition>,
        playbackSpeed: Float,
        cachedCoverageByMediaId: Map<String, Float> = emptyMap(),
        windowCachedRenditions: Set<String> = emptySet(),
    ): String {
        if (candidates.isEmpty()) return ""
        val speed = playbackSpeed.coerceAtLeast(0.5f)
        val estimated = estimator.estimatedBytesPerSec
        val budget = when {
            estimated != null -> (estimated * DOWN_SAFETY_FACTOR).toLong()
            speed >= HIGH_SPEED_THRESHOLD -> DEFAULT_HIGH_SPEED_BUDGET_BPS
            else -> Long.MAX_VALUE
        }
        val cacheBudget = if (budget == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            (budget * CACHE_BUDGET_BONUS_FACTOR).toLong()
        }

        // 优先挑出具备本地缓存亲和性且安全可持续的档位（重播优先高覆盖/窗口命中，其次高画质）
        if (cachedCoverageByMediaId.isNotEmpty() || windowCachedRenditions.isNotEmpty()) {
            var bestCached: AbrRendition? = null
            var bestTier = -1
            for (candidate in candidates) {
                val coverage = coverageOf(candidate, cachedCoverageByMediaId)
                val windowHit = isWindowCached(candidate, windowCachedRenditions)
                if (!windowHit && coverage < CACHE_AFFINITY_THRESHOLD) continue
                val effectivelyLocal = windowHit || coverage >= FULL_CACHE_THRESHOLD
                val safe = effectivelyLocal || effectiveBitrate(candidate, speed) <= cacheBudget
                if (!safe) continue
                val tier = when {
                    windowHit && coverage >= FULL_CACHE_THRESHOLD -> 3
                    effectivelyLocal -> 2
                    coverage >= STRONG_CACHE_THRESHOLD -> 1
                    else -> 0
                }
                if (tier > bestTier) {
                    bestTier = tier
                    bestCached = candidate
                }
            }
            if (bestCached != null) {
                return bestCached.name
            }
        }

        return candidates.firstOrNull { effectiveBitrate(it, speed) <= budget }?.name
            ?: candidates.last().name
    }

    /**
     * 执行一次周期性（约 1s）ABR 评估；返回 `null` 表示保持当前画质。
     */
    fun evaluate(obs: AbrObservation): AbrDecision? {
        val candidates = obs.candidates
        if (candidates.size < 2) return null
        val curIndex = candidates.indexOfFirst { it.name == obs.currentName }
        if (curIndex < 0) return null

        val speed = obs.playbackSpeed.coerceAtLeast(0.5f)
        val current = candidates[curIndex]
        val currentDemand = effectiveBitrate(current, speed)
        val estimatedBps = estimator.estimatedBytesPerSec
        val bufferSec = obs.bufferSeconds
        val now = obs.nowMillis

        val currentCoverage = coverageOf(current, obs.cachedCoverageByMediaId)
        val currentHasWindow = obs.currentWindowCached || isWindowCached(current, obs.windowCachedRenditions)
        val currentHasCacheAffinity = currentHasWindow || currentCoverage >= CACHE_AFFINITY_THRESHOLD
        val currentEffectivelyLocal = currentHasWindow || currentCoverage >= FULL_CACHE_THRESHOLD

        if (obs.isBuffering && obs.isPlaying) {
            consecutiveBufferingSecs++
            consecutiveStableSecs = 0
        } else {
            consecutiveBufferingSecs = 0
            if (obs.isPlaying) {
                consecutiveStableSecs++
            }
        }

        if (speed >= HIGH_SPEED_THRESHOLD) {
            if (highSpeedSinceMillis == null) highSpeedSinceMillis = now
            normalSpeedSinceMillis = null
        } else {
            highSpeedSinceMillis = null
            if (speed <= NORMAL_SPEED_THRESHOLD) {
                if (normalSpeedSinceMillis == null) normalSpeedSinceMillis = now
            } else {
                normalSpeedSinceMillis = null
            }
        }

        val sinceLastSwitchMs = if (lastSwitchMillis == Long.MIN_VALUE) {
            Long.MAX_VALUE
        } else {
            (now - lastSwitchMillis).coerceAtLeast(0L)
        }
        val inStartupGrace = sessionStartMillis != Long.MIN_VALUE &&
            (now - sessionStartMillis).coerceAtLeast(0L) < STARTUP_GRACE_MS

        // 1. 持续高倍速降档（>= 2.0x 持续 >= 1.2s）：
        // 若当前档位前方窗口或主体已缓存在本地磁盘且未发生真实卡顿，SSD 读盘轻松撑住 3.5x，无需降档。
        val sustainedHighSpeedMs = highSpeedSinceMillis?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        if (speed >= HIGH_SPEED_THRESHOLD &&
            sustainedHighSpeedMs >= HIGH_SPEED_HOLD_MS &&
            curIndex < candidates.lastIndex &&
            sinceLastSwitchMs >= HIGH_SPEED_HOLD_MS &&
            (!currentEffectivelyLocal || obs.isBuffering)
        ) {
            val budget = estimatedBps?.let { (it * DOWN_SAFETY_FACTOR).toLong() } ?: DEFAULT_HIGH_SPEED_BUDGET_BPS
            val bufferTight = obs.isBuffering || (bufferSec != null && bufferSec < 3.5)
            if (currentDemand > budget || bufferTight) {
                val lower = candidates.drop(curIndex + 1)
                val target = pickPreferredLowerCandidate(
                    lower = lower,
                    speed = speed,
                    budget = budget,
                    coverageMap = obs.cachedCoverageByMediaId,
                    windowCached = obs.windowCachedRenditions,
                )
                noteAutoSwitch(now, forSpeed = true)
                return AbrDecision(target.name, forSpeed = true, reason = "high-speed-step-down")
            }
        }

        // 计算 DASH.js BOLA 效用最优索引（在 candidates 高->低 坐标系中的索引）
        val bolaIndex = bufferSec?.let { computeBolaCandidateIndex(candidates, speed, it) }

        // 2. 弱网 / 卡顿 / BOLA 低缓冲降档（reload 感知冷却 >= 4s；开播暖机期内不因初始缓冲爬坡或无带宽样本误降）
        // 真实卡顿（stalling，例如命中未缓存空洞）始终打破缓存亲和性；但前方窗口已命中本地缓存且未卡顿时不因历史测速误降。
        if (curIndex < candidates.lastIndex && sinceLastSwitchMs >= MIN_DECREASE_INTERVAL_MS) {
            val stalling = consecutiveBufferingSecs >= STALL_STEP_DOWN_SECS
            val lowBuffer = bufferSec != null && bufferSec < BOLA_MIN_BUFFER_SECONDS
            val throughputBelowCurrent = estimatedBps != null && currentDemand > (estimatedBps * DOWN_SAFETY_FACTOR).toLong()
            val throughputStarved = estimatedBps != null &&
                currentDemand > (estimatedBps * STARVED_SAFETY_FACTOR).toLong() &&
                (bufferSec == null || bufferSec < LOW_BUFFER_GUARD_SECONDS)
            val bolaDemandsDrop = !inStartupGrace &&
                lowBuffer &&
                bolaIndex != null &&
                bolaIndex > curIndex &&
                (throughputBelowCurrent || (estimatedBps == null && obs.isBuffering))
            val effectiveThroughputStarved = throughputStarved && (!inStartupGrace || obs.isBuffering)

            val shouldDrop = stalling || (!currentHasWindow && (bolaDemandsDrop || effectiveThroughputStarved))
            if (shouldDrop) {
                val safeBudget = estimatedBps?.let { (it * DOWN_SAFETY_FACTOR).toLong() }
                val throughputSafeIndex = safeBudget?.let { budget ->
                    candidates.indexOfFirst { effectiveBitrate(it, speed) <= budget }
                        .takeIf { it >= 0 } ?: candidates.lastIndex
                }
                val cachedLowerIndex = findBestCachedLowerIndex(
                    candidates = candidates,
                    curIndex = curIndex,
                    speed = speed,
                    safeBudget = safeBudget,
                    coverageMap = obs.cachedCoverageByMediaId,
                    windowCached = obs.windowCachedRenditions,
                )
                val targetIndex = when {
                    cachedLowerIndex != null -> cachedLowerIndex
                    throughputSafeIndex != null && throughputSafeIndex > curIndex -> throughputSafeIndex
                    bolaIndex != null && bolaIndex > curIndex && lowBuffer -> minOf(bolaIndex, curIndex + 1)
                    else -> curIndex + 1
                }.coerceIn(curIndex + 1, candidates.lastIndex)

                noteAutoSwitch(now, forSpeed = false)
                return AbrDecision(candidates[targetIndex].name, forSpeed = false, reason = "weak-network-or-bola-drop")
            }
        }

        // 3. 升档（高倍速回常速快速恢复 / 稳定高带宽或 BOLA 充盈缓冲升档）
        if (curIndex > 0 && !obs.isBuffering && obs.isPlaying && speed <= NORMAL_SPEED_THRESHOLD) {
            // 3a. 高倍速回落到常速后快速恢复（>= 4s）
            val normalHeldMs = normalSpeedSinceMillis?.let { (now - it).coerceAtLeast(0L) } ?: 0L
            if (steppedDownForSpeed &&
                normalHeldMs >= SPEED_RESTORE_INTERVAL_MS &&
                sinceLastSwitchMs >= SPEED_RESTORE_INTERVAL_MS
            ) {
                val targetIndex = if (estimatedBps != null) {
                    val upBudget = (estimatedBps * UP_SAFETY_FACTOR).toLong()
                    candidates.indexOfFirst { idxCandidate ->
                        val safe = effectiveBitrate(idxCandidate, speed) <= upBudget ||
                            hasCacheAffinity(idxCandidate, obs.cachedCoverageByMediaId, obs.windowCachedRenditions)
                        safe && (!currentHasCacheAffinity || hasCacheAffinity(idxCandidate, obs.cachedCoverageByMediaId, obs.windowCachedRenditions))
                    }.takeIf { it in 0 until curIndex }
                } else if (bufferSec == null || bufferSec >= SPEED_RESTORE_MIN_BUFFER_SECONDS) {
                    if (!currentHasCacheAffinity) {
                        0
                    } else {
                        candidates.take(curIndex).indexOfFirst {
                            hasCacheAffinity(it, obs.cachedCoverageByMediaId, obs.windowCachedRenditions)
                        }.takeIf { it >= 0 }
                    }
                } else {
                    null
                }
                if (targetIndex != null && targetIndex < curIndex) {
                    noteAutoSwitch(now, forSpeed = false)
                    return AbrDecision(candidates[targetIndex].name, forSpeed = false, reason = "speed-restore")
                }
            }

            // 3b. 常规网络恢复升档：满足 reload 滞回（>= 20s 稳定播放 + 健康缓冲 >= 12s）
            val bufferHealthy = bufferSec == null || bufferSec >= MIN_INCREASE_BUFFER_SECONDS
            if (sinceLastSwitchMs >= MIN_INCREASE_INTERVAL_MS &&
                consecutiveStableSecs >= MIN_STABLE_SECS_FOR_INCREASE &&
                bufferHealthy
            ) {
                val throughputFresh = estimator.lastSampleTimeMillis != Long.MIN_VALUE &&
                    (now - estimator.lastSampleTimeMillis) <= STALE_THROUGHPUT_MS

                // 若当前档位已具备可观本地缓存亲和性，禁止盲目升档至未缓存高档位（避免浪费流量与碎片化缓存），
                // 仅当更高的目标档位本身也已有本地缓存且网络/缓冲安全时才升档。
                if (currentHasCacheAffinity) {
                    val upBudget = estimatedBps?.let { (it * UP_SAFETY_FACTOR).toLong() }
                    val cachedHigherIdx = candidates.take(curIndex).indexOfFirst { higher ->
                        val cov = coverageOf(higher, obs.cachedCoverageByMediaId)
                        val win = isWindowCached(higher, obs.windowCachedRenditions)
                        val cached = win || cov >= CACHE_AFFINITY_THRESHOLD
                        val safe = win || cov >= FULL_CACHE_THRESHOLD || upBudget == null || effectiveBitrate(higher, speed) <= upBudget
                        cached && safe
                    }
                    if (cachedHigherIdx in 0 until curIndex) {
                        noteAutoSwitch(now, forSpeed = false)
                        return AbrDecision(candidates[cachedHigherIdx].name, forSpeed = false, reason = "cache-affinity-upgrade")
                    }
                    return null
                }

                // 规则 A：活跃突发 Dual-EWMA 明确证明更高画质可持续（若某高档位已有本地缓存则优先命中）
                if (estimatedBps != null) {
                    val upBudget = (estimatedBps * UP_SAFETY_FACTOR).toLong()
                    val cacheUpBudget = (estimatedBps * UP_CACHE_BONUS_FACTOR).toLong()
                    val cachedTargetIdx = candidates.take(curIndex).indexOfFirst { higher ->
                        val cov = coverageOf(higher, obs.cachedCoverageByMediaId)
                        val win = isWindowCached(higher, obs.windowCachedRenditions)
                        (win || cov >= CACHE_AFFINITY_THRESHOLD) &&
                            (win || cov >= FULL_CACHE_THRESHOLD || effectiveBitrate(higher, speed) <= cacheUpBudget)
                    }
                    if (cachedTargetIdx in 0 until curIndex) {
                        noteAutoSwitch(now, forSpeed = false)
                        return AbrDecision(candidates[cachedTargetIdx].name, forSpeed = false, reason = "cache-affinity-upgrade")
                    }

                    val bestThroughputIdx = candidates.indexOfFirst { effectiveBitrate(it, speed) <= upBudget }
                    if (bestThroughputIdx in 0 until curIndex) {
                        val chosenIdx = if (bufferSec == null || bufferSec >= BOLA_HIGH_WATERMARK_SECONDS) {
                            bestThroughputIdx
                        } else {
                            maxOf(bestThroughputIdx, curIndex - 1)
                        }
                        noteAutoSwitch(now, forSpeed = false)
                        return AbrDecision(candidates[chosenIdx].name, forSpeed = false, reason = "dual-ewma-recovery")
                    }
                }

                // 规则 B：BOLA 高缓冲兜底（当活跃突发遥测缺失或已陈旧 >= 15s，且缓冲充盈 >= 14s 零卡顿，防止被困低码率）
                if ((!throughputFresh || estimatedBps == null) &&
                    bufferSec != null &&
                    bufferSec >= BOLA_HIGH_WATERMARK_SECONDS &&
                    bolaIndex != null &&
                    bolaIndex < curIndex
                ) {
                    val stepIdx = (curIndex - 1).coerceAtLeast(bolaIndex)
                    noteAutoSwitch(now, forSpeed = false)
                    return AbrDecision(candidates[stepIdx].name, forSpeed = false, reason = "bola-high-buffer-recovery")
                }
            }
        }

        return null
    }

    private fun pickPreferredLowerCandidate(
        lower: List<AbrRendition>,
        speed: Float,
        budget: Long,
        coverageMap: Map<String, Float>,
        windowCached: Set<String>,
    ): AbrRendition {
        // 先找既满足预算又有本地缓存的低档位，或者几乎完全缓存在本地的低档位
        lower.firstOrNull { cand ->
            val cov = coverageOf(cand, coverageMap)
            val win = isWindowCached(cand, windowCached)
            (win || cov >= FULL_CACHE_THRESHOLD) ||
                (cov >= CACHE_AFFINITY_THRESHOLD && effectiveBitrate(cand, speed) <= budget)
        }?.let { return it }
        return lower.firstOrNull { effectiveBitrate(it, speed) <= budget } ?: lower.last()
    }

    private fun findBestCachedLowerIndex(
        candidates: List<AbrRendition>,
        curIndex: Int,
        speed: Float,
        safeBudget: Long?,
        coverageMap: Map<String, Float>,
        windowCached: Set<String>,
    ): Int? {
        if (coverageMap.isEmpty() && windowCached.isEmpty()) return null
        for (i in (curIndex + 1) until candidates.size) {
            val cand = candidates[i]
            val cov = coverageOf(cand, coverageMap)
            val win = isWindowCached(cand, windowCached)
            if (win || cov >= FULL_CACHE_THRESHOLD) return i
            if (cov >= CACHE_AFFINITY_THRESHOLD && (safeBudget == null || effectiveBitrate(cand, speed) <= safeBudget)) {
                return i
            }
        }
        return null
    }

    private fun coverageOf(rendition: AbrRendition, map: Map<String, Float>): Float {
        if (map.isEmpty()) return 0f
        val byMedia = rendition.mediaId?.let { map[it] }
        val raw = byMedia ?: map[rendition.name] ?: 0f
        return raw.coerceIn(0f, 1f)
    }

    private fun isWindowCached(rendition: AbrRendition, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        return (rendition.mediaId != null && rendition.mediaId in set) || rendition.name in set
    }

    private fun hasCacheAffinity(
        rendition: AbrRendition,
        coverageMap: Map<String, Float>,
        windowCached: Set<String>,
    ): Boolean = isWindowCached(rendition, windowCached) || coverageOf(rendition, coverageMap) >= CACHE_AFFINITY_THRESHOLD

    /**
     * DASH.js / SIGCOMM'16 BOLA 李雅普诺夫效用最大化：
     * - 设 $S_0 \le \dots \le S_{M-1}$ 为按当前倍速缩放后的有效码率，$v_m = \ln(S_m / S_0)$；
     * - 设 $Q_{\text{eff}} = \max(0, Q - Q_{\min})$，$V = (Q_{\max} - Q_{\min}) / (v_{M-1} + \gamma p)$；
     * - 最大化 $\text{score}(m) = (V(v_m + \gamma p) - Q_{\text{eff}}) / S_m$，在 $Q \le Q_{\min}$ 必选最低档、在 $Q \ge Q_{\max}$ 必选最高档。
     */
    internal fun computeBolaCandidateIndex(
        candidatesHighToLow: List<AbrRendition>,
        playbackSpeed: Float,
        bufferSeconds: Double,
    ): Int {
        val mCount = candidatesHighToLow.size
        if (mCount <= 1) return 0
        val speed = playbackSpeed.coerceAtLeast(0.5f)
        // BOLA 索引从低到高（0 = 最低码率，mCount - 1 = 最高码率）
        val ascendingRates = DoubleArray(mCount) { idx ->
            val r = candidatesHighToLow[mCount - 1 - idx]
            effectiveBitrate(r, speed).toDouble().coerceAtLeast(1.0)
        }
        val sMin = ascendingRates[0]
        val utilities = DoubleArray(mCount) { idx ->
            ln((ascendingRates[idx] / sMin).coerceAtLeast(1.0))
        }
        val vMax = utilities[mCount - 1]
        val qEff = (bufferSeconds - BOLA_MIN_BUFFER_SECONDS).coerceAtLeast(0.0)
        val vParam = (BOLA_TARGET_BUFFER_SECONDS - BOLA_MIN_BUFFER_SECONDS) / (vMax + BOLA_GAMMA_P)

        var bestAscIdx = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until mCount) {
            val score = (vParam * (utilities[i] + BOLA_GAMMA_P) - qEff) / ascendingRates[i]
            if (score > bestScore) {
                bestScore = score
                bestAscIdx = i
            }
        }
        // 转换回 candidatesHighToLow（0 = 最高画质）的索引
        return mCount - 1 - bestAscIdx
    }

    private fun effectiveBitrate(rendition: AbrRendition, speed: Float): Long =
        (rendition.bytesPerSec.coerceAtLeast(1L) * speed.toDouble()).toLong().coerceAtLeast(1L)

    companion object {
        const val HIGH_SPEED_THRESHOLD = 2.0f
        const val NORMAL_SPEED_THRESHOLD = 1.5f
        const val HIGH_SPEED_HOLD_MS = 1_200L
        const val SPEED_RESTORE_INTERVAL_MS = 4_000L
        const val MIN_DECREASE_INTERVAL_MS = 4_000L
        /** 开播/切档首帧后的暖机保护窗，防止初始缓冲爬坡阶段无样本误降到无索引转码流。 */
        const val STARTUP_GRACE_MS = 8_000L
        const val MIN_INCREASE_INTERVAL_MS = 20_000L
        const val MIN_STABLE_SECS_FOR_INCREASE = 20
        const val STALL_STEP_DOWN_SECS = 3
        const val STALE_THROUGHPUT_MS = 15_000L

        const val DOWN_SAFETY_FACTOR = 0.80
        const val STARVED_SAFETY_FACTOR = 0.85
        const val UP_SAFETY_FACTOR = 0.80
        const val UP_CACHE_BONUS_FACTOR = 0.95
        const val CACHE_BUDGET_BONUS_FACTOR = 1.15

        /** 触发缓存亲和性加成的最低本地覆盖比例（25%）。 */
        const val CACHE_AFFINITY_THRESHOLD = 0.25f
        const val STRONG_CACHE_THRESHOLD = 0.50f
        /** 视为几乎完整本地化、无需网络测速护航的覆盖比例（85%）。 */
        const val FULL_CACHE_THRESHOLD = 0.85f

        const val BOLA_MIN_BUFFER_SECONDS = 3.0
        const val BOLA_TARGET_BUFFER_SECONDS = 18.0
        const val BOLA_HIGH_WATERMARK_SECONDS = 14.0
        const val BOLA_GAMMA_P = 1.5
        const val MIN_INCREASE_BUFFER_SECONDS = 12.0
        const val SPEED_RESTORE_MIN_BUFFER_SECONDS = 8.0
        const val LOW_BUFFER_GUARD_SECONDS = 5.0

        const val DEFAULT_HIGH_SPEED_BUDGET_BPS = 750L * 1024L
    }
}
