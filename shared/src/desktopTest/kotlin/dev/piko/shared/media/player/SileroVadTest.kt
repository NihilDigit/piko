package dev.piko.shared.media.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class SileroVadTest {

    @Test
    fun `bundled Silero VAD v5 loads cleanly and rejects pure silence`() {
        val vad = assertNotNull(JvmSileroVadLoader.create(), "Bundled Silero VAD weights should load")
        val silence = FloatArray(SileroVad.WINDOW_SAMPLES)
        var maxSilenceProb = 0f
        repeat(10) {
            val p = vad.scoreWindow(silence)
            if (p > maxSilenceProb) maxSilenceProb = p
        }
        assertTrue(maxSilenceProb < 0.1f, "Expected silence prob < 0.1, got $maxSilenceProb")
    }

    @Test
    fun `Silero VAD reset restores identical deterministic output and runs well above realtime`() {
        val vad = assertNotNull(JvmSileroVadLoader.create())
        val tone = FloatArray(SileroVad.WINDOW_SAMPLES) { i ->
            (0.2 * sin(2.0 * PI * 300.0 * i / SileroVad.SAMPLE_RATE)).toFloat()
        }
        val firstRun = FloatArray(8) { vad.scoreWindow(tone) }
        vad.reset()
        val secondRun = FloatArray(8) { vad.scoreWindow(tone) }
        for (i in firstRun.indices) {
            assertTrue(abs(firstRun[i] - secondRun[i]) < 1e-6f, "Mismatch at frame $i after reset")
        }

        // Throughput smoke check: 100 windows = 3.2s of audio
        val mark = TimeSource.Monotonic.markNow()
        repeat(100) { vad.scoreWindow(tone) }
        val elapsedMs = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1L)
        val realtimeFactor = 3200.0 / elapsedMs.toDouble()
        assertTrue(realtimeFactor >= 3.0, "Pure-Kotlin Silero VAD too slow: ${realtimeFactor}x realtime (${elapsedMs}ms for 3.2s)")
    }
}
