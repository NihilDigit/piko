package dev.piko.ui

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.state.ArchiveCompletion
import dev.piko.shared.state.ArchiveJob
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.FolderVaultSession
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 传输页的行、浮动卡片与 Android 通知共用进度描述，校验归档分阶段进度、停止保位、待确认态与通知合并。
 */
class WorkProgressTest {
    private fun meterOf(work: WorkProgress?) = (work?.meter as WorkMeter.Determinate).fraction
    private fun job(name: String, status: ArchiveJobStatus) = ArchiveJob(FileStat(id = name, name = name), status)

    @Test
    fun unconfirmedCopyKeepsItsResultWithoutAnimating() {
        val work = dev.piko.shared.state.FileWriteWork(
            1,
            "account",
            dev.piko.shared.state.FileWriteCommand.Copy(listOf("file"), dev.piko.shared.data.PikoPathBreadcrumb("target", "target")),
            1,
            result = dev.piko.shared.state.FileWriteResult(
                dev.piko.shared.state.FileWriteCompletion.UNCONFIRMED,
                "请检查实际文件",
                unconfirmed = setOf("file"),
                finishedAtMs = 2,
            ),
        )
        assertEquals("结果待确认", work.workProgress().status)
        assertEquals(WorkMeter.None, work.workProgress().meter)
        assertEquals("请检查实际文件", work.workProgress().detail)
        val undo = DriveChangeJournal.UndoResult(DriveChangeJournal.UndoStatus.RUNNING, "正在撤销已确认部分")
        assertEquals(WorkMeter.Indeterminate, work.workProgress(undo).meter)
        assertEquals("正在撤销已确认部分", work.workProgress(undo).status)
    }

    @Test
    fun retainedVaultResultReflectsTheUndoOfThatSpecificChange() {
        val record = FolderVaultSession.Record(
            FolderVaultSession.Work(1, "folder", true, 1),
            FolderVaultSession.Completion.PARTIAL,
            "恢复未全部完成",
            "已恢复 1 项，归档清单未更新",
            2,
        )
        assertEquals(WorkMeter.None, record.workProgress().meter)
        assertEquals("恢复未全部完成", record.workProgress().status)
        assertEquals(WorkMeter.Indeterminate, record.workProgress(DriveChangeJournal.UndoResult(DriveChangeJournal.UndoStatus.RUNNING, "正在撤销")).meter)
        assertEquals("已撤销", record.workProgress(DriveChangeJournal.UndoResult(DriveChangeJournal.UndoStatus.UNDONE, "已撤销")).status)
        assertEquals("撤销未全部完成", record.workProgress(DriveChangeJournal.UndoResult(DriveChangeJournal.UndoStatus.FAILED, "请重试")).status)
    }

    @Test
    fun archiveProgressMeasuresTheCurrentStage() {
        val preparing = vaultArchiveProgress(FolderVaultSession.Progress("电影", done = 0, total = 40, prepared = 20), stopping = false)
        assertEquals(0.5f, meterOf(preparing))
        assertEquals(WorkMeasure(20, 40, WorkUnit.ITEMS), preparing.measure)
        assertEquals("准备归档 20 / 40 个文件", preparing.status)

        val archiving = vaultArchiveProgress(FolderVaultSession.Progress("电影", done = 30, total = 40, prepared = 40), stopping = false)
        assertEquals(0.75f, meterOf(archiving))
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

    @Test
    fun unconfirmedExtractionHasNoProgressBarAndIsNotReportedAsFailed() {
        val work = job("pack.zip", ArchiveJobStatus.Finished(ArchiveCompletion.UNCONFIRMED, "查询暂不可用", 1)).workProgress()
        assertEquals(WorkMeter.None, work.meter)
        assertEquals("结果待确认", work.status)
        assertEquals("查询暂不可用", work.detail)
        assertEquals(WorkMeter.Indeterminate, job("pack.zip", ArchiveJobStatus.Checking).workProgress().meter)
    }

    @Test
    fun notificationMirrorsTheFirstWorkAndListsTheRest() {
        val vault = vaultArchiveProgress(FolderVaultSession.Progress("电影", 10, 40, 40), stopping = false)
        val extract = job("b.zip", ArchiveJobStatus.Extracting(40)).workProgress()

        val single = workNotificationContent(listOf(vault))
        assertEquals(vault.title, single.title)
        assertEquals(vault.status, single.text)
        assertEquals(vault.meter, single.meter)
        assertTrue(single.lines.isEmpty())

        val both = workNotificationContent(listOf(vault, extract))
        assertEquals("归档「电影」，另有 1 项", both.title)
        assertEquals(WorkMeter.None, both.meter)
        assertEquals(listOf("归档「电影」：${vault.status}", "解压「b.zip」：${extract.status}"), both.lines)
    }
}
