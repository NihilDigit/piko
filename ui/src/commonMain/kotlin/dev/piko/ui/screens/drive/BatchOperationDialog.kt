package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.DriveClipboard
import dev.piko.shared.state.FileWritePhase
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteWork
import dev.piko.shared.state.TrashSession
import dev.piko.ui.components.PikoDialog

@Composable
internal fun ClipboardBanner(clip: DriveClipboard, canPaste: Boolean, onCancel: () -> Unit, onPaste: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val message = if (clip.cut) {
                tr("已剪切 ${clip.sources.size} 项", "Cut ${enCount(clip.sources.size, "item", "items")}", "${clip.sources.size} 項目を切り取りました", "${clip.sources.size}개 항목 잘라내기 완료")
            } else {
                tr("已复制 ${clip.sources.size} 项", "Copied ${enCount(clip.sources.size, "item", "items")}", "${clip.sources.size} 項目をコピーしました", "${clip.sources.size}개 항목 복사 완료")
            }
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.common_cancel)) }
            TextButton(onClick = onPaste, enabled = canPaste) { Text(stringResource(Res.string.text_paste_here)) }
        }
    }
}

@Composable
internal fun BatchOperationDialog(work: FileWriteWork?, trash: TrashSession, onStopWrite: (Long) -> Unit) {
    val cleanup = trash.progress ?: trash.undoProgress
    if (work == null && cleanup == null && !trash.isUndoing) return
    val stopping = work?.stopping ?: trash.stopping
    val completed = work?.completedCount ?: cleanup?.completed
    val total = work?.totalCount ?: cleanup?.total
    val preparing = work?.phase == FileWritePhase.PREPARING || work?.phase == FileWritePhase.CHECKING
    PikoDialog(
        onDismissRequest = {},
        title = { Text(work?.command?.title ?: if (trash.isUndoing) tr("撤销清理", "Undo cleanup", "整理を元に戻す", "정리 실행 취소") else tr("移入回收站", "Move to trash", "ゴミ箱に移動", "휴지통으로 이동")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (total != null && total > 0 && completed != null && !preparing) {
                    LinearProgressIndicator(progress = { completed.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    val batchCount = (total + 99) / 100
                    val batch = (completed / 100 + 1).coerceAtMost(batchCount)
                    Text(tr("已处理 $completed / $total 项（第 $batch / $batchCount 批）", "Processed $completed / ${enCount(total, "item", "items")} (batch $batch / $batchCount)", "処理済み $completed / $total 項目（$batch / $batchCount バッチ）", "$completed / ${total}개 항목 처리 완료 ($batch / $batchCount 배치)"))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(if (trash.isUndoing && work == null) tr("正在恢复已清理的项目", "Restoring cleaned items", "整理した項目を復元中", "정리된 항목 복원 중") else tr("正在检查待处理项目", "Checking pending items", "未処理の項目を確認中", "처리 대기 항목 확인 중"))
                }
                if (stopping) Text(stringResource(Res.string.text_stop_after_current_batch), style = MaterialTheme.typography.bodySmall)
                if (work?.command is FileWriteCommand.Copy) {
                    Text(stringResource(Res.string.text_stopping_keeps_submitted_cloud_copies_check_their_results_in_tasks), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (work != null) onStopWrite(work.id) else trash.requestStop() }, enabled = !stopping) {
                Text(if (stopping) tr("正在停止", "Stopping", "停止中", "중지 중") else tr("停止", "Stop", "停止", "중지"))
            }
        },
    )
}
