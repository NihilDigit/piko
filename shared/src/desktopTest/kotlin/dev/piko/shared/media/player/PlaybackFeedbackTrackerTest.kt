package dev.piko.shared.media.player

import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class PlaybackFeedbackTrackerTest {
    @Test fun delayedPublicationDoesNotRefreshTheSampleClock() {
        val clock = TestTimeSource()
        val feedback = PlaybackFeedbackTracker(clock)
        val generation = feedback.invalidate()
        feedback.restarted(generation)
        val sampledAt = clock.markNow()
        clock += 1000.milliseconds
        feedback.sample(generation, 2.0, 1f, sampledAt)
        assertEquals(1000.milliseconds, feedback.snapshot!!.sampledAt.elapsedNow())
    }

    @Test fun seekInvalidatesFeedbackAndRejectsDelayedOldCallbacks() {
        val clock = TestTimeSource()
        val feedback = PlaybackFeedbackTracker(clock)
        val first = feedback.invalidate()
        feedback.sample(first, 5.0, 1f)
        assertNull(feedback.snapshot!!.bufferedDuration)
        assertTrue(feedback.restarted(first))
        feedback.sample(first, 0.2, 2f)
        assertEquals(100.milliseconds, feedback.snapshot!!.bufferedDuration)
        val seek = feedback.invalidate()
        assertNull(feedback.snapshot!!.bufferedDuration)
        assertFalse(feedback.restarted(first))
        feedback.sample(first, 30.0, 1f)
        assertEquals(seek, feedback.snapshot!!.generation)
        assertNull(feedback.snapshot!!.bufferedDuration)
        feedback.restarted(seek)
        feedback.sample(seek, 0.0, 1f)
        assertEquals(0.milliseconds, feedback.snapshot!!.bufferedDuration)
    }
}
