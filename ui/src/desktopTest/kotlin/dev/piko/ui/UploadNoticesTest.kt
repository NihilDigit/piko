package dev.piko.ui

import dev.piko.shared.upload.UploadPreparation
import dev.piko.shared.upload.UploadStatus
import dev.piko.shared.upload.UploadTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadNoticesTest {
    private fun task(id: String, account: String, status: UploadStatus) = UploadTask(
        id, account, id, "$id.bin", 1, 1, "parent", "parent", status = status, createdAtMs = 1,
    )

    @Test
    fun anEmptyQueueWhileStillPreparingDoesNotProducePrematurePerFileSummaries() {
        val notices = UploadNoticeAccumulator(0)
        val preparing = listOf(UploadPreparation("p", "A", "parent", "parent", 1, 1))
        val first = task("first", "A", UploadStatus.COMPLETED)
        assertNull(notices.accept(mapOf(first.taskId to first), preparing, "A"))
        val second = task("second", "A", UploadStatus.COMPLETED)
        val all = mapOf(first.taskId to first, second.taskId to second)
        assertNull(notices.accept(all, preparing, "A"))
        val summary = notices.accept(all, emptyList(), "A")
        assertEquals("上传完成", summary?.title)
        assertEquals("2 个文件已上传", summary?.message)
        assertNull(notices.accept(all, emptyList(), "A"))
    }

    @Test
    fun switchingAccountsDropsThePreviousSummaryWithoutReplayingOtherAccountsHistory() {
        val notices = UploadNoticeAccumulator(0)
        val a = task("old-account", "A", UploadStatus.COMPLETED)
        val b = task("historical", "B", UploadStatus.COMPLETED)
        val all = mapOf(a.taskId to a, b.taskId to b)
        assertNull(notices.accept(all, listOf(UploadPreparation("p", "A", "parent", "parent", 1, 1)), "A"))
        assertNull(notices.accept(all, emptyList(), "B"))
        val new = task("current", "B", UploadStatus.QUEUED)
        assertNull(notices.accept(all + (new.taskId to new), emptyList(), "B"))
        val summary = notices.accept(all + (new.taskId to new.copy(status = UploadStatus.COMPLETED)), emptyList(), "B")
        assertTrue(summary?.message?.contains("current.bin") == true)
        assertTrue(summary?.message?.contains("old-account") == false)
        assertTrue(summary?.message?.contains("historical") == false)
    }
}
