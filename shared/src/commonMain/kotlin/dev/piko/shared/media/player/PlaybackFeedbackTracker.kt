package dev.piko.shared.media.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nihildigit.pikpak.PlaybackBufferSnapshot
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.TimeMark

/** 缓冲读数属于一次 load/seek；旧回调不能重新建立新位置的缓冲余量。 */
class PlaybackFeedbackTracker(private val clock: TimeSource = TimeSource.Monotonic) {
    var generation by mutableLongStateOf(0L)
        private set
    var snapshot by mutableStateOf<PlaybackBufferSnapshot?>(null)
        private set
    private var pending = true

    fun invalidate(): Long {
        generation++
        pending = true
        snapshot = PlaybackBufferSnapshot(generation, null, clock.markNow())
        return generation
    }

    fun restarted(generation: Long): Boolean {
        if (generation != this.generation) return false
        pending = false
        return true
    }

    fun sample(generation: Long, mediaSeconds: Double?, speed: Float, sampledAt: TimeMark = clock.markNow()) {
        if (generation != this.generation) return
        val seconds = mediaSeconds?.takeIf { !pending && it.isFinite() && it >= 0 && speed.isFinite() && speed > 0 }
        snapshot = PlaybackBufferSnapshot(generation, seconds?.let { (it / speed).seconds }, sampledAt)
    }
}
