package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.isPlayableVideo
import dev.piko.ui.components.FileTypeIcon
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.metaParts
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

/**
 * 单个条目的操作面板，列表与网格共用。
 *
 * 用模态 BottomSheet 而不是锚在更多按钮上的 DropdownMenu：面板顶部能放下完整、
 * 可选中复制的文件名与元信息，列表里被截成两行的长名字在这里总能看全；操作项的
 * 触控区也按列表项给足。
 *
 * 头部只用类型图标，不放缩略图：从这里绕过防窥遮蔽看到画面不符合用户预期。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileActionsSheet(
    file: FileStat,
    locationLabel: String?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onDownloadSegment: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 先播完收起动画再执行动作：直接移出组合会让面板瞬间消失，
    // 接着弹出的对话框也少了一个视觉上的因果。
    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            FileTypeIcon(
                file = file,
                iconSize = 24.dp,
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                SelectionContainer {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                MetaRow(
                    parts = file.metaParts(includeDate = false),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                modifiedLabel(file)?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!locationLabel.isNullOrEmpty()) {
                    Text(
                        text = locationLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            if (!file.isFolder) {
                SheetAction(Icons.Outlined.Download, "下载到本地") { dismissThen(onDownload) }
                if (file.isPlayableVideo()) {
                    SheetAction(Icons.Outlined.ContentCut, "下载指定段落") { dismissThen(onDownloadSegment) }
                }
            }
            SheetAction(Icons.Outlined.Edit, "重命名") { dismissThen(onRename) }
            SheetAction(Icons.Outlined.DriveFileMove, "移动到") { dismissThen(onMove) }
            SheetAction(Icons.Outlined.Delete, "移入回收站", destructive = true) { dismissThen(onTrash) }
        }
    }
}

private fun modifiedLabel(file: FileStat): String? {
    if (file.modifiedTime.isEmpty()) return null
    // ISO 8601 取到分钟：2024-05-01T12:34:56.789+08:00 -> 2024-05-01 12:34
    return "修改于 " + file.modifiedTime.take(16).replace('T', ' ')
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        onClick = onClick,
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(
            contentColor = color,
            leadingContentColor = if (destructive) color else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        content = { Text(label) },
    )
}
