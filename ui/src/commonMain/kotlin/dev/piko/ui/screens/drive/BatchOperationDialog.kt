package dev.piko.ui.screens.drive

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
            Text("已${if (clip.cut) "剪切" else "复制"} ${clip.sources.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text("取消") }
            TextButton(onClick = onPaste, enabled = canPaste) { Text("粘贴到此处") }
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
        title = { Text(work?.command?.title ?: if (trash.isUndoing) "撤销清理" else "移入回收站") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (total != null && total > 0 && completed != null && !preparing) {
                    LinearProgressIndicator(progress = { completed.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    val batchCount = (total + 99) / 100
                    val batch = (completed / 100 + 1).coerceAtMost(batchCount)
                    Text("已处理 $completed / $total 项（第 $batch / $batchCount 批）")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(if (trash.isUndoing && work == null) "正在恢复已清理的项目" else "正在检查待处理项目")
                }
                if (stopping) Text("当前批次完成后停止", style = MaterialTheme.typography.bodySmall)
                if (work?.command is FileWriteCommand.Copy) {
                    Text("停止会保留已提交的云端复制，可在任务页继续查询结果", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (work != null) onStopWrite(work.id) else trash.requestStop() }, enabled = !stopping) {
                Text(if (stopping) "正在停止" else "停止")
            }
        },
    )
}
