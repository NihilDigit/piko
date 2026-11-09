package dev.piko.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchGroupRangeTest {
    @Test fun repeatedCaptureTextKeepsItsOwnOffset() {
        val match = Regex("(abc)-(abc)").find("前 abc-abc 后")!!
        assertEquals(2..4, match.groups[1]!!.matchRange)
        assertEquals(6..8, match.groups[2]!!.matchRange)
        assertEquals(2..8, match.range)
    }

    @Test fun emptyAndUnmatchedCapturesRemainDistinct() {
        val match = Regex("a()(b)?").find("前 a")!!
        val emptyRange = match.groups[1]!!.matchRange
        assertTrue(emptyRange.isEmpty())
        assertEquals(3, emptyRange.first)
        assertEquals(2, emptyRange.last)
        assertNull(match.groups[2])
    }

    @Test fun replacementChangesOnlyTheCapturedSpan() {
        val input = "prefix: repeat/repeat?tail=repeat"
        val match = Regex("repeat/(repeat)").find(input)!!
        assertEquals("prefix: repeat/changed?tail=repeat", input.replaceRange(match.groups[1]!!.matchRange, "changed"))
    }
}
