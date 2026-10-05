package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.TransferItem
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListMoreButton
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.screens.drive.vaultArchiveStatus
import dev.piko.ui.screens.drive.vaultRestoreStatus

private fun ArchiveJobStatus.label(): String = when (this) {
    ArchiveJobStatus.Waiting -> "排队中"
    ArchiveJobStatus.Submitting -> "正在提交"
    is ArchiveJobStatus.Extracting -> "$progress%"
    is ArchiveJobStatus.NeedsPassword -> if (incorrect) "密码错误" else "需要密码"
}

@Composable
private fun ArchiveJobStatus.color(): Color =
    if (this is ArchiveJobStatus.NeedsPassword) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant

/** 一行开头的类别标签，与云端任务同一种写法。 */
@Composable
private fun KindLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
}

/**
 * 服务端解压的列表项。密码框由 ArchiveExtractHost 在应用最外层弹出，这里不另给输入入口：
 * 待输密码的压缩包一直有框开着，关掉框就是放弃它。
 */
@Composable
internal fun ExtractTransferRow(
    item: TransferItem.Extract,
    onShowInDrive: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    selection: RowSelection = RowSelection.None,
) {
    val status = item.job.status
    FileListItem(
        headline = item.job.file.name,
        onLongClick = selection.onLongClick,
        isSelectionMode = selection.active,
        isSelected = selection.selected,
        onSelectToggle = { selection.onToggle() },
        leading = { ListLeadingIcon(if (status is ArchiveJobStatus.NeedsPassword) Icons.Outlined.Lock else Icons.Outlined.Unarchive) },
        onClick = onShowInDrive,
        onMoreClick = onMoreClick,
        modifier = modifier,
        headlineMaxLines = 1,
        supporting = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindLabel("解压")
                    Text(text = status.label(), color = status.color(), maxLines = 1)
                }
                when (status) {
                    is ArchiveJobStatus.Extracting -> {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { status.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    ArchiveJobStatus.Submitting -> {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    else -> Unit
                }
            }
        },
        trailing = { ListMoreButton(onClick = onMoreClick) },
    )
}

/** 解压的全部操作。提交后的解压在服务端取消不了，只有待输密码的能放弃。 */
internal fun extractTransferActions(item: TransferItem.Extract, onShowInDrive: () -> Unit, onSkip: () -> Unit): List<SheetAction> = buildList {
    add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", onShowInDrive))
    if (item.job.status is ArchiveJobStatus.NeedsPassword) add(SheetAction(Icons.Outlined.Close, "放弃解压", onSkip, destructive = true))
}

@Composable
internal fun ExtractTransferSheet(item: TransferItem.Extract, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val status = item.job.status
    ItemDetailsSheet(
        title = item.job.file.name,
        headerIcon = { ListLeadingIcon(Icons.Outlined.Unarchive) },
        actions = actions,
        onDismiss = onDismiss,
        metaParts = listOf("解压", status.label()),
        extraLines = {
            // 提交前后都可能停在这里，说清解到哪里、为什么不能取消
            Text("解压到压缩包所在的文件夹。已提交的解压由服务端完成，无法中途取消。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

private fun vaultStatus(item: TransferItem.Vault, session: FolderVaultSession): String =
    (if (item.restoring) vaultRestoreStatus(session) else vaultArchiveStatus(session)).orEmpty()

/** 进度比例，总数还没数出来时为 null，画不定进度条。准备与归档各算一半，与浮动卡片相同。 */
private fun vaultProgress(item: TransferItem.Vault, session: FolderVaultSession): Float? {
    if (item.restoring) {
        val progress = session.restoreProgress ?: return null
        val total = progress.total?.takeIf { it > 0 } ?: return null
        return progress.done.toFloat() / total
    }
    val progress = session.progress ?: return null
    if (progress.total <= 0) return null
    return (progress.prepared.toFloat() + progress.done) / (progress.total.toFloat() * 2)
}

private fun TransferItem.Vault.kindLabel(): String = if (restoring) "取消归档" else "归档"

/** 归档或取消归档的列表项。状态读的是进程级会话，进度变化时随之重组。 */
@Composable
internal fun VaultTransferRow(
    item: TransferItem.Vault,
    session: FolderVaultSession,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    selection: RowSelection = RowSelection.None,
) {
    FileListItem(
        headline = item.folderName,
        onLongClick = selection.onLongClick,
        isSelectionMode = selection.active,
        isSelected = selection.selected,
        onSelectToggle = { selection.onToggle() },
        leading = { ListLeadingIcon(if (item.restoring) Icons.Outlined.Unarchive else Icons.Outlined.Inventory2) },
        onClick = onMoreClick,
        onMoreClick = onMoreClick,
        modifier = modifier,
        headlineMaxLines = 1,
        supporting = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindLabel(item.kindLabel())
                    Text(text = vaultStatus(item, session), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                val progress = vaultProgress(item, session)
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TooltipIconButton(Icons.Outlined.Stop, "停止", onClick = session::stop, enabled = !session.stopping)
                ListMoreButton(onClick = onMoreClick)
            }
        },
    )
}

internal fun vaultTransferActions(stopping: Boolean, onStop: () -> Unit): List<SheetAction> =
    if (stopping) emptyList() else listOf(SheetAction(Icons.Outlined.Stop, "停止", onStop))

@Composable
internal fun VaultTransferSheet(
    item: TransferItem.Vault,
    session: FolderVaultSession,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
) {
    ItemDetailsSheet(
        title = item.folderName,
        headerIcon = { ListLeadingIcon(if (item.restoring) Icons.Outlined.Unarchive else Icons.Outlined.Inventory2) },
        actions = actions,
        onDismiss = onDismiss,
        metaParts = listOf(item.kindLabel()),
        extraLines = {
            Text(vaultStatus(item, session), color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 停止不回滚：进行中的文件夹处理完才停，已写成的引用保留
            Text("停止后，已处理完的文件夹保持现状。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
