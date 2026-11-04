package dev.piko.shared.state

import dev.piko.download.DownloadBatch
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.upload.UploadStatus
import dev.piko.shared.upload.UploadTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class TransferItemCacheTest {
    @Test
    fun `one progress tick preserves hundreds of completed row instances without constructing replacements`() {
        val cache = TransferItemCache()
        var constructions = 0
        fun project(task: DownloadTask): TransferItem = cache.project("local:${task.taskId}", task) {
            constructions++
            TransferItem.Local(task)
        }
        val completed = (1..300).map { task(it.toString()).copy(status = DownloadStatus.COMPLETED) }
        val active = task("active")
        val first = (completed + active).map(::project)
        val next = (completed.map { it.copy() } + active.copy(downloadedBytes = 50)).map(::project)
        completed.indices.forEach { assertSame(first[it], next[it]) }
        assertNotSame(first.last(), next.last())
        assertEquals(302, constructions)
        cache.retain(setOf(next.last().key))
        assertNotSame(first.first(), project(completed.first()), "已移除项应释放缓存")
    }

    @Test
    fun `batch children are stored and unchanged child rows survive sibling progress`() {
        val cache = TransferItemCache()
        val batch = DownloadBatch("batch", "Folder")
        val firstTask = task("active").copy(batch = batch)
        val secondTask = task("completed").copy(batch = batch, status = DownloadStatus.COMPLETED)
        fun project(tasks: List<DownloadTask>): TransferItem = cache.project("batch:${batch.id}", tasks) {
            TransferItem.LocalBatch(batch, tasks, null, tasks.map { task ->
                cache.project("local:${task.taskId}", task) { TransferItem.Local(task) }
            })
        }
        val first = project(listOf(firstTask, secondTask))
        assertSame(first.children, first.children)
        assertSame(first, project(listOf(firstTask.copy(), secondTask.copy())))
        val next = project(listOf(firstTask.copy(downloadedBytes = 50), secondTask))
        assertNotSame(first, next)
        assertNotSame(first.children[0], next.children[0])
        assertSame(first.children[1], next.children[1])
        val defaultBatch = TransferItem.LocalBatch(batch, listOf(firstTask), null)
        assertSame(defaultBatch.children, defaultBatch.children)
        val upload = UploadTask("upload", "account", "source", "file.bin", 100, 0, "", "Drive", status = UploadStatus.PAUSED)
        val uploadBatch = TransferItem.UploadBatch("uploads", "Files", "", "Drive", listOf(upload), false)
        assertSame(uploadBatch.children, uploadBatch.children)
        assertEquals("upload:upload", uploadBatch.children.single().key)
        assertSame(TransferItem.Local(firstTask).children, TransferItem.Local(secondTask).children)
    }

    private fun task(id: String) = DownloadTask(id, id, "$id.bin", "HASH$id", 100,
        status = DownloadStatus.DOWNLOADING)
}
