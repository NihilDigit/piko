package dev.piko.shared.media.player

import io.github.nihildigit.pikpak.AiSubtitleCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiSubtitleControllerTest {

    @Test
    fun `resampler downsamples 48kHz mono float into 16kHz 100ms pcm_s16le frames`() {
        val resampler = AiSubtitlePcmResampler()
        val input = FloatArray(12_000) { i -> if (i % 2 == 0) 0.5f else -0.5f }
        val frames = ArrayList<AiSubtitlePcmFrame>()

        resampler.pushMonoFloat(
            monoSamples = input,
            sampleCount = input.size,
            inputSampleRate = 48_000,
            ptsMs = 1_000L,
            outFrames = frames,
        )

        assertEquals(2, frames.size)
        assertEquals(3_200, frames[0].pcm.size)
        assertEquals(3_200, frames[1].pcm.size)
        assertEquals(1_000L, frames[0].positionMs)
        assertEquals(1_100L, frames[1].positionMs)

        resampler.flush(frames)
        assertEquals(3, frames.size)
        assertEquals(1_600, frames[2].pcm.size)
    }

    @Test
    fun `cue store strictly prevents any overlapping cues on single track`() {
        val store = AiSubtitleCueStore()
        // Small sequential tail collision
        store.add(AiSubtitleCue(startMs = 1_000L, endMs = 3_300L, text = "First sentence"))
        store.add(AiSubtitleCue(startMs = 3_000L, endMs = 5_000L, text = "Second sentence"))

        assertEquals("First sentence", store.textAt(2_999L))
        assertEquals("Second sentence", store.textAt(3_000L))
        assertEquals("Second sentence", store.textAt(5_100L))
        assertNull(store.textAt(5_200L))

        // Large overlap (>600ms) still cuts previous cue cleanly at next cue's startMs
        store.clear()
        store.add(AiSubtitleCue(startMs = 10_000L, endMs = 13_000L, text = "Earlier line"))
        store.add(AiSubtitleCue(startMs = 10_500L, endMs = 13_200L, text = "Next line"))
        assertEquals("Earlier line", store.textAt(10_499L))
        assertEquals("Next line", store.textAt(10_500L))
        assertEquals("Next line", store.textAt(11_000L))
    }

    @Test
    fun `cue store replaces near-same-start streaming revisions`() {
        val store = AiSubtitleCueStore()
        // Real subsrelay pattern: 198ms start drift on revised/expanded utterance (piko.log 649660..650660 -> 649858..652565)
        store.add(AiSubtitleCue(startMs = 649_660L, endMs = 650_660L, text = "Partial line"))
        store.add(AiSubtitleCue(startMs = 649_858L, endMs = 652_565L, text = "Expanded complete line"))
        assertNull(store.textAt(649_700L))
        assertEquals("Expanded complete line", store.textAt(649_900L))
        assertEquals("Expanded complete line", store.textAt(652_500L))
        assertTrue(store.hasCueNear(650_000L))
        assertFalse(store.hasCueNear(660_000L))
    }

    @Test
    fun `resampler reset clears buffered state cleanly`() {
        val resampler = AiSubtitlePcmResampler()
        val frames = ArrayList<AiSubtitlePcmFrame>()
        resampler.pushMonoFloat(FloatArray(800) { 0.25f }, 800, 16_000, 0L, frames)
        assertTrue(frames.isEmpty())
        resampler.reset()
        resampler.flush(frames)
        assertTrue(frames.isEmpty())
    }
}
