package dev.piko.shared.log

import io.github.nihildigit.pikpak.RangeAttempt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// 健康的主机首字节 300 到 500 ms（2026-09-26 实测），到一秒已经是会被察觉的等待
private val SLOW_FIRST_BYTE = 1.seconds

/**
 * 记下慢的与出错的 CDN 请求：哪台主机、首字节多久、怎么结束的。换主机、限流、半路断流在播放器与下载那头
 * 都只是「卡了一下」「慢了」，不记这一层就只能猜。正常的请求一秒几十个，不逐条记，只进 [TransportSummary]。
 * 交给 handle 的 onRangeAttempt。
 */
fun logRangeAttempt(attempt: RangeAttempt) {
    TransportSummary.add(attempt)
    // 读者不要了才停的请求不是传输问题：播放器读够就断、翻走就关，一秒几十个
    if (attempt.outcome == RangeAttempt.Outcome.Cancelled) return
    val firstByte = attempt.timeToFirstByte
    val slow = firstByte == null || firstByte >= SLOW_FIRST_BYTE
    if (attempt.outcome == RangeAttempt.Outcome.Complete && !slow) return
    PikoLog.d(
        "Transport",
        "${attempt.host ?: "?"} ${attempt.outcome}，优先级 ${attempt.priority}：首字节 ${firstByte?.inWholeMilliseconds?.let { "$it ms" } ?: "无"}，" +
            "送出 ${attempt.delivered / 1024} KiB，历时 ${attempt.duration.inWholeMilliseconds} ms",
    )
}

/**
 * 每 [WINDOW] 汇总一次全部请求，快的也算：一共收了多少、每条请求多大多快、首字节多久、各档位各几条。
 * 只记慢请求的话，看不出吞吐卡在哪里：信息流取流只有下载的四分之一时，逐条日志里几乎没有慢请求（2026-09-28）。
 *
 * 不起定时器：窗口到时由下一条请求带出，没有请求的时段本来也没什么可记。
 * 回调来自多个线程，窗口整个换成新的一份，靠 StateFlow 的比较交换保证不丢不重。
 */
private object TransportSummary {
    private val WINDOW = 10.seconds

    private class Window(
        val started: TimeMark = TimeSource.Monotonic.markNow(),
        val requests: Int = 0,
        val bytes: Long = 0,
        val firstByteMsSum: Long = 0,
        val firstByteCount: Int = 0,
        val durationMsSum: Long = 0,
        val maxActive: Int = 0,
        val byPriority: Map<Int, Int> = emptyMap(),
    ) {
        fun plus(attempt: RangeAttempt) = Window(
            started = started,
            requests = requests + 1,
            bytes = bytes + attempt.delivered,
            firstByteMsSum = firstByteMsSum + (attempt.timeToFirstByte?.inWholeMilliseconds ?: 0),
            firstByteCount = firstByteCount + if (attempt.timeToFirstByte != null) 1 else 0,
            durationMsSum = durationMsSum + attempt.duration.inWholeMilliseconds,
            maxActive = maxOf(maxActive, attempt.activeReads),
            byPriority = byPriority + (attempt.priority to (byPriority[attempt.priority] ?: 0) + 1),
        )
    }

    private val window = MutableStateFlow(Window())

    fun add(attempt: RangeAttempt) {
        var finished: Window? = null
        window.getAndUpdate { current ->
            if (current.started.elapsedNow() >= WINDOW) {
                finished = current
                Window().plus(attempt)
            } else {
                finished = null
                current.plus(attempt)
            }
        }
        finished?.takeIf { it.requests > 0 }?.let(::log)
    }

    private fun log(w: Window) {
        val elapsedMs = w.started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        val perRequestKiBps = if (w.durationMsSum > 0) w.bytes * 1000 / w.durationMsSum / 1024 else 0
        val priorities = w.byPriority.entries.sortedByDescending { it.key }.joinToString(" ") { "${it.key}:${it.value}" }
        PikoLog.d(
            "Transport",
            "近 ${elapsedMs / 1000} 秒：${w.requests} 个请求，收 ${w.bytes / 1024} KiB，合计 ${w.bytes * 1000 / elapsedMs / 1024} KiB/s；" +
                "每条平均 ${w.bytes / w.requests / 1024} KiB、${w.durationMsSum / w.requests} ms、$perRequestKiBps KiB/s，" +
                "首字节平均 ${if (w.firstByteCount > 0) w.firstByteMsSum / w.firstByteCount else 0} ms；" +
                "单文件在途最多 ${w.maxActive}；档位 $priorities",
        )
    }
}
