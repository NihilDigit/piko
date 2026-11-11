package dev.piko.shared.media.player

import dev.piko.shared.i18n.tr
import kotlinx.coroutines.delay
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 播放器与信息流共用的加载期传输速率采样器。
 *
 * 规则：
 * 1. 云端冷存唤醒中（`isReactivating == true`）立即返回本地化唤醒提示，不做任何延迟，并在唤醒结束后重置字节基线。
 * 2. 取流元数据阶段（`currentBytes == null`）以及起播前 [SPEED_APPEAR_DELAY_MS] 的短探测窗口内不展示速率文字，
 *    避免将 API 查询空窗期或容器头尾几十 KB 的串行探测折算成「0 KB/s」「200 KB/s」造成卡顿错觉。
 * 3. 满 [SPEED_APPEAR_DELAY_MS] 且累计收到至少一个完整块（[MIN_STREAMING_EVIDENCE_BYTES]）的正片流量后，
 *    首拍直接取当前周期瞬时速率（不与 0 做平滑）；若真实卡顿超过 [SPEED_STALLED_DELAY_MS] 仍无流量，则如实显示 0 KB/s 或低速。
 */
class LoadingSpeedSampler(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private var sessionToken: Any? = UninitializedToken
    private var hasBaseline = false
    private var startedAt: TimeMark = timeSource.markNow()
    private var baselineBytes = 0L
    private var lastBytes = 0L
    private var lastMark: TimeMark = startedAt
    private var smoothedBytesPerSec = 0.0
    private var revealed = false
    private var wasReactivating = false

    fun reset() {
        sessionToken = UninitializedToken
        hasBaseline = false
        baselineBytes = 0L
        lastBytes = 0L
        smoothedBytesPerSec = 0.0
        revealed = false
        wasReactivating = false
    }

    fun sample(
        token: Any? = DefaultToken,
        currentBytes: Long?,
        isReactivating: Boolean,
    ): String? {
        if (token !== sessionToken) {
            sessionToken = token
            hasBaseline = false
            smoothedBytesPerSec = 0.0
            revealed = false
            wasReactivating = false
        }

        if (isReactivating) {
            wasReactivating = true
            if (currentBytes != null) {
                hasBaseline = true
                baselineBytes = currentBytes
                lastBytes = currentBytes
            } else {
                hasBaseline = false
            }
            val now = timeSource.markNow()
            startedAt = now
            lastMark = now
            smoothedBytesPerSec = 0.0
            revealed = false
            return reactivatingStatusText()
        }

        if (currentBytes == null) {
            lastMark = timeSource.markNow()
            return null
        }

        if (!hasBaseline || wasReactivating) {
            wasReactivating = false
            hasBaseline = true
            val now = timeSource.markNow()
            startedAt = now
            lastMark = now
            baselineBytes = currentBytes
            lastBytes = currentBytes
            smoothedBytesPerSec = 0.0
            revealed = false
            return null
        }

        val elapsedMs = lastMark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1L)
        lastMark = timeSource.markNow()
        val delta = (currentBytes - lastBytes).coerceAtLeast(0L)
        lastBytes = currentBytes
        val instantRate = delta * 1000.0 / elapsedMs

        if (!revealed) {
            val totalElapsedMs = startedAt.elapsedNow().inWholeMilliseconds
            val totalReceived = (currentBytes - baselineBytes).coerceAtLeast(0L)
            val streamingReady = totalElapsedMs >= SPEED_APPEAR_DELAY_MS &&
                totalReceived >= MIN_STREAMING_EVIDENCE_BYTES &&
                delta > 0L
            val stalledReady = totalElapsedMs >= SPEED_STALLED_DELAY_MS
            if (!streamingReady && !stalledReady) {
                return null
            }
            revealed = true
            smoothedBytesPerSec = instantRate
        } else {
            smoothedBytesPerSec = if (smoothedBytesPerSec <= 0.0) {
                instantRate
            } else {
                smoothedBytesPerSec * 0.45 + instantRate * 0.55
            }
        }
        return formatTransferRate(smoothedBytesPerSec.toLong())
    }

    companion object {
        const val SPEED_SAMPLE_INTERVAL_MS = 350L
        const val SPEED_APPEAR_DELAY_MS = 600L
        const val SPEED_STALLED_DELAY_MS = 1_500L
        const val MIN_STREAMING_EVIDENCE_BYTES = 256L * 1024L

        private val UninitializedToken = Any()
        private val DefaultToken = Any()

        fun reactivatingStatusText(): String = tr(
            "云端冷存唤醒中…",
            "Waking archived file…",
            "クラウドから復元中…",
            "클라우드 파일 깨우는 중…",
            "雲端冷存喚醒中…",
        )
    }
}

suspend fun observeLoadingSpeed(
    token: () -> Any? = { Unit },
    sampleBytes: () -> Long?,
    isReactivating: () -> Boolean = { false },
    onReactivating: (Any?) -> Unit = {},
    onUpdate: (String?) -> Unit,
) {
    val sampler = LoadingSpeedSampler()
    val initialToken = token()
    val initialReactivating = isReactivating()
    if (initialReactivating) onReactivating(initialToken)
    onUpdate(sampler.sample(initialToken, sampleBytes(), initialReactivating))
    while (true) {
        delay(LoadingSpeedSampler.SPEED_SAMPLE_INTERVAL_MS)
        val curToken = token()
        val curReactivating = isReactivating()
        if (curReactivating) onReactivating(curToken)
        onUpdate(sampler.sample(curToken, sampleBytes(), curReactivating))
    }
}
