package dev.piko.ui.screens.drive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.piko.shared.state.VaultArchiveOptions
import dev.piko.ui.LocalPikoServices
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
 *
 * 丢失的风险只在这里讲清楚。考虑过给回收站里的原件打标记、清空时再提醒，否决了：官方客户端照样能清空回收站，
 * Piko 拦不住，拦一半只会让人以为有保护。
 */
@Composable
internal fun VaultFolderDialog(
    folder: PikoPathBreadcrumb,
    session: FolderVaultSession,
    onDismiss: () -> Unit,
) {
    val preferences = LocalPikoServices.current.preferences
    val survey by produceState<Result<FolderVaultSession.Survey>?>(null, folder.id) { value = session.survey(folder) }
    // 读出上次的选择之前不画勾选，免得先按默认值画出来再跳
    var options by remember { mutableStateOf<VaultArchiveOptions?>(null) }
    LaunchedEffect(Unit) { options = VaultArchiveOptions.load(preferences) }
    val scope = rememberCoroutineScope()
    val chosen = options ?: VaultArchiveOptions()
    val all = survey?.getOrNull()
    val counted = all?.let { if (chosen.skipSmallFiles) it.largeFiles else it }
    val files = counted?.let { if (chosen.sourcedOnly) it.files - it.unsourcedFiles else it.files } ?: 0
    val bytes = counted?.let { if (chosen.sourcedOnly) it.bytes - it.unsourcedBytes else it.bytes } ?: 0L
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text("归档文件夹") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VaultDialogHeading(folder.name, survey, counted?.files, files, bytes, emptyText = "无可归档的文件")
                // 原文件放进回收站也一样醒目：清空回收站是常事，人会想「反正有归档」，清空后只剩引用
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
                val current = options
                if (current != null) {
                    // 是偏好而不是这一次的参数：一改就存，取消归档也记住，与设置页的开关相同
                    fun update(next: VaultArchiveOptions) {
                        options = next
                        scope.launch { VaultArchiveOptions.save(preferences, next) }
                    }
                    val unsourced = all?.unsourcedFiles ?: 0
                    VaultCheckboxOption(
                        checked = current.sourcedOnly,
                        onCheckedChange = { update(current.copy(sourcedOnly = it)) },
                        label = "仅归档有来源记录的文件",
                        supporting = when {
                            !current.sourcedOnly -> "自己上传或秒传的文件失效后无法重新添加"
                            unsourced > 0 -> "$unsourced 个无来源记录的文件留在网盘"
                            else -> "无来源记录的文件留在网盘"
                        },
                    )
                    VaultCheckboxOption(
                        checked = current.toTrash,
                        onCheckedChange = { update(current.copy(toTrash = it)) },
                        label = "原文件放入回收站",
                        supporting = if (current.toTrash) "清空回收站前仍可找回原文件" else "原文件将永久删除",
                    )
                    VaultCheckboxOption(
                        checked = current.skipSmallFiles,
                        onCheckedChange = { update(current.copy(skipSmallFiles = it)) },
                        label = "不归档小文件",
                        supporting = "50 MiB 以下的文件留在网盘",
                    )
                }
            }
        },
        confirmButton = {
            // 永久删除原文件时按钮换成错误色。复选框下的说明不染红：M3 的错误色只给校验出错，取消勾选不是错；
            // 在警告框里另加一行红字也试过，像补丁
            PikoDialogConfirm(
                label = "归档",
                destructive = !chosen.toTrash,
                enabled = files > 0 && options != null,
                onClick = {
                    session.archive(folder, includeUnsourced = !chosen.sourcedOnly, onlyLargeFiles = chosen.skipSmallFiles, moveToTrash = chosen.toTrash)
                    onDismiss()
                },
            )
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
    PikoDialog(
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
            PikoDialogConfirm(
                label = "恢复到网盘",
                enabled = counted != null && counted.entries > 0 && counted.fits,
                onClick = {
                    session.restoreFolder(folder)
                    onDismiss()
                },
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VaultCheckboxOption(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    supporting: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            // 说明写的是勾或不勾的后果，做决定要看，与标签同一档字号，不缩成附注
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val STOPPING_TEXT = "正在停止，进行中的文件夹处理完即停"

/** 归档进度的一行状态，传输页与浮动任务卡片共用。没有在归档时为 null。 */
internal fun vaultArchiveStatus(session: FolderVaultSession): String? {
    val progress = session.progress ?: return null
    return when {
        session.stopping -> STOPPING_TEXT
        progress.prepared < progress.total -> "准备归档 ${progress.prepared} / ${progress.total}"
        else -> "归档中 ${progress.done} / ${progress.total}"
    }
}

/** 取消归档进度的一行状态。 */
internal fun vaultRestoreStatus(session: FolderVaultSession): String? {
    val progress = session.restoreProgress ?: return null
    return when {
        session.stopping -> STOPPING_TEXT
        progress.total == null -> "${progress.stage}，已扫描 ${progress.scannedFolders} 个文件夹"
        else -> "${progress.stage} ${progress.done} / ${progress.total}"
    }
}
