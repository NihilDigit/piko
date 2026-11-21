package dev.piko.ui.screens.instant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.ImportTreeSource
import dev.piko.shared.presentation.ImportTreeSourceVersion
import dev.piko.shared.presentation.importSourceForest
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.presentation.visibleImportSources
import dev.piko.shared.state.AddressCompletion
import dev.piko.shared.state.DriveItemName
import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.InstantBatchState
import dev.piko.shared.state.InstantSheetState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.*
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.*
import dev.piko.ui.screens.drive.*
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.islandSurface
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

internal data class ImportSource(val key: String, val state: InstantSheetState, val onRemove: (() -> Unit)? = null, val enabled: Boolean = true)

internal class ImportManagementState {
    var query by mutableStateOf("")
    var searchShown by mutableStateOf(false)
    var collapsed by mutableStateOf<Set<String>>(emptySet())
    var settingsShown by mutableStateOf(false)
    val list = LazyListState()
}

internal class ImportBrowserState {
    val path = mutableStateOf("")
    val query = mutableStateOf("")
    var searchShown by mutableStateOf(false)
    val mode = mutableStateOf(DriveViewMode.LIST.name)
    val selected = mutableStateOf(emptyList<String>())
    val grid = LazyGridState()
    val detail = DetailPanelState<ImportResultEntry>(DetailPanelSpec({ tr("属性", "Properties", "プロパティ", "속성", "屬性") }, { tr("关闭属性", "Close properties", "プロパティを閉じる", "속성 닫기", "關閉屬性") }))
}

/** Owned by the workspace context, so moving between a tab and a routed page retains the same UI state. */
class ImportWorkspaceViewState {
    var expanded by mutableStateOf(false)
    var started by mutableStateOf(false)
    var enteredWorkspace by mutableStateOf(false)
    var managerWidthDp by mutableStateOf<Float?>(null)
    internal val management = ImportManagementState()
    internal val browser = ImportBrowserState()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImportIconToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    uncheckedIcon: ImageVector,
    checkedIcon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val isEInk = LocalAppearance.current.isEInk
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        FilledTonalIconToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            shapes = IconButtonDefaults.toggleableShapes(),
            colors = if (isEInk) {
                IconButtonDefaults.filledTonalIconToggleButtonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    checkedContainerColor = Color.Black,
                    checkedContentColor = Color.White,
                )
            } else {
                IconButtonDefaults.filledTonalIconToggleButtonColors()
            },
            modifier = if (isEInk && !checked) Modifier.border(1.dp, Color.Black, CircleShape) else Modifier,
        ) {
            Icon(
                imageVector = if (checked) checkedIcon else uncheckedIcon,
                contentDescription = label,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResolvedImportWorkspace(
    sources: List<ImportSource>,
    view: ImportWorkspaceViewState,
    onDownloaded: () -> Unit,
    onOpenLink: (String) -> Unit,
    managementShown: Boolean,
    onManagementChange: (Boolean) -> Unit,
    onShortcut: (KeyEvent) -> Boolean,
    status: @Composable () -> Unit,
    saveBar: @Composable () -> Unit,
    config: @Composable ColumnScope.() -> Unit,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val width = with(density) { viewport.width.toDp() }
    val height = with(density) { viewport.height.toDp() }
    val isEInk = LocalAppearance.current.isEInk
    // Change sheet ownership in composition, after measuring; disposing popup owners during subcomposition is invalid.
    val split = width >= 840.dp || (width >= 720.dp && height < 480.dp)
    val defaultManagerWidth = if (height < 480.dp) 320.dp else 360.dp
    val maxManagerWidth = (width - 360.dp).coerceAtLeast(280.dp)
    val managerWidth = (view.managerWidthDp?.dp ?: defaultManagerWidth).coerceIn(280.dp, maxManagerWidth)
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        val management = view.management
        val browser = view.browser
        val scope = rememberCoroutineScope()
        @Composable fun Result() { ImportResultBrowser(sources, browser, onDownloaded, onOpenLink) }
        @Composable fun Manager(modifier: Modifier) {
            SourceImportTree(sources, management, modifier, config)
        }
        @Composable fun Header() {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(Res.string.text_manage_links), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    ImportIconToggleButton(
                        checked = management.searchShown,
                        onCheckedChange = { next ->
                            management.searchShown = next
                            if (!next) management.query = ""
                            else if (!split) onManagementChange(true)
                            scope.launch { management.list.scrollToItem(0) }
                        },
                        uncheckedIcon = Icons.Outlined.Search,
                        checkedIcon = Icons.Filled.Search,
                        label = tr("搜索来源文件", "Search source files", "ソースファイルを検索", "원본 파일 검색", "搜尋來源檔案"),
                    )
                    ImportIconToggleButton(
                        checked = management.settingsShown,
                        onCheckedChange = { next ->
                            management.settingsShown = next
                            if (next && !split) onManagementChange(true)
                            scope.launch { management.list.scrollToItem(0) }
                        },
                        uncheckedIcon = Icons.Outlined.Tune,
                        checkedIcon = Icons.Filled.Tune,
                        label = tr("保存设置", "Save options", "保存設定", "저장 설정", "儲存設定"),
                    )
                }
                status()
            }
        }
        if (split) Row(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.islandSurface,
                border = if (isEInk) BorderStroke(1.dp, Color.Black) else null,
            ) {
                Result()
            }
            SplitPaneDragHandle(
                onDragDeltaPx = { deltaPx ->
                    val current = view.managerWidthDp ?: defaultManagerWidth.value
                    val deltaDp = with(density) { deltaPx.toDp().value }
                    view.managerWidthDp = (current - deltaDp).coerceIn(280f, maxManagerWidth.value)
                },
                onResetWidth = { view.managerWidthDp = null },
            )
            Surface(
                modifier = Modifier.width(managerWidth).fillMaxHeight(),
                shape = MaterialTheme.shapes.large,
                color = if (isEInk) Color.White else MaterialTheme.colorScheme.surfaceContainerLow,
                border = if (isEInk) BorderStroke(1.dp, Color.Black) else null,
            ) {
                Column(Modifier.onPreviewKeyEvent(onShortcut)) {
                    Header()
                    Manager(Modifier.weight(1f))
                    Box(Modifier.padding(12.dp)) { saveBar() }
                }
            }
        } else TaskSheetScaffold(
            TaskSheetModel(
                key = "import-management",
                expanded = managementShown,
                onExpandedChange = onManagementChange,
                header = { Header() },
                body = {
                    Manager(Modifier.fillMaxWidth().weight(1f).onPreviewKeyEvent(onShortcut))
                    Box(Modifier.fillMaxWidth().padding(12.dp)) { saveBar() }
                },
            ),
        ) { Result() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SplitPaneDragHandle(
    onDragDeltaPx: (Float) -> Unit,
    onResetWidth: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val latestDrag by rememberUpdatedState(onDragDeltaPx)
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val pillColor by animateColorAsState(
        targetValue = when {
            isEInk -> Color.Black
            dragging -> colors.primary
            hovered -> colors.onSurfaceVariant
            else -> colors.outlineVariant
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "split_pane_handle",
    )
    val resizeCursor = LocalHorizontalResizeCursor.current
    Box(
        modifier = Modifier
            .width(12.dp)
            .fillMaxHeight()
            .then(if (resizeCursor != null) Modifier.pointerHoverIcon(resizeCursor) else Modifier)
            .hoverable(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {},
                onDoubleClick = onResetWidth,
            )
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    latestDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(if (dragging || hovered) 4.dp else 3.dp)
                .height(if (dragging) 48.dp else 36.dp)
                .clip(CircleShape)
                .background(pillColor),
        )
    }
}

@Composable
internal fun ImportResolutionStatus(state: InstantSheetState, notice: PanelNotice?) {
    val plan = state.savePlan
    val missing = state.sourceSelectedIndices.count { !state.items[it].isInstantReady }
    val error = state.saveBlockReason ?: state.errorMessage ?: when {
        state.willCreateFolder && state.folderNameToSave.isBlank() -> tr("保存文件夹名称不能为空", "Save folder name cannot be empty", "保存先フォルダー名を入力してください", "저장 폴더 이름을 입력해 주세요", "儲存資料夾名稱不能為空")
        state.effectiveSaveAsReference && state.missingReferenceCount > 0 && state.referenceItemsToSave.isEmpty() -> tr("所选项目均缺少引用，无法以引用保存", "Selected items lack references and cannot be saved as references", "選択した項目に参照情報がないため、参照として保存できません", "선택한 항목에 참조가 없어 참조로 저장할 수 없습니다", "所選項目均缺少引用，無法以引用儲存")
        plan?.lacksSpace == true -> tr("网盘空间不足，请减少选择或更换保存位置", "Not enough cloud space. Select fewer files or change destination", "空き容量が不足しています。選択を減らすか保存先を変更してください", "클라우드 공간이 부족합니다. 선택을 줄이거나 저장 위치를 변경하세요", "雲端硬碟空間不足，請減少選取項目或更換儲存位置")
        plan?.lacksOfflineCount == true -> tr("今日离线次数已用完", "Daily cloud download limit reached", "本日のクラウドダウンロード回数を使い切りました", "오늘의 클라우드 다운로드 횟수를 모두 사용했습니다", "今日離線次數已用完")
        else -> null
    }
    val message = error ?: notice?.text ?: when {
        state.isResolving -> tr("正在解析链接", "Resolving link", "リンクを解析中", "링크 분석 중", "正在解析連結")
        state.resolution?.resource?.truncated == true -> tr("清单不完整，自动裁剪已停用", "Incomplete file list; automatic trimming disabled", "ファイル一覧が不完全なため、自動整理を無効にしました", "파일 목록이 불완전하여 자동 정리를 껐습니다", "清單不完整，已停用自動裁剪")
        state.effectiveSaveAsReference && state.missingReferenceCount > 0 -> tr("已选文件中有 ${state.missingReferenceCount} 项缺少引用，保存时将跳过", "${state.missingReferenceCount} selected items lack references and will be skipped", "選択した ${state.missingReferenceCount} 項目に参照がなく、保存時にスキップします", "선택한 ${state.missingReferenceCount}개 항목에 참조가 없어 저장 시 건너뜁니다", "已選檔案中有 ${state.missingReferenceCount} 項缺少引用，儲存時將略過")
        missing > 0 -> tr("已选文件中有 ${missing} 项未收录", "${missing} selected items are not indexed", "選択した ${missing} 項目は未収録です", "선택한 ${missing}개 항목이 미등록입니다", "已選檔案中有 ${missing} 項未收錄")
        else -> null
    }
    if (message != null) {
        val isError = error != null || notice?.isError == true
        val colors = MaterialTheme.colorScheme
        val isEInk = LocalAppearance.current.isEInk
        val containerColor = when {
            isEInk -> Color.White
            isError -> colors.errorContainer
            else -> colors.surfaceContainerHigh
        }
        val contentColor = when {
            isEInk -> Color.Black
            isError -> colors.onErrorContainer
            else -> colors.onSurfaceVariant
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            contentColor = contentColor,
            border = if (isEInk) BorderStroke(1.dp, Color.Black) else null,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.isResolving && !isError) {
                    InlineLoadingIndicator(color = contentColor)
                } else {
                    Icon(
                        imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = contentColor,
                    )
                }
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = contentColor)
                if (state.effectiveSaveAsReference && state.missingReferenceCount > 0 && state.referenceItemsToSave.isNotEmpty()) {
                    TextButton(onClick = state::deselectUnindexed, enabled = state.canEdit) { Text(stringResource(Res.string.text_uncheck)) }
                }
                if (state.resolution?.resource?.truncated == true) TooltipIconButton(
                    Icons.Outlined.Refresh, tr("重新解析链接", "Resolve link again", "リンクを再解析", "링크 다시 분석", "重新解析連結"), state::retryResolve, enabled = state.canEdit,
                )
            }
        }
    }
}

@Composable
internal fun ImportBatchStatus(batch: InstantBatchState, notice: PanelNotice?) {
    val error = batch.blockedReason ?: when {
        batch.lacksSpace -> tr("网盘空间不足：需要 ${batch.neededBytes.toReadableSize()}，剩余 ${(batch.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}", "Insufficient cloud space: ${batch.neededBytes.toReadableSize()} required, ${(batch.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()} available", "空き容量不足：${batch.neededBytes.toReadableSize()} 必要、空き容量 ${(batch.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}", "클라우드 공간 부족: ${batch.neededBytes.toReadableSize()} 필요, 남은 공간 ${(batch.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}", "雲端硬碟空間不足：需要 ${batch.neededBytes.toReadableSize()}，剩餘 ${(batch.remainingBytes ?: 0L).coerceAtLeast(0L).toReadableSize()}")
        batch.lacksOfflineCount -> tr("需使用 ${batch.offlineCount} 次离线下载额度，今日剩余 ${batch.offlineLeft} 次", "Requires ${batch.offlineCount} cloud downloads; ${batch.offlineLeft} remaining today", "${batch.offlineCount} 回のクラウドダウンロード枠が必要です（本日の残り ${batch.offlineLeft} 回）", "클라우드 다운로드 할당량 ${batch.offlineCount}회 필요 (오늘 ${batch.offlineLeft}회 남음)", "需使用 ${batch.offlineCount} 次離線下載額度，今日剩餘 ${batch.offlineLeft} 次")
        else -> null
    }
    val message = error ?: notice?.text ?: when {
        batch.offlineCount > 0 -> tr("其中 ${batch.offlineCount} 项需离线下载", "${batch.offlineCount} require cloud download", "うち ${batch.offlineCount} 件はクラウドダウンロードが必要", "그중 ${batch.offlineCount}개는 클라우드 다운로드 필요", "其中 ${batch.offlineCount} 項需離線下載")
        else -> null
    }
    if (message != null) {
        val isError = error != null || notice?.isError == true
        val colors = MaterialTheme.colorScheme
        val isEInk = LocalAppearance.current.isEInk
        val containerColor = when {
            isEInk -> Color.White
            isError -> colors.errorContainer
            else -> colors.surfaceContainerHigh
        }
        val contentColor = when {
            isEInk -> Color.Black
            isError -> colors.onErrorContainer
            else -> colors.onSurfaceVariant
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            contentColor = contentColor,
            border = if (isEInk) BorderStroke(1.dp, Color.Black) else null,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = contentColor)
            }
        }
    }
}

@Composable
internal fun SharePassCodeInput(state: InstantSheetState) {
    if (!state.needsSharePassCode) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.sharePassCode,
            onValueChange = state::updateSharePassCode,
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(Res.string.text_access_code)) },
            singleLine = true,
            enabled = state.canEdit,
        )
        TextButton(onClick = state::submitSharePassCode, enabled = state.canEdit && state.sharePassCode.isNotBlank()) {
            Text(stringResource(Res.string.text_resolve))
        }
    }
}

@Composable
private fun PillSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    closeLabel: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    val isEInk = LocalAppearance.current.isEInk
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    if (query.isNotEmpty()) onQueryChange("") else onClose()
                    true
                } else false
            }
            .then(if (isEInk) Modifier.border(1.dp, Color.Black, MaterialTheme.shapes.large) else Modifier),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = if (isEInk) Color.White else MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = if (isEInk) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        trailingIcon = {
            IconButton(
                onClick = { if (query.isNotEmpty()) onQueryChange("") else onClose() },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = if (query.isNotEmpty()) clearLabel else closeLabel,
                )
            }
        },
    )
}

@Composable
private fun SourceImportTree(sources: List<ImportSource>, management: ImportManagementState, modifier: Modifier, config: @Composable ColumnScope.() -> Unit) {
    val versions = sources.map { source ->
        val resolution = source.state.resolution
        ImportTreeSourceVersion(source.key, resolution?.resource?.name ?: source.state.input, resolution?.resource?.files)
    }
    val nodes = remember(versions) {
        importSourceForest(versions.map { version -> ImportTreeSource(version.key, version.files.orEmpty().map { it.path }) })
    }
    LaunchedEffect(nodes) {
        if (nodes.size > 200 && management.collapsed.isEmpty()) {
            management.collapsed = nodes.filter { it.node.isFolder && it.node.depth >= 1 }.mapTo(HashSet()) { it.key }
        }
    }
    val query = management.query
    val collapsed = management.collapsed
    val visible = remember(nodes, collapsed, query) {
        val expanded = nodes.filter { it.node.isFolder && it.key !in collapsed }.mapTo(HashSet()) { it.key }
        visibleImportSources(nodes, expanded, query)
    }
    val hasBranches = visible.any { it.node.isFolder }
    val sourceByKey = sources.associateBy { it.key }
    var details by remember { mutableStateOf<ImportSource?>(null) }
    val platform = LocalPikoPlatform.current
    fun sourceActions(source: ImportSource): List<SheetAction> = buildList {
        add(SheetAction(Icons.Outlined.ContentCopy, tr("复制来源链接", "Copy source link", "ソースリンクをコピー", "출처 링크 복사", "複製來源連結"), { platform.copyToClipboard(tr("来源链接", "Source link", "ソースリンク", "출처 링크", "來源連結"), source.state.input) }))
        if (source.enabled && source.state.canEdit && source.state.input.isNotBlank()) add(SheetAction(Icons.Outlined.Refresh, tr("重新解析链接", "Resolve link again", "リンクを再解析", "링크 다시 분석", "重新解析連結"), source.state::retryResolve))
        if (source.enabled) source.onRemove?.let { remove -> add(SheetAction(Icons.Outlined.Close, tr("移除此链接", "Remove this link", "このリンクを削除", "이 링크 삭제", "移除此連結"), remove, destructive = true)) }
    }
    LazyColumn(modifier, state = management.list, contentPadding = PaddingValues(bottom = 8.dp)) {
        if (management.searchShown) item(key = "search") {
            PillSearchField(
                query = query,
                onQueryChange = { management.query = it },
                placeholder = stringResource(Res.string.text_search_original_files),
                clearLabel = tr("清空来源搜索", "Clear source search", "ソースの検索を消去", "원본 검색 지우기", "清除來源搜尋"),
                closeLabel = tr("关闭搜索", "Close search", "検索を閉じる", "검색 닫기", "關閉搜尋"),
                onClose = { management.searchShown = false },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                autoFocus = true,
            )
        }
        if (management.settingsShown) item(key = "settings") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = config)
        }
        items(visible, key = { "source:${it.key}" }) { entry ->
            val source = sourceByKey[entry.sourceKey] ?: return@items
            val state = source.state
            val editable = source.enabled && state.canEdit
            val node = entry.node
            val selected = state.sourceSelection.countIn(node.indices)
            val checked = when (selected) {
                0 -> ToggleableState.Off
                node.indices.size -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            }
            val index = node.fileIndex
            val toggle = { management.collapsed = if (entry.key in collapsed) collapsed - entry.key else collapsed + entry.key }
            val item = if (index == null) FilePresentation(
                node.key, FileItemName(node.name), FileVisual(node.name, isFolder = true),
                metadata = listOf(tr("${node.indices.size} 个文件", "${enCount(node.indices.size, "file", "files")}", "${node.indices.size} ファイル", "파일 ${node.indices.size}개", "${node.indices.size} 個檔案")),
            ) else (state.items.getOrNull(index) ?: return@items).file.filePresentation(node.key)
            val open = { if (node.isFolder) toggle() else if (editable) state.setSourceSelected(node.indices, selected == 0) }
            ContextMenuArea(actions = { sourceActions(source) }) {
                WorkspaceTreeRow(
                    node.name, node.depth, item.visual.typeIcon(),
                    expanded = if (node.isFolder) entry.key !in collapsed else null, onExpand = toggle,
                    onOpen = open, onLongClick = { details = source }, item = item, reserveExpandSpace = hasBranches,
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
        @OptIn(ExperimentalFoundationApi::class)
        items(sources.filter { it.state.items.isEmpty() && (query.isBlank() || it.state.input.contains(query, true)) }, key = { "pending:${it.key}" }) { source ->
            val state = source.state
            val longClick = fileItemLongPress { details = source }
            ContextMenuArea(actions = { sourceActions(source) }) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = {}, onLongClick = longClick)
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.resolution?.resource?.name ?: state.input,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = state.errorMessage ?: when {
                                    state.isResolving -> tr("正在解析链接", "Resolving link", "リンクを解析中", "링크 분석 중", "正在解析連結")
                                    state.needsSharePassCode -> tr("需要提取码", "Access code required", "アクセスコードが必要です", "접근 코드 필요", "需要提取碼")
                                    else -> tr("整条链接离线下载", "Download entire link to cloud", "リンク全体をクラウドダウンロード", "전체 링크 클라우드 다운로드", "整條連結離線下載")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            SharePassCodeInput(state)
        }
    }
    details?.let { source ->
        ItemDetailsSheet(
            title = source.state.resolution?.resource?.name ?: tr("来源链接", "Source link", "ソースリンク", "출처 링크", "來源連結"),
            headerIcon = { Icon(Icons.Outlined.Link, null) },
            onDismiss = { details = null },
            extraLines = { Text(source.state.input, style = MaterialTheme.typography.bodySmall) },
            actions = sourceActions(source),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImportResultViewToggle(
    viewMode: DriveViewMode,
    onViewModeChange: (DriveViewMode) -> Unit,
) {
    val modes = DriveViewMode.entries
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val toggleColors = OutlinedToggleButtonDefaults.colors(
        containerColor = if (isEInk) Color.White else Color.Transparent,
        contentColor = if (isEInk) Color.Black else colors.onSurfaceVariant,
        checkedContainerColor = if (isEInk) Color.Black else colors.secondaryContainer,
        checkedContentColor = if (isEInk) Color.White else colors.onSecondaryContainer,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        modes.forEachIndexed { index, itemMode ->
            val selected = itemMode == viewMode
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = { PlainTooltip { Text(itemMode.label) } },
                state = rememberTooltipState(),
            ) {
                OutlinedToggleButton(
                    checked = selected,
                    onCheckedChange = { if (!selected) onViewModeChange(itemMode) },
                    shapes = connectedToggleShapes(index, modes.size),
                    colors = toggleColors,
                    border = if (isEInk) BorderStroke(1.dp, Color.Black) else OutlinedToggleButtonDefaults.border(enabled = true, checked = selected),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Icon(
                        imageVector = itemMode.icon(selected = selected),
                        contentDescription = tr("${itemMode.label}视图", "${itemMode.label} view", "${itemMode.label}表示", "${itemMode.label} 보기", "${itemMode.label}檢視"),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ImportSelectionFloatingToolbar(
    visible: Boolean,
    allSelected: Boolean,
    onClearSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onPreview: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    onProperties: (() -> Unit)?,
    onExcludeFromSave: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val toolbarColors = if (isEInk) {
        FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = Color.White,
            toolbarContentColor = Color.Black,
        )
    } else {
        FloatingToolbarDefaults.standardFloatingToolbarColors()
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(motion.fastSpatialSpec()) { it / 2 } + fadeIn(motion.fastEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it / 2 } + fadeOut(motion.fastEffectsSpec()),
        modifier = modifier.padding(
            horizontal = FloatingToolbarDefaults.ScreenOffset,
            vertical = FloatingToolbarDefaults.ScreenOffset,
        ),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = toolbarColors,
            expandedShadowElevation = if (isEInk) 0.dp else 3.dp,
            collapsedShadowElevation = if (isEInk) 0.dp else 2.dp,
            modifier = if (isEInk) Modifier.border(1.dp, Color.Black, FloatingToolbarDefaults.ContainerShape) else Modifier,
        ) {
            val contentTint = LocalContentColor.current
            TooltipIconButton(
                icon = Icons.Outlined.Close,
                label = tr("取消选择", "Deselect", "選択を解除", "선택 해제", "取消選取"),
                onClick = onClearSelection,
                tint = contentTint,
            )
            TooltipIconButton(
                icon = if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                label = if (allSelected) tr("取消全选", "Deselect all", "すべて選択解除", "모두 선택 해제", "取消全選") else tr("全选", "Select all", "すべて選択", "모두 선택", "全選"),
                onClick = onToggleSelectAll,
                tint = contentTint,
            )
            val hasPrimaryActions = onPreview != null || onDownload != null || onProperties != null
            if (hasPrimaryActions) {
                VerticalDivider(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(horizontal = 2.dp)
                        .height(20.dp),
                    color = if (isEInk) Color.Black else colors.outlineVariant.copy(alpha = 0.7f),
                )
            }
            if (onPreview != null) {
                TooltipIconButton(
                    icon = Icons.Outlined.PlayCircle,
                    label = tr("预览", "Preview", "プレビュー", "미리 보기", "預覽"),
                    onClick = onPreview,
                    tint = contentTint,
                )
            }
            if (onDownload != null) {
                TooltipIconButton(
                    icon = Icons.Outlined.CloudDownload,
                    label = tr("下载", "Downloads", "ダウンロード", "다운로드", "下載"),
                    onClick = onDownload,
                    tint = contentTint,
                )
            }
            if (onProperties != null) {
                TooltipIconButton(
                    icon = Icons.Outlined.Info,
                    label = tr("属性", "Properties", "プロパティ", "속성", "屬性"),
                    onClick = onProperties,
                    tint = contentTint,
                )
            }
            if (onExcludeFromSave != null) {
                VerticalDivider(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(horizontal = 2.dp)
                        .height(20.dp),
                    color = if (isEInk) Color.Black else colors.outlineVariant.copy(alpha = 0.7f),
                )
                TooltipIconButton(
                    icon = Icons.Outlined.RemoveCircleOutline,
                    label = tr("取消勾选保存", "Exclude from save", "保存対象から外す", "저장 대상에서 제외", "取消勾選儲存"),
                    onClick = onExcludeFromSave,
                    tint = if (isEInk) Color.Black else colors.error,
                )
            }
        }
    }
}

/** The normal drive grid receives passive FileStat projections; its write actions are absent. */
@Composable
private fun ImportResultBrowser(sources: List<ImportSource>, browser: ImportBrowserState, onDownloaded: () -> Unit, onOpenLink: (String) -> Unit) {
    val descriptors = sources.map { source -> ImportProjectionSource(source.key, source.state.projectedSaveEntries) }
    val entries = remember(descriptors) { importResultEntries(descriptors) }
    val all = entries.map { it.file }
    val entryById = entries.associateBy { it.file.id }
    val byId = all.associateBy { it.id }
    val sourceByKey = sources.associateBy { it.key }
    val services = LocalPikoServices.current
    val downloads = rememberDownloadLauncher()
    val platform = LocalPikoPlatform.current
    val detail = browser.detail
    var moreId by remember { mutableStateOf<String?>(null) }
    var path by browser.path
    var query by browser.query
    var mode by browser.mode
    var selected by browser.selected
    fun stackFor(directory: String): List<PikoPathBreadcrumb> {
        val ancestors = mutableListOf<PikoPathBreadcrumb>()
        var current = byId[directory]
        while (current != null) {
            ancestors += PikoPathBreadcrumb(current.id, current.name)
            current = byId[current.parentId]
        }
        return listOf(PikoPathBreadcrumb("", tr("保存结果", "Save results", "保存結果", "저장 결과", "儲存結果"))) + ancestors.asReversed()
    }
    fun hasSelectedAncestor(file: FileStat, selectedSet: Set<String>): Boolean {
        var parentId = file.parentId
        while (parentId.isNotEmpty()) {
            if (parentId in selectedSet) return true
            parentId = byId[parentId]?.parentId.orEmpty()
        }
        return false
    }
    val stack = stackFor(path)
    val address = AddressBarModel(
        stack = stack,
        onNavigate = { index -> path = stack[index].id; query = "" },
        onOpenStack = { target -> path = target.last().id; query = "" },
        onSubmitPath = { text ->
            val directory = text.trim().replace('\\', '/').trim('/').removePrefix(tr("保存结果", "Save results", "保存結果", "저장 결과", "儲存結果")).trim('/')
            val match = all.filter { it.isFolder && stackFor(it.id).drop(1).joinToString("/") { it.name } == directory }.singleOrNull()
            if (directory.isEmpty() || match != null) { path = match?.id.orEmpty(); query = ""; true } else false
        },
        completions = { text ->
            AddressCompletion(
                text.substringAfterLast('/'), true,
                all.filter { it.isFolder && stackFor(it.id).drop(1).joinToString("/") { it.name }.startsWith(text.removePrefix(tr("保存结果/", "Save results/", "保存結果/", "저장 결과/", "儲存結果/")), true) }.map { stackFor(it.id) },
            )
        },
        subfoldersOf = { id -> Result.success(all.filter { it.isFolder && it.parentId == id }.map { PikoPathBreadcrumb(it.id, it.name) }) },
        recent = emptyList(), pinned = emptyList(), onOpenPinned = {}, onRemoveRecent = {},
        onOpenLink = onOpenLink,
        onSearchHere = { query = it; browser.searchShown = true },
        onSearchAll = { query = it; browser.searchShown = true },
        destinations = emptyList(),
    )
    LaunchedEffect(all) {
        if (path.isNotEmpty() && path !in byId) path = ""
        selected = selected.filter { it in byId }
        if (detail.target?.file?.id !in byId) detail.close()
    }
    val shown = all.filter { (if (query.isBlank()) it.parentId == path else it.name.contains(query, true)) }
    val selectedSet = remember(selected) { selected.toSet() }
    val coveredFileEntries = remember(entries, selectedSet, byId) {
        if (selectedSet.isEmpty()) emptyList()
        else entries.filter { entry ->
            entry.sourceIndex != null && (entry.file.id in selectedSet || hasSelectedAncestor(entry.file, selectedSet))
        }
    }
    val downloadableResolved = remember(coveredFileEntries, sourceByKey) {
        coveredFileEntries.mapNotNull { entry ->
            val state = sourceByKey[entry.sourceKey]?.state ?: return@mapNotNull null
            val idx = entry.sourceIndex ?: return@mapNotNull null
            val item = state.items.getOrNull(idx)?.takeIf { it.isInstantReady } ?: return@mapNotNull null
            val relPath = stackFor(entry.file.parentId).drop(1).joinToString("/") { it.name }
            val outPath = if (relPath.isEmpty()) entry.file.name else "$relPath/${entry.file.name}"
            item.file.copy(path = outPath)
        }
    }
    val canExcludeSelection = remember(coveredFileEntries, sourceByKey) {
        coveredFileEntries.isNotEmpty() && coveredFileEntries.any { entry ->
            val src = sourceByKey[entry.sourceKey]
            src != null && src.enabled && src.state.canEdit
        }
    }
    fun properties(file: FileStat) { entryById[file.id]?.let { detail.show(it, detail.anchors.beside(file.id)) } }
    fun open(file: FileStat) {
        if (file.isFolder) {
            path = file.id
            query = ""
            selected = emptyList()
        } else entryById[file.id]?.let { entry ->
            val state = sourceByKey[entry.sourceKey]?.state ?: return@let
            val index = entry.sourceIndex
            if (index != null && state.canPreview(index)) state.preview(index)
        }
    }
    fun actions(file: FileStat): List<SheetAction> = buildList {
        val entry = entryById[file.id] ?: return@buildList
        val source = sourceByKey[entry.sourceKey] ?: return@buildList
        val state = source.state
        val index = entry.sourceIndex
        if (file.isFolder) add(SheetAction(Icons.Outlined.FolderOpen, tr("打开", "Open", "開く", "열기", "開啟"), { open(file) }))
        if (index != null && state.canPreview(index)) add(SheetAction(Icons.Outlined.PlayCircle, tr("预览", "Preview", "プレビュー", "미리 보기", "預覽"), { state.preview(index) }))
        if (index != null && state.items.getOrNull(index)?.isInstantReady == true) add(SheetAction(Icons.Outlined.CloudDownload, tr("下载", "Downloads", "ダウンロード", "다운로드", "下載"), download@{
            val item = state.items.getOrNull(index)?.takeIf { it.isInstantReady } ?: return@download
            downloads.downloadResolved(listOf(item.file.copy(path = file.name)), state.target?.id.orEmpty()) { notice ->
                notice?.let(services::noticeOnTransfers)
                onDownloaded()
            }
        }))
        add(SheetAction(Icons.Outlined.Info, tr("属性", "Properties", "プロパティ", "속성", "屬性"), { properties(file) }, group = ActionGroup.Properties))
        add(SheetAction(Icons.Outlined.ContentCopy, tr("复制来源链接", "Copy source link", "ソースリンクをコピー", "출처 링크 복사", "複製來源連結"), { platform.copyToClipboard(tr("来源链接", "Source link", "ソースリンク", "출처 링크", "來源連結"), state.input) }))
        if (source.enabled && state.canEdit && index != null) {
            add(SheetAction(Icons.Outlined.RemoveCircleOutline, tr("取消勾选保存", "Exclude from save", "保存対象から外す", "저장 대상에서 제외", "取消勾選儲存"), {
                state.setSourceSelected(setOf(index), false)
            }, destructive = true))
        }
    }
    val callbacks = DriveItemCallbacks(
        onOpen = ::open, onRename = null, onMore = { moreId = it.id }, onProperties = ::properties,
        onPlaced = { file, coords -> detail.anchors.placed(file.id, coords) },
        onStartSelection = { file -> selected = listOf(file.id) },
        onLongClick = { file -> selected = if (file.id in selectedSet) selected - file.id else selected + file.id },
        onSelect = { file, value -> selected = if (value) (selected + file.id).distinct() else selected - file.id },
        onToggleSelect = { id -> selected = if (id in selectedSet) selected - id else selected + id },
        onExtendSelect = { ids -> selected = (selected + ids).distinct() },
        dragPayload = { null }, onMiddleClick = {},
        onBoxSelect = { base, boxed -> selected = (base + boxed).distinct() },
        onBackgroundClick = { selected = emptyList() }, onFocusChanged = { _, _ -> }, contextActions = ::actions,
        onToggleSection = {}, onFolderVisible = {},
    )
    BackHandler(path.isNotEmpty()) { path = byId[path]?.parentId.orEmpty() }
    BackHandler(selected.isNotEmpty()) { selected = emptyList() }
    val searchExpanded = browser.searchShown || query.isNotEmpty()
    val singleSelectedFile = selected.singleOrNull()?.let { byId[it] }
    val singlePreviewAction: (() -> Unit)? = singleSelectedFile?.let { file ->
        val entry = entryById[file.id]
        val state = entry?.let { sourceByKey[it.sourceKey]?.state }
        val idx = entry?.sourceIndex
        if (state != null && idx != null && state.canPreview(idx)) ({ state.preview(idx) }) else null
    }
    val batchDownloadAction: (() -> Unit)? = if (downloadableResolved.isNotEmpty()) ({
        val targetFolderId = sources.firstOrNull()?.state?.target?.id.orEmpty()
        downloads.downloadResolved(downloadableResolved, targetFolderId) { notice ->
            notice?.let(services::noticeOnTransfers)
            onDownloaded()
        }
    }) else null
    val singlePropertiesAction: (() -> Unit)? = singleSelectedFile?.let { file -> { properties(file) } }
    val excludeAction: (() -> Unit)? = if (canExcludeSelection) ({
        for ((sourceKey, group) in coveredFileEntries.groupBy { it.sourceKey }) {
            val src = sourceByKey[sourceKey] ?: continue
            if (src.enabled && src.state.canEdit) {
                val indices = group.mapNotNullTo(HashSet()) { it.sourceIndex }
                if (indices.isNotEmpty()) src.state.setSourceSelected(indices, false)
            }
        }
        selected = emptyList()
    }) else null
    val allShownSelected = shown.isNotEmpty() && shown.all { it.id in selectedSet }

    CompositionLocalProvider(LocalFileDrag provides null) {
        Box(Modifier.fillMaxSize().itemPanelAnchorArea(detail.anchors)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton({ path = byId[path]?.parentId.orEmpty() }, enabled = path.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("上一级保存结果", "Parent saved folder", "保存結果の上の階層", "저장 결과 상위 폴더", "上一層儲存結果"))
                    }
                    Box(Modifier.weight(1f)) { DrivePathTitle(address, editRequests = 0) }
                    ImportIconToggleButton(
                        checked = searchExpanded,
                        onCheckedChange = { next ->
                            browser.searchShown = next
                            if (!next) query = ""
                        },
                        uncheckedIcon = Icons.Outlined.Search,
                        checkedIcon = Icons.Filled.Search,
                        label = stringResource(Res.string.text_search_saved_results),
                    )
                    ImportResultViewToggle(
                        viewMode = DriveViewMode.of(mode),
                        onViewModeChange = { mode = it.name },
                    )
                }
                if (searchExpanded) {
                    PillSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = stringResource(Res.string.text_search_saved_results),
                        clearLabel = tr("清空结果搜索", "Clear result search", "結果の検索を消去", "결과 검색 지우기", "清除結果搜尋"),
                        closeLabel = tr("关闭搜索", "Close search", "検索を閉じる", "검색 닫기", "關閉搜尋"),
                        onClose = { browser.searchShown = false },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        autoFocus = browser.searchShown && query.isEmpty(),
                    )
                }
                if (shown.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PikoEmptyState(
                            title = if (entries.isEmpty()) {
                                tr("从来源文件中选择要保存的内容", "Select source files to save", "保存するソースファイルを選択", "저장할 원본 파일 선택", "從來源檔案中選取要儲存的內容")
                            } else {
                                tr("没有匹配的文件", "No matching files", "一致するファイルはありません", "일치하는 파일이 없습니다", "沒有符合的檔案")
                            },
                            icon = if (entries.isEmpty()) Icons.Outlined.Checklist else Icons.Outlined.SearchOff,
                        )
                    }
                } else {
                    DriveFileGrid(
                        items = shown.map { DriveListItem.File(it, null) },
                        viewMode = DriveViewMode.of(mode),
                        tileSize = TileSize.MEDIUM,
                        onZoom = {},
                        gridState = browser.grid,
                        isSelectionMode = selected.isNotEmpty(),
                        selectedIds = selectedSet,
                        highlightedIds = emptySet(),
                        highlightRevision = 0,
                        structureReady = true,
                        locatedItemId = null,
                        isBlurred = { false },
                        hitLocations = emptyMap(),
                        folderView = { null },
                        callbacks = callbacks,
                        bottomPadding = if (selected.isNotEmpty()) 88.dp else 8.dp,
                        moreButton = LocalPointerSource.current.isTouchLike,
                        backgroundActions = { emptyList() },
                        columnReferenceWidth = null,
                        activeItemId = selected.lastOrNull(),
                        keyboardFocusTarget = null,
                        onKeyboardFocusMoved = {},
                        header = {},
                        foldBanner = null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            ImportSelectionFloatingToolbar(
                visible = selected.isNotEmpty(),
                allSelected = allShownSelected,
                onClearSelection = { selected = emptyList() },
                onToggleSelectAll = {
                    selected = if (allShownSelected) emptyList() else shown.map { it.id }
                },
                onPreview = singlePreviewAction,
                onDownload = batchDownloadAction,
                onProperties = singlePropertiesAction,
                onExcludeFromSave = excludeAction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            DetailHost(detail) { entry, modifier ->
                val location = stackFor(entry.file.parentId).joinToString(" / ") { it.localizedName }
                PropertiesPane(
                    target = PropertiesTarget.Single(DriveItemName(entry.file, null, null), emptyList(), location, false),
                    modifier = modifier,
                    onParseSource = { url ->
                        detail.close()
                        onOpenLink(url)
                    },
                )
            }
        }
        moreId?.let { id ->
            byId[id]?.let { file ->
                ItemDetailsSheet(
                    title = file.name,
                    headerIcon = { FileListLeadingVisual(file = file, size = 40.dp, allowThumbnail = false) },
                    actions = withSelectAction(actions(file), if (selected.isEmpty()) ({ moreId = null; selected = listOf(file.id) }) else null),
                    onDismiss = { moreId = null },
                    metaParts = listOf(file.sizeBytes.toReadableSize()),
                )
            }
        }
    }
}
