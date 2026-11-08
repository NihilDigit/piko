package dev.piko.shared.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderIndexConcurrencyTest {
    @Test fun `an edit cannot publish through an older in flight projection`() {
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
}
