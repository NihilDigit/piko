package dev.piko.shared.state

data class ActivitySummary(
    val active: List<TransferItem>,
    val needsAttention: List<TransferItem>,
    val paused: List<TransferItem>,
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val executionRequired: Boolean = false,
)

/** A composed job owns its server tasks; the server listing is a fallback. */
internal fun resolveWorkIdentities(items: List<TransferItem>): List<TransferItem> {
    val preparations = items.filterIsInstance<TransferItem.UploadPreparing>().associateBy { it.work.id }
    val batches = items.filterIsInstance<TransferItem.UploadBatch>().associateBy { it.batchId }
    val ownedUploads = preparations.values.flatMap { it.work.files.map { file -> file.taskId } }.toSet()
    val owners = items.filterNot { item -> item is TransferItem.Cloud ||
        item is TransferItem.UploadBatch && item.batchId in preparations ||
        item is TransferItem.Upload && item.task.taskId in ownedUploads
    }.map { item ->
        if (item is TransferItem.UploadPreparing) item.copy(children = batches[item.work.id]?.children
            ?: items.filterIsInstance<TransferItem.Upload>().filter { child -> item.work.files.any { it.taskId == child.task.taskId } })
        else item
    }
    val remoteIds = owners.flatMapTo(HashSet()) { it.remoteTaskIds }
    return (owners + items.filterIsInstance<TransferItem.Cloud>().filter { it.task.id !in remoteIds })
        .associateBy { it.key }.values.toList()
}

fun TransferItem.requiresLocalExecution(): Boolean = when (this) {
    is TransferItem.Cloud, is TransferItem.Instant -> false
    is TransferItem.Pack -> isBusy || job.isActive
    is TransferItem.Write -> work.isRunning
    is TransferItem.Share -> work.isRunning
    is TransferItem.OfflineSubmit -> work.isRunning
    is TransferItem.InstantAttempt -> work.isRunning
    is TransferItem.Extract -> job.status !is ArchiveJobStatus.Finished && job.status !is ArchiveJobStatus.NeedsPassword
    is TransferItem.Vault -> record == null
    is TransferItem.Local -> task.status == dev.piko.download.DownloadStatus.DOWNLOADING || task.status == dev.piko.download.DownloadStatus.PENDING
    is TransferItem.LocalBatch -> listing?.isListing == true || children.any { it.requiresLocalExecution() }
    is TransferItem.Upload -> task.status in setOf(dev.piko.shared.upload.UploadStatus.QUEUED, dev.piko.shared.upload.UploadStatus.HASHING, dev.piko.shared.upload.UploadStatus.UPLOADING)
    is TransferItem.UploadBatch -> children.any { it.requiresLocalExecution() }
    is TransferItem.UploadPreparing -> work.isRunning || children.any { it.requiresLocalExecution() }
}
