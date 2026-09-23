package dev.piko.ui.screens.instant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.label
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.NameGroup
import dev.piko.shared.state.NameLeaf
import dev.piko.shared.state.InstantActionKind
import dev.piko.shared.state.InstantPrimaryAction
import dev.piko.shared.state.NameGroupSummary
import dev.piko.ui.components.FileNameField
import dev.piko.ui.components.FolderPickerDialog
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.fileNameTypeIcon
import dev.piko.ui.components.icon
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay

/**
 * 嵌入在 BottomSheet 里的秒传与磁力确认工作台。
 *
 * 状态机在 shared 的 [InstantSheetState]，由 [InstantSession] 持有，面板收起时不丢；
 * 这里只有 Material 的布局与外观。保存结果也不在这里收：面板可能已经收起，结果由持有会话的
 * 网盘页处理。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InstantSheetContent(state: InstantSheetState) {
    val context = LocalContext.current
    var showTargetPicker by remember { mutableStateOf(false) }

    val pendingMagnet = state.normalizedMagnet
    val result = state.resolution

    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 点面板的空白处交出输入框的焦点。子项自己的点击先消费，走不到这里
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 面板有拖动条，下滑、点遮罩、返回都能关，标题行不再放关闭按钮
        Text(
            text = "添加链接",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (state.isInputVisible) {
            OutlinedTextField(
                value = state.input,
                onValueChange = state::updateInput,
                label = { Text("磁力链接或下载地址") },
                placeholder = { Text("magnet:?xt=urn:btih:…") },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.largeIncreased,
                maxLines = 3,
                enabled = !state.isSaving,
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
        }

        if (state.isResolving) {
            ResolvingRow()
        }

        state.errorMessage?.let { err ->
            // 解析失败与未收录都给重试：未收录的资源过一阵可能就被索引了
            val canRetry = pendingMagnet != null && result == null && !state.isResolving
            ErrorBanner(
                message = err,
                onRetry = if (canRetry) state::retryResolve else null,
            )
        }

        if (result != null) {
            ResolutionSection(state = state, resourceName = result.resource.name)
        }

        val action = state.primaryAction
        if (action != null) {
            TargetRow(
                target = state.target,
                notice = state.targetNotice,
                enabled = !state.isSaving,
                onClick = { showTargetPicker = true },
            )
            Button(
                onClick = state::performPrimaryAction,
                enabled = action.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                shapes = ButtonDefaults.shapes(),
            ) {
                if (state.isSaving) {
                    PikoLoadingIndicator(size = 20.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在保存")
                } else {
                    Icon(action.kind.icon(), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(action.label())
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

private fun InstantActionKind.icon(): ImageVector = when (this) {
    InstantActionKind.INSTANT_SAVE -> Icons.Outlined.Bolt
    InstantActionKind.OFFLINE_SAVE, InstantActionKind.SUBMIT_OFFLINE -> Icons.Outlined.CloudDownload
}

private fun InstantPrimaryAction.label(): String = when (kind) {
    InstantActionKind.INSTANT_SAVE -> "秒传 $fileCount 个文件"
    InstantActionKind.OFFLINE_SAVE -> "离线下载 $fileCount 个文件"
    InstantActionKind.SUBMIT_OFFLINE -> "离线下载"
}

@Composable
private fun ResolvingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PikoLoadingIndicator(size = 20.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "正在查询云端索引",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: (() -> Unit)?) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(start = 16.dp, end = 8.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            )
            if (onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                ) {
                    Text("重试")
                }
            }
        }
    }
}

/** 解析结果：资源名（多文件秒传时即新建目录名，可改）与文件勾选列表。 */
@Composable
private fun ColumnScope.ResolutionSection(state: InstantSheetState, resourceName: String) {
    // 输入框收起后链接就看不到了，标题右侧留一个复制入口，好转发或换设备打开
    Row(verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.weight(1f)) {
            if (state.willCreateFolder) {
                val isBlank = state.folderName.isBlank()
                FileNameField(
                    value = state.folderName,
                    onValueChange = state::updateFolderName,
                    label = "新建文件夹",
                    collapseWhenIdle = true,
                    enabled = !state.isSaving,
                    isError = isBlank,
                    supportingText = if (isBlank) "名称不能为空" else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = resourceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    // 首行中线落在 24dp，与右侧 48dp 按钮的中线对齐
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        if (!state.isInputVisible) {
            CopyLinkButton(
                link = state.input.trim(),
                // 输入框顶上留 8dp 给浮动标签，框的中线在 36dp，按钮下移 12dp 对上；
                // 右移 12dp 让图标贴齐内容右缘，触控区不变
                modifier = Modifier
                    .padding(start = 4.dp, top = if (state.willCreateFolder) 12.dp else 0.dp)
                    .offset(x = 12.dp),
            )
        }
    }

    // 列表占去面板剩下的高度，不再定死 320dp：长资源的上半部分有文件夹名、计数与芯片，
    // 定高时列表里只看得到两三行。fill = false 让短列表照常收缩
    Column(modifier = Modifier.weight(1f, fill = false)) {
        SelectionHeader(state)
        if (state.categoryIndices.size > 1) {
            CategoryChips(state)
        }
        FileTreeList(state, modifier = Modifier.weight(1f, fill = false))
        // 秒传与离线不能拆着来（见 InstantSheetState.canInstantSaveAll），勾上一项未收录的，
        // 按钮就从「秒传」变成「离线下载」。不说明的话，这个切换看起来毫无来由
        if (!state.canInstantSaveAll && state.selectedItems.isNotEmpty()) {
            Text(
                text = "所选含未收录文件，将整体离线下载",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
        }
    }
}

/** 已选计数与收录情况。可秒传是常态，所以只在这里说一次，行里只标未收录的例外。 */
@Composable
private fun SelectionHeader(state: InstantSheetState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "已选 ${state.selectedEntryCount} / ${state.entryCount}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val unindexed = state.items.count { !it.isInstantReady }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (unindexed == 0) Icons.Filled.Bolt else Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    tint = if (unindexed == 0) LocalStatusColors.current.success else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (unindexed == 0) "全部可秒传" else "$unindexed 项未收录",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = state::toggleSelectAll) {
            Text(if (state.isAllSelected) "全不选" else "全选")
        }
    }
}

/** 按大类整批勾选。字幕组的种子里常见的需求是「只要视频」或「视频加字幕」，逐行勾要点几十下。 */
@Composable
private fun CategoryChips(state: InstantSheetState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.categoryIndices.forEach { (category, indices) ->
            val selected = state.selectedIndices.containsAll(indices)
            FilterChip(
                selected = selected,
                onClick = { state.toggleCategory(category) },
                label = { Text("${category.label} ${indices.size}") },
                leadingIcon = {
                    Icon(
                        imageVector = if (selected) Icons.Outlined.Check else category.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}


/** 层级、展开状态与组统计都在 [InstantSheetState]，这里只按行渲染。 */
@Composable
private fun FileTreeList(state: InstantSheetState, modifier: Modifier = Modifier) {
    val rows = state.treeRows

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                when (val node = row.node) {
                    is NameGroup -> {
                        GroupRow(
                            group = node,
                            depth = row.depth,
                            isExpanded = state.isGroupExpanded(row.key, row.depth),
                            summary = state.summaryOf(node),
                            onToggleExpanded = { state.toggleGroupExpanded(row.key, row.depth) },
                            onSelectAll = { state.setItemsSelected(node.indices, it) },
                        )
                    }
                    is NameLeaf -> {
                        val item = state.items[node.index]
                        InstantFileRow(
                            bundledSubtitles = state.subtitleBundles[node.index]?.size ?: 0,
                            label = node.label.ifEmpty { item.file.name },
                            fullName = item.file.name,
                            size = item.file.size.toReadableSize(),
                            depth = row.depth,
                            isInstantReady = item.isInstantReady,
                            checked = node.index in state.selectedIndices,
                            onCheckedChange = { state.setItemSelected(node.index, it) },
                        )
                    }
                }
            }
        }
    }
}

// 名字不长于此就与大小排在同一行
private const val SHORT_LABEL = 16

// 每深一层缩进这么多，让子项的复选框落在上一层名字的起点附近
private val TreeIndent = 16.dp

// 没勾的行压暗，扫一眼就知道会存哪些；复选框不压，免得看起来像禁用
private const val UNSELECTED_ALPHA = 0.6f

@Composable
private fun GroupRow(
    group: NameGroup,
    depth: Int,
    isExpanded: Boolean,
    summary: NameGroupSummary,
    onToggleExpanded: () -> Unit,
    onSelectAll: (Boolean) -> Unit,
) {
    val selectedCount = summary.selected
    val total = summary.total
    val checkState = when (selectedCount) {
        0 -> ToggleableState.Off
        total -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    // 行本身负责展开收起，复选框负责整组勾选，两个点击目标分开
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (isExpanded) "收起" else "展开", onClick = onToggleExpanded)
            .padding(start = 4.dp + TreeIndent * depth, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = checkState, onClick = { onSelectAll(checkState != ToggleableState.On) })
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (selectedCount == 0) UNSELECTED_ALPHA else 1f),
        ) {
            Text(
                text = group.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 子项里剥掉的公共结尾写在这里，否则展开后看不出这些是什么格式
            if (group.suffix.isNotEmpty()) {
                Text(
                    text = "…" + group.suffix,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MetaRow(
                parts = listOf(
                    if (selectedCount == total || selectedCount == 0) "$total 项" else "已选 $selectedCount / $total",
                    summary.bytes.toReadableSize(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (summary.hasUnindexed) {
            UnindexedMark()
        }
        Icon(
            imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun UnindexedMark() {
    Icon(
        imageVector = Icons.Outlined.CloudDownload,
        contentDescription = "未收录，需离线下载",
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .padding(start = 12.dp)
            .size(20.dp),
    )
}

/**
 * 复制磁力链的图标按钮。用链接图标而不是剪贴板：剪贴板图标挨着标题，读起来像「复制标题」；
 * 链接图标说明复制的是什么。长按出提示文字，点完换成对勾一秒半作为回执，
 * 系统在 Android 13 以下不提示已复制。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CopyLinkButton(link: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text("复制磁力链接") } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("磁力链接", link))
            copied = true
        }) {
            Crossfade(targetState = copied, label = "copy-link") { done ->
                Icon(
                    imageVector = if (done) Icons.Outlined.Check else Icons.Outlined.Link,
                    contentDescription = if (done) "已复制" else "复制磁力链接",
                    tint = if (done) LocalStatusColors.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InstantFileRow(
    bundledSubtitles: Int,
    label: String,
    fullName: String,
    size: String,
    depth: Int,
    isInstantReady: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val isCompact = label.length <= SHORT_LABEL
    // 整行是一个复选项，Checkbox 只作显示，免得同一次点击被行与复选框各处理一遍
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            // 单行时就是复选框的 48dp，再加上下边距，二十几集的列表会显得松散
            .padding(start = 4.dp + TreeIndent * depth, end = 16.dp, top = if (isCompact) 0.dp else 4.dp, bottom = if (isCompact) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.minimumInteractiveComponentSize())
        Row(
            modifier = Modifier
                .weight(1f)
                .alpha(if (checked) 1f else UNSELECTED_ALPHA),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 种子里视频、字幕、图片、nfo 常混在一起，只读文件名分不快。类型按全名判断：
            // 折叠后的片段可能只剩「.sc.ass」，也可能是不带扩展名的中段
            Icon(
                imageVector = fileNameTypeIcon(fullName),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            val meta = listOfNotNull(size, "+$bundledSubtitles 字幕".takeIf { bundledSubtitles > 0 })
            // 公共前后缀剥掉之后，剧集常只剩「[01]」，再给大小单占一行，一集就是两行高。
            // 短名字把大小放到同一行右侧，二十几集的列表矮一半
            if (isCompact) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                MetaRow(
                    parts = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MetaRow(
                        parts = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!isInstantReady) {
            UnindexedMark()
        }
    }
}

/**
 * 保存位置。目标还没取到时不可点，也不拿 My Packs 顶替，免得闪一个可能是错的名字。
 * 原先是 labelSmall 的小胶囊，挤在输入框下，不像能点的东西；改为紧挨主按钮的整行。
 */
@Composable
private fun TargetRow(
    target: PathBreadcrumb?,
    notice: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled && target != null,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "保存到",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = target?.name ?: "正在确认",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                notice?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (target != null) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "更换保存位置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 面板收起后留在屏幕底部的把手，外观是一块只露出顶边的面板。点按或上拉重新展开，
 * 右侧的关闭才真正结束这次会话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstantSheetHandle(
    state: InstantSheetState,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = state.resolution
    val title = result?.resource?.name ?: state.input.trim().ifEmpty { "添加链接" }
    val status = when {
        state.isSaving -> "正在保存"
        state.isResolving -> "正在查询云端索引"
        state.errorMessage != null -> state.errorMessage
        result != null -> "已选 ${state.selectedEntryCount} / ${state.entryCount}"
        else -> null
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -8f) onExpand() }
            },
        onClick = onExpand,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        // 与底栏同色，看上去是从底栏里探出的一截。用面板本身的 surfaceContainerLow 时，
        // 它紧贴着更亮的 surfaceContainer 底栏，深色主题下像一条黑带；阴影也会在两者的
        // 接缝上画出一道线，所以不加
        color = NavigationBarDefaults.containerColor,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BottomSheetDefaults.DragHandle(modifier = Modifier.padding(top = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onClose, enabled = !state.isSaving) {
                    Icon(Icons.Outlined.Close, contentDescription = "放弃这次添加")
                }
            }
        }
    }
}

