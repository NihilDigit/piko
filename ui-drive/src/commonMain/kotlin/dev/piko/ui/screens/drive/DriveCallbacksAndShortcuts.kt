package dev.piko.ui.screens.drive

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.data.repository.isHtmlDocument
import dev.piko.data.repository.isPlayableAudio
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.data.repository.isReadableTextDocument
import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.isExtractableArchive
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.data.isLocal
import dev.piko.shared.data.isVaulted
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.naming.av.hasAvCode
import dev.piko.shared.presentation.DriveTab
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.upload.isUploading
import dev.piko.ui.components.ContributePaletteItems
import dev.piko.ui.components.FileDragPayload
import dev.piko.ui.components.ItemPanelAnchors
import dev.piko.ui.components.PaletteItem
import dev.piko.ui.components.PanelAnchor
import dev.piko.ui.components.PointerSource
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.previewImageCoverUrl
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.workbench.TaskSlot
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

@Composable
internal fun rememberDriveItemCallbacks(
    state: DriveScreenState,
    actionIntents: DriveItemActionIntents,
    events: DriveViewEvents,
    snapshot: DriveViewSnapshot,
    taskSlot: TaskSlot,
    snackbarHostState: SnackbarHostState,
    propertiesAnchors: ItemPanelAnchors,
    pointerSource: PointerSource,
    shortcutFocus: FocusRequester,
    focusedFileProvider: () -> FileStat?,
    commandFileProvider: () -> FileStat?,
    onFocusedFileChange: (FileStat?) -> Unit,
    onCommandFileChange: (FileStat?) -> Unit,
    onShowProperties: (List<FileStat>, PanelAnchor) -> Unit,
): Pair<DriveItemCallbacks, (FileStat, List<FileStat>, Boolean) -> List<SheetAction>> {
    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()
    val latestIntents by rememberUpdatedState(actionIntents)
    val latestEvents by rememberUpdatedState(events)
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestShowProperties by rememberUpdatedState(onShowProperties)
    val latestFocusedFile by rememberUpdatedState(focusedFileProvider)
    val latestCommandFile by rememberUpdatedState(commandFileProvider)
    val latestOnFocusedChange by rememberUpdatedState(onFocusedFileChange)
    val latestOnCommandChange by rememberUpdatedState(onCommandFileChange)
    val latestPointerSource by rememberUpdatedState(pointerSource)

    fun targetsOf(file: FileStat): List<FileStat> =
        if (state.isSelectionMode && file.id in state.selectedIdSet && state.selectedFileIds.size > 1) {
            latestIntents.displayedFiles.filter { it.id in state.selectedIdSet }
        } else listOf(file)

    fun itemActions(file: FileStat, targets: List<FileStat>, fromMenu: Boolean): List<SheetAction> =
        buildDriveOperations(state, file, targets, latestIntents) + DriveActions.properties {
            latestShowProperties(targets, if (fromMenu) propertiesAnchors.fromMenu(file.id) else propertiesAnchors.beside(file.id))
        }

    val openLeafFile: (FileStat) -> Unit = { file ->
        val currentFiles = latestIntents.displayedFiles
        when {
            file.isImportPreview && file.hash.isBlank() -> scope.launch {
                snackbarHostState.showSnackbar(
                    tr("未收录的链接项目需先通过离线下载保存至网盘", "Save unindexed link items via cloud download before opening", "未収録のリンク項目は、先にクラウドダウンロードでドライブに保存してください", "미등록 링크 항목은 먼저 클라우드 다운로드로 드라이브에 저장해 주세요", "未收錄的連結項目需先透過離線下載儲存至雲端硬碟"),
                    withDismissAction = true,
                )
            }
            file.isPlayableVideo() -> latestEvents.onNavigateToPlayer(
                file,
                if (latestIntents.galleryActive) emptyList() else currentFiles.filter { it.isPlayableVideo() && (!it.isImportPreview || it.hash.isNotBlank()) },
            )
            file.isPlayableAudio() -> latestEvents.onNavigateToPlayer(
                file,
                if (latestIntents.galleryActive) emptyList() else currentFiles.filter { it.isPlayableAudio() && (!it.isImportPreview || it.hash.isNotBlank()) },
            )
            file.isPreviewableImage() && file.previewImageCoverUrl().isNotBlank() -> latestEvents.onRequestImagePreview(file)
            file.isReadableTextDocument() || file.isHtmlDocument() -> latestEvents.onOpenDocument(file)
            file.isLocal -> platform.localFiles.openExternally(file.params["local_path"].orEmpty(), false)
            else -> latestEvents.onRequestDownload(listOf(file), false)
        }
    }
    val latestOpenLeafFile by rememberUpdatedState(openLeafFile)
    val latestItemActions by rememberUpdatedState<(FileStat, List<FileStat>, Boolean) -> List<SheetAction>>(::itemActions)

    val callbacks = remember(state) {
        DriveItemCallbacks(
            onOpen = { file ->
                when {
                    state.libraryView == DriveLibrary.TRASH -> latestEvents.onOpenSingleItemSheet(file)
                    file.isFolder -> state.openFolder(file.id, file.name)
                    file.isUploading -> scope.launch {
                        snackbarHostState.showSnackbar(tr("文件仍在上传", "File is still uploading", "ファイルはアップロード中です", "파일 업로드 중입니다", "檔案仍在上傳"), withDismissAction = true)
                    }
                    file.isExtractableArchive && !file.isLocal && !file.isVaulted && !ArchiveEntryId.isEntry(file.id) ->
                        taskSlot.claim(tr("打开压缩包", "Open archive", "圧縮ファイルを開く", "압축 파일 열기", "開啟壓縮檔"), tr("打开压缩包", "Open archive", "圧縮ファイルを開く", "압축 파일 열기", "開啟壓縮檔"), task = null, conflictsWith = setOf(TaskSlot.Task.FEED)) { state.openArchive(file) }
                    ArchiveEntryId.isEntry(file.id) -> scope.launch { state.prepareArchiveEntry(file)?.let(latestOpenLeafFile) }
                    else -> latestOpenLeafFile(file)
                }
            },
            onRename = { file ->
                if (!file.isLocal && !file.isUploading && !file.isImportPreview && state.libraryView != DriveLibrary.TRASH) {
                    latestEvents.onRequestRename(file)
                }
            },
            onMore = { latestEvents.onOpenSingleItemSheet(it) },
            onProperties = { latestShowProperties(targetsOf(it), propertiesAnchors.beside(it.id)) },
            onPlaced = { file, coordinates -> propertiesAnchors.placed(file.id, coordinates) },
            onStartSelection = { state.enterSelection(it.id) },
            onLongClick = { file ->
                if (state.libraryView == DriveLibrary.DUPLICATES && file.isFolder) latestEvents.onOpenSingleItemSheet(file)
                else if (state.isSelectionMode) state.setSelected(file.id, file.id !in state.selectedIdSet)
                else state.enterSelection(file.id)
            },
            onSelect = { file, selected -> state.setSelected(file.id, selected) },
            onToggleSelect = { id ->
                if (latestIntents.galleryActive) {
                    val select = id !in state.selectedIdSet
                    state.setSelected(id, select)
                    if (!select && state.selectedFileIds.isEmpty()) state.exitSelection()
                } else state.toggleSelected(id)
            },
            onExtendSelect = { id ->
                if (latestIntents.galleryActive) {
                    val files = latestIntents.displayedFiles
                    val to = files.indexOfFirst { it.id == id }
                    val anchorId = state.selectedFileIds.lastOrNull() ?: latestCommandFile()?.id
                    val from = anchorId?.let { anchor -> files.indexOfFirst { it.id == anchor } } ?: -1
                    if (to >= 0 && from >= 0) {
                        state.selectFiles(files.subList(minOf(from, to), maxOf(from, to) + 1).map { it.id })
                    } else state.setSelected(id, true)
                } else state.selectRange(id)
            },
            onBoxSelect = { base, boxed ->
                if (latestCommandFile() != null || latestFocusedFile() != null) {
                    latestOnCommandChange(null)
                    latestOnFocusedChange(null)
                    runCatching { shortcutFocus.requestFocus() }
                }
                state.selectBoxed(base, boxed)
            },
            onMiddleClick = { file ->
                if (file.isFolder && !file.isImportPreview && state.libraryView != DriveLibrary.TRASH) latestIntents.onOpenInNewTab?.invoke(file)
            },
            dragPayload = dragPayload@{ file ->
                if (state.libraryView == DriveLibrary.TRASH || state.archiveView != null) return@dragPayload null
                val batch = if (state.isSelectionMode && file.id in state.selectedIdSet) {
                    latestIntents.displayedFiles.filter { it.id in state.selectedIdSet }
                } else {
                    listOf(file)
                }.filterNot { it.isLocal || it.isUploading || it.isVaulted || it.isImportPreview }
                if (batch.isEmpty()) null else FileDragPayload(
                    ids = batch.map { it.id },
                    parentIds = batch.mapTo(HashSet()) { it.parentId },
                    label = batch.singleOrNull()?.let { state.itemName(it).headingText }
                        ?: tr("${batch.size} 项", enCount(batch.size, "item", "items"), "${batch.size} 項目", "${batch.size}개 항목", "${batch.size} 個項目"),
                    perform = { target, copy ->
                        val ids = batch.map { it.id }
                        if (copy) state.copy(ids, target.id, target.name) else state.move(ids, target.id, target.name)
                    },
                )
            },
            onBackgroundClick = {
                latestOnCommandChange(null)
                latestOnFocusedChange(null)
                latestEvents.onClearLocatedFile()
                if (state.isSelectionMode) state.exitSelection()
                runCatching { shortcutFocus.requestFocus() }
            },
            onFocusChanged = { file, focused ->
                if (focused) {
                    if (latestSnapshot.locatedFileId != file.id) latestEvents.onClearLocatedFile()
                    latestOnFocusedChange(file)
                    if (!latestPointerSource.isTouchLike) latestOnCommandChange(file)
                } else if (latestFocusedFile()?.id == file.id) {
                    latestOnFocusedChange(null)
                }
            },
            contextActions = { file -> latestItemActions(file, targetsOf(file), true) },
            onToggleSection = state::toggleSection,
            onFolderVisible = state::onFolderVisible,
        )
    }
    return callbacks to ::itemActions
}

@Composable
internal fun DrivePaletteContribution(
    state: DriveScreenState,
    shortcuts: ShortcutModifier,
    isWorkspaceBrowser: Boolean,
    pathInTopBar: Boolean,
    folderStack: List<PathBreadcrumb>,
    activeFolder: PathBreadcrumb,
    viewMode: DriveViewMode,
    folderMapToggleLabel: String,
    mountAction: SheetAction?,
    commandTargets: List<FileStat>,
    targetCommands: ItemCommands,
    tabsAvailable: Boolean,
    tabs: List<DriveTab>,
    activeTabId: Long,
    onNewFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onToggleFolderMap: () -> Unit,
    onEditAddress: () -> Unit,
    onFocusSearch: () -> Unit,
    onShowProperties: () -> Unit,
    onSelectViewMode: (DriveViewMode) -> Unit,
    onSelectAll: () -> Unit,
    onRefresh: () -> Unit,
    onNewRootTab: () -> Unit,
    onCloseTab: (Long) -> Unit,
    onSelectTab: (Long) -> Unit,
    onFindDuplicates: () -> Unit,
    onOpenSpaceMap: ((String, String) -> Unit)?,
    onNameTargetsByCode: (List<FileStat>) -> Unit,
    onCanonicalCurrentFolder: () -> Unit,
) {
    ContributePaletteItems("drive") {
        val label = shortcuts::label
        buildList {
            if (!state.isVirtualPlace && isWorkspaceBrowser) {
                add(PaletteItem(tr("新建文件夹", "New folder", "新規フォルダー", "새 폴더", "新增資料夾"), Icons.Outlined.CreateNewFolder, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "new folder mkdir", run = onNewFolder))
                mountAction?.let { action ->
                    add(PaletteItem(action.label, action.icon, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "mount local folder 挂载 本地 文件夹", run = action.onClick))
                }
                add(PaletteItem(tr("上传文件", "Upload files", "ファイルをアップロード", "파일 업로드", "上傳檔案"), Icons.Outlined.UploadFile, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "upload", run = onPickFiles))
                add(PaletteItem(tr("上传文件夹", "Upload folder", "フォルダーをアップロード", "폴더 업로드", "上傳資料夾"), Icons.Outlined.DriveFolderUpload, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "upload folder", run = onPickFolder))
            }
            add(
                PaletteItem(
                    folderMapToggleLabel,
                    Icons.Outlined.AccountTree,
                    tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"),
                    detail = if (shortcuts == ShortcutModifier.Command) "⌘⇧E" else "Ctrl+Shift+E",
                    keywords = "folder tree map 树",
                    run = onToggleFolderMap,
                ),
            )
            add(PaletteItem(tr("转到路径", "Go to path", "パスへ移動", "경로로 이동", "前往路徑"), Icons.Outlined.FolderOpen, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), detail = label("L"), keywords = "path address 路径", run = onEditAddress))
            add(
                PaletteItem(tr("搜索文件", "Search files", "ファイルを検索", "파일 검색", "搜尋檔案"), Icons.Outlined.Search, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), detail = label("F"), keywords = "search find") {
                    if (pathInTopBar) onFocusSearch() else state.openSearch()
                },
            )
            add(PaletteItem(tr("属性", "Properties", "プロパティ", "속성", "屬性"), Icons.Outlined.Info, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), detail = label("I"), keywords = "properties info details", run = onShowProperties))
            DriveViewMode.entries.filter { it != viewMode }.forEach { mode ->
                val modeLabel = mode.paletteLabel
                add(
                    PaletteItem(
                        tr("切换到$modeLabel", "Switch to $modeLabel", "${modeLabel}に切り替え", "$modeLabel(으)로 전환", "切換至$modeLabel"),
                        mode.icon(),
                        tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"),
                        keywords = "视图 view ${mode.name.lowercase()}",
                    ) { onSelectViewMode(mode) },
                )
            }
            add(PaletteItem(tr("全选", "Select all", "すべて選択", "모두 선택", "全選"), Icons.Outlined.SelectAll, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), detail = label("A"), keywords = "select all", run = onSelectAll))
            add(PaletteItem(tr("刷新", "Refresh", "更新", "새로고침", "重新整理"), Icons.Outlined.Refresh, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), detail = "F5", keywords = "refresh reload", run = onRefresh))
            if (folderStack.size > 1) {
                add(PaletteItem(tr("上一级", "Parent folder", "上の階層", "상위 폴더", "上一層"), Icons.Outlined.ArrowUpward, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), detail = "Backspace", keywords = "up parent", run = state::navigateUp))
            }
            if (tabsAvailable) {
                add(PaletteItem(tr("新建标签页", "New tab", "新しいタブ", "새 탭", "新增分頁"), Icons.Outlined.Add, tr("标签", "Tabs", "タブ", "탭", "分頁"), detail = label("T"), keywords = "new tab", run = onNewRootTab))
                if (tabs.size > 1) {
                    add(PaletteItem(tr("关闭标签页", "Close tab", "タブを閉じる", "탭 닫기", "關閉分頁"), Icons.Outlined.Close, tr("标签", "Tabs", "タブ", "탭", "分頁"), detail = label("W"), keywords = "close tab") { onCloseTab(activeTabId) })
                    tabs.filter { it.id != activeTabId }.forEach { tab ->
                        add(PaletteItem(tab.title, Icons.Outlined.Tab, tr("标签", "Tabs", "タブ", "탭", "分頁"), detail = tab.stack.joinToString(" › ") { it.localizedName }, keywords = "tab") {
                            onSelectTab(tab.id)
                        })
                    }
                }
            }
            if (!state.isVirtualPlace) {
                add(PaletteItem(tr("在当前文件夹查找重复", "Find duplicates in current folder", "現在のフォルダーの重複を検索", "현재 폴더에서 중복 찾기", "在目前資料夾尋找重複檔案"), Icons.Outlined.FileCopy, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "duplicate dedupe", run = onFindDuplicates))
                if (onOpenSpaceMap != null && isDriveFolderId(activeFolder.id)) {
                    add(
                        PaletteItem(tr("查看当前文件夹空间分布", "View space usage of current folder", "現在のフォルダーのストレージ内訳を表示", "현재 폴더의 공간 분포 보기", "檢視目前資料夾空間分佈"), Icons.Outlined.PieChart, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "space usage storage treemap 空间 容量") {
                            onOpenSpaceMap(activeFolder.id, activeFolder.localizedName)
                        },
                    )
                }
            }
            val nameTargets = commandTargets.filterNot { it.isUploading }
            when {
                nameTargets.isNotEmpty() -> if (targetCommands.rename && nameTargets.any { it.isFolder || hasAvCode(it.name) }) {
                    add(PaletteItem(tr("按番号规范命名", "Normalize names by catalog code", "作品番号で名前を整理", "품번으로 이름 정리", "依番號規範命名"), Icons.Outlined.DriveFileRenameOutline, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "rename av code 番号 重命名") { onNameTargetsByCode(nameTargets) })
                }
                !state.isVirtualPlace -> add(
                    PaletteItem(tr("按番号规范命名当前文件夹", "Normalize this folder by catalog code", "現在のフォルダーを作品番号で命名", "현재 폴더를 품번으로 이름 정리", "依番號規範命名目前資料夾"), Icons.Outlined.DriveFileRenameOutline, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "rename av code 番号 重命名 整理", run = onCanonicalCurrentFolder),
                )
            }
        }
    }
}

internal fun handleDriveKeyShortcut(
    event: KeyEvent,
    shortcutModifier: ShortcutModifier,
    state: DriveScreenState,
    gridState: LazyGridState,
    focusManager: FocusManager,
    pathInTopBar: Boolean,
    folderStackSize: Int,
    focusedFile: FileStat?,
    commandFile: FileStat?,
    commandTargets: List<FileStat>,
    displayedFiles: List<FileStat>,
    hasClipboard: Boolean,
    tabsAvailable: Boolean,
    canCloseActiveTab: Boolean,
    onFocusFirstVisible: (String) -> Unit,
    onOpenItemActions: (FileStat) -> Unit,
    onOpenItem: (FileStat) -> Unit,
    onStartRename: (FileStat) -> Unit,
    onBatchRename: (List<FileStat>) -> Unit,
    onLibraryConfirm: (LibraryConfirm) -> Unit,
    onEditAddress: () -> Unit,
    onToggleFolderMap: () -> Unit,
    onFocusDesktopSearch: () -> Unit,
    onNewRootTab: () -> Unit,
    onCloseActiveTab: () -> Unit,
    onCycleTab: (Int) -> Unit,
    onShowTargetProperties: (String) -> Unit,
    onShowFolderProperties: () -> Unit,
    onRefresh: () -> Unit,
    onSelectAll: () -> Unit,
    onUndoLast: () -> Boolean,
    onBackInBrowser: () -> Boolean,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val primary = shortcutModifier.isPressed(event)
    val isMac = shortcutModifier == ShortcutModifier.Command
    val inTrash = state.libraryView == DriveLibrary.TRASH
    val arrow = when (event.key) {
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        else -> null
    }
    if (arrow != null && !event.isAltPressed && !event.isShiftPressed && !primary) {
        if (focusedFile != null) {
            focusManager.moveFocus(arrow)
        } else {
            val visible = gridState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
            val targetKey = state.displayItems.firstOrNull { it is DriveListItem.File && it.key in visible }?.key ?: return false
            onFocusFirstVisible(targetKey)
        }
        return true
    }
    if (focusedFile != null && (event.key == Key.Menu || (event.isShiftPressed && event.key == Key.F10))) {
        onOpenItemActions(focusedFile)
        return true
    }
    fun delete(ids: List<String>) {
        if (inTrash) onLibraryConfirm(LibraryConfirm.DeleteForever(ids, emptying = false)) else state.moveToTrash(ids)
    }
    if (focusedFile != null && !state.isSelectionMode) {
        when {
            (event.key == Key.Delete || (primary && event.key == Key.Backspace)) && !focusedFile.isLocal -> {
                delete(listOf(focusedFile.id))
                return true
            }
            event.key == Key.F2 && !focusedFile.isLocal && !focusedFile.isUploading && !focusedFile.isImportPreview && !inTrash -> {
                onStartRename(focusedFile)
                return true
            }
            isMac && primary && event.key == Key.DirectionDown -> {
                onOpenItem(focusedFile)
                return true
            }
        }
    }
    val trashKey = event.key == Key.Delete || (primary && event.key == Key.Backspace)
    val addressKey = (primary && event.key == Key.L) || (!isMac && event.isAltPressed && event.key == Key.D) ||
        (!isMac && event.key == Key.F4 && !event.isAltPressed)
    val place = commandPlaceOf(state, folderStackSize)
    when {
        addressKey -> onEditAddress()
        primary && event.isShiftPressed && event.key == Key.E -> onToggleFolderMap()
        primary && event.key == Key.F -> if (pathInTopBar) onFocusDesktopSearch() else state.openSearch()
        primary && event.key == Key.X && itemCommands(place, commandTargets).cut ->
            state.putOnClipboard(commandTargets.map { it.id }, cut = true)
        primary && event.key == Key.C && itemCommands(place, commandTargets).cutCopy ->
            state.putOnClipboard(commandTargets.map { it.id }, cut = false)
        primary && event.key == Key.V && hasClipboard && !state.isVirtualPlace -> state.paste()
        tabsAvailable && primary && event.key == Key.T -> onNewRootTab()
        tabsAvailable && primary && event.key == Key.W && canCloseActiveTab -> onCloseActiveTab()
        tabsAvailable && event.isCtrlPressed && event.key == Key.Tab -> onCycleTab(if (event.isShiftPressed) -1 else 1)
        tabsAvailable && primary && event.key == Key.PageDown -> onCycleTab(1)
        tabsAvailable && primary && event.key == Key.PageUp -> onCycleTab(-1)
        (primary && event.key == Key.I) || (!isMac && event.isAltPressed && event.key == Key.Enter) -> {
            val near = (commandFile ?: commandTargets.firstOrNull())?.id
            if (commandTargets.isNotEmpty() && near != null) onShowTargetProperties(near) else onShowFolderProperties()
        }
        event.key == Key.F5 || (primary && event.key == Key.R) -> onRefresh()
        primary && event.key == Key.A -> onSelectAll()
        primary && event.key == Key.Z && !event.isShiftPressed -> if (!onUndoLast()) return false
        trashKey && state.isSelectionMode && state.selectedFileIds.any { !LocalLocation.isLocal(it) } ->
            delete(state.selectedFileIds.filterNot(LocalLocation::isLocal))
        event.key == Key.F2 && state.isSelectionMode && state.selectedFileIds.isNotEmpty() && !inTrash -> {
            val targets = displayedFiles.filter { it.id in state.selectedFileIds && !it.isLocal && !it.isUploading && !it.isImportPreview }
            when {
                targets.size == 1 -> onStartRename(targets.single())
                targets.size > 1 -> onBatchRename(targets)
            }
        }
        (event.isAltPressed && event.key == Key.DirectionLeft) || (primary && event.key == Key.LeftBracket) ||
            (!isMac && !primary && event.key == Key.Backspace) -> onBackInBrowser()
        (event.isAltPressed && event.key == Key.DirectionRight) || (primary && event.key == Key.RightBracket) -> state.goForward()
        ((!isMac && event.isAltPressed) || (isMac && primary)) && event.key == Key.DirectionUp &&
            folderStackSize > 1 -> state.navigateUp()
        else -> return false
    }
    return true
}
