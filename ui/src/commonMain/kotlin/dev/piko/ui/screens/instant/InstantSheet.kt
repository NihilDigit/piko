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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.HourglassTop
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.ui.LocalPikoServices
import io.github.nihildigit.pikpak.shareIdFromUrl
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
    /** 窄窗口的 sheet 顶上已有一行标题与状态（收起时露出的那一截），内容里不再画标题。 */
    headerShown: Boolean = false,
    /** 预览的文件已秒传进 Piko-Temp，交给播放器打开。 */
    onPreview: (fileId: String, fileName: String) -> Unit,
) {
    val platform = LocalPikoPlatform.current
    var showTargetPicker by remember { mutableStateOf(false) }

    val batch = state.batch
    // 批量时预览与提示来自各行的子实例，与本体的一起收
    val sheets = remember(state, batch?.rows) { listOf(state) + batch?.rows?.map { it.state }.orEmpty() }
    val currentOnPreview by rememberUpdatedState(onPreview)
    LaunchedEffect(sheets) {
        sheets.map { it.previewRequests }.merge().collect { currentOnPreview(it.fileId, it.fileName) }
    }
    // 面板盖在网盘页的 Snackbar 之上，一次性提示就地显示几秒。状态里发来的都是失败（预览失败、
    // 批量里有几项没存上），面板自己发的是回执，两者颜色不同：回执画成红色会被当成出错
    var notice by remember { mutableStateOf<PanelNotice?>(null) }
    LaunchedEffect(sheets) {
        sheets.map { it.messages }.merge().collect { notice = PanelNotice(it, isError = true) }
    }
    val showReceipt: (String) -> Unit = { notice = PanelNotice(it, isError = false) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4000)
            notice = null
        }
    }

    // 分享链接走转存，与磁力的解析、秒传、离线互不相干，下面整段换成分享面板
    val shareId = remember(state.input) { InstantSheetState.findShareLink(state.input)?.let(::shareIdFromUrl) }
    val driveRepo = LocalPikoServices.current.driveRepository
    val shareScope = rememberCoroutineScope()
    val shareState = remember(shareId) {
        shareId?.let {
            ShareSaveState(driveRepo, shareScope, it, initialPassCode = InstantSheetState.findSharePassCode(state.input).orEmpty())
        }
    }

    val action = state.primaryAction
    var askOffline by remember { mutableStateOf<OfflineConfirm?>(null) }
    // 单条磁力的保存：免费账号要离线时先确认，按钮与快捷键走同一条路
    val saveMagnet: () -> Unit = {
        val confirm = action?.let { offlineConfirmOf(state, it) }
        if (confirm != null) askOffline = confirm else state.performPrimaryAction()
    }
    // 主修饰键+Enter 保存。不用单独的 Enter：它在输入框里换行（多条链接一行一条），在文件行上是勾选
    val saveByShortcut: (() -> Unit)? = when {
        batch != null -> if (batch.openedRow == null && batch.canSaveAll) batch::saveAll else null
        shareState != null -> state.target?.takeIf { shareState.selectedIds.isNotEmpty() && !shareState.isSaving }
            ?.let { target -> { shareState.save(PikoPathBreadcrumb(target.id, target.name), canonicalNames = state.useCanonicalNames) } }
        action != null && action.enabled && !state.isSaving && state.savePlan?.blocked != true -> saveMagnet
        else -> null
    }
    val shortcutModifier = platform.shortcutModifier

    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 侧边面板是整窗高：占满它，保存栏才能钉在底部，文件树用满中间的高度。
            // 底部 sheet 随内容定高，照旧收缩
            .then(if (inSideSheet) Modifier.fillMaxHeight() else Modifier)
            // 点面板的空白处交出输入框的焦点。子项自己的点击先消费，走不到这里
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
            .onPreviewKeyEvent { event ->
                val save = saveByShortcut
                if (save != null && event.type == KeyEventType.KeyDown && event.key == Key.Enter && shortcutModifier.isPressed(event)) {
                    save()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = if (inSideSheet) 16.dp else 24.dp)
            .padding(bottom = if (inSideSheet) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val openedRow = batch?.openedRow
        if (batch != null) {
            if (openedRow != null) {
                BatchRowDetail(batch, openedRow, notice, onReceipt = showReceipt)
            } else {
                BatchList(
                    batch, state, notice,
                    showTitle = !inSideSheet && !headerShown,
                    fillHeight = inSideSheet,
                    onPickTarget = { showTargetPicker = true },
                )
            }
            return@Column
        }

        // 内容与保存栏分开：侧边面板里内容占满中间，保存栏钉在底部，解析前后、勾多勾少，按钮都在同一处
        Column(
            modifier = Modifier.weight(1f, fill = inSideSheet),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InstantBody(
                state = state,
                shareState = shareState,
                showTitle = !inSideSheet && !headerShown,
                notice = notice,
                onReceipt = showReceipt,
            )
        }

        if (shareState != null) {
            ShareSaveFooter(state = shareState, target = state.target, canonicalNames = state.useCanonicalNames, onPickTarget = { showTargetPicker = true })
        } else if (action != null) {
            TargetRow(
                target = state.target,
                notice = state.targetNotice,
                enabled = !state.isSaving,
                onClick = { showTargetPicker = true },
            )
            SaveBar(state = state, action = action, onSave = saveMagnet)
        }
    }

    askOffline?.let { confirm ->
        OfflineConfirmDialog(
            confirm = confirm,
            offlineLeft = state.offlineLeft,
            onOffline = {
                askOffline = null
                state.performPrimaryAction()
            },
            onInstant = {
                askOffline = null
                state.saveSelectionInstantly()
            },
            onDismiss = { askOffline = null },
        )
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

/** 一次性提示。[isError] 为 false 的是回执（如已加入下载队列）。 */
internal class PanelNotice(val text: String, val isError: Boolean)

@Composable
internal fun NoticeBanner(notice: PanelNotice) {
    if (notice.isError) {
        ErrorBanner(message = notice.text, onRetry = null)
    } else {
        StatusBanner(
            message = notice.text,
            icon = Icons.Outlined.CheckCircle,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 单条链接时保存栏以上的部分：标题、输入框、解析进度与出错说明、解析结果或分享内容。 */
@Composable
private fun ColumnScope.InstantBody(
    state: InstantSheetState,
    shareState: ShareSaveState?,
    showTitle: Boolean,
    notice: PanelNotice?,
    onReceipt: (String) -> Unit,
) {
    val platform = LocalPikoPlatform.current
    val downloads = LocalPikoServices.current.downloadManager
    val pendingMagnet = state.normalizedMagnet
    val result = state.resolution

    // 底部 sheet 有拖动条，下滑、点遮罩、返回都能关，标题行不再放关闭按钮
    if (showTitle) {
        Text(
            text = "添加链接",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    // 输入框一直在，外部带链接打开的也是：换链接就是在这里改或粘贴，同一个会话重新解析，不另设按钮。
    // 解析出结果后缩成一行把高度让给文件列表，点进去编辑时放回三行
    var inputFocused by remember { mutableStateOf(false) }
    val inputCompact = result != null && !inputFocused
    OutlinedTextField(
        value = state.input,
        onValueChange = state::updateInput,
        label = { Text("磁力链接、下载地址或分享链接") },
        placeholder = { Text("magnet:?xt=urn:btih:…") },
        modifier = Modifier.fillMaxWidth().onFocusChanged { inputFocused = it.isFocused },
        shape = MaterialTheme.shapes.largeIncreased,
        singleLine = inputCompact,
        maxLines = if (inputCompact) 1 else 3,
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

    if (shareState != null) {
        ShareSaveSection(
            state = shareState,
            canonicalNames = state.useCanonicalNames,
            onCanonicalNamesChange = state::updateUseCanonicalNames,
            onPreview = state::previewSharedFile,
            previewingId = state.previewingSharedId,
            onDownload = { file ->
                downloads.enqueueResolved(listOf(io.github.nihildigit.pikpak.ResolvedFile(file.name, file.sizeBytes, file.hash)), state.target?.id.orEmpty())
                onReceipt(QUEUED_RECEIPT)
            },
        )
    }

    if (shareState == null && state.isResolving) {
        ResolvingRow(text = resolvingText(state))
    }

    if (shareState == null) state.errorMessage?.let { err ->
        // 解析失败与未收录都给重试：未收录的资源过一阵可能就被索引了
        val canRetry = pendingMagnet != null && result == null && !state.isResolving
        ErrorBanner(
            message = err,
            onRetry = if (canRetry) state::retryResolve else null,
        )
    }

    notice?.let { NoticeBanner(it) }

    if (shareState == null && result != null) {
        ResolutionSection(
            state = state,
            resourceName = result.resource.name,
            // 链接就在上面的输入框里
            showCopyLink = false,
            onDownloaded = { onReceipt(QUEUED_RECEIPT) },
        )
    }
}

internal const val QUEUED_RECEIPT = "已加入下载队列"

/** 解析的两段：先等服务端列出文件，再在本机按文件名整理。用户只需知道还在进行，不提「索引」这类内部说法。 */
internal fun resolvingText(state: InstantSheetState): String = if (state.isAnalyzing) "正在整理文件" else "正在解析链接"

/** 免费账号这次保存要不要先确认离线；会员与秒传为 null。 */
private fun offlineConfirmOf(state: InstantSheetState, action: InstantPrimaryAction): OfflineConfirm? {
    val plan = state.savePlan.takeIf { state.resolution != null && !state.contentMissing }
    val fallback = plan?.fallback
    return when {
        !state.confirmsOffline -> null
        action.kind == InstantActionKind.SUBMIT_OFFLINE -> OfflineConfirm.WholeLink
        plan == null || plan.blocked || plan.route != SaveRoute.OFFLINE_PACK -> null
        fallback != null -> OfflineConfirm.PackOrInstant(plan, fallback)
        else -> OfflineConfirm.Pack(plan)
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
private fun SaveBar(state: InstantSheetState, action: InstantPrimaryAction, onSave: () -> Unit) {
    val plan = state.savePlan.takeIf { state.resolution != null && !state.contentMissing }
    val fallback = plan?.fallback
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
                onClick = onSave,
            )
        }
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
    StatusBanner(
        message = message,
        icon = Icons.Outlined.ErrorOutline,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        actionLabel = "重试".takeIf { onRetry != null },
        onAction = onRetry,
    )
}

/**
 * 面板里与内容同宽的一条说明：出错、回执、未收录提示都用它，只换颜色与图标。
 * 正文 bodyMedium，不再用计数下面那种 labelMedium 小字：这些话要据此做决定，得读得清。
 */
@Composable
internal fun StatusBanner(
    message: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(start = 16.dp, end = 8.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                ) {
                    Text(actionLabel)
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

/** 解析结果：资源名（多文件秒传时即新建目录名，可改）与文件勾选列表。 */
@Composable
internal fun ColumnScope.ResolutionSection(
    state: InstantSheetState,
    resourceName: String,
    showCopyLink: Boolean,
    /** 文件行上的「下载」把文件加进了本机下载队列，面板据此给回执。 */
    onDownloaded: () -> Unit,
) {
    // 批量列表里点开的一行没有输入框，链接看不到，标题右侧留一个复制入口，好转发或换设备打开
    Row(verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.weight(1f)) {
            if (state.willCreateFolder) {
                val isBlank = state.folderNameToSave.isBlank()
                FileNameField(
                    value = state.folderNameToSave,
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
    UnindexedBanner(state)
    if (state.offersCanonicalNames) CanonicalNamesToggle(state.useCanonicalNames, enabled = !state.isSaving, onChange = state::updateUseCanonicalNames)

    // 列表连同它的页眉占去剩下的高度，短列表照常收缩
    Column(modifier = Modifier.weight(1f, fill = false)) {
        SelectionHeader(state)
        FileTreeList(state, onDownloaded = onDownloaded, modifier = Modifier.weight(1f, fill = false))
    }
}

/**
 * 未收录的行要离线下载，保存慢；免费账号还要多占一次离线。全部已收录时不说话：怎么保存是程序的事。
 *
 * 放在列表上方、与列表同宽，正文 bodyMedium：原先是「已选」计数下面一行 labelMedium 小字，手机上读不清，
 * 也说不出是哪几项。哪几项靠后面的按钮：只列出这些行。没有选替人展开组再滚过去，理由见
 * [InstantSheetState.showsOnlyUnindexed]；未收录的散在几个组里时，滚到第一项也只找到一项。
 */
@Composable
private fun UnindexedBanner(state: InstantSheetState) {
    val count = state.unindexedEntryCount
    if (count == 0) return
    val filtering = state.isUnindexedFilterActive
    StatusBanner(
        message = "$count 项$UNINDEXED_HINT",
        icon = UnindexedIcon,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        actionLabel = if (filtering) "显示全部" else "只看这些",
        onAction = state::toggleOnlyUnindexed,
    )
}

/**
 * 保存时按番号规范命名。资源里有会改名的番号文件才出现，每次打开面板都是关的；开着时列表行直接显示存进去的名字，
 * 新建文件夹名同样换成规范名，看着就是保存后的样子。磁力与分享转存共用这一行。
 */
@Composable
internal fun CanonicalNamesToggle(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.minimumInteractiveComponentSize())
        Text("按番号规范命名", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

// 横幅、组行与文件行的标记同一种说法与图标。用沙漏而不是云下载：行上的「下载到本机」按钮就是云下载，
// 两者挨着时分不清哪个能点
internal const val UNINDEXED_HINT = "未收录，保存较慢"
internal val UnindexedIcon = Icons.Outlined.HourglassTop

/** 已选计数与全选。筛选只看未收录时不给全选：它作用于全部行，眼前却只列着几行，点了会动到看不见的勾选。 */
@Composable
private fun SelectionHeader(state: InstantSheetState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已选 ${state.selectedEntryCount} / ${state.entryCount}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (!state.isUnindexedFilterActive) {
            TextButton(onClick = state::toggleSelectAll) {
                Text(if (state.isAllSelected) "全不选" else "全选")
            }
        }
    }
}

/** 层级、展开状态与组统计都在 [InstantSheetState]，这里只按行渲染。 */
@Composable
private fun FileTreeList(state: InstantSheetState, onDownloaded: () -> Unit, modifier: Modifier = Modifier) {
    val rows = state.treeRows
    val downloads = LocalPikoServices.current.downloadManager
    val filtering = state.isUnindexedFilterActive

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        // 切换筛选时回到顶上：两份列表长短不同，停在原来的位置可能整屏都是空的
        val listState = rememberLazyListState()
        LaunchedEffect(filtering) { listState.scrollToItem(0) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                when (val node = row.node) {
                    is InstantGroup -> {
                        GroupRow(
                            group = node,
                            depth = row.depth,
                            // 筛选时组只是给几行交代出处，一律摊开，不让收起
                            isExpanded = filtering || state.isGroupExpanded(node),
                            summary = state.summaryOf(node),
                            onToggleExpanded = if (filtering) null else ({ state.toggleGroupExpanded(node) }),
                            onSelectAll = { state.setGroupSelected(node, it) },
                        )
                    }
                    is InstantRow -> {
                        // 开着规范命名时行上就是存进去的名字，番号已在名字里，不再挂芯片
                        val renamed = state.renamedLabel(node)
                        InstantFileRow(
                            row = if (renamed != null) node.copy(label = renamed, code = null) else node,
                            fullName = state.items[node.index].file.name,
                            depth = row.depth,
                            isInstantReady = !state.isUnindexed(node),
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
                                onDownloaded()
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
    /** 为 null 时不能收起，也不画展开箭头。 */
    onToggleExpanded: (() -> Unit)?,
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
            .then(
                if (onToggleExpanded != null) {
                    Modifier.clickable(onClickLabel = if (isExpanded) "收起" else "展开", onClick = onToggleExpanded)
                } else {
                    Modifier
                },
            )
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
        // 收起的组里看不到行上的标记，组行上写明有几项，展开前就知道里面有慢的
        if (summary.unindexed > 0) {
            UnindexedMark(count = summary.unindexed)
        }
        if (onToggleExpanded != null) {
            Icon(
                imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * 未收录的标记：文件行上只有图标，组行上带组内的行数。原先是 outline 色的云下载图标，浅得像禁用，
 * 又与旁边的下载按钮同形；改为沙漏、tertiary 色，悬停或长按给出与横幅相同的说法。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnindexedMark(count: Int? = null) {
    val description = if (count != null) "其中 $count 项$UNINDEXED_HINT" else UNINDEXED_HINT
    val tint = MaterialTheme.colorScheme.tertiary
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
        modifier = Modifier.padding(start = 12.dp),
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = UnindexedIcon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
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

/** 收起后的一行：标题是链接或资源名，状态是解析与勾选的进度。窄窗口 sheet 收起时露出的那一截与宽窗口的浮动卡片共用。 */
internal class InstantSummary(val title: String, val status: String?, val busy: Boolean, val resolving: Boolean)

internal fun InstantSheetState.summary(): InstantSummary {
    val result = resolution
    val batch = batch
    val saving = isSaving || batch?.isSaving == true
    val title = when {
        batch != null -> "${batch.rows.size} 条链接"
        else -> result?.resource?.name ?: input.trim().ifEmpty { "添加链接" }
    }
    val status = when {
        saving -> "正在保存"
        batch != null -> batch.blockedReason ?: "可保存 ${batch.submittableCount} 项"
        // 分享链接不走磁力解析，errorMessage 里那句「非磁力链接，可离线下载」说的不是它
        InstantSheetState.findShareLink(input) != null -> "分享链接"
        isResolving -> resolvingText(this)
        errorMessage != null -> errorMessage
        result != null -> "已选 $selectedEntryCount / $entryCount"
        else -> null
    }
    return InstantSummary(title, status, busy = saving, resolving = saving || isResolving)
}
