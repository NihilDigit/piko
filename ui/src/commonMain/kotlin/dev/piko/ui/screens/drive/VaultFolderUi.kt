package dev.piko.ui.screens.drive

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import dev.piko.ui.components.TooltipIconButton
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
    var showScope by remember { mutableStateOf(false) }
    val all = survey?.getOrNull()
    val counted = all?.let { if (onlyLargeFiles) it.largeFiles else it }
    val files = counted?.let { if (includeUnsourced) it.files else it.files - it.unsourcedFiles } ?: 0
    val bytes = counted?.let { if (includeUnsourced) it.bytes else it.bytes - it.unsourcedBytes } ?: 0L
    // 两项筛选合起来只说留下了几个：默认留下的几乎都是字幕一类的小文件，绝大多数人不必管为什么
    val leftOut = all?.let { it.files - files } ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("归档文件夹") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                VaultDialogHeading(folder.name, survey, counted?.files, files, bytes, emptyText = "无可归档的文件")
                // 选「移入回收站」也照样醒目：清空回收站是常事，人会想「反正有归档」，清空后只剩引用。
                // 这条风险与原文件去哪无关，不能只在选了永久删除时才说
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("原文件删除后，只能从云端取回", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Text("云端不再保存这份内容时将无法找回。清空回收站同样会删除原文件。", style = MaterialTheme.typography.bodyMedium)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(moveToTrash, { moveToTrash = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("原文件移入回收站") }
                        SegmentedButton(!moveToTrash, { moveToTrash = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("永久删除") }
                    }
                    Text(
                        if (moveToTrash) "清空回收站前仍可找回原文件" else "立即释放空间，原文件无法找回",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (moveToTrash) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                if (all != null && (leftOut > 0 || showScope)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (leftOut > 0) "另有 $leftOut 个文件不归档" else "归档全部文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showScope = !showScope }) { Text(if (showScope) "收起" else "更改") }
                    }
                }
                if (showScope && all != null) {
                    if (all.unsourcedFiles > 0) {
                        VaultCheckboxOption(includeUnsourced, { includeUnsourced = it }, "包括无来源的文件", "自己上传或秒传，失效后无法重新添加")
                    }
                    VaultCheckboxOption(onlyLargeFiles, { onlyLargeFiles = it }, "只归档 50 MiB 以上的文件", null)
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
            ) { Text(if (moveToTrash) "归档" else "归档并删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 两个对话框的开头：文件夹名单行截断，下面是统计结果。名字放进标题的话，长名字一换行，标题成了最吵的东西。 */
@Composable
private fun VaultDialogHeading(name: String, survey: Result<*>?, total: Int?, files: Int, bytes: Long, emptyText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        when {
            survey == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                InlineLoadingIndicator()
                Spacer(Modifier.width(12.dp))
                Text("正在统计", style = MaterialTheme.typography.titleMedium)
            }
            survey.isFailure -> Text("统计失败，请重试", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            total == 0 -> Text(emptyText, style = MaterialTheme.typography.titleMedium)
            else -> Text("$files 个文件，${bytes.toReadableSize()}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * 取消一个文件夹的归档之前的确认。先清点整棵树：多少条，其中几条能从回收站取回原文件，其余要占多少新空间；
 * 放不下时直接说明，不让人点了再失败。
 */
@Composable
internal fun RestoreVaultFolderDialog(
    folder: PikoPathBreadcrumb,
    session: FolderVaultSession,
    onDismiss: () -> Unit,
) {
    val survey by produceState<Result<FolderVaultSession.RestoreSurvey>?>(null, folder.id) { value = session.surveyRestore(folder) }
    val counted = survey?.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("取消归档") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                VaultDialogHeading(folder.name, survey, counted?.entries, counted?.entries ?: 0, counted?.bytes ?: 0L, emptyText = "没有归档的文件")
                if (counted != null && counted.entries > 0) {
                    val parts = buildList {
                        if (counted.fromTrash > 0) add("${counted.fromTrash} 个从回收站取回")
                        if (counted.neededBytes > 0) {
                            add("需占用 ${counted.neededBytes.toReadableSize()}" + counted.remainingBytes?.let { "，剩余 ${it.toReadableSize()}" }.orEmpty())
                        }
                    }
                    if (parts.isNotEmpty()) {
                        Text(
                            parts.joinToString("；"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (counted.fits) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!counted.fits) Text("空间不足", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = counted != null && counted.entries > 0 && counted.fits,
                onClick = {
                    session.restoreFolder(folder)
                    onDismiss()
                },
            ) { Text("恢复到网盘") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VaultCheckboxOption(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, supporting: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    text = when {
                        session.stopping -> STOPPING_TEXT
                        progress.prepared < progress.total -> "准备归档 ${progress.prepared} / ${progress.total}"
                        else -> "归档中 ${progress.done} / ${progress.total}"
                    },
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
            VaultStopButton(session)
        }
    }
}

/** 取消归档的扫描与恢复进度。 */
@Composable
internal fun VaultRestoreStatus(session: FolderVaultSession, modifier: Modifier = Modifier) {
    val progress = session.restoreProgress ?: return
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
                    when {
                        session.stopping -> STOPPING_TEXT
                        progress.total == null -> "${progress.stage}，已扫描 ${progress.scannedFolders} 个文件夹"
                        else -> "${progress.stage} ${progress.done} / ${progress.total}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val total = progress.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { progress.done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
            VaultStopButton(session)
        }
    }
}

private const val STOPPING_TEXT = "正在停止，进行中的文件夹处理完即停"

@Composable
private fun VaultStopButton(session: FolderVaultSession) {
    TooltipIconButton(
        icon = Icons.Outlined.Close,
        label = "停止",
        onClick = session::stop,
        enabled = !session.stopping,
    )
}
