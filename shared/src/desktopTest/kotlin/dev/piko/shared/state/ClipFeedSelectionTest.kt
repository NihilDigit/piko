package dev.piko.shared.state

import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipFeedSelectionTest {
    private fun file(id: String, hash: String = id) = FileStat(id = id, hash = hash, size = "100")

    @Test fun duplicateContentIsSelectedOnce() {
        val files = listOf(file("a", "same"), file("b", "SAME"), file("c"))
        assertEquals(listOf("a", "c"), choose(files).map { it.id })
    }

    @Test fun queuedCopiesAndRecentlyWatchedContentAreExcluded() {
        val files = listOf(file("a"), file("b"), file("c"), file("d"))
        val result = choose(files, queued = setOf(files[0].clipContentKey()), recent = listOf(files[1].clipContentKey()))
        assertEquals(listOf("c", "d"), result.map { it.id })
    }

    @Test fun leastSelectedContentPrecedesPreviouslyRepeatedContent() {
        val files = listOf(file("a"), file("b"), file("c"))
        assertEquals(listOf("c", "b", "a"), choose(files, counts = mapOf(files[0].clipContentKey() to 4, files[1].clipContentKey() to 1)).map { it.id })
    }

    @Test fun collectionWaitsForNewContentBeforeRepeating() {
        val files = listOf(file("a"), file("b"))
        assertEquals(listOf("b"), choose(files, counts = mapOf(files[0].clipContentKey() to 1), collecting = true).map { it.id })
    }

    @Test fun singleVideoCanRepeatWithoutDuplicatingPendingClips() {
        val files = listOf(file("a"))
        assertEquals(1, choose(files, recent = listOf(files[0].clipContentKey())).size)
        assertTrue(choose(files, queued = setOf(files[0].clipContentKey())).isEmpty())
    }

    @Test fun blankHashesKeepDistinctFiles() {
        assertEquals(2, choose(listOf(file("a", ""), file("b", ""))).size)
    }

    private fun choose(
        files: List<FileStat>, queued: Set<String> = emptySet(), recent: List<String> = emptyList(),
        counts: Map<String, Int> = emptyMap(), collecting: Boolean = false,
    ) = selectFeedCandidates(files, queued, recent, counts, collecting)
}
