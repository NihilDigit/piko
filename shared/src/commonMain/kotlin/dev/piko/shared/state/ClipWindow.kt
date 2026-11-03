package dev.piko.shared.state

/** 本地原文件没有物理 EOF 边界，播放器时间跨过窗口末尾时必须主动循环或切到下一段。 */
fun clipWindowEnded(positionMs: Long, startMs: Long, lengthMs: Long, local: Boolean, staleSlackMs: Long): Boolean {
    if (lengthMs <= 0 || positionMs < startMs) return false
    val elapsed = positionMs - startMs
    return elapsed >= lengthMs && (local || elapsed - lengthMs <= staleSlackMs)
}

/** 本地完整源允许顺延至片尾；未知时长由播放器就绪后的真实时长约束。 */
fun localClipMaxWindowMs(startMs: Long, videoDurationMs: Long): Long? =
    if (videoDurationMs > 0) (videoDurationMs - startMs.coerceAtLeast(0L)).coerceAtLeast(1_000L) else null

fun localClipWindowMs(startMs: Long, videoDurationMs: Long): Long =
    minOf(CLIP_LENGTH_MS, (videoDurationMs - startMs.coerceAtLeast(0)).coerceAtLeast(0))
