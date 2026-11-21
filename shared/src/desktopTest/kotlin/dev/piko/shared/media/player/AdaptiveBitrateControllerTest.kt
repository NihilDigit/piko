package dev.piko.shared.media.player

import dev.piko.shared.media.ORIGINAL_QUALITY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptiveBitrateControllerTest {
    private val candidates = listOf(
        AbrRendition(name = ORIGINAL_QUALITY, bytesPerSec = 450L * 1024L, height = 1080),
        AbrRendition(name = "1080P", bytesPerSec = 140L * 1024L, height = 1080),
        AbrRendition(name = "720P", bytesPerSec = 90L * 1024L, height = 720),
        AbrRendition(name = "480P", bytesPerSec = 60L * 1024L, height = 480),
    )

    @Test
    fun paced480pPlaybackRecoversToOriginalWhenActiveBurstThroughputRecovers() {
        val abr = AdaptiveBitrateController()
        var now = 0L

        // 1. 弱网期间（65 KB/s 活跃拉取）先自然降到 480P
        var current = ORIGINAL_QUALITY
        repeat(6) {
            now += 1_000L
            abr.recordActiveBurst(deltaBytes = 65L * 1024L, deltaNanos = 1_000_000_000L, nowMillis = now)
            val decision = abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = candidates,
                    currentName = current,
                    playbackSpeed = 1f,
                    bufferSeconds = 2.0,
                    isBuffering = true,
                    isPlaying = true,
                ),
            )
            if (decision != null) current = decision.targetName
        }
        assertEquals("480P", current)

        // 2. Wi-Fi 恢复到 50 MB/s，但 480P 满缓冲限速下每 4 秒才发起一次 256 KiB 的 5ms 活跃突发
        var recoveredTarget: String? = null
        repeat(25) { sec ->
            now += 1_000L
            if (sec % 4 == 0) {
                // 256 KiB in 5ms => ~51.2 MB/s 真实活跃链路吞吐
                abr.recordActiveBurst(deltaBytes = 256L * 1024L, deltaNanos = 5_000_000L, nowMillis = now)
            }
            val decision = abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = candidates,
                    currentName = current,
                    playbackSpeed = 1f,
                    bufferSeconds = 15.0,
                    isBuffering = false,
                    isPlaying = true,
                ),
            )
            if (decision != null) {
                recoveredTarget = decision.targetName
                current = decision.targetName
            }
        }

        assertEquals(ORIGINAL_QUALITY, recoveredTarget)
        assertTrue((abr.estimatedBytesPerSec ?: 0L) > 1_000_000L)
    }

    @Test
    fun bolaHighBufferRescuesFrom480pWhenBurstTelemetryIsSparse() {
        val abr = AdaptiveBitrateController()
        var now = 0L
        abr.noteAutoSwitch(nowMillis = now, forSpeed = false)
        var current = "480P"

        // 无新活跃突发，但缓冲持续健康充盈至 16s 且 20s 零卡顿：BOLA 效用自然授权升档
        var firstStepUp: AbrDecision? = null
        repeat(22) {
            now += 1_000L
            val decision = abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = candidates,
                    currentName = current,
                    playbackSpeed = 1f,
                    bufferSeconds = 16.0,
                    isBuffering = false,
                    isPlaying = true,
                ),
            )
            if (decision != null && firstStepUp == null) {
                firstStepUp = decision
                current = decision.targetName
            }
        }

        assertNotNull(firstStepUp)
        assertEquals("720P", firstStepUp!!.targetName)
        assertEquals("bola-high-buffer-recovery", firstStepUp!!.reason)
    }

    @Test
    fun highSpeedBoostStepsDownCleanlyAndRestoresAt1x() {
        val abr = AdaptiveBitrateController()
        var now = 0L

        // 稳态 600 KB/s 带宽：1x 原画（450 KB/s）毫无压力
        repeat(4) {
            now += 1_000L
            abr.recordActiveBurst(deltaBytes = 600L * 1024L, deltaNanos = 1_000_000_000L, nowMillis = now)
        }

        // 切到 3.5x：原画有效需求 450 * 3.5 = 1575 KB/s >> 600 * 0.80 = 480 KB/s，持续 >= 1.2s 后降档
        var current = ORIGINAL_QUALITY
        var speedDrop: AbrDecision? = null
        repeat(4) {
            now += 1_000L
            val decision = abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = candidates,
                    currentName = current,
                    playbackSpeed = 3.5f,
                    bufferSeconds = 8.0,
                    isBuffering = false,
                    isPlaying = true,
                ),
            )
            if (decision != null && speedDrop == null) {
                speedDrop = decision
                current = decision.targetName
            }
        }
        assertNotNull(speedDrop)
        assertTrue(speedDrop!!.forSpeed)
        // 1080P @ 3.5x = 490 KB/s > 480 KB/s 预算，720P @ 3.5x = 315 KB/s <= 480 KB/s
        assertEquals("720P", speedDrop!!.targetName)

        // 回到 1.0x 常速并保持 4s 以上，迅速恢复至原画（原画 450 KB/s <= 600 * 0.80 = 480 KB/s）
        var restored: AbrDecision? = null
        repeat(6) {
            now += 1_000L
            val decision = abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = candidates,
                    currentName = current,
                    playbackSpeed = 1.0f,
                    bufferSeconds = 12.0,
                    isBuffering = false,
                    isPlaying = true,
                ),
            )
            if (decision != null && restored == null) {
                restored = decision
                current = decision.targetName
            }
        }
        assertNotNull(restored)
        assertEquals(ORIGINAL_QUALITY, restored!!.targetName)
        assertEquals("speed-restore", restored!!.reason)
    }

    @Test
    fun steadyWeakNetworkNeverPingPongsUpToUnsustainableRendition() {
        val abr = AdaptiveBitrateController()
        var now = 0L
        abr.noteAutoSwitch(nowMillis = now, forSpeed = false)
        val current = "480P"

        // 稳态弱网 80 KB/s（高于 480P 的 60 KB/s，因此缓冲会缓慢涨到 18s，但低于 720P 所需的 90/0.80 = 112.5 KB/s）
        val switches = mutableListOf<AbrDecision>()
        repeat(120) { sec ->
            now += 1_000L
            if (sec % 2 == 0) {
                // 每 2 秒拉一次 120 KiB 耗时 1.5s => 稳态 80 KB/s
                abr.recordActiveBurst(deltaBytes = 120L * 1024L, deltaNanos = 1_500_000_000L, nowMillis = now)
            }
            val decision = abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = candidates,
                    currentName = current,
                    playbackSpeed = 1f,
                    bufferSeconds = 18.0,
                    isBuffering = false,
                    isPlaying = true,
                ),
            )
            if (decision != null) switches += decision
        }

        assertTrue(switches.isEmpty(), "Expected zero ping-pong switches on steady 80 KB/s link, got: $switches")
    }

    @Test
    fun bolaUtilityBoundariesAndMonotonicity() {
        val abr = AdaptiveBitrateController()
        // 低缓冲（<= 3s）必选最低档（索引 3 = 480P）
        assertEquals(3, abr.computeBolaCandidateIndex(candidates, playbackSpeed = 1f, bufferSeconds = 2.0))
        assertEquals(3, abr.computeBolaCandidateIndex(candidates, playbackSpeed = 1f, bufferSeconds = 3.0))
        // 高缓冲（>= 18s）必选最高档（索引 0 = Original）
        assertEquals(0, abr.computeBolaCandidateIndex(candidates, playbackSpeed = 1f, bufferSeconds = 18.0))
        assertEquals(0, abr.computeBolaCandidateIndex(candidates, playbackSpeed = 1f, bufferSeconds = 24.0))

        // 随缓冲升高，所选画质索引（0 最高 -> 3 最低）单调不增
        var prevIdx = 3
        for (q in 3..20) {
            val idx = abr.computeBolaCandidateIndex(candidates, playbackSpeed = 1f, bufferSeconds = q.toDouble())
            assertTrue(idx <= prevIdx, "BOLA index should improve monotonically at q=${q}s: prev=$prevIdx, cur=$idx")
            prevIdx = idx
        }
    }

    @Test
    fun subThresholdTinyReadsDoNotPolluteBandwidthEstimate() {
        val estimator = DualEwmaBandwidthEstimator()
        assertEquals(false, estimator.recordActiveBurst(deltaBytes = 16L * 1024L, deltaNanos = 10_000_000L, nowMillis = 1_000L))
        assertNull(estimator.estimatedBytesPerSec)
    }

    @Test
    fun pickInitialFavorsHeavilyCachedRenditionForZeroTrafficReplay() {
        val abr = AdaptiveBitrateController()
        val withMediaIds = listOf(
            AbrRendition(name = ORIGINAL_QUALITY, bytesPerSec = 450L * 1024L, height = 1080, mediaId = null),
            AbrRendition(name = "1080P", bytesPerSec = 140L * 1024L, height = 1080, mediaId = "m1080"),
            AbrRendition(name = "720P", bytesPerSec = 90L * 1024L, height = 720, mediaId = "m720"),
            AbrRendition(name = "480P", bytesPerSec = 60L * 1024L, height = 480, mediaId = "m480"),
        )
        // 无历史测速（带宽充裕默认会挑原画），但 720P 已在本地缓存 90%：重播优先零流量复用 720P
        val picked = abr.pickInitial(
            candidates = withMediaIds,
            playbackSpeed = 1f,
            cachedCoverageByMediaId = mapOf("m720" to 0.90f),
            windowCachedRenditions = setOf("m720"),
        )
        assertEquals("720P", picked)

        // 当原画与 720P 均完整缓存时，挑更高画质的原画
        val pickedHigher = abr.pickInitial(
            candidates = withMediaIds,
            playbackSpeed = 1f,
            cachedCoverageByMediaId = mapOf(ORIGINAL_QUALITY to 0.95f, "m720" to 0.90f),
            windowCachedRenditions = setOf(ORIGINAL_QUALITY, "m720"),
        )
        assertEquals(ORIGINAL_QUALITY, pickedHigher)
    }

    @Test
    fun steadyStateCacheAffinitySuppressesNeedlessUpgradeToUncachedRendition() {
        val abr = AdaptiveBitrateController()
        var now = 0L
        abr.noteAutoSwitch(nowMillis = now, forSpeed = false)
        val withMediaIds = listOf(
            AbrRendition(name = ORIGINAL_QUALITY, bytesPerSec = 450L * 1024L, height = 1080, mediaId = null),
            AbrRendition(name = "1080P", bytesPerSec = 140L * 1024L, height = 1080, mediaId = "m1080"),
            AbrRendition(name = "720P", bytesPerSec = 90L * 1024L, height = 720, mediaId = "m720"),
        )
        // 即使网络极快（50 MB/s）且缓冲充盈，只要当前 720P 具备本地缓存覆盖（>= 25%），不得切走去下未缓存原画
        val switches = mutableListOf<AbrDecision>()
        repeat(30) { sec ->
            now += 1_000L
            if (sec % 3 == 0) {
                abr.recordActiveBurst(deltaBytes = 256L * 1024L, deltaNanos = 5_000_000L, nowMillis = now)
            }
            val decision = abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = withMediaIds,
                    currentName = "720P",
                    playbackSpeed = 1f,
                    bufferSeconds = 18.0,
                    isBuffering = false,
                    isPlaying = true,
                    cachedCoverageByMediaId = mapOf("m720" to 0.45f),
                    currentWindowCached = true,
                    windowCachedRenditions = setOf("m720"),
                ),
            )
            if (decision != null) switches += decision
        }
        assertTrue(switches.isEmpty(), "Expected cached 720P to resist switching to uncached higher tier, got: $switches")

        // 但若 1080P 本身也已缓存（>= 25%）且网络充裕，则允许升档至同样已缓存的 1080P
        now += 1_000L
        val upgrade = abr.evaluate(
            AbrObservation(
                nowMillis = now,
                candidates = withMediaIds,
                currentName = "720P",
                playbackSpeed = 1f,
                bufferSeconds = 18.0,
                isBuffering = false,
                isPlaying = true,
                cachedCoverageByMediaId = mapOf("m720" to 0.45f, "m1080" to 0.60f),
                currentWindowCached = true,
                windowCachedRenditions = setOf("m720", "m1080"),
            ),
        )
        assertNotNull(upgrade)
        assertEquals("1080P", upgrade!!.targetName)
        assertEquals("cache-affinity-upgrade", upgrade.reason)
    }

    @Test
    fun highSpeedBoostStaysOnLocallyCachedRenditionAndStallOverridesCacheAffinity() {
        val abr = AdaptiveBitrateController()
        var now = 0L
        // 慢网（100 KB/s）但原画前方窗口已完整缓存在本地：3.5x 倍速不降档
        repeat(3) {
            now += 1_000L
            abr.recordActiveBurst(deltaBytes = 100L * 1024L, deltaNanos = 1_000_000_000L, nowMillis = now)
        }
        repeat(4) {
            now += 1_000L
            val decision = abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = candidates,
                    currentName = ORIGINAL_QUALITY,
                    playbackSpeed = 3.5f,
                    bufferSeconds = 10.0,
                    isBuffering = false,
                    isPlaying = true,
                    cachedCoverageByMediaId = mapOf(ORIGINAL_QUALITY to 0.90f),
                    currentWindowCached = true,
                    windowCachedRenditions = setOf(ORIGINAL_QUALITY),
                ),
            )
            assertNull(decision, "Locally cached window should sustain 3.5x without stepping down")
        }

        // 一旦遇到未缓存空洞导致真实卡顿（isBuffering 连续 >= 3s），必须打破缓存亲和性及时降档，
        // 并且当 480P 有本地缓存时优先降到有缓存的安全档位
        var drop: AbrDecision? = null
        repeat(5) {
            now += 1_000L
            val decision = abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = candidates,
                    currentName = ORIGINAL_QUALITY,
                    playbackSpeed = 1.0f,
                    bufferSeconds = 1.0,
                    isBuffering = true,
                    isPlaying = true,
                    cachedCoverageByMediaId = mapOf(ORIGINAL_QUALITY to 0.35f, "480P" to 0.80f),
                    currentWindowCached = false,
                    windowCachedRenditions = setOf("480P"),
                ),
            )
            if (decision != null && drop == null) drop = decision
        }
        assertNotNull(drop)
        assertEquals("480P", drop!!.targetName)
    }

    @Test
    fun startupGracePreventsPrematureBolaDowngradeWithoutBandwidthSample() {
        val abr = AdaptiveBitrateController()
        abr.resetSession(nowMillis = 0L, clearBandwidth = true)
        abr.notePlaybackReady(nowMillis = 1_000L)

        // 首帧后初始缓冲尚在 1.5s 爬坡且尚无活跃突发带宽样本（ewmaBps == null），但画面平稳播放未卡顿：
        // 绝不可误触发 BOLA 降档切入无索引 MPEG-TS 转码流
        var now = 1_000L
        repeat(12) {
            now += 1_000L
            val decision = abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = candidates,
                    currentName = ORIGINAL_QUALITY,
                    playbackSpeed = 1f,
                    bufferSeconds = 1.5,
                    isBuffering = false,
                    isPlaying = true,
                ),
            )
            assertNull(decision, "Must not prematurely downgrade from Original at t=${now}ms without bandwidth sample or buffering")
        }
    }

    @Test
    fun startupGraceStillDowngradesOnRealContinuousStallAfterDecreaseCooldown() {
        val abr = AdaptiveBitrateController()
        abr.resetSession(nowMillis = 0L, clearBandwidth = true)
        abr.notePlaybackReady(nowMillis = 1_000L)

        // 前 3 秒在 MIN_DECREASE_INTERVAL_MS (4s) 冷却内不降档；满 4s 且连续卡顿 >= 3s 时及时降档止损
        var droppedAt: Long? = null
        var decision: AbrDecision? = null
        var now = 1_000L
        repeat(6) {
            now += 1_000L
            val d = abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = candidates,
                    currentName = ORIGINAL_QUALITY,
                    playbackSpeed = 1f,
                    bufferSeconds = 0.5,
                    isBuffering = true,
                    isPlaying = true,
                ),
            )
            if (d != null && decision == null) {
                decision = d
                droppedAt = now
            }
        }
        assertNotNull(decision, "Real continuous buffering stall must still downgrade")
        assertTrue((droppedAt ?: 0L) >= 5_000L, "Must respect MIN_DECREASE_INTERVAL_MS after first frame, dropped at $droppedAt")
    }

    @Test
    fun notePlaybackReadyPreservesSpeedStepDownForNormalSpeedRestore() {
        val abr = AdaptiveBitrateController()
        var now = 0L
        repeat(4) {
            now += 1_000L
            abr.recordActiveBurst(deltaBytes = 600L * 1024L, deltaNanos = 1_000_000_000L, nowMillis = now)
        }
        var current = ORIGINAL_QUALITY
        repeat(4) {
            now += 1_000L
            abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = candidates,
                    currentName = current,
                    playbackSpeed = 3.5f,
                    bufferSeconds = 8.0,
                    isBuffering = false,
                    isPlaying = true,
                ),
            )?.let { current = it.targetName }
        }
        assertEquals("720P", current)

        // 切档后的新流到达首帧触发 notePlaybackReady，不应抹掉 steppedDownForSpeed
        now += 500L
        abr.notePlaybackReady(now)

        var restored: AbrDecision? = null
        repeat(6) {
            now += 1_000L
            val d = abr.evaluate(
                AbrObservation(
                    nowMillis = now,
                    candidates = candidates,
                    currentName = current,
                    playbackSpeed = 1.0f,
                    bufferSeconds = 12.0,
                    isBuffering = false,
                    isPlaying = true,
                ),
            )
            if (d != null && restored == null) restored = d
        }
        assertNotNull(restored)
        assertEquals(ORIGINAL_QUALITY, restored!!.targetName)
        assertEquals("speed-restore", restored!!.reason)
    }
}
