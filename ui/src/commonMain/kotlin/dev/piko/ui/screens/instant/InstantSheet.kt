package dev.piko.ui.screens.instant

import dev.piko.shared.i18n.enCount
import dev.piko.shared.presentation.localizedName

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.Key

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import dev.piko.shared.state.VaultArchiveOptions
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.screens.drive.VaultRiskWarningDialog
import kotlinx.coroutines.launch
import dev.piko.ui.components.FileNameField
import dev.piko.ui.components.FolderPickerDialog
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.fileNameTypeIcon
import dev.piko.ui.components.toReadableSize
import dev.piko.shared.presentation.ImportTreeSourceVersion
import dev.piko.shared.presentation.importTree
import dev.piko.shared.presentation.visibleImportTree
import dev.piko.ui.components.FileItemHover
import dev.piko.ui.components.FileItemName
import dev.piko.ui.components.FileItemSelection
import dev.piko.ui.components.FileItemSelectionControl
import dev.piko.ui.components.FilePresentation
import dev.piko.ui.components.FileVisual
import dev.piko.ui.components.WorkspaceTreeRow
import dev.piko.ui.components.filePresentation
import dev.piko.ui.components.typeIcon
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.ui.backhandler.BackHandler
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge

/**
 * 独立导入草稿及批量链接子编辑器的共同输入与提交界面。
 *
 * 状态机在 shared 的 [InstantSheetState]，由 [InstantSession] 持有，面板收起时不丢；
 * 这里只有 Material 的布局与外观。保存结果也不在这里收：面板可能已经收起，结果由持有会话的
 * 网盘页处理。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun InstantSheetContent(
    state: InstantSheetState,
    view: ImportWorkspaceViewState = remember { ImportWorkspaceViewState() },
    /**
     * 在侧栏或模态侧边面板里：标题由面板顶上那一行画，内容不再画；侧栏只有三百来 dp，两边留白收窄。
     * 底部 sheet 没有那一行，标题照旧在内容里。
     */
    inSideSheet: Boolean = false,
    /** 窄窗口的 sheet 顶上已有一行标题与状态（收起时露出的那一截），内容里不再画标题。 */
    headerShown: Boolean = false,
    fillHeight: Boolean = false,
    /** 选中保存位置后的回调（例如驱动左侧网盘同步导航到该目录）。 */
    onTargetPicked: ((List<PathBreadcrumb>) -> Unit)? = null,
    /** 预览的文件已秒传进 Piko-Temp，交给播放器打开。 */
    onPreview: (fileId: String, fileName: String) -> Unit,
) {
    val platform = LocalPikoPlatform.current
    val services = LocalPikoServices.current
    val platformDownloads = services.downloadManager
    val preferences = services.preferences
    val scope = rememberCoroutineScope()
    var showTargetPicker by remember { mutableStateOf(false) }
    var vaultOptions by remember { mutableStateOf<VaultArchiveOptions?>(null) }
    var pendingReferenceWarning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vaultOptions = VaultArchiveOptions.load(preferences) }

    val batch = state.batch
    LaunchedEffect(state.resolution, batch) {
        if (state.resolution != null || batch != null) view.started = true
    }
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
    val shareState = state.shareSave

    val action = state.primaryAction
    var askOffline by remember { mutableStateOf<OfflineConfirm?>(null) }
    // 单条磁力的保存：免费账号要离线时先确认，按钮与快捷键走同一条路
    val saveMagnet: () -> Unit = {
        val confirm = action?.let { offlineConfirmOf(state, it) }
        if (confirm != null) askOffline = confirm else state.performPrimaryAction()
    }
    // 主修饰键+Enter 保存。不用单独的 Enter：它在输入框里换行（多条链接一行一条），在文件行上是勾选
    val saveByShortcut: (() -> Unit)? = when {
        batch != null -> if (batch.canSaveAll) batch::saveAll else null
        shareState != null -> state.target?.takeIf { shareState.selectedIds.isNotEmpty() && !shareState.isSaving }
            ?.let { target -> { shareState.save(PikoPathBreadcrumb(target.id, target.name), canonicalNames = state.useCanonicalNames) } }
        action != null && action.enabled && !state.isSaving && (state.hasInstantRetry || state.savePlan?.blocked != true) -> saveMagnet
        else -> null
    }
    val shortcutModifier = platform.shortcutModifier
    val saveKeyHandler: (KeyEvent) -> Boolean = { event ->
        val save = saveByShortcut
        if (save != null && event.type == KeyEventType.KeyDown && event.key == Key.Enter && shortcutModifier.isPressed(event)) {
            save()
            true
        } else false
    }

    val focusManager = LocalFocusManager.current
    val fileListState = key(state.input) { rememberLazyListState() }
    var isTreeMaximized by rememberSaveable(state.input) { mutableStateOf(false) }
    LaunchedEffect(state.resolution) {
        if (state.resolution == null) isTreeMaximized = false
    }
    BackHandler(enabled = isTreeMaximized) { isTreeMaximized = false }
    @Composable
    fun SaveSettings() {
        if (state.resolution != null || batch != null) {
            val referenceEnabled = state.canEdit && !state.hasRetainedInstantAttempt && batch?.rows?.any { !it.state.canEdit || it.state.hasRetainedInstantAttempt } != true
            val reference = batch?.saveAsReference ?: state.saveAsReference
            val rawUpdateReference: (Boolean) -> Unit = if (batch != null) batch::updateSaveAsReference else state::updateSaveAsReference
            val updateReference: (Boolean) -> Unit = { checked ->
                val opts = vaultOptions
                if (checked && opts != null && !opts.riskNoticeAcknowledged) {
                    pendingReferenceWarning = true
                } else {
                    rawUpdateReference(checked)
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(reference, enabled = referenceEnabled,
                role = Role.Checkbox, onValueChange = updateReference), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(reference, null, Modifier.size(48.dp).clearAndSetSemantics {}, enabled = referenceEnabled)
                Text(stringResource(Res.string.text_save_as_references), style = MaterialTheme.typography.bodyMedium)
            }
            val referenceStates = batch?.rows?.map { it.state } ?: listOf(state)
            val missing = referenceStates.sumOf { it.missingReferenceCount }
            if (reference && missing > 0) {
                val allMissing = referenceStates.all { it.referenceItemsToSave.isEmpty() }
                Text(if (allMissing) tr("所选项目均缺少引用，无法以引用保存", "Selected items lack references and cannot be saved as references", "選択した項目に参照情報がないため、参照として保存できません", "선택한 항목에 참조가 없어 참조로 저장할 수 없습니다", "所選項目均缺少引用，無法以引用儲存") else tr("已选文件中有 $missing 项缺少引用，保存时将跳过", "${enCount(missing, "selected item", "selected items")} lack references and will be skipped", "選択したファイルのうち $missing 項目は参照がないためスキップします", "선택한 파일 중 ${missing}개 항목은 참조가 없어 건너뜁니다", "已選檔案中有 $missing 項缺少引用，儲存時將略過"),
                    color = if (allMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!allMissing) TextButton(onClick = { referenceStates.forEach { it.deselectUnindexed() } }, enabled = referenceEnabled) { Text(stringResource(Res.string.text_deselect_items_without_references)) }
            }
        }
        TargetRow(target = state.target, notice = state.targetNotice, enabled = state.canEdit,
            onClick = { showTargetPicker = true })
    }
    BoxWithConstraints(Modifier.fillMaxWidth().then(if (inSideSheet || fillHeight) Modifier.fillMaxHeight() else Modifier)) {
        val splitSelection = fillHeight && maxWidth >= 720.dp && maxHeight < 480.dp && batch == null &&
            (shareState != null || state.resolution != null)
        LaunchedEffect(splitSelection) {
            if (splitSelection) isTreeMaximized = false
        }
        @Composable
        fun Footer() {
            if (shareState != null) {
                ShareSaveFooter(state = shareState, target = state.target, canonicalNames = state.useCanonicalNames, onPickTarget = { showTargetPicker = true })
            } else if (action != null) {
                if (!isTreeMaximized || splitSelection) SaveSettings()
                SaveBar(state = state, action = action, onSave = saveMagnet)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 侧边面板是整窗高：占满它，保存栏才能钉在底部，文件树用满中间的高度。
                // 底部 sheet 随内容定高，照旧收缩
                .then(if (inSideSheet || fillHeight) Modifier.fillMaxHeight() else Modifier)
                // 点面板的空白处交出输入框的焦点。子项自己的点击先消费，走不到这里
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .onPreviewKeyEvent(saveKeyHandler)
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
                        fillHeight = inSideSheet || fillHeight,
                        saveSettings = { SaveSettings() },
                    )
                }
                return@Column
            }

            if (splitSelection) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(.45f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        InstantBody(state, shareState, false, notice, showReceipt, showSelection = false)
                        Footer()
                    }
                    Column(Modifier.weight(.55f).fillMaxHeight()) {
                        if (shareState != null) {
                            ShareSaveSection(
                                state = shareState, canonicalNames = state.useCanonicalNames,
                                onCanonicalNamesChange = state::updateUseCanonicalNames,
                                onPreview = state::previewSharedFile, previewingId = state.previewingSharedId,
                                onDownload = { file ->
                                    platformDownloads.enqueueResolved(listOf(io.github.nihildigit.pikpak.ResolvedFile(file.name, file.sizeBytes, file.hash)), state.target?.id.orEmpty())
                                    showReceipt(QUEUED_RECEIPT)
                                },
                            )
                        } else {
                            SelectionHeader(state)
                            FileTreeList(state, { showReceipt(QUEUED_RECEIPT) }, Modifier.weight(1f), fileListState)
                        }
                    }
                }
            } else {
                // 内容与保存栏分开：侧边面板里内容占满中间，保存栏钉在底部，解析前后、勾多勾少，按钮都在同一处
                Column(
                    modifier = Modifier.weight(1f, fill = inSideSheet || fillHeight || isTreeMaximized),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isTreeMaximized && shareState == null && state.resolution != null) {
                        Column(modifier = Modifier.weight(1f)) {
                            SelectionHeader(
                                state = state,
                                isMaximized = true,
                                onToggleMaximized = { isTreeMaximized = false },
                            )
                            FileTreeList(
                                state = state,
                                onDownloaded = { showReceipt(QUEUED_RECEIPT) },
                                modifier = Modifier.weight(1f),
                                retainedListState = fileListState,
                            )
                        }
                    } else {
                        InstantBody(
                            state = state,
                            shareState = shareState,
                            showTitle = !inSideSheet && !headerShown,
                            notice = notice,
                            onReceipt = showReceipt,
                            fileListState = fileListState,
                            onMaximizeTree = { isTreeMaximized = true },
                        )
                    }
                }

                Footer()
            }
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

    if (pendingReferenceWarning) {
        VaultRiskWarningDialog(
            onAcknowledge = {
                pendingReferenceWarning = false
                val next = (vaultOptions ?: VaultArchiveOptions()).copy(riskNoticeAcknowledged = true)
                vaultOptions = next
                scope.launch { VaultArchiveOptions.save(preferences, next) }
                if (batch != null) batch.updateSaveAsReference(true) else state.updateSaveAsReference(true)
            },
            onDismiss = { pendingReferenceWarning = false },
        )
    }

    if (showTargetPicker) {
        FolderPickerDialog(
            title = tr("选择保存位置", "Choose save location", "保存先を選択", "저장 위치 선택", "選擇儲存位置"),
            confirmLabel = tr("保存到此处", "Save here", "ここに保存", "여기에 저장", "儲存至此處"),
            onDismiss = { showTargetPicker = false },
            onConfirm = { targetId, targetName ->
                showTargetPicker = false
                state.changeTarget(PathBreadcrumb(targetId, targetName))
                true
            },
            onConfirmPath = { path -> onTargetPicked?.invoke(path) },
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
    showSelection: Boolean = true,
    fileListState: LazyListState? = null,
    onMaximizeTree: (() -> Unit)? = null,
) {
    if (state.resolution?.resource?.truncated == true) {
        StatusBanner(
            tr("清单不完整：仅保存已解析文件，禁用自动裁剪", "Incomplete list: save resolved files only; automatic trimming disabled", "一覧が不完全です。解析済みのファイルのみ保存し、自動整理は行いません", "불완전한 목록: 분석된 파일만 저장하고 자동 정리는 사용하지 않습니다", "清單不完整：僅儲存已解析的檔案，停用自動裁剪"),
            Icons.Outlined.Info, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
            actionLabel = tr("重新解析", "Resolve again", "再解析", "다시 분석", "重新解析"), onAction = state::retryResolve,
        )
    }
    val platform = LocalPikoPlatform.current
    val downloads = LocalPikoServices.current.downloadManager
    val pendingMagnet = state.normalizedMagnet
    val result = state.resolution

    // 底部 sheet 有拖动条，下滑、点遮罩、返回都能关，标题行不再放关闭按钮
    if (showTitle) {
        Text(
            text = stringResource(Res.string.text_add_link),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    // 输入框一直在，外部带链接打开的也是：换链接就是在这里改或粘贴，同一个会话重新解析，不另设按钮。
    // 解析出结果后缩成一行把高度让给文件列表，点进去编辑时放回三行
    LinkInput(state)
    SharePassCodeInput(state)

    if (shareState != null && showSelection) {
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
    if (shareState == null) state.saveBlockReason?.takeUnless { it == state.errorMessage }?.let { ErrorBanner(it, onRetry = null) }

    notice?.let { NoticeBanner(it) }

    if (shareState == null && result != null) {
        ResolutionSection(
            state = state,
            resourceName = result.resource.name,
            // 链接就在上面的输入框里
            showCopyLink = false,
            onDownloaded = { onReceipt(QUEUED_RECEIPT) },
            showFileSelection = showSelection,
            fileListState = fileListState,
            onMaximizeTree = onMaximizeTree,
        )
    }
}


@Composable
internal fun LinkInput(state: InstantSheetState) {
    val platform = LocalPikoPlatform.current
    LinkInputField(
        value = state.input,
        hasResolution = state.resolution != null,
        enabled = state.canEdit,
        onValueChange = state::updateInput,
        onPaste = {
            val clip = platform.readClipboardText()
            if (!clip.isNullOrBlank()) state.updateInput(clip.trim())
        },
    )
}

@Composable
fun LinkInputField(
    value: String,
    hasResolution: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onPaste: () -> Unit,
) {
    var inputFocused by remember { mutableStateOf(false) }
    val inputCompact = hasResolution && !inputFocused
    // 始终保持 singleLine = false（纵向滚动器），仅通过 maxLines 在 1 行与 3 行间折叠：
    // CoreTextField 内部用不带 orientation 键的 rememberSaveable 缓存 TextFieldScrollerPosition，
    // 动态切换 singleLine 会在聚焦展开为 3 行时触发 Mismatching scroller orientation 炸毁 Skiko 帧循环。
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(Res.string.text_magnet_download_or_share_link)) },
        placeholder = { Text("magnet:?xt=urn:btih:…") },
        modifier = Modifier.fillMaxWidth().onFocusChanged { inputFocused = it.isFocused },
        shape = MaterialTheme.shapes.largeIncreased,
        singleLine = false,
        maxLines = if (inputCompact) 1 else 3,
        enabled = enabled,
        trailingIcon = {
            IconButton(onClick = onPaste) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = tr("从剪贴板粘贴", "Paste from clipboard", "クリップボードから貼り付け", "클립보드에서 붙여넣기", "從剪貼簿貼上"))
            }
        },
    )
}

internal val QUEUED_RECEIPT: String get() = tr("已加入下载队列", "Added to download queue", "ダウンロードキューに追加しました", "다운로드 대기열에 추가했습니다", "已加入下載佇列")

/** 解析的两段：先等服务端列出文件，再在本机按文件名整理。用户只需知道还在进行，不提「索引」这类内部说法。 */
internal fun resolvingText(state: InstantSheetState): String = if (state.isAnalyzing) tr("正在整理文件", "Organizing files", "ファイルを整理中", "파일 정리 중", "正在整理檔案") else tr("正在解析链接", "Resolving link", "リンクを解析中", "링크 분석 중", "正在解析連結")

/** 免费账号这次保存要不要先确认离线；会员与秒传为 null。 */
private fun offlineConfirmOf(state: InstantSheetState, action: InstantPrimaryAction): OfflineConfirm? {
    val plan = state.savePlan.takeIf { state.resolution != null && !state.contentMissing && !state.hasInstantRetry && state.saveIssue?.requiresInspection != true }
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
private fun SaveBar(state: InstantSheetState, action: InstantPrimaryAction, onSave: () -> Unit, showProblems: Boolean = true) {
    val plan = state.savePlan.takeIf { state.resolution != null && !state.contentMissing && !state.hasInstantRetry && state.saveIssue?.requiresInspection != true }
    val fallback = plan?.fallback
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showProblems && plan?.incompleteCatalog == true) {
            ErrorBanner(tr("文件清单不完整，无法在整包离线下载后自动裁剪", "Incomplete file list; automatic trimming unavailable after full cloud download", "ファイル一覧が不完全なため、一括クラウドダウンロード後の自動整理は利用できません", "파일 목록이 불완전하여 전체 클라우드 다운로드 후 자동 정리를 사용할 수 없습니다", "檔案清單不完整，無法於整包離線下載後自動裁剪"), onRetry = null)
        } else if (showProblems && plan != null && plan.lacksSpace) {
            // 秒传没有退路按钮可给，只能少选几项；整包离线的退路在下面的按钮上
            val hint = if (plan.route == SaveRoute.INSTANT) tr("，请减少所选文件", "; select fewer files", "。選択数を減らしてください", ". 선택 항목을 줄여 주세요", "，請減少所選檔案") else ""
            ErrorBanner(
                message = tr("网盘空间不足：需要 ${plan.neededBytes.toReadableSize()}，", "Insufficient cloud space: ${plan.neededBytes.toReadableSize()} required, ", "空き容量不足：${plan.neededBytes.toReadableSize()} 必要、", "클라우드 공간 부족: ${plan.neededBytes.toReadableSize()} 필요, ", "雲端硬碟空間不足：需要 ${plan.neededBytes.toReadableSize()}，") +
                    tr("剩余 ${(state.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}$hint", "${(state.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()} available$hint", "空き容量 ${(state.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}$hint", "남은 공간 ${(state.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}$hint", "剩餘 ${(state.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}$hint"),
                onRetry = null,
            )
        } else if (showProblems && plan != null && plan.lacksOfflineCount) {
            ErrorBanner(message = tr("今日离线次数已用完", "Daily cloud download limit reached", "本日のクラウドダウンロード回数を使い切りました", "오늘의 클라우드 다운로드 횟수를 모두 사용했습니다", "今日離線次數已用完"), onRetry = null)
        }
        if (plan?.blocked == true && fallback != null) {
            SaveButton(
                label = if (fallback.skippedCount > 0) tr("仅保存已收录文件", "Save indexed files only", "収録済みファイルのみ保存", "등록된 파일만 저장", "僅儲存已收錄檔案") else tr("仅保存所选文件", "Save selected files only", "選択したファイルのみ保存", "선택한 파일만 저장", "僅儲存所選檔案"),
                enabled = state.canSaveSelection,
                isSaving = state.isSaving,
                onClick = state::saveSelectionInstantly,
            )
            if (fallback.skippedCount > 0) SaveCaption(tr("将跳过 ${fallback.skippedCount} 个未收录文件", "Skip ${fallback.skippedCount} unindexed files", "未収録の ${fallback.skippedCount} ファイルをスキップ", "미등록 파일 ${fallback.skippedCount}개 건너뛰기", "將略過 ${fallback.skippedCount} 個未收錄檔案"))
        } else {
            SaveButton(
                label = when {
                    state.hasInstantRetry && state.retryFileCount > 0 -> tr("重试未保存的 ${state.retryFileCount} 个文件", "Retry ${state.retryFileCount} unsaved files", "未保存の ${state.retryFileCount} ファイルを再試行", "저장 안 된 파일 ${state.retryFileCount}개 다시 시도", "重試未儲存的 ${state.retryFileCount} 個檔案")
                    state.saveIssue?.requiresInspection == true -> tr("请先核对保存结果", "Check save results first", "先に保存結果を確認してください", "먼저 저장 결과를 확인하세요", "請先核對儲存結果")
                    state.contentMissing -> tr("离线下载", "Cloud download", "クラウドダウンロード", "클라우드 다운로드", "離線下載")
                    else -> tr("保存", "Save", "保存", "저장", "儲存")
                },
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
    val count = offlineLeft?.let { tr("（今日剩余 $it 次）", " ($it remaining today)", "（本日の残り $it 回）", " (오늘 ${it}회 남음)", "（今日剩餘 $it 次）") }.orEmpty()
    val (title, message) = when (confirm) {
        OfflineConfirm.WholeLink -> tr("离线下载", "Cloud download", "クラウドダウンロード", "클라우드 다운로드", "離線下載") to tr("将使用 1 次离线下载额度$count。", "Uses 1 cloud download quota$count.", "クラウドダウンロード枠を1回使用します$count。", "클라우드 다운로드 할당량 1회를 사용합니다$count.", "將使用 1 次離線下載額度$count。")
        is OfflineConfirm.Pack -> tr("整包离线", "Download entire package to cloud", "一括クラウドダウンロード", "전체 클라우드 다운로드", "整包離線下載") to
            tr("所选文件未收录，需执行整包离线下载，将占用 ${confirm.plan.packBytes.toReadableSize()} 网盘空间与 1 次离线下载额度$count。", "Selected files are not indexed. Full cloud download requires ${confirm.plan.packBytes.toReadableSize()} of storage and 1 cloud download quota$count.", "選択したファイルは未収録のため、一括クラウドダウンロードが必要です。${confirm.plan.packBytes.toReadableSize()} の容量と1回の利用枠を使用します$count。", "선택한 파일이 미등록 상태이므로 전체 클라우드 다운로드가 필요하며, ${confirm.plan.packBytes.toReadableSize()} 공간과 할당량 1회를 사용합니다$count.", "所選檔案未收錄，需執行整包離線下載，將占用 ${confirm.plan.packBytes.toReadableSize()} 雲端硬碟空間與 1 次離線下載額度$count。")
        is OfflineConfirm.PackOrInstant -> tr("部分文件未收录", "Some files are not indexed", "一部のファイルは未収録です", "일부 파일이 미등록입니다", "部分檔案未收錄") to
            tr("${confirm.fallback.skippedCount} 个文件未收录，整包离线下载将", "${enCount(confirm.fallback.skippedCount, "file", "files")} are not indexed. Full cloud download ", "${confirm.fallback.skippedCount} ファイルが未収録です。一括クラウドダウンロードでは ", "파일 ${confirm.fallback.skippedCount}개가 미등록 상태입니다. 전체 클라우드 다운로드 시 ", "${confirm.fallback.skippedCount} 個檔案未收錄，整包離線下載將") +
            tr("占用 ${confirm.plan.packBytes.toReadableSize()} 网盘空间与 1 次离线下载额度$count。", "requires ${confirm.plan.packBytes.toReadableSize()} of storage and 1 cloud download quota$count.", "${confirm.plan.packBytes.toReadableSize()} の容量と1回の利用枠を使用します$count。", "${confirm.plan.packBytes.toReadableSize()} 공간과 할당량 1회를 사용합니다$count.", "占用 ${confirm.plan.packBytes.toReadableSize()} 雲端硬碟空間與 1 次離線下載額度$count。") +
            tr("也可仅秒传已收录的 ${confirm.fallback.fileCount} 个文件。", "Alternatively, instantly save the ${confirm.fallback.fileCount} indexed files only.", "収録済みの ${confirm.fallback.fileCount} ファイルのみをインスタント保存することも可能です。", "등록된 파일 ${confirm.fallback.fileCount}개만 즉시 저장할 수도 있습니다.", "亦可僅秒傳已收錄的 ${confirm.fallback.fileCount} 個檔案。")
    }
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            // 两条路并列时都是文字按钮：哪条更好由人按空间与次数定，给哪条底色都是替人选
            if (confirm is OfflineConfirm.PackOrInstant) {
                Row {
                    TextButton(onClick = onOffline) { Text(stringResource(Res.string.text_download_entire_package_to_cloud)) }
                    TextButton(onClick = onInstant) { Text(stringResource(Res.string.text_save_indexed_only)) }
                }
            } else {
                PikoDialogConfirm(tr("离线下载", "Cloud download", "クラウドダウンロード", "클라우드 다운로드", "離線下載"), onClick = onOffline)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
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
            Text(stringResource(Res.string.text_saving))
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
        actionLabel = tr("重试", "Retry", "再試行", "다시 시도", "重試").takeIf { onRetry != null },
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
    showFileSelection: Boolean = true,
    fileListState: LazyListState? = null,
    onMaximizeTree: (() -> Unit)? = null,
) {
    // 批量列表里点开的一行没有输入框，链接看不到，标题右侧留一个复制入口，好转发或换设备打开
    Row(verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.weight(1f)) {
            if (state.willCreateFolder) {
                val isBlank = state.folderNameToSave.isBlank()
                FileNameField(
                    value = state.folderNameToSave,
                    onValueChange = state::updateFolderName,
                    label = tr("新建文件夹", "New folder", "新規フォルダー", "새 폴더", "新增資料夾"),
                    collapseWhenIdle = true,
                    enabled = state.canEdit,
                    isError = isBlank,
                    supportingText = if (isBlank) tr("名称不能为空", "Name cannot be empty", "名前を入力してください", "이름을 입력해 주세요", "名稱不能為空") else null,
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
    if (state.offersCanonicalNames) CanonicalNamesToggle(state.useCanonicalNames, enabled = state.canEdit, onChange = state::updateUseCanonicalNames)

    if (!showFileSelection) return

    // 列表连同它的页眉占去剩下的高度，短列表照常收缩
    Column(modifier = Modifier.weight(1f, fill = false)) {
        SelectionHeader(state, isMaximized = false, onToggleMaximized = onMaximizeTree)
        FileTreeList(state, onDownloaded = onDownloaded, modifier = Modifier.weight(1f, fill = false), retainedListState = fileListState)
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
        message = tr("$count 项$UNINDEXED_HINT", "${enCount(count, "item", "items")}: $UNINDEXED_HINT", "$count 項目：$UNINDEXED_HINT", "${count}개 항목: $UNINDEXED_HINT", "$count 項$UNINDEXED_HINT"),
        icon = UnindexedIcon,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        actionLabel = if (filtering) tr("显示全部", "Show all", "すべて表示", "모두 표시", "顯示全部") else tr("仅显示未收录", "Show unindexed only", "未収録のみ表示", "미등록만 표시", "僅顯示未收錄"),
        onAction = state::toggleOnlyUnindexed,
    )
}

/**
 * 保存时按番号规范命名。资源里有会改名的番号文件才出现，默认开启；开着时列表行直接显示存进去的名字，
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
        Text(stringResource(Res.string.text_normalize_names_by_catalog_code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

// 横幅、组行与文件行的标记同一种说法与图标。用沙漏而不是云下载：行上的「下载到本机」按钮就是云下载，
// 两者挨着时分不清哪个能点
internal val UNINDEXED_HINT: String get() = tr("未收录，需离线下载", "not indexed; requires cloud download", "未収録（クラウドダウンロードが必要）", "미등록 (클라우드 다운로드 필요)", "未收錄，需離線下載")
internal val UnindexedIcon = Icons.Outlined.HourglassTop

/** 已选计数、全选与展开/收起文件树按钮。筛选只看未收录时不给全选：它作用于全部行，眼前却只列着几行，点了会动到看不见的勾选。 */
@Composable
private fun SelectionHeader(
    state: InstantSheetState,
    isMaximized: Boolean = false,
    onToggleMaximized: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tr("原始文件（已选 ${state.sourceSelectedIndices.size} / ${state.items.size}）", "Original files (${state.sourceSelectedIndices.size} / ${state.items.size} selected)", "元ファイル（${state.sourceSelectedIndices.size} / ${state.items.size} 選択済み）", "원본 파일 (${state.sourceSelectedIndices.size} / ${state.items.size}개 선택됨)", "原始檔案（已選 ${state.sourceSelectedIndices.size} / ${state.items.size}）"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (!state.isUnindexedFilterActive) {
            TextButton(onClick = state::toggleSelectAll, enabled = state.canEdit) {
                Text(if (state.isAllSelected) tr("取消全选", "Deselect all", "すべて選択解除", "모두 선택 해제", "取消全選") else tr("全选", "Select all", "すべて選択", "모두 선택", "全選"))
            }
        }
        if (onToggleMaximized != null) {
            TooltipIconButton(
                icon = if (isMaximized) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull,
                label = if (isMaximized) tr("收起文件树", "Collapse file tree", "ファイルツリーを折りたたむ", "파일 트리 접기", "收起檔案樹狀目錄") else tr("展开文件树", "Expand file tree", "ファイルツリーを展開", "파일 트리 펼치기", "展開檔案樹狀目錄"),
                onClick = onToggleMaximized,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 右侧显示原始文件目录树，默认勾选需要的条目，支持展开目录精细调整。 */
@Composable
private fun FileTreeList(state: InstantSheetState, onDownloaded: () -> Unit, modifier: Modifier = Modifier, retainedListState: LazyListState? = null) {
    val version = remember(state.resolution) {
        ImportTreeSourceVersion("single", state.resolution?.resource?.name ?: state.input, state.resolution?.resource?.files)
    }
    val nodes = remember(version) {
        importTree(version.files.orEmpty().map { it.path })
    }
    var collapsed by remember(version) { mutableStateOf(emptySet<String>()) }
    val filtering = state.isUnindexedFilterActive
    val visible = remember(nodes, collapsed, filtering, state.items) {
        val base = visibleImportTree(nodes, nodes.mapTo(HashSet()) { it.key } - collapsed, "")
        if (!filtering) base else base.filter { node -> node.indices.any { !state.items[it].isInstantReady } }
    }
    val hasBranches = visible.any { it.isFolder }
    val editable = state.canEdit

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        val listState = retainedListState ?: rememberLazyListState()
        var previousFilter by rememberSaveable { mutableStateOf(filtering) }
        LaunchedEffect(filtering) {
            if (previousFilter != filtering) {
                listState.scrollToItem(0)
                previousFilter = filtering
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(visible, key = { it.key }) { node ->
                val selected = node.indices.count { it in state.sourceSelectedIndices }
                val checked = when (selected) {
                    0 -> ToggleableState.Off
                    node.indices.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
                val index = node.fileIndex
                val toggle = { collapsed = if (node.key in collapsed) collapsed - node.key else collapsed + node.key }
                val item = if (index == null) FilePresentation(
                    node.key, FileItemName(node.name), FileVisual(node.name, isFolder = true),
                    metadata = listOf(tr("${node.indices.size} 个文件", "${enCount(node.indices.size, "file", "files")}", "${node.indices.size} ファイル", "파일 ${node.indices.size}개", "${node.indices.size} 個檔案")),
                ) else state.items[index].file.filePresentation(node.key)
                val open = { if (node.isFolder) toggle() else if (editable) state.setSourceSelected(node.indices, selected == 0) }
                WorkspaceTreeRow(
                    name = node.name,
                    depth = node.depth,
                    icon = item.visual.typeIcon(),
                    expanded = if (node.isFolder) node.key !in collapsed else null,
                    onExpand = toggle,
                    onOpen = open,
                    onLongClick = null,
                    item = item,
                    reserveExpandSpace = hasBranches,
                    leading = {
                        FileItemSelectionControl(
                            FileItemSelection(
                                checked,
                                { if (editable) state.setSourceSelected(node.indices, checked != ToggleableState.On) },
                                enabled = editable,
                                label = tr("选择来源 ${node.name}", "Select source ${node.name}", "ソース ${node.name} を選択", "원본 ${node.name} 선택", "選取來源 ${node.name}"),
                            ),
                        )
                    },
                )
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
    selectionEnabled: Boolean,
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
                    Modifier.clickable(onClickLabel = if (isExpanded) tr("收起", "Collapse", "折りたたむ", "접기", "收起") else tr("展开", "Expand", "展開", "펼치기", "展開"), onClick = onToggleExpanded)
                } else {
                    Modifier
                },
            )
            .padding(start = 4.dp + TreeIndent * depth, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = checkState, enabled = selectionEnabled, onClick = { onSelectAll(checkState != ToggleableState.On) })
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
                    if (selectedCount == total || selectedCount == 0) tr("$total 项", "${enCount(total, "item", "items")}", "$total 項目", "${total}개 항목", "$total 項") else tr("已选 $selectedCount / $total", "Selected $selectedCount / $total", "選択済み $selectedCount / $total", "$selectedCount / ${total}개 선택됨", "已選 $selectedCount / $total"),
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
    val description = if (count != null) tr("其中 $count 项$UNINDEXED_HINT", "Including ${enCount(count, "item", "items")}: $UNINDEXED_HINT", "うち $count 項目：$UNINDEXED_HINT", "그중 ${count}개 항목: $UNINDEXED_HINT", "其中 $count 項$UNINDEXED_HINT") else UNINDEXED_HINT
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
    val kind = if (link.startsWith("magnet:", ignoreCase = true)) tr("磁力链接", "Magnet link", "マグネットリンク", "마그넷 링크", "磁力連結") else tr("链接", "Link", "リンク", "링크", "連結")
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(tr("复制$kind", "Copy $kind", "$kind をコピー", "$kind 복사", "複製$kind")) } },
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
                    contentDescription = if (done) tr("已复制", "Copied", "コピーしました", "복사했습니다", "已複製") else tr("复制$kind", "Copy $kind", "$kind をコピー", "$kind 복사", "複製$kind"),
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
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onPreview: (() -> Unit)?,
    isPreviewing: Boolean,
    onDownload: (() -> Unit)?,
) {
    val isCompact = row.label.length <= SHORT_LABEL
    val meta = listOfNotNull(
        row.bytes.toReadableSize(),
        tr("+${row.subtitleCount} 字幕", "+${row.subtitleCount} subtitles", "+${row.subtitleCount} 字幕", "+${row.subtitleCount}개 자막", "+${row.subtitleCount} 字幕").takeIf { row.subtitleCount > 0 },
        tr("+${row.audioTrackCount} 音轨", "+${row.audioTrackCount} audio tracks", "+${row.audioTrackCount} 音声", "+${row.audioTrackCount}개 오디오 트랙", "+${row.audioTrackCount} 音軌").takeIf { row.audioTrackCount > 0 },
    )
    // 整行是一个复选项，Checkbox 只作显示，免得同一次点击被行与复选框各处理一遍
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            // 单行时就是复选框的 48dp，再加上下边距，二十几集的列表会显得松散
            .padding(start = 4.dp + TreeIndent * depth, end = 16.dp, top = if (isCompact) 0.dp else 4.dp, bottom = if (isCompact) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = null, modifier = Modifier.minimumInteractiveComponentSize())
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
            label = tr("预览", "Preview", "プレビュー", "미리 보기", "預覽"),
            onClick = onClick,
            modifier = Modifier.padding(start = 4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DownloadContentButton(onClick: () -> Unit) {
    TooltipIconButton(Icons.Outlined.CloudDownload, tr("下载", "Downloads", "ダウンロード", "다운로드", "下載"), onClick,
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
                    text = stringResource(Res.string.text_save_to),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = target?.localizedName ?: tr("正在确认", "Confirming", "確認中", "확인 중", "正在確認"),
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
                    contentDescription = tr("更换保存位置", "Change save location", "保存先を変更", "저장 위치 변경", "更換儲存位置"),
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
        batch != null -> tr("${batch.rows.size} 条链接", "${enCount(batch.rows.size, "link", "links")}", "${batch.rows.size} 件のリンク", "링크 ${batch.rows.size}개", "${batch.rows.size} 條連結")
        else -> result?.resource?.name ?: shareSave?.info?.title?.takeIf { it.isNotBlank() }
            ?: input.trim().ifEmpty { tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結") }
    }
    val status = when {
        saving -> tr("正在保存", "Saving", "保存中", "저장 중", "正在儲存")
        batch != null -> batch.blockedReason ?: tr("可保存 ${batch.submittableCount} 项", "${enCount(batch.submittableCount, "item", "items")} ready to save", "${batch.submittableCount} 項目を保存可能", "${batch.submittableCount}개 항목 저장 가능", "可儲存 ${batch.submittableCount} 項")
        // 分享链接不走磁力解析，errorMessage 里那句「非磁力链接，可离线下载」说的不是它
        shareSave != null -> shareSave?.doneMessage ?: shareSave?.errorMessage ?: tr("分享链接", "Share link", "共有リンク", "공유 링크", "分享連結")
        isResolving -> resolvingText(this)
        errorMessage != null -> errorMessage
        result != null -> tr("已选 $selectedEntryCount / $entryCount", "Selected $selectedEntryCount / $entryCount", "選択済み $selectedEntryCount / $entryCount", "$selectedEntryCount / ${entryCount}개 선택됨", "已選 $selectedEntryCount / $entryCount")
        else -> null
    }
    return InstantSummary(title, status, busy = saving, resolving = saving || isResolving)
}
