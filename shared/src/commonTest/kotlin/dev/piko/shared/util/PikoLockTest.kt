package dev.piko.shared.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PikoLockTest {
    @Test
    fun lockCanBeReenteredAndReleasedAfterException() {
        val lock = PikoLock()
        assertEquals(7, withPikoLock(lock) { withPikoLock(lock) { 7 } })
        assertFailsWith<IllegalStateException> {
            withPikoLock(lock) { error("failure") }
        }
        assertEquals(9, withPikoLock(lock) { 9 })
    }

    @Test
    fun concurrentUpdatesDoNotLoseIncrements() = runBlocking {
        val lock = PikoLock()
        var count = 0
        List(8) {
            async(Dispatchers.Default) {
                repeat(500) { withPikoLock(lock) { count++ } }
            }
        }.awaitAll()
        assertEquals(4000, count)
    }

    @Test
    fun nonLocalReturnReleasesLock() {
        val lock = PikoLock()
        fun leave(): Int = withPikoLock(lock) { return 3 }
        assertEquals(3, leave())
        assertEquals(4, withPikoLock(lock) { 4 })
    }
}
