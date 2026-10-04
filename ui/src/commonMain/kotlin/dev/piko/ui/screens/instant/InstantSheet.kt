package dev.piko.ui.screens.instant

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.shared.state.InstantActionKind
import dev.piko.shared.state.InstantFallback
import dev.piko.shared.state.SaveRoute
import dev.piko.shared.state.InstantGroup
import dev.piko.shared.state.InstantPrimaryAction
import dev.piko.shared.state.SavePlan
import dev.piko.shared.state.InstantRow
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.NameGroupSummary
import dev.piko.shared.state.ShareSaveState
import dev.piko.ui.LocalPikoServices
import io.github.nihildigit.pikpak.shareIdFromUrl
import dev.piko.ui.components.CollapsedSheetHandle
import dev.piko.ui.components.FileNameField
import dev.piko.ui.components.FolderPickerDialog
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.fileNameTypeIcon
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge

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
    /**
     * 在侧栏或模态侧边面板里：标题由面板顶上那一行画，内容不再画；侧栏只有三百来 dp，两边留白收窄。
     * 底部 sheet 没有那一行，标题照旧在内容里。
     */
    inSideSheet: Boolean = false,
    /** 预览的文件已秒传进 Piko-Temp，交给播放器打开。 */
    onPreview: (fileId: String, fileName: String) -> Unit,
) {
    val platform = LocalPikoPlatform.current
    val downloads = LocalPikoServices.current.downloadManager
    var showTargetPicker by remember { mutableStateOf(false) }

    val batch = state.batch
    // 批量时预览与提示来自各行的子实例，与本体的一起收
    val sheets = remember(state, batch?.rows) { listOf(state) + batch?.rows?.map { it.state }.orEmpty() }
    val currentOnPreview by rememberUpdatedState(onPreview)
    LaunchedEffect(sheets) {
        sheets.map { it.previewRequests }.merge().collect { currentOnPreview(it.fileId, it.fileName) }
    }
    // 面板盖在网盘页的 Snackbar 之上，一次性提示就地显示几秒
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sheets) {
        sheets.map { it.messages }.merge().collect { notice = it }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4000)
            notice = null
        }
    }

    val pendingMagnet = state.normalizedMagnet
    val result = state.resolution
    // 分享链接走转存，与磁力的解析、秒传、离线互不相干，下面整段换成分享面板
    val shareId = remember(state.input) { InstantSheetState.findShareLink(state.input)?.let(::shareIdFromUrl) }
    val driveRepo = LocalPikoServices.current.driveRepository
    val shareScope = rememberCoroutineScope()
    val shareState = remember(shareId) {
        shareId?.let {
            ShareSaveState(driveRepo, shareScope, it, initialPassCode = InstantSheetState.findSharePassCode(state.input).orEmpty())
        }
    }

    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 点面板的空白处交出输入框的焦点。子项自己的点击先消费，走不到这里
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
            .padding(horizontal = if (inSideSheet) 16.dp else 24.dp)
            .padding(bottom = if (inSideSheet) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val openedRow = batch?.openedRow
        if (batch != null) {
            if (openedRow != null) {
                BatchRowDetail(batch, openedRow, notice)
            } else {
                BatchList(batch, state, notice, showTitle = !inSideSheet, onPickTarget = { showTargetPicker = true })
            }
            return@Column
        }

        // 底部 sheet 有拖动条，下滑、点遮罩、返回都能关，标题行不再放关闭按钮
        if (!inSideSheet) {
            Text(
                text = "添加链接",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (state.isInputVisible) {
            OutlinedTextField(
                value = state.input,
                onValueChange = state::updateInput,
                label = { Text("磁力链接、下载地址或分享链接") },
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

        if (shareState != null) {
            ShareSaveSection(
                state = shareState,
                target = state.target,
                onPickTarget = { showTargetPicker = true },
                onPreview = state::previewSharedFile,
                previewingId = state.previewingSharedId,
                onDownload = { file ->
                    downloads.enqueueResolved(listOf(io.github.nihildigit.pikpak.ResolvedFile(file.name, file.sizeBytes, file.hash)), state.target?.id.orEmpty())
                    notice = "已加入下载队列"
                },
            )
        }

        if (shareState == null && state.isResolving) {
            ResolvingRow(text = if (state.isAnalyzing) "正在整理文件" else "正在查询云端索引")
        }

        if (shareState == null) state.errorMessage?.let { err ->
            // 解析失败与未收录都给重试：未收录的资源过一阵可能就被索引了
            val canRetry = pendingMagnet != null && result == null && !state.isResolving
            ErrorBanner(
                message = err,
                onRetry = if (canRetry) state::retryResolve else null,
            )
        }

        notice?.let { ErrorBanner(message = it, onRetry = null) }

        if (shareState == null && result != null) {
            ResolutionSection(state = state, resourceName = result.resource.name, showCopyLink = !state.isInputVisible)
        }

        val action = state.primaryAction
        if (shareState == null && action != null) {
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
 * 只有碰到限制才出声：整包放不进网盘或离线次数用完时不让提交，说明原因，并给出只存选中文件的退路。
 * 免费账号要整包离线时先问一句：一天只有几次离线，未收录的那几个未必值得。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SaveBar(state: InstantSheetState, action: InstantPrimaryAction) {
    val plan = state.savePlan.takeIf { state.resolution != null && !state.contentMissing }
    val fallback = plan?.fallback
    val confirm = when {
        !state.confirmsOffline -> null
        action.kind == InstantActionKind.SUBMIT_OFFLINE -> OfflineConfirm.WholeLink
        plan == null || plan.blocked || plan.route != SaveRoute.OFFLINE_PACK -> null
        fallback != null -> OfflineConfirm.PackOrInstant(plan, fallback)
        else -> OfflineConfirm.Pack(plan)
    }
    var askOffline by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (plan != null && plan.lacksSpace) {
            // 秒传没有退路按钮可给，只能少选几项；整包离线的退路在下面的按钮上
            val hint = if (plan.route == SaveRoute.INSTANT) "，请减少勾选" else ""
            ErrorBanner(
                message = "网盘空间不足：需要 ${plan.neededBytes.toReadableSize()}，" +
                    "剩余 ${(state.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}$hint",
                onRetry = null,
            )
        } else if (plan != null && plan.lacksOfflineCount) {
            ErrorBanner(message = "今日离线次数已用完", onRetry = null)
        }
        if (plan?.blocked == true && fallback != null) {
            SaveButton(
                label = "只保存所选文件",
                enabled = state.canSaveSelection,
                isSaving = state.isSaving,
                onClick = state::saveSelectionInstantly,
            )
            if (fallback.skippedCount > 0) SaveCaption("将跳过 ${fallback.skippedCount} 个未收录文件")
        } else {
            SaveButton(
                label = if (state.contentMissing) "离线下载" else "保存",
                enabled = action.enabled,
                isSaving = state.isSaving,
                onClick = { if (confirm != null) askOffline = true else state.performPrimaryAction() },
            )
        }
    }
    if (askOffline && confirm != null) {
        OfflineConfirmDialog(
            confirm = confirm,
            offlineLeft = state.offlineLeft,
            onOffline = {
                askOffline = false
                state.performPrimaryAction()
            },
            onInstant = {
                askOffline = false
                state.saveSelectionInstantly()
            },
            onDismiss = { askOffline = false },
        )
    }
}

/** 免费账号建离线任务前要确认的几种情形。离线一天只有几次，每一次都写明代价。 */
private sealed interface OfflineConfirm {
    /** 整条链接交给离线：未收录的磁力、非磁力链接、云端暂无内容的单文件。大小未知。 */
    data object WholeLink : OfflineConfirm

    /** 选中的全是未收录的，只能整包离线。 */
    data class Pack(val plan: SavePlan) : OfflineConfirm

    /** 一部分未收录：整包离线，或只秒传已收录的。 */
    data class PackOrInstant(val plan: SavePlan, val fallback: InstantFallback) : OfflineConfirm
}

/** 代价低的一项放在最右的主位，手快点错也不白占一次离线。 */
@Composable
private fun OfflineConfirmDialog(
    confirm: OfflineConfirm,
    offlineLeft: Int?,
    onOffline: () -> Unit,
    onInstant: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = offlineLeft?.let { "（今日剩 $it 次）" }.orEmpty()
    val (title, message) = when (confirm) {
        OfflineConfirm.WholeLink -> "离线下载" to "将占用 1 次离线$count。"
        is OfflineConfirm.Pack -> "整包离线" to
            "所选文件未收录，需整包离线，占用 ${confirm.plan.packBytes.toReadableSize()} 空间与 1 次离线$count。"
        is OfflineConfirm.PackOrInstant -> "部分文件未收录" to
            "${confirm.fallback.skippedCount} 个文件未收录，需整包离线，" +
            "占用 ${confirm.plan.packBytes.toReadableSize()} 空间与 1 次离线$count。" +
            "也可只秒传已收录的 ${confirm.fallback.fileCount} 个文件。"
    }
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            // 两条路并列时都是文字按钮：哪条更好由人按空间与次数定，给哪条底色都是替人选
            if (confirm is OfflineConfirm.PackOrInstant) {
                Row {
                    TextButton(onClick = onOffline) { Text("整包离线") }
                    TextButton(onClick = onInstant) { Text("只存已收录的") }
                }
            } else {
                PikoDialogConfirm("离线", onClick = onOffline)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SaveButton(label: String, enabled: Boolean, isSaving: Boolean, onClick: () -> Unit) {
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
            InlineLoadingIndicator(color = LocalContentColor.current)
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
internal fun SaveCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ResolvingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        InlineLoadingIndicator()
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ErrorBanner(message: String, onRetry: (() -> Unit)?) {
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
internal fun ColumnScope.ResolutionSection(state: InstantSheetState, resourceName: String, showCopyLink: Boolean) {
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
        if (showCopyLink) {
            CopyLinkButton(
                link = state.normalizedMagnet ?: state.input.trim(),
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
    val downloads = LocalPikoServices.current.downloadManager

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
                            onDownload = if (state.items[node.index].isInstantReady) ({
                                downloads.enqueueResolved(node.indices.map { state.items[it].file }, state.target?.id.orEmpty())
                            }) else null,
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
internal fun CopyLinkButton(link: String, modifier: Modifier = Modifier) {
    val platform = LocalPikoPlatform.current
    // 批量列表里也有 http 与 ed2k 链接
    val kind = if (link.startsWith("magnet:", ignoreCase = true)) "磁力链接" else "链接"
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text("复制$kind") } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(onClick = {
            platform.copyToClipboard(kind, link)
            copied = true
        }) {
            Crossfade(targetState = copied, label = "copy-link") { done ->
                Icon(
                    imageVector = if (done) Icons.Outlined.Check else Icons.Outlined.Link,
                    contentDescription = if (done) "已复制" else "复制$kind",
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
    onDownload: (() -> Unit)?,
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
                    MediaTagRow(tags = row.tags, lead = row.code)
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
                        if (row.tags.isNotEmpty() || row.code != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                MediaTagRow(tags = row.tags, lead = row.code)
                            }
                        }
                    }
                }
            }
        }
        if (onPreview != null) {
            PreviewButton(onClick = onPreview, isPreviewing = isPreviewing)
        }
        if (onDownload != null) {
            DownloadContentButton(onClick = onDownload)
        }
        if (!isInstantReady) {
            UnindexedMark()
        }
    }
}

/** 预览按钮。秒传进 Piko-Temp 要一两秒，期间换成转圈，免得连点。 */
@Composable
internal fun PreviewButton(onClick: () -> Unit, isPreviewing: Boolean) {
    if (isPreviewing) {
        Box(modifier = Modifier.padding(start = 4.dp).size(48.dp), contentAlignment = Alignment.Center) {
            InlineLoadingIndicator()
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

@Composable
internal fun DownloadContentButton(onClick: () -> Unit) {
    TooltipIconButton(Icons.Outlined.CloudDownload, "下载", onClick,
        modifier = Modifier.padding(start = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * 保存位置。目标还没取到时不可点，也不拿 My Packs 顶替，免得闪一个可能是错的名字。
 * 原先是 labelSmall 的小胶囊，挤在输入框下，不像能点的东西；改为紧挨主按钮的整行。
 */
@Composable
internal fun TargetRow(
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

/** 添加链接面板收起后的把手：标题是链接或资源名，状态是解析与勾选的进度。 */
@Composable
fun InstantSheetHandle(
    state: InstantSheetState,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = state.resolution
    val batch = state.batch
    val isSaving = state.isSaving || batch?.isSaving == true
    val title = when {
        batch != null -> "${batch.rows.size} 条链接"
        else -> result?.resource?.name ?: state.input.trim().ifEmpty { "添加链接" }
    }
    val status = when {
        isSaving -> "正在保存"
        batch != null -> batch.blockedReason ?: "可保存 ${batch.submittableCount} 项"
        state.isResolving -> "正在查询云端索引"
        state.errorMessage != null -> state.errorMessage
        result != null -> "已选 ${state.selectedEntryCount} / ${state.entryCount}"
        else -> null
    }
    CollapsedSheetHandle(
        title = title,
        status = status,
        closeLabel = "放弃这次添加",
        onExpand = onExpand,
        onClose = onClose,
        modifier = modifier,
        closeEnabled = !isSaving,
    )
}
