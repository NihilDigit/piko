package dev.piko.shared.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuzzyMatchTest {
    @Test
    fun `letters must appear in order`() {
        assertNotNull(fuzzyScore("frn", "Frieren"))
        assertNull(fuzzyScore("nrf", "Frieren"))
        assertEquals(0, fuzzyScore("  ", "anything"))
    }

    @Test
    fun `contiguous and prefix matches rank first`() {
        val prefix = fuzzyScore("fri", "Frieren")!!
        val inside = fuzzyScore("ere", "Frieren")!!
        val scattered = fuzzyScore("frn", "Frieren")!!
        assertTrue(prefix > inside)
        assertTrue(inside > scattered)
    }

    @Test
    fun `shorter names win among equal matches, chinese works`() {
        assertTrue(fuzzyScore("动画", "动画")!! > fuzzyScore("动画", "动画 2024 合集")!!)
        assertNotNull(fuzzyScore("回收", "打开回收站"))
    }
}
