package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
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
    val counted = survey?.getOrNull()
    val files = counted?.let { if (includeUnsourced) it.files else it.files - it.unsourcedFiles } ?: 0
    val bytes = counted?.let { if (includeUnsourced) it.bytes else it.bytes - it.unsourcedBytes } ?: 0L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("归档「${folder.name}」") },
        text = {
            Column {
                when {
                    survey == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        InlineLoadingIndicator()
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("统计中")
                    }
                    counted == null -> Text("统计失败，请重试")
                    counted.files == 0 -> Text("无可归档的文件")
                    else -> {
                        // 免费账号的回收站照样占空间，原文件直接删除，这一点要说在前面
                        val originals = if (counted.deletesOriginals) "原文件随即删除并释放空间" else "原文件移入回收站，保留 15 天"
                        Text(
                            "$files 个文件（${bytes.toReadableSize()}）将转为归档记录，$originals。" +
                                "归档文件仍在原位显示，打开时自云端获取；云端不再保存时无法恢复。",
                        )
                        if (counted.unsourcedFiles > 0) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                                    .toggleable(value = includeUnsourced, role = Role.Checkbox) { includeUnsourced = it },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = includeUnsourced, onCheckedChange = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                val risk = if (counted.deletesOriginals) "删除后可能无法找回" else "更难恢复"
                                Text(
                                    "包含 ${counted.unsourcedFiles} 个无来源记录的文件（${counted.unsourcedBytes.toReadableSize()}）。" +
                                        "此类文件多为本地上传或秒传，$risk。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = files > 0,
                onClick = {
                    session.archive(folder, includeUnsourced)
                    onDismiss()
                },
            ) { Text("归档") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
                    text = "归档中 ${progress.done} / ${progress.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.done.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
