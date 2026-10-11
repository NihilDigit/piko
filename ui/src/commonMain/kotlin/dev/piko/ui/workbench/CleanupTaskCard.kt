package dev.piko.ui.workbench

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
                        when { session.isUndoing -> "正在撤销清理"; progress != null -> "正在移入回收站"; failure != null -> "清理未完成"; else -> "已移入回收站" },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        when {
                            progress != null -> "${progress.completed} / ${progress.total} 项"
                            failure != null -> "已完成 ${failure.completed} 项，剩余 ${failure.remainingIds.size} 项"
                            else -> "${session.completedCount} 项"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (progress == null && !session.isUndoing) IconButton(onClick = session::dismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "移除清理记录")
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
                        if (availability is dev.piko.shared.presentation.ActionAvailability.Blocked) taskSlot.showBlocked("重试清理", availability.reason) else session.retry()
                    }) { Text("重试剩余项") }
                    if (session.undoChange != null) TextButton(onClick = {
                        session.undoChange?.let { change ->
                            val availability = commands.undoAvailability(change)
                            if (availability is dev.piko.shared.presentation.ActionAvailability.Blocked) taskSlot.showBlocked("撤销", availability.reason) else session.undo()
                        }
                    }) { Text("撤销本次清理") }
                }
            }
        }
    }
}
