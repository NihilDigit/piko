package dev.piko.shared.state

import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.upload.*
import io.github.nihildigit.pikpak.DriveTask
import kotlin.test.*

class WorkIdentityTest {
    @Test fun copyOwnsItsCloudReceipt() {
        val copy = TransferItem.Write(FileWriteWork(1, "account",
            FileWriteCommand.Copy(listOf("source"), PikoPathBreadcrumb("target", "target")), 0,
            copies = listOf(FileCopyTask("remote", listOf("source")))))
        val rows = resolveWorkIdentities(listOf(TransferItem.Cloud(DriveTask(id = "remote")), copy))
        assertEquals(listOf(copy), rows)
    }

    @Test fun uploadPreparationAndFilesShareOneIdentityAndExecutionLifetime() {
        val task = UploadTask(taskId = "file", account = "account", sourceUri = "file", fileName = "file",
            size = 100, lastModifiedMs = 0, parentId = "target", parentName = "target",
            status = UploadStatus.UPLOADING, batchId = "batch")
        val child = TransferItem.Upload(task)
        val batch = TransferItem.UploadBatch("batch", "folder", "target", "target", listOf(task), true)
        val prep = TransferItem.UploadPreparing(UploadPreparation("batch", "account", "target", "target", 1, 0,
            files = listOf(UploadPreparedFile("source", "file", task.taskId)),
            result = UploadPreparationResult(UploadPreparationCompletion.PARTIAL, "source unavailable", 1)))
        val before = resolveWorkIdentities(listOf(child, batch, prep))
        assertEquals(1, before.size)
        assertEquals(listOf(child), before.single().children)
        assertTrue(before.single().requiresLocalExecution(), "Transfers continue while the parent has an actionable issue")
        val after = resolveWorkIdentities(listOf(batch))
        assertEquals(before.single().key, after.single().key)
        assertFalse(child.copy(task = task.copy(status = UploadStatus.PAUSED)).requiresLocalExecution())
    }
}
