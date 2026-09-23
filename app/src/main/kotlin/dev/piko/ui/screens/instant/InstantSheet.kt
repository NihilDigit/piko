package dev.piko.ui.screens.instant

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.PikoApplication
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.state.InstantSheetState
import dev.piko.ui.components.FolderPickerDialog
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalFixedColors

/**
 * 嵌入在 BottomSheet 里的秒传与磁力确认工作台。
 *
 * 状态机在 shared 的 [InstantSheetState]，这里只有 Material 的布局与外观。
 * 这里只保存、不导航：目标目录随回调交给调用方，由它经 DriveScreenState 切过去，
 * 顺带清掉搜索与选中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstantSheetContent(
    initialMagnet: String = "",
    onDismiss: () -> Unit,
    onSuccess: (createdIds: List<String>, targetBread: PathBreadcrumb) -> Unit,
    onOfflineTaskCreated: (targetBread: PathBreadcrumb) -> Unit,
) {
    val app = PikoApplication.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember {
        InstantSheetState(
            instantRepo = app.instantMagnetRepository,
            driveRepo = app.driveRepository,
            preferences = app.sessionManager,
            scope = scope,
            initialMagnet = initialMagnet,
        )
    }
    var showTargetPicker by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        state.outcomes.collect { outcome ->
            when (outcome) {
                is InstantSaveOutcome.InstantSaved -> onSuccess(outcome.createdIds, outcome.target)
                is InstantSaveOutcome.OfflineTaskCreated -> onOfflineTaskCreated(outcome.target)
            }
        }
    }

    val pendingMagnet = state.normalizedMagnet
    val targetBreadcrumb = state.target

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "秒传与磁力直通",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "毫秒级探测云端秒传与离线下载",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.isInputVisible) {
            OutlinedTextField(
                value = state.input,
                onValueChange = state::updateInput,
                label = { Text("磁力链接或下载地址") },
                placeholder = { Text("magnet:?xt=urn:btih:... 或 http://...") },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.largeIncreased,
                maxLines = 2,
                trailingIcon = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                        if (!clip.isNullOrBlank()) state.updateInput(clip.trim())
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "从剪贴板粘贴")
                    }
                },
            )

            // 已经出结果时按钮没有可触发的东西。解析中与解析失败都留着，否则没有重试手段。
            if (state.resolution == null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { if (pendingMagnet != null) state.retryResolve() else state.submitOfflineTask() },
                    enabled = !state.isResolving && !state.isSaving && state.input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (state.isResolving) {
                        PikoLoadingIndicator(size = 20.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在毫秒级探测云端索引...")
                    } else {
                        Icon(
                            imageVector = if (pendingMagnet != null) Icons.Outlined.Bolt else Icons.Outlined.CloudDownload,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (pendingMagnet != null) "重新解析磁力资源" else "提交离线下载")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InstantTargetChip(
                target = targetBreadcrumb,
                enabled = !state.isSaving,
                onClick = { showTargetPicker = true },
            )
            // 外部那条路没有解析按钮，进度只能落在这里
            if (state.isResolving && !state.isInputVisible) {
                Spacer(modifier = Modifier.width(10.dp))
                PikoLoadingIndicator(size = 16.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "正在探测云端索引...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.targetNotice?.let { notice ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.errorMessage?.let { err ->
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        val result = state.resolution
        if (result != null) {
            Spacer(modifier = Modifier.height(14.dp))

            // 资源标题卡片。多文件秒传会以这个名字建一层目录，所以这一行本身就是那个
            // 目录名，直接在原地改，不另起一个输入框。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.willCreateFolder) {
                        val isBlank = state.folderName.isBlank()
                        val nameColor = if (isBlank) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        BasicTextField(
                            value = state.folderName,
                            onValueChange = state::updateFolderName,
                            enabled = !state.isSaving,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = nameColor),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (isBlank) {
                                    Text(
                                        text = "目录名不能为空",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                inner()
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "修改新建目录的名称",
                            tint = nameColor,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Text(
                            text = result.resource.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "待保存文件 (${state.selectedIndices.size} / ${state.items.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(
                    onClick = state::toggleSelectAll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(if (state.isAllSelected) "全不选" else "全选")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(state.items) { index, item ->
                    val isChecked = index in state.selectedIndices
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { state.toggleItem(index) },
                        color = if (isChecked) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked -> state.setItemSelected(index, checked) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = item.file.size.toReadableSize(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Icon(
                                imageVector = if (item.isInstantReady) Icons.Outlined.Check else Icons.Outlined.ErrorOutline,
                                contentDescription = if (item.isInstantReady) "云端已有，可秒传" else "云端没有，需下载",
                                tint = if (item.isInstantReady) {
                                    LocalFixedColors.current.InstantMatchGreen
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = state::saveSelection,
                enabled = state.canSaveSelection,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (state.isSaving) {
                    PikoLoadingIndicator(size = 18.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("保存中...")
                } else {
                    Icon(
                        imageVector = if (state.canInstantSaveAll) Icons.Outlined.Bolt else Icons.Outlined.CloudDownload,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("保存 ${state.selectedItems.size} 项到 ${targetBreadcrumb?.name.orEmpty()}")
                }
            }
        } else if (pendingMagnet != null) {
            // 云端没有收录时，磁力本身仍然可以直接交给离线下载。非磁力的输入不在这里出口：
            // 顶部那个按钮已经是它唯一的提交入口，两个一样的按钮只会让人犹豫点哪个。
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = state::submitOfflineTask,
                enabled = !state.isSaving && !state.isResolving && targetBreadcrumb != null,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (state.isSaving) {
                    PikoLoadingIndicator(size = 18.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("保存中...")
                } else {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("保存到 ${targetBreadcrumb?.name.orEmpty()}")
                }
            }
        }
    }

    if (showTargetPicker) {
        FolderPickerDialog(
            title = "选择保存位置",
            confirmLabel = "存到这里",
            onDismiss = { showTargetPicker = false },
            onConfirm = { targetId, targetName ->
                showTargetPicker = false
                state.changeTarget(PathBreadcrumb(targetId, targetName))
            },
        )
    }
}

/** 保存位置胶囊。目标还没取到时不可点，也不拿 My Packs 顶替，免得闪一个可能是错的名字。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstantTargetChip(
    target: PathBreadcrumb?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled && target != null,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (target == null) "正在确认保存位置" else "目标：${target.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (target != null) {
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "更换保存位置",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
