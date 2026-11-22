package dev.piko.ui.screens.instant

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.VaultArchiveOptions
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.FileNameField
import dev.piko.ui.components.FolderPickerDialog
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.*
import dev.piko.ui.screens.drive.VaultRiskWarningDialog
import dev.piko.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * 独立导入草稿及批量链接子编辑器的共同输入与提交界面。
 *
 * 状态机在 shared 的 [InstantSheetState]，由 [InstantSession] 持有，面板收起时不丢；
 * 这里只有 Material 的布局与外观。保存结果也不在这里收：面板可能已经收起，结果由持有会话的
 * 网盘页处理。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InstantSheetContent(
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
    /** 宽窗口独立导入草稿启用双栏 `ResolvedImportWorkspace`（单链与批量解析后自动切入）。 */
    useImportWorkspace: Boolean = fillHeight,
    /** 选中保存位置后的回调（例如驱动左侧网盘同步导航到该目录）。 */
    onTargetPicked: ((List<PathBreadcrumb>) -> Unit)? = null,
    /** 结果属性或地址栏触发新链接解析。 */
    onOpenLink: ((String) -> Unit)? = null,
    /** 预览的文件已秒传进 Piko-Temp，交给播放器打开。 */
    onPreview: (fileId: String, fileName: String) -> Unit,
) {
    val platform = LocalPikoPlatform.current
    val services = LocalPikoServices.current
    val preferences = services.preferences
    val scope = rememberCoroutineScope()
    var showTargetPicker by remember { mutableStateOf(false) }
    var vaultOptions by remember { mutableStateOf<VaultArchiveOptions?>(null) }
    var pendingReferenceWarning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vaultOptions = VaultArchiveOptions.load(preferences) }

    val batch = state.batch
    LaunchedEffect(state.resolution, batch, useImportWorkspace) {
        if (state.resolution != null || batch != null) {
            view.started = true
            if (useImportWorkspace) view.enteredWorkspace = true
        }
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

    val supportsReferenceSave = (state.resolution != null && state.supportsReferenceSave) ||
        (batch != null && batch.rows.any { it.state.supportsReferenceSave })
    val referenceEnabled = state.canEdit && !state.hasRetainedInstantAttempt &&
        batch?.rows?.any { !it.state.canEdit || it.state.hasRetainedInstantAttempt } != true
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
    val referenceToggle = if (supportsReferenceSave) {
        ReferenceSaveToggle(checked = reference, enabled = referenceEnabled, onCheckedChange = updateReference)
    } else null

    val focusManager = LocalFocusManager.current
    val fileListState = key(state.input) { rememberLazyListState() }
    var isTreeMaximized by rememberSaveable(state.input) { mutableStateOf(false) }
    LaunchedEffect(state.resolution) {
        if (state.resolution == null) isTreeMaximized = false
    }
    BackHandler(enabled = isTreeMaximized) { isTreeMaximized = false }

    @Composable
    fun SaveSettings(showReferenceCheckbox: Boolean) {
        if (supportsReferenceSave) {
            if (showReferenceCheckbox) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(reference, enabled = referenceEnabled, role = Role.Checkbox, onValueChange = updateReference),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(reference, null, Modifier.size(48.dp).clearAndSetSemantics {}, enabled = referenceEnabled)
                    Text(stringResource(Res.string.text_save_as_references), style = MaterialTheme.typography.bodyMedium)
                }
            }
            val referenceStates = (batch?.rows?.map { it.state } ?: listOf(state)).filter { it.supportsReferenceSave }
            val missing = referenceStates.sumOf { it.missingReferenceCount }
            if (reference && missing > 0) {
                val allMissing = referenceStates.all { it.referenceItemsToSave.isEmpty() }
                Text(
                    text = if (allMissing) {
                        tr("所选项目均缺少引用，无法以引用保存", "Selected items lack references and cannot be saved as references", "選択した項目に参照情報がないため、参照として保存できません", "선택한 항목에 참조가 없어 참조로 저장할 수 없습니다", "所選項目均缺少引用，無法以引用儲存")
                    } else {
                        tr("已选文件中有 $missing 项缺少引用，保存时将跳过", "${enCount(missing, "selected item", "selected items")} lack references and will be skipped", "選択したファイルのうち $missing 項目は参照がないためスキップします", "선택한 파일 중 ${missing}개 항목은 참조가 없어 건너뜁니다", "已選檔案中有 $missing 項缺少引用，儲存時將略過")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (allMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!allMissing) {
                    TextButton(onClick = { referenceStates.forEach { it.deselectUnindexed() } }, enabled = referenceEnabled) {
                        Text(stringResource(Res.string.text_deselect_items_without_references))
                    }
                }
            }
        }
        TargetRow(
            target = state.target,
            notice = state.targetNotice,
            enabled = state.canEdit,
            onClick = { showTargetPicker = true },
        )
    }

    val density = LocalDensity.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val fullWorkspace = (useImportWorkspace || view.enteredWorkspace) && fillHeight && !state.isLazyShareMode &&
        (batch != null || state.resolution != null || view.started)
    val splitSelection = !fullWorkspace && fillHeight &&
        containerSize.width > 0 &&
        containerSize.height > 0 &&
        with(density) { containerSize.width.toDp() >= 720.dp && containerSize.height.toDp() < 480.dp } &&
        batch == null &&
        state.resolution != null

    Box(
        Modifier
            .fillMaxWidth()
            .then(if (inSideSheet || fillHeight) Modifier.fillMaxHeight() else Modifier)
            .onSizeChanged { containerSize = it },
    ) {
        LaunchedEffect(splitSelection, fullWorkspace) {
            if (splitSelection || fullWorkspace) isTreeMaximized = false
        }
        @Composable
        fun Footer() {
            if (action != null) {
                if (!isTreeMaximized || splitSelection) SaveSettings(showReferenceCheckbox = true)
                SaveBar(state = state, action = action, onSave = saveMagnet, saveAsReference = reference, referenceToggle = null)
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
                .padding(horizontal = if (fullWorkspace) 0.dp else if (inSideSheet) 16.dp else 24.dp)
                .padding(bottom = if (fullWorkspace) 0.dp else if (inSideSheet) 16.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (fullWorkspace) {
                val sources = batch?.rows?.map { row -> ImportSource(row.key, row.state, { batch.remove(row) }, !batch.isSaving) }
                    ?: listOf(ImportSource("single", state))
                ResolvedImportWorkspace(
                    sources = sources,
                    view = view,
                    onDownloaded = { showReceipt(QUEUED_RECEIPT) },
                    onOpenLink = onOpenLink ?: state::updateInput,
                    managementShown = view.expanded,
                    onManagementChange = { view.expanded = it },
                    onShortcut = saveKeyHandler,
                    status = {
                        if (batch == null) ImportResolutionStatus(state, notice)
                        else ImportBatchStatus(batch, notice)
                    },
                    saveBar = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SaveSettings(showReferenceCheckbox = true)
                            if (batch != null) {
                                SaveButton(
                                    label = if (reference) tr("全部以引用保存", "Save all as references", "すべて参照として保存", "모두 참조로 저장", "全部以引用儲存") else tr("全部保存", "Save all", "すべて保存", "모두 저장", "全部儲存"),
                                    enabled = batch.canSaveAll,
                                    isSaving = batch.isSaving,
                                    onClick = batch::saveAll,
                                )
                            } else if (action != null) {
                                SaveBar(state = state, action = action, onSave = saveMagnet, showProblems = false, saveAsReference = reference, referenceToggle = null)
                            }
                        }
                    },
                    config = {
                        if (batch == null) {
                            LinkInput(state)
                            SharePassCodeInput(state)
                        }
                        if (sources.any { it.state.offersCanonicalNames }) {
                            CanonicalNamesToggle(state.useCanonicalNames, enabled = state.canEdit, onChange = state::updateUseCanonicalNames)
                        }
                        sources.filter { it.state.willCreateFolder }.forEach { source ->
                            val blank = source.state.folderNameToSave.isBlank()
                            FileNameField(
                                value = source.state.folderNameToSave,
                                onValueChange = source.state::updateFolderName,
                                label = tr("新建文件夹", "New folder", "新規フォルダー", "새 폴더", "新增資料夾"),
                                collapseWhenIdle = false,
                                enabled = source.state.canEdit && batch?.isSaving != true,
                                isError = blank,
                                supportingText = if (blank) tr("名称不能为空", "Name cannot be empty", "名前を入力してください", "이름을 입력해 주세요", "名稱不能為空") else null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                )
                return@Column
            }

            val openedRow = batch?.openedRow
            if (batch != null) {
                if (openedRow != null) {
                    BatchRowDetail(batch, openedRow, notice, onReceipt = showReceipt)
                } else {
                    BatchList(
                        batch = batch,
                        state = state,
                        notice = notice,
                        showTitle = !inSideSheet && !headerShown,
                        fillHeight = inSideSheet || fillHeight,
                        referenceToggle = referenceToggle,
                        saveSettings = { SaveSettings(showReferenceCheckbox = true) },
                    )
                }
                return@Column
            }

            if (splitSelection) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(
                        Modifier.weight(.45f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        InstantBody(state, false, notice, showReceipt, showSelection = false)
                        Footer()
                    }
                    Column(Modifier.weight(.55f).fillMaxHeight()) {
                        SelectionHeader(state)
                        FileTreeList(state, { showReceipt(QUEUED_RECEIPT) }, Modifier.weight(1f), fileListState)
                    }
                }
            } else {
                // 内容与保存栏分开：侧边面板里内容占满中间，保存栏钉在底部，解析前后、勾多勾少，按钮都在同一处
                Column(
                    modifier = Modifier.weight(1f, fill = inSideSheet || fillHeight || isTreeMaximized),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isTreeMaximized && state.resolution != null) {
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

/** 单条链接时保存栏以上的部分：标题、输入框、解析进度与出错说明、解析结果。 */
@Composable
private fun ColumnScope.InstantBody(
    state: InstantSheetState,
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

    if (state.isResolving) {
        ResolvingRow(text = resolvingText(state))
    }

    state.errorMessage?.let { err ->
        // 解析失败与未收录都给重试：未收录的资源过一阵可能就被索引了
        val canRetry = pendingMagnet != null && result == null && !state.isResolving
        ErrorBanner(
            message = err,
            onRetry = if (canRetry) state::retryResolve else null,
        )
    }
    state.saveBlockReason?.takeUnless { it == state.errorMessage }?.let { ErrorBanner(it, onRetry = null) }

    notice?.let { NoticeBanner(it) }

    if (result != null) {
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

internal val QUEUED_RECEIPT: String get() = tr("已加入下载队列", "Added to download queue", "ダウンロードキューに追加しました", "다운로드 대기열에 추가했습니다", "已加入下載佇列")

/** 解析的两段：先等服务端列出文件，再在本机按文件名整理。用户只需知道还在进行，不提「索引」这类内部说法。 */
internal fun resolvingText(state: InstantSheetState): String =
    if (state.isAnalyzing) tr("正在整理文件", "Organizing files", "ファイルを整理中", "파일 정리 중", "正在整理檔案")
    else tr("正在解析链接", "Resolving link", "リンクを解析中", "링크 분석 중", "正在解析連結")

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
    state.shareTree?.takeIf { it.isLazyMode }?.let { browser ->
        ShareTreeProgressBanner(browser = browser, onCancel = state::cancelShareSave)
    }
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
 * 也说不出是哪几项。哪几项靠后面的按钮：只列出这些行。
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
internal fun SelectionHeader(
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
        val headerLabel = state.shareTree?.takeIf { it.isLazyMode }?.let { browser ->
            tr("分享内容（${browser.selectedSummaryText}）", "Shared items (${browser.selectedSummaryText})", "共有アイテム（${browser.selectedSummaryText}）", "공유 항목 (${browser.selectedSummaryText})", "分享內容（${browser.selectedSummaryText}）")
        } ?: tr("原始文件（已选 ${state.sourceSelectedIndices.size} / ${state.items.size}）", "Original files (${state.sourceSelectedIndices.size} / ${state.items.size} selected)", "元ファイル（${state.sourceSelectedIndices.size} / ${state.items.size} 選択済み）", "원본 파일 (${state.sourceSelectedIndices.size} / ${state.items.size}개 선택됨)", "原始檔案（已選 ${state.sourceSelectedIndices.size} / ${state.items.size}）")
        Text(
            text = headerLabel,
            style = MaterialTheme.typography.labelLarge.tabular,
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

/** 收起后的一行：标题是链接或资源名，状态是解析与勾选的进度。窄窗口 sheet 收起时露出的那一截与宽窗口的浮动卡片共用。 */
class InstantSummary(val title: String, val status: String?, val busy: Boolean, val resolving: Boolean)

fun InstantSheetState.summary(): InstantSummary {
    val result = resolution
    val batch = batch
    val saving = isSaving || batch?.isSaving == true
    val title = when {
        batch != null -> tr("${batch.rows.size} 条链接", "${enCount(batch.rows.size, "link", "links")}", "${batch.rows.size} 件のリンク", "링크 ${batch.rows.size}개", "${batch.rows.size} 條連結")
        else -> result?.resource?.name
            ?: input.trim().ifEmpty { tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結") }
    }
    val status = when {
        saving -> tr("正在保存", "Saving", "保存中", "저장 중", "正在儲存")
        batch != null -> batch.blockedReason ?: tr("可保存 ${batch.submittableCount} 项", "${enCount(batch.submittableCount, "item", "items")} ready to save", "${batch.submittableCount} 項目を保存可能", "${batch.submittableCount}개 항목 저장 가능", "可儲存 ${batch.submittableCount} 項")
        isResolving -> resolvingText(this)
        errorMessage != null -> errorMessage
        isLazyShareMode -> shareTree?.selectedSummaryText
        result != null -> tr("已选 $selectedEntryCount / $entryCount", "Selected $selectedEntryCount / $entryCount", "選択済み $selectedEntryCount / $entryCount", "$selectedEntryCount / ${entryCount}개 선택됨", "已選 $selectedEntryCount / $entryCount")
        else -> null
    }
    return InstantSummary(title, status, busy = saving, resolving = saving || isResolving)
}
