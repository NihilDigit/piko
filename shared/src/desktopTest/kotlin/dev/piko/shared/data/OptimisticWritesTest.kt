package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptimisticWritesTest {
    @Test fun `rollback preserves later rename and independent fields`() {
        val writes = OptimisticWrites()
        val base = FileStat(id = "f", name = "original", parentId = "a", starred = true)
        val rename = writes.begin("account", DriveChange.Renamed("f", "first", "a"), mapOf("f" to base))
        writes.begin("account", DriveChange.Renamed("f", "second", "a"), mapOf("f" to base.copy(name = "first")))
        writes.begin("account", DriveChange.Starred(listOf("f"), false, setOf("a")), mapOf("f" to base))
        writes.finish(rename)
        val visible = writes.project("account", "a", listOf(base)).single()
        assertEquals("second", visible.name)
        assertFalse(visible.isStarred)
        assertEquals("original", writes.project("other", "a", listOf(base)).single().name)
    }

    @Test fun `trash stays hidden under a later metadata write and rollback restores latest name`() {
        val writes = OptimisticWrites()
        val base = FileStat(id = "f", name = "original", parentId = "a")
        val trash = writes.begin("account", DriveChange.Trashed(listOf("f"), setOf("a")), mapOf("f" to base))
        writes.begin("account", DriveChange.Renamed("f", "renamed", "a"), mapOf("f" to base))
        assertTrue(writes.project("account", "a", listOf(base)).isEmpty())
        assertEquals("renamed", writes.project("account", null, listOf(base), trash = true).single().name)
        writes.finish(trash)
        assertEquals("renamed", writes.project("account", "a", listOf(base)).single().name)
    }

    @Test fun `overlapping requests wait while independent files proceed`() = runBlocking {
        val writes = OptimisticWrites()
        val first = writes.begin("account", DriveChange.Renamed("a", "one", ""), emptyMap())
        val second = writes.begin("account", DriveChange.Renamed("a", "two", ""), emptyMap())
        val other = writes.begin("account", DriveChange.Renamed("b", "three", ""), emptyMap())
        val waiting = async { second.awaitTurn() }
        other.awaitTurn()
        assertFalse(waiting.isCompleted)
        writes.finish(first)
        waiting.await()
    }
}
