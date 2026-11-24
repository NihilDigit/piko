package dev.piko.ui.screens.drive

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriveGridEnhancementsTest {
    private fun task(
        taskId: String,
        fileId: String,
        status: DownloadStatus,
        isSegment: Boolean = false,
        account: String = "acc-1",
    ) = DownloadTask(
        taskId = taskId,
        fileId = fileId,
        fileName = "$fileId.mp4",
        gcid = "gcid-$fileId",
        totalBytes = 1024L,
        status = status,
        isSegment = isSegment,
        account = account,
    )

    @Test
    fun `shouldShowBackToTop hides when viewport is empty or near top`() {
        assertFalse(shouldShowBackToTop(firstVisibleItemIndex = 0, leadingItemCount = 1, visibleItemCount = 0))
        assertFalse(shouldShowBackToTop(firstVisibleItemIndex = 10, leadingItemCount = 1, visibleItemCount = 0))
        assertFalse(shouldShowBackToTop(firstVisibleItemIndex = 0, leadingItemCount = 1, visibleItemCount = 8))
        assertFalse(shouldShowBackToTop(firstVisibleItemIndex = 6, leadingItemCount = 1, visibleItemCount = 4))
    }

    @Test
    fun `shouldShowBackToTop respects minimum item threshold for short viewports`() {
        // leadingItemCount = 1, visibleItemCount = 4 -> threshold = max(6, 4) = 6 -> index >= 7
        assertFalse(shouldShowBackToTop(firstVisibleItemIndex = 6, leadingItemCount = 1, visibleItemCount = 4))
        assertTrue(shouldShowBackToTop(firstVisibleItemIndex = 7, leadingItemCount = 1, visibleItemCount = 4))
        // leadingItemCount = 2 (with fold banner) -> index >= 8
        assertFalse(shouldShowBackToTop(firstVisibleItemIndex = 7, leadingItemCount = 2, visibleItemCount = 4))
        assertTrue(shouldShowBackToTop(firstVisibleItemIndex = 8, leadingItemCount = 2, visibleItemCount = 4))
    }

    @Test
    fun `shouldShowBackToTop adapts to multi-column grid visible item count`() {
        // 4-column poster grid showing 16 items per screen -> requires scrolling a full screen (16 items)
        assertFalse(shouldShowBackToTop(firstVisibleItemIndex = 12, leadingItemCount = 1, visibleItemCount = 16))
        assertFalse(shouldShowBackToTop(firstVisibleItemIndex = 16, leadingItemCount = 1, visibleItemCount = 16))
        assertTrue(shouldShowBackToTop(firstVisibleItemIndex = 17, leadingItemCount = 1, visibleItemCount = 16))
    }

    @Test
    fun `completedDownloadFileIds includes only completed full files for active account`() {
        val tasks = listOf(
            task("t1", "f-done", DownloadStatus.COMPLETED, account = "acc-1"),
            task("t2", "f-legacy", DownloadStatus.COMPLETED, account = ""),
            task("t3", "f-other-account", DownloadStatus.COMPLETED, account = "acc-2"),
            task("t4", "f-downloading", DownloadStatus.DOWNLOADING, account = "acc-1"),
            task("t5", "f-pending", DownloadStatus.PENDING, account = "acc-1"),
            task("t6", "f-paused", DownloadStatus.PAUSED, account = "acc-1"),
            task("t7", "f-failed", DownloadStatus.FAILED, account = "acc-1"),
            task("t8", "f-segment", DownloadStatus.COMPLETED, isSegment = true, account = "acc-1"),
            task("t9", "", DownloadStatus.COMPLETED, account = "acc-1"),
            task("t10", "f-done", DownloadStatus.COMPLETED, account = "acc-1"),
        )
        assertEquals(setOf("f-done", "f-legacy"), completedDownloadFileIds(tasks, currentAccount = "acc-1"))
        assertEquals(setOf("f-legacy", "f-other-account"), completedDownloadFileIds(tasks, currentAccount = "acc-2"))
        assertEquals(setOf("f-done", "f-legacy", "f-other-account"), completedDownloadFileIds(tasks, currentAccount = null))
    }

    @Test
    fun `completedDownloadFileIds returns empty set for empty task list`() {
        assertEquals(emptySet(), completedDownloadFileIds(emptyList(), currentAccount = "acc-1"))
    }
}
