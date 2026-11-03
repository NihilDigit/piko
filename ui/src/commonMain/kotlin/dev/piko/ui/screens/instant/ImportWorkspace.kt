package dev.piko.ui.screens.instant

import dev.piko.shared.i18n.enCount
import dev.piko.shared.presentation.localizedName

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.piko.shared.presentation.ImportTreeSource
import dev.piko.shared.presentation.ImportTreeSourceVersion
import dev.piko.shared.presentation.importSourceForest
import dev.piko.shared.presentation.visibleImportSources
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.AddressCompletion
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.components.*
import dev.piko.ui.screens.drive.*
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

internal data class ImportSource(val key: String, val state: InstantSheetState, val onRemove: (() -> Unit)? = null, val enabled: Boolean = true)

internal class ImportManagementState {
    var query by mutableStateOf("")
    var searchShown by mutableStateOf(false)
    var collapsed by mutableStateOf(emptyList<String>())
    var settingsShown by mutableStateOf(false)
    val list = LazyListState()
}

internal class ImportBrowserState {
    val path = mutableStateOf("")
    val query = mutableStateOf("")
    val mode = mutableStateOf(DriveViewMode.LIST.name)
    val selected = mutableStateOf(emptyList<String>())
    val grid = LazyGridState()
    val detail = DetailPanelState<ImportResultEntry>(DetailPanelSpec({ tr("属性", "Properties", "プロパティ", "속성", "屬性") }, { tr("关闭属性", "Close properties", "プロパティを閉じる", "속성 닫기", "關閉屬性") }))
}

/** Owned by the workspace context, so moving between a tab and a routed page retains the same UI state. */
internal class ImportWorkspaceViewState {
    var expanded by mutableStateOf(false)
    var started by mutableStateOf(false)
    val management = ImportManagementState()
    val browser = ImportBrowserState()
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
    // Change sheet ownership in composition, after measuring; disposing popup owners during subcomposition is invalid.
    val split = width >= 840.dp || (width >= 720.dp && height < 480.dp)
    val managerWidth = if (height < 480.dp) 320.dp else 360.dp
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        val management = view.management
        val browser = view.browser
        val scope = rememberCoroutineScope()
        @Composable fun Result() { ImportResultBrowser(sources, browser, onDownloaded, onOpenLink) }
        @Composable fun Manager(modifier: Modifier) {
            SourceImportTree(sources, management, modifier, config)
        }
        @Composable fun Header() {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.text_manage_links), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TooltipIconButton(Icons.Outlined.Search, tr("搜索来源文件", "Search source files", "ソースファイルを検索", "원본 파일 검색", "搜尋來源檔案"), {
                        management.searchShown = !management.searchShown
                        if (!management.searchShown) management.query = ""
                        else if (!split) onManagementChange(true)
                        scope.launch { management.list.scrollToItem(0) }
                    })
                    TooltipIconButton(Icons.Outlined.Tune, tr("保存设置", "Save options", "保存設定", "저장 설정", "儲存設定"), {
                        management.settingsShown = !management.settingsShown
                        if (management.settingsShown && !split) onManagementChange(true)
                        scope.launch { management.list.scrollToItem(0) }
                    })
                }
                status()
            }
        }
        if (split) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.weight(1f).fillMaxHeight(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Result()
            }
            Surface(Modifier.width(managerWidth).fillMaxHeight(), shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    Header()
                    Manager(Modifier.weight(1f))
                    Box(Modifier.padding(12.dp)) { saveBar() }
                }
            }
        } else TaskSheetScaffold(
            TaskSheetModel(key = "import-management", expanded = managementShown, onExpandedChange = onManagementChange,
                header = { Header() }, body = {
                    Manager(Modifier.fillMaxWidth().weight(1f).onPreviewKeyEvent(onShortcut))
                    Box(Modifier.fillMaxWidth().padding(12.dp)) { saveBar() }
                }),
        ) { Result() }
    }
}

@Composable
internal fun ImportResolutionStatus(state: InstantSheetState, notice: PanelNotice?) {
    val plan = state.savePlan
    val missing = state.sourceSelectedIndices.count { !state.items[it].isInstantReady }
    val error = state.saveBlockReason ?: state.errorMessage ?: when {
        state.willCreateFolder && state.folderNameToSave.isBlank() -> tr("保存文件夹名称不能为空", "Save folder name cannot be empty", "保存先フォルダー名を入力してください", "저장 폴더 이름을 입력해 주세요", "儲存資料夾名稱不能為空")
        state.saveAsReference && state.missingReferenceCount > 0 && state.referenceItemsToSave.isEmpty() -> tr("所选项目均缺少引用，无法以引用保存", "Selected items lack references and cannot be saved as references", "選択した項目に参照情報がないため、参照として保存できません", "선택한 항목에 참조가 없어 참조로 저장할 수 없습니다", "所選項目均缺少引用，無法以引用儲存")
        plan?.lacksSpace == true -> tr("网盘空间不足，请减少选择或更换保存位置", "Not enough cloud space. Select fewer files or change destination", "空き容量が不足しています。選択を減らすか保存先を変更してください", "클라우드 공간이 부족합니다. 선택을 줄이거나 저장 위치를 변경하세요", "雲端硬碟空間不足，請減少選取項目或更換儲存位置")
        plan?.lacksOfflineCount == true -> tr("今日离线次数已用完", "Daily cloud download limit reached", "本日のクラウドダウンロード回数を使い切りました", "오늘의 클라우드 다운로드 횟수를 모두 사용했습니다", "今日離線次數已用完")
        else -> null
    }
    val message = error ?: notice?.text ?: when {
        state.isResolving -> tr("正在解析链接", "Resolving link", "リンクを解析中", "링크 분석 중", "正在解析連結")
        state.resolution?.resource?.truncated == true -> tr("清单不完整，自动裁剪已停用", "Incomplete file list; automatic trimming disabled", "ファイル一覧が不完全なため、自動整理を無効にしました", "파일 목록이 불완전하여 자동 정리를 껐습니다", "清單不完整，已停用自動裁剪")
        state.saveAsReference && state.missingReferenceCount > 0 -> tr("已选文件中有 ${state.missingReferenceCount} 项缺少引用，保存时将跳过", "${state.missingReferenceCount} selected items lack references and will be skipped", "選択した ${state.missingReferenceCount} 項目に参照がなく、保存時にスキップします", "선택한 ${state.missingReferenceCount}개 항목에 참조가 없어 저장 시 건너뜁니다", "已選檔案中有 ${state.missingReferenceCount} 項缺少引用，儲存時將略過")
        missing > 0 -> tr("已选文件中有 ${missing} 项未收录", "${missing} selected items are not indexed", "選択した ${missing} 項目は未収録です", "선택한 ${missing}개 항목이 미등록입니다", "已選檔案中有 ${missing} 項未收錄")
        else -> null
    }
    if (message != null) Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val color = if (error != null || notice?.isError == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = color)
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = color)
        if (state.saveAsReference && state.missingReferenceCount > 0 && state.referenceItemsToSave.isNotEmpty()) {
            TextButton(onClick = state::deselectUnindexed, enabled = state.canEdit) { Text(stringResource(Res.string.text_uncheck)) }
        }
        if (state.resolution?.resource?.truncated == true) TooltipIconButton(
            Icons.Outlined.Refresh, tr("重新解析链接", "Resolve link again", "リンクを再解析", "링크 다시 분석", "重新解析連結"), state::retryResolve, enabled = state.canEdit,
        )
    }
}

@Composable
internal fun SharePassCodeInput(state: InstantSheetState) {
    if (!state.needsSharePassCode) return
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = state.sharePassCode, onValueChange = state::updateSharePassCode,
            modifier = Modifier.weight(1f), label = { Text(stringResource(Res.string.text_access_code)) }, singleLine = true, enabled = state.canEdit)
        TextButton(onClick = state::submitSharePassCode, enabled = state.canEdit && state.sharePassCode.isNotBlank()) { Text(stringResource(Res.string.text_resolve)) }
    }
}

@Composable
private fun SourceImportTree(sources: List<ImportSource>, management: ImportManagementState, modifier: Modifier, config: @Composable ColumnScope.() -> Unit) {
    val versions = sources.map { source ->
        val resolution = source.state.resolution
        ImportTreeSourceVersion(source.key, resolution?.resource?.name ?: source.state.input,
            resolution?.resource?.files)
    }
    val nodes = remember(versions) {
        importSourceForest(versions.map { version -> ImportTreeSource(version.key, version.files.orEmpty().map { it.path }) })
    }
    val query = management.query
    val collapsed = management.collapsed
    val visible = remember(nodes, collapsed, query) { visibleImportSources(nodes, nodes.map { it.key }.toSet() - collapsed.toSet(), query) }
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
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            TextField(query, { management.query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).focusRequester(focus),
                    placeholder = { Text(stringResource(Res.string.text_search_original_files)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = MaterialTheme.shapes.large,
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                    trailingIcon = { if(query.isNotEmpty()) IconButton({ management.query = "" }) { Icon(Icons.Outlined.Close, tr("清空来源搜索", "Clear source search", "ソースの検索を消去", "원본 검색 지우기", "清除來源搜尋")) } })
        }
        if (management.settingsShown) item(key = "settings") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = config)
        }
        items(visible, key = { "source:${it.key}" }) { entry ->
            val source = sourceByKey.getValue(entry.sourceKey)
            val state = source.state
            val editable = source.enabled && state.canEdit
            val node = entry.node
            val selected = node.indices.count { it in state.sourceSelectedIndices }
            val checked = when(selected) { 0 -> ToggleableState.Off; node.indices.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
            val index = node.fileIndex
            val toggle = { management.collapsed = if(entry.key in collapsed) collapsed - entry.key else collapsed + entry.key }
            val item = if (index == null) FilePresentation(
                node.key, FileItemName(node.name), FileVisual(node.name, isFolder = true),
                metadata = listOf(tr("${node.indices.size} 个文件", "${enCount(node.indices.size, "file", "files")}", "${node.indices.size} ファイル", "파일 ${node.indices.size}개", "${node.indices.size} 個檔案")),
            ) else state.items[index].file.filePresentation(node.key)
            val open = { if (node.isFolder) toggle() else if (editable) state.setSourceSelected(node.indices, selected == 0) }
            ContextMenuArea(actions = { sourceActions(source) }) {
                WorkspaceTreeRow(node.name, node.depth, item.visual.typeIcon(),
                    expanded = if (node.isFolder) entry.key !in collapsed else null, onExpand = toggle,
                    onOpen = open, onLongClick = { details = source }, item = item, reserveExpandSpace = hasBranches,
                    leading = { FileItemSelectionControl(FileItemSelection(checked,
                        { if (editable) state.setSourceSelected(node.indices, checked != ToggleableState.On) },
                        enabled = editable, label = tr("选择来源 ${node.name}", "Select source ${node.name}", "ソース ${node.name} を選択", "원본 ${node.name} 선택", "選取來源 ${node.name}"))) })
            }
        }
        @OptIn(ExperimentalFoundationApi::class)
        items(sources.filter { it.state.items.isEmpty() && (query.isBlank() || it.state.input.contains(query, true)) }, key = { "pending:${it.key}" }) { source ->
            val state = source.state
            val longClick = fileItemLongPress { details = source }
            ContextMenuArea(actions = { sourceActions(source) }) {
                ListItem(headlineContent = { Text(state.resolution?.resource?.name ?: state.input, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    supportingContent = { Text(state.errorMessage ?: when {
                        state.isResolving -> tr("正在解析链接", "Resolving link", "リンクを解析中", "링크 분석 중", "正在解析連結")
                        state.needsSharePassCode -> tr("需要提取码", "Access code required", "アクセスコードが必要です", "접근 코드 필요", "需要提取碼")
                        else -> tr("整条链接离线下载", "Download entire link to cloud", "リンク全体をクラウドダウンロード", "전체 링크 클라우드 다운로드", "整條連結離線下載")
                    }) },
                    leadingContent = { Icon(Icons.Outlined.Link, null) },
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = longClick),
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
            }
            SharePassCodeInput(state)
        }
    }
    details?.let { source ->
        ItemDetailsSheet(title = source.state.resolution?.resource?.name ?: tr("来源链接", "Source link", "ソースリンク", "출처 링크", "來源連結"),
            headerIcon = { Icon(Icons.Outlined.Link, null) }, onDismiss = { details = null },
            extraLines = { Text(source.state.input, style = MaterialTheme.typography.bodySmall) },
            actions = sourceActions(source))
    }
}

/** The normal drive grid receives passive FileStat projections; its write actions are absent. */
@Composable
private fun ImportResultBrowser(sources: List<ImportSource>, browser: ImportBrowserState, onDownloaded: () -> Unit, onOpenLink: (String) -> Unit) {
    val descriptors = sources.map { source -> ImportProjectionSource(source.key,
        source.state.projectedSaveEntries) }
    val entries = remember(descriptors) { importResultEntries(descriptors) }
    val all = entries.map { it.file }
    val entryById = entries.associateBy { it.file.id }
    val byId = all.associateBy { it.id }
    val sourceByKey = sources.associateBy { it.key }
    val downloads = LocalPikoServices.current.downloadManager
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
        completions = { text -> AddressCompletion(text.substringAfterLast('/'), true,
            all.filter { it.isFolder && stackFor(it.id).drop(1).joinToString("/") { it.name }.startsWith(text.removePrefix(tr("保存结果/", "Save results/", "保存結果/", "저장 결과/", "儲存結果/")), true) }.map { stackFor(it.id) }) },
        subfoldersOf = { id -> Result.success(all.filter { it.isFolder && it.parentId == id }.map { PikoPathBreadcrumb(it.id, it.name) }) },
        recent = emptyList(), pinned = emptyList(), onOpenPinned = {}, onRemoveRecent = {},
        onOpenLink = onOpenLink, onSearchHere = { query = it }, onSearchAll = { query = it }, destinations = emptyList(),
    )
    LaunchedEffect(all) {
        if (path.isNotEmpty() && path !in byId) path = ""
        selected = selected.filter { it in byId }
        if (detail.target?.file?.id !in byId) detail.close()
    }
    val shown = all.filter { (if(query.isBlank()) it.parentId == path else it.name.contains(query, true)) }
    fun properties(file: FileStat) { entryById[file.id]?.let { detail.show(it, detail.anchors.beside(file.id)) } }
    fun open(file: FileStat) {
        if(file.isFolder) { path = file.id; query = ""; selected = emptyList() }
        else entryById[file.id]?.let { entry ->
            val state = sourceByKey.getValue(entry.sourceKey).state
            val index = entry.sourceIndex
            if (index != null && state.canPreview(index)) state.preview(index)
        }
    }
    fun actions(file: FileStat): List<SheetAction> = buildList {
        val entry = entryById.getValue(file.id)
        val state = sourceByKey.getValue(entry.sourceKey).state
        val index = entry.sourceIndex
        if (file.isFolder) add(SheetAction(Icons.Outlined.FolderOpen, tr("打开", "Open", "開く", "열기", "開啟"), { open(file) }))
        if (index != null && state.canPreview(index)) add(SheetAction(Icons.Outlined.PlayCircle, tr("预览", "Preview", "プレビュー", "미리 보기", "預覽"), { state.preview(index) }))
        if (index != null && state.items[index].isInstantReady) add(SheetAction(Icons.Outlined.CloudDownload, tr("下载", "Downloads", "ダウンロード", "다운로드", "下載"), {
            downloads.enqueueResolved(listOf(state.items[index].file.copy(path = file.name)), state.target?.id.orEmpty())
            onDownloaded()
        }))
        add(SheetAction(Icons.Outlined.Info, tr("属性", "Properties", "プロパティ", "속성", "屬性"), { properties(file) }, group = ActionGroup.Properties))
        add(SheetAction(Icons.Outlined.ContentCopy, tr("复制来源链接", "Copy source link", "ソースリンクをコピー", "출처 링크 복사", "複製來源連結"), { platform.copyToClipboard(tr("来源链接", "Source link", "ソースリンク", "출처 링크", "來源連結"), state.input) }))
    }
    val callbacks = DriveItemCallbacks(
        onOpen = ::open, onRename = null, onMore = { moreId = it.id }, onProperties = ::properties,
        onPlaced = { file, coords -> detail.anchors.placed(file.id, coords) },
        onStartSelection = { file -> selected = listOf(file.id) },
        onLongClick = { file -> selected = if (file.id in selected) selected - file.id else selected + file.id },
        onSelect = { file, value -> selected = if(value) selected + file.id else selected - file.id },
        onToggleSelect = { selected = if(it in selected) selected - it else selected + it }, onExtendSelect = { selected = selected + it },
        dragPayload = { null }, onMiddleClick = {}, onBoxSelect = { base, boxed -> selected = (base + boxed).toList() },
        onBackgroundClick = { selected = emptyList() }, onFocusChanged = { _, _ -> }, contextActions = ::actions,
        onToggleSection = {}, onFolderVisible = {},
    )
    BackHandler(path.isNotEmpty()) { path = byId[path]?.parentId.orEmpty() }
    CompositionLocalProvider(LocalFileDrag provides null) {
        Box(Modifier.fillMaxSize().itemPanelAnchorArea(detail.anchors)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ path = byId[path]?.parentId.orEmpty() }, enabled = path.isNotEmpty()) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("上一级保存结果", "Parent saved folder", "保存結果の上の階層", "저장 결과 상위 폴더", "上一層儲存結果")) }
                    Box(Modifier.weight(1f)) { DrivePathTitle(address, editRequests = 0) }
                    TooltipIconButton(Icons.Outlined.ViewModule, tr("切换保存结果视图", "Switch result view", "保存結果の表示を切り替え", "저장 결과 보기 전환", "切換儲存結果檢視"), { mode = if(mode == DriveViewMode.LIST.name) DriveViewMode.POSTER.name else DriveViewMode.LIST.name })
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text(stringResource(Res.string.text_search_saved_results)) }, singleLine = true,
                    trailingIcon = { if(query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Outlined.Close, tr("清空结果搜索", "Clear result search", "結果の検索を消去", "결과 검색 지우기", "清除結果搜尋")) } })
                if(shown.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(if(entries.isEmpty()) tr("从来源文件中选择要保存的内容", "Select source files to save", "保存するソースファイルを選択", "저장할 원본 파일 선택", "從來源檔案中選取要儲存的內容") else tr("没有匹配的文件", "No matching files", "一致するファイルはありません", "일치하는 파일이 없습니다", "沒有符合的檔案")) }
                else DriveFileGrid(items = shown.map { DriveListItem.File(it, null) }, viewMode = DriveViewMode.of(mode), tileSize = TileSize.MEDIUM,
                    onZoom = {}, gridState = browser.grid, isSelectionMode = selected.isNotEmpty(), selectedIds = selected.toSet(), highlightedIds = emptySet(),
                    highlightRevision = 0, structureReady = true, locatedItemId = null, isBlurred = { false }, hitLocations = emptyMap(), folderView = { null },
                    callbacks = callbacks, bottomPadding = 8.dp, moreButton = LocalPointerSource.current.isTouchLike, backgroundActions = { emptyList() }, columnReferenceWidth = null,
                    activeItemId = selected.lastOrNull(), keyboardFocusTarget = null, onKeyboardFocusMoved = {}, header = {}, foldBanner = null,
                    modifier = Modifier.weight(1f))
            }
        DetailHost(detail) { entry, modifier ->
            val location = stackFor(entry.file.parentId).joinToString(" / ") { it.localizedName }
            PropertiesPane(
                target = PropertiesTarget.Single(dev.piko.shared.state.DriveItemName(entry.file, null, null), emptyList(), location, false),
                modifier = modifier,
                onParseSource = { url ->
                    detail.close()
                    onOpenLink(url)
                },
            )
        }
        }
        moreId?.let { id -> byId[id]?.let { file ->
            ItemDetailsSheet(title = file.name, headerIcon = { FileTypeIcon(file, 24.dp, Modifier.fillMaxSize()) },
                actions = withSelectAction(actions(file), if (selected.isEmpty()) ({ moreId = null; selected = listOf(file.id) }) else null),
                onDismiss = { moreId = null }, metaParts = listOf(file.sizeBytes.toReadableSize()))
        } }
    }
}
