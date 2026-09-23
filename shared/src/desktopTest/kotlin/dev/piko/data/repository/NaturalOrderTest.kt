package dev.piko.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class NaturalOrderTest {

    @Test
    fun `episode numbers sort by value, not by character`() {
        val names = listOf("[10].mkv", "[2].mkv", "[1].mkv", "[100].mkv", "[02].ass")
        assertEquals(listOf("[1].mkv", "[02].ass", "[2].mkv", "[10].mkv", "[100].mkv"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun `digit runs longer than a long do not overflow`() {
        val big = "9".repeat(30)
        assertEquals(listOf("a2", "a$big"), listOf("a$big", "a2").sortedWith(NaturalOrder))
    }
}
