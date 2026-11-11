package dev.piko.shared.log

import io.github.nihildigit.pikpak.RangeAttempt
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

// 健康 CDN 边缘节点的首字节通常在 300–500 ms（2026-09-26 实测），达到 1 s 已属于用户可感知延迟
private val SLOW_FIRST_BYTE = 1.seconds

/**
 * 记录慢速、重试或异常结束的 CDN 区间请求：边缘主机、首字节耗时、内部重试拆分与结束原因。
 * 正常快速完成的请求不逐条落盘（每秒可达数十次），仅汇入 [TransportSummary] 周期统计。
 */
fun logRangeAttempt(attempt: RangeAttempt) {
    TransportSummary.add(attempt)
    // 消费端主动中断（如播放器读够缓冲或切走关闭）属于正常生命周期，不视为传输故障
    if (attempt.outcome == RangeAttempt.Outcome.Cancelled && attempt.endReason == null) return
    val firstByte = attempt.timeToFirstByte
    val slow = firstByte == null || firstByte >= SLOW_FIRST_BYTE
    if (attempt.outcome == RangeAttempt.Outcome.Complete && !slow && attempt.retries == 0) return

    val sinkWaitText = attempt.sinkWait.inWholeMilliseconds.takeIf { it >= 50L }?.let { ", sinkWait=$it ms" }.orEmpty()
    val retryText = if (attempt.retries > 0) {
        val breakdown = buildList {
            if (attempt.retriedServerErrors > 0) add("5xx=${attempt.retriedServerErrors}")
            if (attempt.retriedRateLimited > 0) add("429=${attempt.retriedRateLimited}")
            if (attempt.retriedTransport > 0) add("transport=${attempt.retriedTransport}")
        }.joinToString("+")
        ", retries=${attempt.retries} ($breakdown, backoff=${attempt.retryBackoff.inWholeMilliseconds} ms)"
    } else {
        ""
    }
    val reasonText = attempt.endReason?.let { ", endReason=$it" }.orEmpty()

    val level = when (attempt.outcome) {
        RangeAttempt.Outcome.Rejected -> LogLevel.WARN
        RangeAttempt.Outcome.Failed -> {
            if (attempt.endReason == RangeAttempt.EndReason.Preempted ||
                attempt.endReason == RangeAttempt.EndReason.DemandDeadline
            ) {
                LogLevel.DEBUG
            } else {
                LogLevel.WARN
            }
        }
        else -> LogLevel.DEBUG
    }

    PikoLog.log(
        level,
        "Transport",
        "${attempt.host ?: "?"} ${attempt.outcome}, prio=${attempt.priority}, " +
            "range=${attempt.start}+${attempt.requested ?: "eof"}, " +
            "ttfb=${firstByte?.inWholeMilliseconds?.let { "$it ms" } ?: "none"}, " +
            "delivered=${attempt.delivered / 1024} KiB, duration=${attempt.duration.inWholeMilliseconds} ms" +
            "$sinkWaitText$retryText$reasonText",
    )
}

/**
 * 按活跃窗口（[WINDOW]）汇总全部区间请求吞吐、首字节延迟、重试构成与各优先级请求分布。
 *
 * 不启动后台定时器，窗口结算由后续请求触发；窗口跨度严格取自首条请求发起至末条请求完成的实际活跃区间，
 * 若两次请求之间存在长空闲或暂停（>=[IDLE_RESET]），先以旧窗口自身的真实活跃时长结算，防止空闲时间稀释吞吐率。
 */
private object TransportSummary {
    private val WINDOW = 10.seconds
    private val IDLE_RESET = 10.seconds

    private class Window(
        val firstStart: ComparableTimeMark? = null,
        val lastEnd: ComparableTimeMark? = null,
        val requests: Int = 0,
        val bytes: Long = 0,
        val firstByteMsSum: Long = 0,
        val firstByteCount: Int = 0,
        val durationMsSum: Long = 0,
        val maxActivePerFile: Int = 0,
        val retried5xx: Int = 0,
        val retried429: Int = 0,
        val retriedTransport: Int = 0,
        val failures: Int = 0,
        val byPriority: Map<Int, Int> = emptyMap(),
    ) {
        fun plus(attempt: RangeAttempt, now: ComparableTimeMark): Window {
            val attemptStart = now - attempt.duration
            val earliest = firstStart?.let { if (attemptStart < it) attemptStart else it } ?: attemptStart
            val failed = attempt.outcome == RangeAttempt.Outcome.Failed || attempt.outcome == RangeAttempt.Outcome.Rejected
            return Window(
                firstStart = earliest,
                lastEnd = now,
                requests = requests + 1,
                bytes = bytes + attempt.delivered,
                firstByteMsSum = firstByteMsSum + (attempt.timeToFirstByte?.inWholeMilliseconds ?: 0),
                firstByteCount = firstByteCount + if (attempt.timeToFirstByte != null) 1 else 0,
                durationMsSum = durationMsSum + attempt.networkDuration.inWholeMilliseconds,
                maxActivePerFile = maxOf(maxActivePerFile, attempt.activeReads),
                retried5xx = retried5xx + attempt.retriedServerErrors,
                retried429 = retried429 + attempt.retriedRateLimited,
                retriedTransport = retriedTransport + attempt.retriedTransport,
                failures = failures + if (failed) 1 else 0,
                byPriority = byPriority + (attempt.priority to (byPriority[attempt.priority] ?: 0) + 1),
            )
        }
    }

    private val window = MutableStateFlow(Window())

    fun add(attempt: RangeAttempt) {
        val now = TimeSource.Monotonic.markNow()
        val attemptStart = now - attempt.duration
        var finished: Window? = null
        window.getAndUpdate { current ->
            val first = current.firstStart
            val last = current.lastEnd
            if (first != null && last != null && ((now - first) >= WINDOW || (attemptStart - last) >= IDLE_RESET)) {
                finished = current
                Window().plus(attempt, now)
            } else {
                finished = null
                current.plus(attempt, now)
            }
        }
        finished?.takeIf { it.requests > 0 }?.let { log(it, now) }
    }

    private fun log(w: Window, now: ComparableTimeMark) {
        val first = w.firstStart ?: return
        val last = w.lastEnd ?: return
        val activeMs = (last - first).coerceAtLeast(1.milliseconds).inWholeMilliseconds
        val aggregateKiBps = w.bytes * 1000 / activeMs / 1024
        val perRequestKiBps = if (w.durationMsSum > 0) w.bytes * 1000 / w.durationMsSum / 1024 else 0
        val avgFirstByteMs = if (w.firstByteCount > 0) w.firstByteMsSum / w.firstByteCount else 0
        val priorities = w.byPriority.entries.sortedByDescending { it.key }.joinToString(" ") { "${it.key}:${it.value}" }
        val idleAgoSec = (now - last).inWholeSeconds
        val endedAgoText = if (idleAgoSec >= 2L) ", endedAgo=${idleAgoSec}s" else ""
        val totalRetries = w.retried5xx + w.retried429 + w.retriedTransport
        val retrySummary = if (totalRetries > 0 || w.failures > 0) {
            ", retries(5xx=${w.retried5xx}, 429=${w.retried429}, transport=${w.retriedTransport}), failed=${w.failures}"
        } else {
            ""
        }

        PikoLog.d(
            "Transport",
            "Window ${activeMs} ms$endedAgoText: requests=${w.requests}, delivered=${w.bytes / 1024} KiB, " +
                "throughput=$aggregateKiBps KiB/s, avgReq=${w.bytes / w.requests / 1024} KiB/" +
                "${w.durationMsSum / w.requests} ms/$perRequestKiBps KiB/s, avgTtfb=$avgFirstByteMs ms, " +
                "peakActivePerFile=${w.maxActivePerFile}, prio=[$priorities]$retrySummary",
        )
    }
}
