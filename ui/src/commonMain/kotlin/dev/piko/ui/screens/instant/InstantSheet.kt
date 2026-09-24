package dev.piko.ui.screens.instant

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.shared.state.InstantGroup
import dev.piko.shared.state.InstantPrimaryAction
import dev.piko.shared.state.InstantRow
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.NameGroupSummary
import dev.piko.ui.components.FileNameField
import dev.piko.ui.components.FolderPickerDialog
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.fileNameTypeIcon
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalPikoPlatform
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
fun InstantSheetContent(
    state: InstantSheetState,
    /** 预览的文件已秒传进 Piko-Temp，交给播放器打开。 */
    onPreview: (fileId: String, fileName: String) -> Unit,
) {
    val platform = LocalPikoPlatform.current
    var showTargetPicker by remember { mutableStateOf(false) }

    val currentOnPreview by rememberUpdatedState(onPreview)
    LaunchedEffect(state) {
        state.previewRequests.collect { currentOnPreview(it.fileId, it.fileName) }
    }
    // 面板盖在网盘页的 Snackbar 之上，一次性提示就地显示几秒
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) {
        state.messages.collect { notice = it }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4000)
            notice = null
        }
    }

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
                        val clip = platform.readClipboardText()
                        if (!clip.isNullOrBlank()) state.updateInput(clip.trim())
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "从剪贴板粘贴")
                    }
                },
            )
        }

        if (state.isResolving) {
            ResolvingRow(text = if (state.isAnalyzing) "正在整理文件" else "正在查询云端索引")
        }

        state.errorMessage?.let { err ->
            // 解析失败与未收录都给重试：未收录的资源过一阵可能就被索引了
            val canRetry = pendingMagnet != null && result == null && !state.isResolving
            ErrorBanner(
                message = err,
                onRetry = if (canRetry) state::retryResolve else null,
            )
        }

        notice?.let { ErrorBanner(message = it, onRetry = null) }

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
            SaveBar(state = state, action = action)
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

/**
 * 保存栏只有一个「保存」。走秒传还是整包离线由 [planSave] 决定，用户不必知道，
 * 两条路各扣哪项额度也不预先说明：额度充裕时这些信息只是噪声。
 * 只有碰到限制才出声：整包放不进网盘时不让提交，说明缺多少，并给出只存选中文件的退路。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SaveBar(state: InstantSheetState, action: InstantPrimaryAction) {
    val plan = state.savePlan.takeIf { state.resolution != null }
    val fallback = plan?.fallback
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (plan != null && plan.lacksSpace) {
            ErrorBanner(
                message = "网盘空间不足：需要 ${plan.packBytes.toReadableSize()}，" +
                    "剩余 ${(state.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}",
                onRetry = null,
            )
        }
        if (fallback != null) {
            SaveButton(
                label = "只保存所选文件",
                enabled = state.canSaveSelection,
                isSaving = state.isSaving,
                onClick = state::saveSelectionInstantly,
            )
            if (fallback.skippedCount > 0) SaveCaption("将跳过 ${fallback.skippedCount} 个未收录文件")
        } else {
            SaveButton(
                label = "保存",
                enabled = action.enabled,
                isSaving = state.isSaving,
                onClick = state::performPrimaryAction,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SaveButton(label: String, enabled: Boolean, isSaving: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !isSaving,
        modifier = Modifier
            .fillMaxWidth()
            .height(ButtonDefaults.MediumContainerHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
        shapes = ButtonDefaults.shapes(),
    ) {
        if (isSaving) {
            PikoLoadingIndicator(size = 20.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("正在保存")
        } else {
            Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(label)
        }
    }
}

@Composable
private fun SaveCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ResolvingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PikoLoadingIndicator(size = 20.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
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
        FileTreeList(state, modifier = Modifier.weight(1f, fill = false))
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
            // 全部已收录时不说话：怎么保存是程序的事。未收录的要从头下载，会慢，值得提一句
            if (unindexed > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "$unindexed 项缺少云端缓存，耗时较长",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(onClick = state::toggleSelectAll) {
            Text(if (state.isAllSelected) "全不选" else "全选")
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
                    is InstantGroup -> {
                        GroupRow(
                            group = node,
                            depth = row.depth,
                            isExpanded = state.isGroupExpanded(node),
                            summary = state.summaryOf(node),
                            onToggleExpanded = { state.toggleGroupExpanded(node) },
                            onSelectAll = { state.setGroupSelected(node, it) },
                        )
                    }
                    is InstantRow -> {
                        InstantFileRow(
                            row = node,
                            fullName = state.items[node.index].file.name,
                            depth = row.depth,
                            isInstantReady = node.indices.all { state.items[it].isInstantReady },
                            checked = node.index in state.selectedIndices,
                            onCheckedChange = { state.setItemSelected(node.index, it) },
                            onPreview = if (state.canPreview(node.index)) {
                                { state.preview(node.index) }
                            } else {
                                null
                            },
                            isPreviewing = state.previewingIndex == node.index,
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
    group: InstantGroup,
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
                text = group.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 作品共有的标签只在这里出现一次，行里只剩有区分度的
            MediaTagRow(tags = group.tags, modifier = Modifier.padding(vertical = 2.dp))
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
    val platform = LocalPikoPlatform.current
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
            platform.copyToClipboard("磁力链接", link)
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
    row: InstantRow,
    fullName: String,
    depth: Int,
    isInstantReady: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onPreview: (() -> Unit)?,
    isPreviewing: Boolean,
) {
    val isCompact = row.label.length <= SHORT_LABEL
    val meta = listOfNotNull(
        row.bytes.toReadableSize(),
        "+${row.subtitleCount} 字幕".takeIf { row.subtitleCount > 0 },
        "+${row.audioTrackCount} 音轨".takeIf { row.audioTrackCount > 0 },
    )
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
            // 视频、图片、压缩包、nfo 常混在一起，短标签看不出类型，图标按原始文件名判断
            Icon(
                imageVector = fileNameTypeIcon(fullName),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            // 短标签（「01」「23 Beta」）与标签、大小排成一行，二十几集的列表矮一半；
            // 原始文件名放不下，标签与大小另起一行
            if (isCompact) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 标签只占剩下的宽度，放不下就整个丢掉，不挤压大小
                Box(modifier = Modifier.weight(1f)) {
                    MediaTagRow(tags = row.tags)
                }
                MetaRow(
                    parts = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetaRow(
                            parts = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (row.tags.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                MediaTagRow(tags = row.tags)
                            }
                        }
                    }
                }
            }
        }
        if (onPreview != null) {
            PreviewButton(onClick = onPreview, isPreviewing = isPreviewing)
        }
        if (!isInstantReady) {
            UnindexedMark()
        }
    }
}

/** 预览按钮。秒传进 Piko-Temp 要一两秒，期间换成转圈，免得连点。 */
@Composable
private fun PreviewButton(onClick: () -> Unit, isPreviewing: Boolean) {
    if (isPreviewing) {
        Box(modifier = Modifier.padding(start = 4.dp).size(48.dp), contentAlignment = Alignment.Center) {
            PikoLoadingIndicator(size = 24.dp)
        }
    } else {
        TooltipIconButton(
            icon = Icons.Outlined.PlayCircle,
            label = "预览",
            onClick = onClick,
            modifier = Modifier.padding(start = 4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

