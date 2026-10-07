package dev.piko.ui.screens.instant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.ShareSaveState
import dev.piko.data.repository.isPlayableVideo
import dev.piko.ui.components.FileTypeIcon
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.toReadableSize
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.delay

/**
 * 分享链接的转存面板，放在添加链接面板的输入框下面，替换磁力那一套解析与保存。
 *
 * 逐层浏览：点文件夹的名字进入，复选框勾选；勾选只在当前层有效（原因见 [ShareSaveState]）。
 * 保存位置与磁力共用同一个目标目录。
 */
@Composable
internal fun ColumnScope.ShareSaveSection(
    state: ShareSaveState,
    /** 转存后按番号规范命名，与磁力共用面板的开关，见 [CanonicalNamesToggle]。 */
    canonicalNames: Boolean,
    onCanonicalNamesChange: (Boolean) -> Unit,
    onPreview: (FileStat) -> Unit,
    previewingId: String?,
    onDownload: (FileStat) -> Unit,
) {
    // 与磁力那一套用同一组部件：读取中、出错横幅、保存位置与保存按钮，两种链接看起来是同一个面板
    val info = state.info
    when {
        state.needsPassCode -> PassCodeRow(state)
        info != null -> {
            // 标题与分享者分两行：挤在同一行 titleMedium 里，分享者读起来像标题的一部分
            Column {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                info.owner.nickname.takeIf { it.isNotBlank() }?.let { owner ->
                    Text(
                        text = "分享者 $owner",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.offersCanonicalNames) CanonicalNamesToggle(canonicalNames, enabled = !state.isSaving, onChange = onCanonicalNamesChange)
            // 与磁力的文件列表一样占去剩下的高度，不定死：长分享在面板里自己滚动，短的照常收缩
            ShareBrowser(state, canonicalNames, onPreview, previewingId, onDownload, modifier = Modifier.weight(1f, fill = false))
        }
    }

    if (state.isLoading) ResolvingRow(text = "正在读取分享")
    state.errorMessage?.let { ErrorBanner(message = it, onRetry = null) }
}

/** 保存位置与转存按钮。与磁力的保存栏同处：侧边面板里钉在底部。 */
@Composable
internal fun ShareSaveFooter(state: ShareSaveState, target: PathBreadcrumb?, canonicalNames: Boolean, onPickTarget: () -> Unit) {
    val info = state.info
    state.doneMessage?.let { message ->
        SaveCaption(message)
        LaunchedEffect(message) {
            delay(DONE_MESSAGE_MILLIS)
            state.doneMessage = null
        }
    }

    if (info != null && !state.needsPassCode) {
        TargetRow(target = target, notice = null, enabled = !state.isSaving, onClick = onPickTarget)
        val size = state.selectedBytes.takeIf { it > 0 }?.let { "（${it.toReadableSize()}）" }.orEmpty()
        SaveButton(
            label = if (state.selectedIds.isEmpty()) "勾选要转存的内容" else "转存 ${state.selectedIds.size} 项$size",
            enabled = target != null && state.selectedIds.isNotEmpty(),
            isSaving = state.isSaving,
            onClick = { target?.let { state.save(PikoPathBreadcrumb(it.id, it.name), canonicalNames) } },
        )
    }
}

@Composable
private fun PassCodeRow(state: ShareSaveState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.passCode,
            onValueChange = { state.passCode = it },
            label = { Text("提取码") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.large,
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = state::open, enabled = state.passCode.isNotBlank() && !state.isLoading) { Text("确定") }
    }
}

/** 当前层的列表。上方一行是所在位置，可退回上一层。 */
@Composable
private fun ShareBrowser(
    state: ShareSaveState,
    canonicalNames: Boolean,
    onPreview: (FileStat) -> Unit,
    previewingId: String?,
    onDownload: (FileStat) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.path.isNotEmpty()) {
                    IconButton(onClick = { state.goTo(state.path.size - 1) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "上一层")
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = state.path.lastOrNull()?.name ?: "分享内容",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                if (state.entries.isNotEmpty()) {
                    TextButton(onClick = state::toggleAll) {
                        Text(if (state.selectedIds.size == state.entries.size) "全不选" else "全选")
                    }
                }
            }
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(state.entries, key = { it.id }) { file ->
                    ShareEntryRow(
                        file = file,
                        renamed = if (canonicalNames) state.canonicalPreview(file) else null,
                        checked = file.id in state.selectedIds,
                        onToggle = { state.toggle(file) },
                        onOpen = { state.enter(file) },
                        onPreview = if (file.isPlayableVideo() && file.hash.isNotBlank()) ({ onPreview(file) }) else null,
                        isPreviewing = file.id == previewingId,
                        onDownload = if (!file.isFolder && file.hash.isNotBlank()) ({ onDownload(file) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareEntryRow(
    file: FileStat,
    /** 开着规范命名时转存后的名字，不改时为 null。 */
    renamed: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onPreview: (() -> Unit)?,
    isPreviewing: Boolean,
    onDownload: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (file.isFolder) onOpen else onToggle)
            // 复选框的起点与磁力文件树的行对齐
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        FileTypeIcon(file = file, iconSize = 20.dp, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            // 与磁力的文件树相同：开着规范命名时行上就是存进去的名字
            Text(renamed ?: file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!file.isFolder) {
                Text(
                    file.sizeBytes.toReadableSize(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (file.isFolder) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (onPreview != null) {
            PreviewButton(onClick = onPreview, isPreviewing = isPreviewing)
        }
        if (onDownload != null) DownloadContentButton(onDownload)
    }
}

private const val DONE_MESSAGE_MILLIS = 4_000L
