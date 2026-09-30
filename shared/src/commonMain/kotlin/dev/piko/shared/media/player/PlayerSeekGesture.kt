package dev.piko.shared.media.player

import kotlin.math.abs

/** 短视频保留细调范围，长视频按片长扩大；快速滑动逐段加速，减速时不倒退目标。 */
fun playerSeekDragDelta(durationMillis: Long, width: Float, distance: Float, elapsedMillis: Long): Long {
    if (width <= 0 || !width.isFinite() || !distance.isFinite()) return 0
    val sweep = (durationMillis.coerceAtLeast(0).toDouble() * 0.2).coerceIn(120_000.0, 1_800_000.0)
    val velocity = if (elapsedMillis > 0) abs(distance) / width * 1000 / elapsedMillis else 0f
    val gain = (1.0 + (velocity - 0.75).coerceAtLeast(0.0)).coerceAtMost(3.0)
    return (distance / width * sweep * gain).toLong()
}
