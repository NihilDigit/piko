package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FolderIndexTest {
    @Test
    fun unchangedUpdatesKeepThePublishedStateAndNodes() {
        val index = FolderIndex()
        val folder = FileStat(id = "child", name = "Child", kind = FileKind.FOLDER)
        assertTrue(index.listed("parent", listOf(folder)))
        val before = index.flow.value
        assertFalse(index.listed("parent", listOf(folder)))
        assertFalse(index.listed("parent", listOf(folder), complete = false))
        assertFalse(index.learned("child", "Child", "parent", true, false))
        assertFalse(index.renamed("child", "Child", always = true))
        assertFalse(index.moved(listOf("child"), "parent"))
        assertFalse(index.removed(listOf("unknown"), emptySet()))
        assertFalse(index.seed(mapOf("child" to "Old"), mapOf("child" to "elsewhere")))
        assertSame(before, index.flow.value)
        assertSame(before.nodes, index.flow.value.nodes)
    }

    @Test
    fun authoritativeListingReplacesProvisionalNodesOnlyOnce() {
        val index = FolderIndex()
        assertTrue(index.seed(mapOf("child" to "Child"), mapOf("child" to "parent")))
        val seeded = index.flow.value
        assertFalse(index.seed(mapOf("child" to "Child"), mapOf("child" to "parent")))
        assertSame(seeded, index.flow.value)
        assertTrue(index.learned("child", "Child", "parent", true, false))
        assertFalse(index.node("child")!!.provisional)
        assertFalse(index.learned("child", "Child", "parent", true, false))
    }

    @Test
    fun anEditCannotPublishThroughAnOlderInFlightProjection() {
        val index = FolderIndex()
        index.learned("folder", "before", "", isFolder = true, trashed = false)
        val transforming = CountDownLatch(1)
        val release = CountDownLatch(1)
        val editing = CountDownLatch(1)
        val edited = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val projection = executor.submit {
                index.project { state ->
                    if (state.nodes["folder"]?.name == "before") {
                        transforming.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    state
                }
            }
            assertTrue(transforming.await(5, TimeUnit.SECONDS))
            val edit = executor.submit {
                editing.countDown()
                index.renamed("folder", "after", always = false)
                edited.countDown()
            }
            assertTrue(editing.await(5, TimeUnit.SECONDS))
            assertFalse(edited.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            projection.get(5, TimeUnit.SECONDS)
            edit.get(5, TimeUnit.SECONDS)
            assertEquals("after", index.confirmedNode("folder")?.name)
            assertEquals("after", index.node("folder")?.name)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun legacyFolderStacksStillDecode() {
        assertEquals(
            listOf(PikoPathBreadcrumb("", "网盘"), PikoPathBreadcrumb("piko:starred", "星标")),
            LastFolderStack.decode("::网盘;piko:starred::星标"),
        )
    }
}
