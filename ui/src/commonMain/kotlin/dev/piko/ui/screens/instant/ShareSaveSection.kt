package dev.piko.ui.screens.instant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
internal fun ShareSaveSection(
    state: ShareSaveState,
    target: PathBreadcrumb?,
    onPickTarget: () -> Unit,
) {
    val info = state.info
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            state.needsPassCode -> PassCodeRow(state)
            info != null -> {
                Text(
                    text = listOf(info.title, info.owner.nickname.takeIf { it.isNotBlank() }?.let { "分享者 $it" })
                        .filterNotNull().joinToString("  "),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ShareBrowser(state)
            }
        }

        if (state.isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InlineLoadingIndicator()
                Spacer(Modifier.width(12.dp))
                Text("正在读取分享", style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        state.doneMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            LaunchedEffect(message) {
                delay(DONE_MESSAGE_MILLIS)
                state.doneMessage = null
            }
        }

        if (info != null && !state.needsPassCode) {
            TargetRow(target = target, notice = null, enabled = !state.isSaving, onClick = onPickTarget)
            Button(
                onClick = { target?.let { state.save(PikoPathBreadcrumb(it.id, it.name)) } },
                enabled = target != null && state.selectedIds.isNotEmpty() && !state.isSaving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (state.isSaving) {
                    InlineLoadingIndicator(color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                    Text("正在转存")
                } else {
                    val size = state.selectedBytes.takeIf { it > 0 }?.let { "（${it.toReadableSize()}）" }.orEmpty()
                    Text(if (state.selectedIds.isEmpty()) "勾选要转存的内容" else "转存 ${state.selectedIds.size} 项$size")
                }
            }
        }
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

/** 当前层的列表。上方一行是所在位置，可退回上一层。列表限高，长分享在面板里自己滚动。 */
@Composable
private fun ShareBrowser(state: ShareSaveState) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
            LazyColumn(modifier = Modifier.heightIn(max = BROWSER_MAX_HEIGHT)) {
                items(state.entries, key = { it.id }) { file ->
                    ShareEntryRow(
                        file = file,
                        checked = file.id in state.selectedIds,
                        onToggle = { state.toggle(file) },
                        onOpen = { state.enter(file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareEntryRow(file: FileStat, checked: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (file.isFolder) onOpen else onToggle)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        FileTypeIcon(file = file, iconSize = 20.dp, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        }
    }
}

private val BROWSER_MAX_HEIGHT = 360.dp
private const val DONE_MESSAGE_MILLIS = 4_000L
