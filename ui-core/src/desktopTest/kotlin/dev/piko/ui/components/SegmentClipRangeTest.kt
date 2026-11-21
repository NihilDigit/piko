package dev.piko.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SegmentClipRangeTest {
    @Test
    fun `unknown and negative durations have no downloadable range`() {
        for (duration in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertNull(normalizeSegmentRange(duration, 0L, 60_000L))
            assertNull(nudgeSegmentRange(duration, 0L, 0L, true, 1000L))
            assertNull(dragSegmentRange(duration, 0L, 0L))
        }
    }

    @Test
    fun `short videos allow full download but no adjustment`() {
        for (duration in listOf(300L, 500L)) {
            val expected = NormalizedSegmentRange(0L, duration, false)
            assertEquals(expected, normalizeSegmentRange(duration, 10_000L, -1L))
            assertEquals(expected, dragSegmentRange(duration, duration, duration))
            for (start in listOf(true, false)) {
                for (delta in listOf(-1000L, 1000L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                    assertEquals(expected, nudgeSegmentRange(duration, 0L, duration, start, delta))
                }
            }
        }
    }

    @Test
    fun `dragging start or end handle clamps against stationary opposite bound`() {
        assertEquals(NormalizedSegmentRange(9500L, 10_000L, true), dragSegmentRange(10_000L, 10_000L, 10_000L))
        // 左手柄向右逼近或越过右手柄时，右手柄固定不变，左手柄停在 end - minSpan
        assertEquals(NormalizedSegmentRange(4500L, 5000L, true), dragSegmentRange(10_000L, 4900L, 5000L, previousStartMs = 4000L, previousEndMs = 5000L))
        // 右手柄向左逼近或越过左手柄时，左手柄固定不变，右手柄停在 start + minSpan
        assertEquals(NormalizedSegmentRange(4000L, 4500L, true), dragSegmentRange(10_000L, 4000L, 4100L, previousStartMs = 4000L, previousEndMs = 5000L))
    }

    @Test
    fun `initial range is clamped to newly discovered shorter duration`() {
        assertEquals(NormalizedSegmentRange(1500L, 2000L, true), normalizeSegmentRange(2000L, 50_000L, 60_000L))
        assertEquals(NormalizedSegmentRange(0L, 500L, true), normalizeSegmentRange(2000L, -1000L, -500L))
        assertEquals(NormalizedSegmentRange(0L, 60_000L, true), normalizeSegmentRange(120_000L, 0L, 60_000L))
    }

    @Test
    fun `repeated nudges saturate without inverting or overflowing bounds`() {
        var range = NormalizedSegmentRange(0L, 5000L, true)
        for (start in listOf(true, false)) {
            for (delta in listOf(1000L, -1000L, Long.MAX_VALUE, Long.MIN_VALUE)) {
                repeat(20) {
                    range = assertNotNull(nudgeSegmentRange(10_000L, range.startMs, range.endMs, start, delta))
                    assertTrue(range.startMs >= 0L)
                    assertTrue(range.endMs <= 10_000L)
                    assertTrue(range.endMs - range.startMs >= 500L)
                }
            }
        }
        assertEquals(NormalizedSegmentRange(0L, 500L, true), range)
    }

    @Test
    fun `custom minimum span and maximum duration remain safe`() {
        assertEquals(NormalizedSegmentRange(0L, 1000L, false), normalizeSegmentRange(1000L, 0L, 500L, minSpanMs = 2000L))
        assertEquals(NormalizedSegmentRange(Long.MAX_VALUE - 500L, Long.MAX_VALUE, true),
            normalizeSegmentRange(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
