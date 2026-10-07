package dev.piko.ui

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.state.ArchiveJob
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.upload.UploadStatus
import dev.piko.shared.upload.UploadTask
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 传输页的行、浮动卡片与 Android 通知都读这一份描述，这里守的是会让它们一起说错的几处：
 * 进度条停住不动、停止时进度条跳空、几件并行时取错了眼下那一件、空闲的任务被算成在跑。
 */
class WorkProgressTest {
    private fun meterOf(work: WorkProgress?) = (work?.meter as WorkMeter.Determinate).fraction

    @Test
    fun archivePreparationMovesTheBarBeforeAnyFolderIsDone() {
        // 取样内容标识要几分钟，这段时间 done 一直是 0；只按 done 算的话进度条停在起点
        val preparing = vaultArchiveProgress(FolderVaultSession.Progress("电影", done = 0, total = 40, prepared = 20), stopping = false)
        assertEquals(0.25f, meterOf(preparing))
        assertEquals("准备归档 20 / 40 个文件", preparing.status)

        val archiving = vaultArchiveProgress(FolderVaultSession.Progress("电影", done = 30, total = 40, prepared = 40), stopping = false)
        assertEquals(0.875f, meterOf(archiving))
        assertEquals("已归档 30 / 40 个文件", archiving.status)
    }

    @Test
    fun stoppingKeepsTheBarWhereItWas() {
        val running = vaultArchiveProgress(FolderVaultSession.Progress("电影", 10, 40, 40), stopping = false)
        val stopping = vaultArchiveProgress(FolderVaultSession.Progress("电影", 10, 40, 40), stopping = true)
        assertEquals(running.meter, stopping.meter)
        assertEquals("正在停止", stopping.status)
        assertTrue(stopping.detail.orEmpty().isNotEmpty())
    }

    @Test
    fun restoreIsIndeterminateUntilTheTreeIsScanned() {
        val scanning = vaultRestoreProgress(FolderVaultSession.RestoreProgress("剧集", scannedFolders = 12), stopping = false)
        assertEquals(WorkMeter.Indeterminate, scanning.meter)
        assertEquals("已扫描 12 个文件夹", scanning.detail)

        val checking = vaultRestoreProgress(
            FolderVaultSession.RestoreProgress("剧集", stage = "正在检查恢复空间", done = 0, total = 8),
            stopping = false,
        )
        assertEquals(0f, meterOf(checking))
        assertEquals("正在检查恢复空间", checking.detail)

        val restoring = vaultRestoreProgress(FolderVaultSession.RestoreProgress("剧集", stage = "正在恢复到网盘", done = 2, total = 8), stopping = false)
        assertEquals(0.25f, meterOf(restoring))
        assertNull(restoring.detail)
    }

    private fun job(name: String, status: ArchiveJobStatus) = ArchiveJob(FileStat(id = name, name = name), status)

    @Test
    fun severalArchivesFollowTheOneBeingExtracted() {
        val jobs = listOf(
            job("a.rar", ArchiveJobStatus.NeedsPassword(incorrect = false)),
            job("b.zip", ArchiveJobStatus.Extracting(40)),
            job("c.7z", ArchiveJobStatus.Waiting),
        )
        val work = jobs.extractProgress()!!
        assertEquals("解压 3 个压缩包", work.title)
        assertEquals(0.4f, meterOf(work))
        assertEquals("b.zip，另有 1 个需要密码", work.detail)
    }

    @Test
    fun onlyPasswordPromptsLeftShowsNoBar() {
        val work = listOf(job("a.rar", ArchiveJobStatus.NeedsPassword(incorrect = true))).extractProgress()!!
        assertEquals(WorkMeter.None, work.meter)
        assertEquals("密码错误", work.status)
    }

    private fun download(name: String, status: DownloadStatus, done: Long = 0, total: Long = 100, speed: Long = 0) =
        DownloadTask(taskId = name, fileId = name, fileName = name, gcid = "", totalBytes = total, downloadedBytes = done, speedBytesPerSec = speed, status = status)

    private fun upload(name: String, status: UploadStatus, done: Long = 0, size: Long = 100) = UploadTask(
        taskId = name, account = "me", sourceUri = name, fileName = name, size = size, lastModifiedMs = 0,
        parentId = "p", parentName = "p", status = status, processedBytes = done,
    )

    @Test
    fun transfersCountOnlyWhatIsMoving() {
        // 暂停与等待中的不传字节，算进总量会让进度条与剩余时间都失真
        val work = transferProgress(
            listOf(download("a.mp4", DownloadStatus.DOWNLOADING, done = 50), download("b.mp4", DownloadStatus.PAUSED, done = 0)),
            emptyList(),
        )!!
        assertEquals("a.mp4", work.title)
        assertEquals(0.5f, meterOf(work))
        assertNull(transferProgress(listOf(download("a.mp4", DownloadStatus.PENDING)), emptyList()))
    }

    @Test
    fun mixedTransfersNameBothKinds() {
        val work = transferProgress(
            listOf(download("a.mp4", DownloadStatus.DOWNLOADING)),
            listOf(upload("b.mkv", UploadStatus.UPLOADING), upload("c.mkv", UploadStatus.QUEUED)),
        )!!
        assertEquals("下载 1 个文件，上传 2 个文件", work.title)
        assertEquals(WorkKind.DOWNLOAD, work.kind)
        assertEquals(WorkKind.UPLOAD, transferProgress(emptyList(), listOf(upload("b.mkv", UploadStatus.UPLOADING)))!!.kind)
    }

    @Test
    fun segmentsWithoutSizeAreIndeterminate() {
        val work = transferProgress(listOf(download("clip.mp4", DownloadStatus.DOWNLOADING, total = 0)), emptyList())!!
        assertEquals(WorkMeter.Indeterminate, work.meter)
    }

    @Test
    fun notificationMirrorsTheFirstWorkAndListsTheRest() {
        val vault = vaultArchiveProgress(FolderVaultSession.Progress("电影", 10, 40, 40), stopping = false)
        val extract = listOf(job("b.zip", ArchiveJobStatus.Extracting(40))).extractProgress()!!

        val single = workNotificationContent(listOf(vault))
        assertEquals(vault.title, single.title)
        assertEquals(vault.status, single.text)
        assertEquals(vault.meter, single.meter)
        assertTrue(single.lines.isEmpty())

        val both = workNotificationContent(listOf(vault, extract))
        assertEquals("归档「电影」，另有 1 项", both.title)
        assertEquals(vault.meter, both.meter)
        assertEquals(listOf("归档「电影」：${vault.status}", "解压「b.zip」：${extract.status}"), both.lines)
    }
}
