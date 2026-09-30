package dev.piko.shared.media.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerSeekGestureTest {
    @Test
    fun `long films and fast swipes cover more than a short slow swipe`() {
        val slow = playerSeekDragDelta(600_000, 400f, 100f, 1_000)
        val fast = playerSeekDragDelta(600_000, 400f, 100f, 50)
        val film = playerSeekDragDelta(7_200_000, 400f, 100f, 1_000)
        assertTrue(fast > slow * 2)
        assertTrue(film > slow * 5)
        assertEquals(-fast, playerSeekDragDelta(600_000, 400f, -100f, 50))
    }

    @Test
    fun `density scaling preserves a gesture and an unmeasured width does nothing`() {
        assertEquals(playerSeekDragDelta(1_200_000, 400f, 100f, 100),
            playerSeekDragDelta(1_200_000, 1200f, 300f, 100))
        assertEquals(0, playerSeekDragDelta(1_200_000, 0f, 100f, 100))
    }
}
