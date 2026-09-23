package dev.piko.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.desktop.ui.components.formatBytes
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.FolderPickerState
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.state.InstantSheetState
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Button
import io.github.composefluent.component.CheckBox
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.DialogSize
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Checkmark
import io.github.composefluent.icons.regular.ChevronRight
import io.github.composefluent.icons.regular.CloudArrowDown
import io.github.composefluent.icons.regular.Folder

/**
 * 秒传与磁力解析对话框。状态机与 Android 的秒传面板共用 [InstantSheetState]，
 * 这里只有 Fluent 的布局。
 *
 * 选保存位置不另弹一层对话框，而是在同一个对话框里切到目录浏览：两层 ContentDialog
 * 叠在一起时，底下那层的按钮仍可点，关闭顺序也难以对应。
 */
@Composable
fun InstantDialog(
    instantRepository: InstantMagnetRepository,
    driveRepository: PikoDriveRepository,
    preferences: PikoUserPreferences,
    initialMagnet: String,
    onDismiss: () -> Unit,
    onSaved: (InstantSaveOutcome) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember {
        InstantSheetState(instantRepository, driveRepository, preferences, scope, initialMagnet)
    }
    var pickingTarget by remember { mutableStateOf(false) }
    // 每次进入选择都从根目录开始，与 Android 的选择器一致
    val picker = remember(pickingTarget) {
        if (pickingTarget) FolderPickerState(driveRepository, scope) else null
    }

    LaunchedEffect(state) {
        state.outcomes.collect(onSaved)
    }

    val primaryText = when {
        picker != null -> "存到这里"
        state.isSaving -> "保存中…"
        state.resolution != null -> "保存 ${state.selectedItems.size} 项到 ${state.target?.name.orEmpty()}"
        else -> "提交离线下载"
    }

    ContentDialog(
        title = if (picker != null) "选择保存位置" else "秒传与磁力解析",
        visible = true,
        size = DialogSize.Max,
        primaryButtonText = primaryText,
        secondaryButtonText = if (picker != null) "返回" else null,
        closeButtonText = "取消",
        onButtonClick = { button ->
            when (button) {
                ContentDialogButton.Primary -> when {
                    picker != null -> {
                        state.changeTarget(picker.current)
                        pickingTarget = false
                    }
                    state.resolution != null -> if (state.canSaveSelection) state.saveSelection()
                    // 解析进行中按下去没有意义；解析失败或非磁力链接才整条交给离线
                    !state.isResolving && state.target != null -> state.submitOfflineTask()
                }
                ContentDialogButton.Secondary -> pickingTarget = false
                ContentDialogButton.Close -> if (!state.isSaving) onDismiss()
            }
        },
        content = {
            if (picker != null) {
                FolderPickerContent(picker)
            } else {
                InstantContent(state, onChangeTarget = { pickingTarget = true })
            }
        },
    )
}

@Composable
private fun InstantContent(state: InstantSheetState, onChangeTarget: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextField(
            value = state.input,
            onValueChange = state::updateInput,
            placeholder = { Text("粘贴磁力链接、40 位哈希或 http/https 下载地址…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Regular.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = state.target?.let { "保存到：${it.name}" } ?: "正在确认保存位置…",
                style = FluentTheme.typography.body,
            )
            SubtleButton(
                onClick = onChangeTarget,
                disabled = state.target == null || state.isSaving,
            ) {
                Text("更改位置")
            }
            if (state.isResolving) {
                ProgressRing(size = ProgressRingSize.Small)
                Text(
                    text = "正在探测云端索引…",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        }

        state.targetNotice?.let {
            Text(it, style = FluentTheme.typography.caption, color = FluentTheme.colors.system.caution)
        }

        state.errorMessage?.let { message ->
            InfoBar(
                title = { Text("提示") },
                message = { Text(message) },
                severity = InfoBarSeverity.Warning,
                modifier = Modifier.fillMaxWidth(),
                action = if (state.normalizedMagnet != null && !state.isResolving) {
                    { Button(onClick = state::retryResolve) { Text("重新解析") } }
                } else {
                    null
                },
            )
        }

        val resolution = state.resolution ?: return@Column

        if (state.willCreateFolder) {
            TextField(
                value = state.folderName,
                onValueChange = state::updateFolderName,
                header = { Text("新建目录名（多个文件会存进这一层目录）") },
                singleLine = true,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = resolution.resource.name,
                style = FluentTheme.typography.bodyStrong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "已选 ${state.selectedIndices.size} / ${state.items.size}",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
            )
            SubtleButton(onClick = state::toggleSelectAll) {
                Text(if (state.isAllSelected) "全不选" else "全选")
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            itemsIndexed(state.items) { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { state.toggleItem(index) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckBox(
                        checked = index in state.selectedIndices,
                        onCheckStateChange = { state.setItemSelected(index, it) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = formatBytes(item.file.size),
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.text.text.secondary,
                        )
                    }
                    Icon(
                        imageVector = if (item.isInstantReady) Icons.Regular.Checkmark else Icons.Regular.CloudArrowDown,
                        contentDescription = if (item.isInstantReady) "云端已有，可秒传" else "云端没有，需离线下载",
                        tint = if (item.isInstantReady) FluentTheme.colors.system.success else FluentTheme.colors.text.text.secondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // 整单离线的取舍在 InstantSheetState 里有说明，这里只把后果告诉用户
        if (state.selectedItems.isNotEmpty() && !state.canInstantSaveAll) {
            Text(
                text = "勾选项中有云端未收录的文件，整条链接将提交为离线任务",
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.system.caution,
            )
        }
    }
}

@Composable
private fun FolderPickerContent(picker: FolderPickerState) {
    var newFolderName by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(picker) {
        picker.messages.collect { notice = it }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            picker.path.forEachIndexed { index, crumb ->
                if (index > 0) {
                    Icon(Icons.Regular.ChevronRight, contentDescription = null, modifier = Modifier.size(12.dp))
                }
                if (index == picker.path.lastIndex) {
                    Text(crumb.name, style = FluentTheme.typography.bodyStrong, modifier = Modifier.padding(horizontal = 8.dp))
                } else {
                    SubtleButton(onClick = { picker.navigateTo(index) }) { Text(crumb.name) }
                }
            }
        }

        (notice ?: picker.loadError?.let { "加载失败：$it" })?.let { message ->
            InfoBar(
                title = { Text("提示") },
                message = { Text(message) },
                severity = InfoBarSeverity.Critical,
                modifier = Modifier.fillMaxWidth(),
                action = { Button(onClick = { notice = null; picker.reload() }) { Text("重试") } },
            )
        }

        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)) {
            if (picker.isLoading) {
                ProgressRing(size = ProgressRingSize.Medium, modifier = Modifier.align(Alignment.Center))
            } else if (picker.folders.isEmpty()) {
                Text(
                    text = "这里没有子文件夹，可以直接存到这里",
                    color = FluentTheme.colors.text.text.secondary,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(picker.folders, key = { it.id }) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picker.open(folder) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Regular.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(Icons.Regular.ChevronRight, contentDescription = null, modifier = Modifier.size(12.dp))
                        }
                    }
                    if (picker.hasMore) {
                        item {
                            SubtleButton(onClick = picker::loadMore, disabled = picker.isLoadingMore) {
                                Text(if (picker.isLoadingMore) "加载中…" else "加载更多")
                            }
                        }
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = newFolderName,
                onValueChange = { newFolderName = it },
                placeholder = { Text("新建文件夹") },
                singleLine = true,
                modifier = Modifier.width(260.dp),
            )
            Button(
                onClick = {
                    picker.createFolder(newFolderName)
                    newFolderName = ""
                },
                disabled = newFolderName.isBlank() || picker.isCreatingFolder,
            ) {
                Text("新建并进入")
            }
        }
    }
}
