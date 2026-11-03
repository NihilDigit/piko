package dev.piko.ui.workbench

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TrashSession

/** 两端共用的任务内容。进度只有一份，不随每批完成反复弹提示。 */
@Composable
internal fun CleanupTaskCard(session: TrashSession, modifier: Modifier = Modifier) {
    val services = dev.piko.ui.LocalPikoServices.current
    val taskSlot = LocalTaskSlot.current
    val commands = dev.piko.shared.presentation.FileCommands(services.driveRepository, services.fileWrites, vault = services.folderVaultSession, vaultEdits = services.vaultEntryEdits, shareSaves = services.shareSaves, receiving = services.receivingActivity)
    val progress = session.progress
    val failure = session.failure
    if (progress == null && failure == null && session.completedCount == 0 && !session.isUndoing) return
    ElevatedCard(modifier) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when { session.isUndoing -> tr("正在撤销清理", "Undoing cleanup", "整理を元に戻しています", "정리 실행 취소 중", "正在復原清理"); progress != null -> tr("正在移入回收站", "Moving to trash", "ゴミ箱に移動中", "휴지통으로 이동 중", "正在移至資源回收筒"); failure != null -> tr("清理未完成", "Cleanup incomplete", "整理未完了", "정리 미완료", "清理未完成"); else -> tr("已移入回收站", "Moved to trash", "ゴミ箱に移動しました", "휴지통으로 이동했습니다", "已移至資源回收筒") },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        when {
                            progress != null -> tr("${progress.completed} / ${progress.total} 项", "${progress.completed} / ${enCount(progress.total, "item", "items")}", "${progress.completed} / ${progress.total} 項目", "${progress.completed} / ${progress.total}개 항목", "${progress.completed} / ${progress.total} 項")
                            failure != null -> tr("已完成 ${failure.completed} 项，剩余 ${failure.remainingIds.size} 项", "${failure.completed} completed, ${failure.remainingIds.size} remaining", "${failure.completed} 項目完了、残り ${failure.remainingIds.size} 項目", "${failure.completed}개 완료, ${failure.remainingIds.size}개 남음", "已完成 ${failure.completed} 項，剩餘 ${failure.remainingIds.size} 項")
                            else -> tr("${session.completedCount} 项", "${enCount(session.completedCount, "item", "items")}", "${session.completedCount} 項目", "${session.completedCount}개 항목", "${session.completedCount} 項")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (progress == null && !session.isUndoing) IconButton(onClick = session::dismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = tr("移除清理记录", "Remove cleanup record", "整理の記録を削除", "정리 기록 삭제", "移除清理紀錄"))
                }
            }
            if (session.isUndoing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
            } else if (progress != null) {
                LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (failure != null) TextButton(onClick = {
                        val availability = commands.filesAvailability(failure.remainingIds)
                        if (availability is dev.piko.shared.presentation.ActionAvailability.Blocked) taskSlot.showBlocked(tr("重试清理", "Retry cleanup", "整理を再試行", "정리 다시 시도", "重試清理"), availability.reason) else session.retry()
                    }) { Text(stringResource(Res.string.text_retry_remaining_items)) }
                    if (session.undoChange != null) TextButton(onClick = {
                        session.undoChange?.let { change ->
                            val availability = commands.undoAvailability(change)
                            if (availability is dev.piko.shared.presentation.ActionAvailability.Blocked) taskSlot.showBlocked(tr("撤销", "Undo", "元に戻す", "실행 취소", "復原"), availability.reason) else session.undo()
                        }
                    }) { Text(stringResource(Res.string.text_undo_this_cleanup)) }
                }
            }
        }
    }
}
