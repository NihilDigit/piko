package dev.piko.shared.log

import io.github.nihildigit.pikpak.RangeAttempt
import kotlin.time.Duration.Companion.seconds

// 健康的主机首字节 300 到 500 ms（2026-09-26 实测），到一秒已经是会被察觉的等待
private val SLOW_FIRST_BYTE = 1.seconds

/**
 * 记下慢的与出错的 CDN 请求：哪台主机、首字节多久、怎么结束的。换主机、限流、半路断流在播放器与下载那头
 * 都只是「卡了一下」「慢了」，不记这一层就只能猜。正常的请求一秒几十个，不记。交给 handle 的 onRangeAttempt。
 */
fun logRangeAttempt(attempt: RangeAttempt) {
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
