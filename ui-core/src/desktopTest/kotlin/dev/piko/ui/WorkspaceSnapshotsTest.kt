package dev.piko.ui

import dev.piko.ui.workbench.WorkspaceSnapshots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceSnapshotsTest {
    @Test
    fun repeatedCapturesReplaceTheSamePageAndEvictTheLeastRecentlyCapturedPage() {
        val cache = WorkspaceSnapshots<String>(pixelBudget = 200)
        cache.put(1, "old", 10, 10)
        cache.put(2, "second", 10, 10)
        cache.put(1, "latest", 10, 10)
        assertEquals(200L, cache.pixels)
        cache.put(3, "third", 10, 10)
        assertEquals("latest", cache[1])
        assertNull(cache[2])
        assertEquals("third", cache[3])
    }

    @Test
    fun closingPagesAndClearingTheOwnerReleaseTheirSnapshots() {
        val cache = WorkspaceSnapshots<String>()
        cache.put(1, "files", 100, 100)
        cache.put(2, "draft", 100, 100)
        cache.retain(setOf(2))
        assertNull(cache[1])
        assertEquals("draft", cache[2])
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0L, cache.pixels)
    }

    @Test
    fun invalidOrOversizedCapturesDoNotDiscardAnExistingSnapshot() {
        val cache = WorkspaceSnapshots<String>(pixelBudget = 100)
        cache.put(1, "valid", 10, 10)
        cache.put(1, "oversized", 11, 10)
        cache.put(1, "empty", 0, 10)
        assertEquals("valid", cache[1])
    }
}
