package dev.piko.shared.state

import kotlin.test.*

class LocalClipWindowTest {
    @Test fun localOriginalLoopsAtWindowEndEvenAfterDelayedProgressUpdates() {
        assertFalse(clipWindowEnded(44_999, 15_000, 30_000, true, 2_000))
        assertTrue(clipWindowEnded(45_000, 15_000, 30_000, true, 2_000))
        assertTrue(clipWindowEnded(60_000, 15_000, 30_000, true, 2_000))
        assertFalse(clipWindowEnded(60_000, 15_000, 30_000, false, 2_000))
        assertFalse(clipWindowEnded(14_000, 15_000, 30_000, true, 2_000))
        assertFalse(clipWindowEnded(45_000, 15_000, 0, true, 2_000))
    }
    @Test fun localWindowIsBoundedByThirtySecondsAndRemainingOriginalDuration() {
        assertEquals(30_000L, localClipWindowMs(15_000, 120_000))
        assertEquals(5_000L, localClipWindowMs(115_000, 120_000))
        assertEquals(0L, localClipWindowMs(130_000, 120_000))
    }
}
