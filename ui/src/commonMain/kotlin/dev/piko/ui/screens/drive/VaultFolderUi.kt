package dev.piko.ui.screens.drive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.FolderVaultSession
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.toReadableSize

/**
 * 归档一个文件夹之前的确认。先清点整棵树：多少文件、多大，其中没有来源记录的（自己上传、秒传）单独计，
 * 默认不带上：它们没有磁力或分享链接可凭，PikPak 不再保存时就找不回来。
 */
@Composable
internal fun VaultFolderDialog(
    folder: PikoPathBreadcrumb,
    session: FolderVaultSession,
    onDismiss: () -> Unit,
) {
    val survey by produceState<Result<FolderVaultSession.Survey>?>(null, folder.id) { value = session.survey(folder) }
    var includeUnsourced by remember { mutableStateOf(false) }
    var onlyLargeFiles by remember { mutableStateOf(false) }
    var moveToTrash by remember { mutableStateOf(true) }
    val counted = survey?.getOrNull()?.let { if (onlyLargeFiles) it.largeFiles else it }
    val files = counted?.let { if (includeUnsourced) it.files else it.files - it.unsourcedFiles } ?: 0
    val bytes = counted?.let { if (includeUnsourced) it.bytes else it.bytes - it.unsourcedBytes } ?: 0L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("归档「${folder.name}」") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    survey == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        InlineLoadingIndicator()
                        Spacer(Modifier.width(12.dp))
                        Text("正在统计文件")
                    }
                    counted == null -> Text("统计失败，请重试")
                    counted.files == 0 -> Text("无可归档的文件")
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("$files 个文件 · ${bytes.toReadableSize()}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("文件仍在原目录显示，打开时从云端取回。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
                ) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("仅保存引用，文件可能无法找回", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                            Text("归档不保存文件内容。云端不再保存对应内容时，将无法播放、下载或恢复。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            Text("建议用于很少使用的合集文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                VaultCheckboxOption(
                    checked = onlyLargeFiles,
                    onCheckedChange = { onlyLargeFiles = it },
                    label = "只归档大文件",
                    supporting = "至少 50 MiB，小文件留在网盘中",
                )
                if (counted != null && counted.unsourcedFiles > 0) {
                    VaultCheckboxOption(
                        checked = includeUnsourced,
                        onCheckedChange = { includeUnsourced = it },
                        label = "包含无来源记录的文件",
                        supporting = "${counted.unsourcedFiles} 个文件 · ${counted.unsourcedBytes.toReadableSize()}。云端内容失效后，无法通过来源链接重新添加。",
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                VaultCheckboxOption(
                    checked = moveToTrash,
                    onCheckedChange = { moveToTrash = it },
                    label = "将原文件移入回收站",
                    supporting = if (moveToTrash) "保留 15 天，期间仍占用网盘空间" else "直接删除原文件，释放网盘空间",
                )
                if (!moveToTrash) {
                    Text("原文件将永久删除，回收站中不会保留副本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = files > 0,
                onClick = {
                    session.archive(folder, includeUnsourced, onlyLargeFiles, moveToTrash)
                    onDismiss()
                },
            ) { Text(if (moveToTrash) "归档" else "归档并删除原文件") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VaultCheckboxOption(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, supporting: String) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 归档进行中的状态条，与解压的状态条同处。没有在归档时不占位。 */
@Composable
internal fun VaultFolderStatus(session: FolderVaultSession, modifier: Modifier = Modifier) {
    val progress = session.progress ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineLoadingIndicator()
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = progress.folderName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (progress.prepared < progress.total) "准备归档 ${progress.prepared} / ${progress.total}"
                        else "归档中 ${progress.done} / ${progress.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { (progress.prepared.toFloat() + progress.done) / (progress.total.toFloat() * 2) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** 取消归档的扫描、恢复与清单提交进度。 */
@Composable
internal fun VaultRestoreStatus(progress: DriveScreenState.VaultRestoreProgress?, modifier: Modifier = Modifier) {
    progress ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineLoadingIndicator()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(progress.folderName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (progress.total == null) "${progress.stage} · 已扫描 ${progress.scannedFolders} 个文件夹"
                    else "${progress.stage} · ${progress.done} / ${progress.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress.fileName?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val total = progress.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { progress.done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
    }
}
