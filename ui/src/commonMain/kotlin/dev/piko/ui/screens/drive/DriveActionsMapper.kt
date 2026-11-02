package dev.piko.ui.screens.drive

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.formatLocalPathForDisplay
import dev.piko.shared.data.isArchiveVolume
import dev.piko.shared.data.isExtractableArchive
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.data.isLocal
import dev.piko.shared.data.isLocalMountRoot
import dev.piko.shared.data.isVaulted
import dev.piko.shared.i18n.tr
import dev.piko.shared.naming.av.hasAvCode
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.upload.isUploading
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.previewImageCoverUrl
import dev.piko.ui.platform.ShortcutModifier
import io.github.nihildigit.pikpak.FileStat

internal val DriveViewMode.paletteLabel: String
    get() = when (this) {
        DriveViewMode.LIST -> tr("列表视图", "List view", "リスト表示", "목록 보기", "清單檢視")
        DriveViewMode.POSTER -> tr("网格视图", "Grid view", "グリッド表示", "격자 보기", "網格檢視")
    }

internal fun commandPlaceOf(state: DriveScreenState, stackDepth: Int): CommandPlace = when {
    state.isLocalPlace -> CommandPlace.LOCAL
    state.importPreviewView != null -> CommandPlace.IMPORT_PREVIEW
    state.archiveView != null -> CommandPlace.ARCHIVE
    state.libraryView == DriveLibrary.TRASH -> CommandPlace.TRASH
    state.libraryView == DriveLibrary.HISTORY -> CommandPlace.HISTORY
    state.libraryView == DriveLibrary.RECENT -> CommandPlace.RECENT
    state.libraryView != null -> CommandPlace.LIBRARY
    state.searchQuery.isNotBlank() || state.isGlobalSearchActive -> CommandPlace.SEARCH
    stackDepth == 1 -> CommandPlace.ROOT
    else -> CommandPlace.FOLDER
}

internal fun selectedGalleryLabelOf(files: List<FileStat>, galleryShowImages: Boolean): String {
    val hasVideo = files.any { it.isPlayableVideo() }
    val hasImage = galleryShowImages && files.any { !it.isPlayableVideo() && it.isPreviewableImage() }
    return when {
        hasVideo && hasImage -> tr("以瀑布流浏览所选媒体", "Browse selected media in gallery", "選択したメディアをギャラリーで表示", "선택한 미디어를 갤러리에서 보기", "以瀑布流瀏覽所選媒體")
        hasImage -> tr("以瀑布流浏览所选图片", "Browse selected images in gallery", "選択した画像をギャラリーで表示", "선택한 이미지를 갤러리에서 보기", "以瀑布流瀏覽所選圖片")
        else -> tr("以瀑布流浏览所选视频", "Browse selected videos in gallery", "選択した動画をギャラリーで表示", "선택한 동영상을 갤러리에서 보기", "以瀑布流瀏覽所選影片")
    }
}

internal fun locationLabelOf(
    file: FileStat,
    rowNotes: Map<String, String>,
    folderStack: List<PathBreadcrumb>,
): String =
    file.params["local_path"]?.let(::formatLocalPathForDisplay)
        ?: rowNotes[file.id]
        ?: folderStack.joinToString(" › ") { it.localizedName }

/**
 * 构建网盘条目（单选或多选）的 UI 动作事件集合（纯映射层：接收不可变快照与向上冒泡的事件回调）。
 */
internal class DriveItemActionIntents(
    val desktop: Boolean,
    val isWorkspaceBrowser: Boolean,
    val galleryActive: Boolean,
    val galleryShowImages: Boolean,
    val isSpoilerBlurEnabled: Boolean,
    val feedSupported: Boolean,
    val hasExternalPlayer: Boolean,
    val activeFolderId: String,
    val folderStack: List<PathBreadcrumb>,
    val displayedFiles: List<FileStat>,
    val vaultedFolders: Set<String>,
    val fullyVaultedFolders: Set<String>,
    val pinnedFolders: List<PathBreadcrumb>,
    val onDownload: (files: List<FileStat>, chooseQuality: Boolean) -> Unit,
    val onExtractArchives: (List<FileStat>) -> Unit,
    val onExtractFromArchive: (List<FileStat>) -> Unit,
    val onShare: (List<FileStat>) -> Unit,
    val onRename: (FileStat) -> Unit,
    val onBatchRename: (files: List<FileStat>, avNaming: Boolean) -> Unit,
    val onNameByCode: (List<FileStat>) -> Unit,
    val onMove: (Set<String>) -> Unit,
    val onCopy: (Set<String>) -> Unit,
    val onVault: (List<FileStat>) -> Unit,
    val onRestoreVaultFolder: (FileStat) -> Unit,
    val onRestoreVaultItems: (List<FileStat>) -> Unit,
    val onLibraryConfirm: (LibraryConfirm) -> Unit,
    val onFindDuplicates: (PathBreadcrumb) -> Unit,
    val onOpenSpaceMap: ((String, String) -> Unit)?,
    val onOpenSegmentDownload: (FileStat) -> Unit,
    val onPrefetchQualities: (String) -> Unit,
    val onCopySource: (FileStat) -> Unit,
    val onOpenSourceUrl: (String) -> Unit,
    val onCopyDownloadLink: (FileStat) -> Unit,
    val onOpenInExternalPlayer: (FileStat) -> Unit,
    val onOpenInNewTab: ((FileStat) -> Unit)?,
    val onTogglePin: (FileStat) -> Unit,
    val onPlayVideo: (FileStat, List<FileStat>) -> Unit,
    val onPreviewImage: (FileStat) -> Unit,
    val onOpenSelectedGallery: (List<FileStat>) -> Unit,
    val onOpenFolderInGallery: (FileStat) -> Unit,
    val onOpenSelectedFeed: ((List<FileStat>) -> Unit)?,
    val onOpenFolderInFeed: ((FileStat) -> Unit)?,
    val onRevealFromGalleryOrLibrary: (FileStat) -> Unit,
    val onRevealLocalInFileManager: (String) -> Unit,
    val onUnmountLocal: (String) -> Unit,
)

internal fun buildArchiveEntryActions(
    state: DriveScreenState,
    files: List<FileStat>,
    commands: ItemCommands,
    intents: DriveItemActionIntents,
): List<SheetAction> = buildList {
    if (commands.download) add(DriveActions.download { intents.onDownload(files, false) })
    if (!state.isArchiveInImportPreview) add(DriveActions.extractEntries { intents.onExtractFromArchive(files) })
}

internal fun buildImportPreviewActions(
    state: DriveScreenState,
    targets: List<FileStat>,
    intents: DriveItemActionIntents,
): List<SheetAction> = buildList {
    val ids = targets.map { it.id }
    val canDownload = targets.any { file -> if (file.isFolder) true else file.hash.isNotBlank() }
    if (canDownload) add(DriveActions.download { intents.onDownload(targets, false) })
    val single = targets.singleOrNull()
    if (single != null && !single.isFolder && single.hash.isNotBlank()) {
        add(DriveActions.copyDownloadLink { intents.onCopyDownloadLink(single) })
    }
    add(
        SheetAction(
            Icons.Outlined.Delete,
            if (targets.size > 1) tr("不保存所选 ${targets.size} 项", "Do not save ${targets.size} selected items", "選択した ${targets.size} 項目を保存しない", "선택한 ${targets.size}개 항목 저장 안 함", "不儲存所選 ${targets.size} 個項目") else tr("不保存此项", "Do not save this item", "この項目を保存しない", "이 항목 저장 안 함", "不儲存此項目"),
            { state.removeFromImportPreview(ids) },
            destructive = true,
            shortLabel = tr("不保存", "Do not save", "保存しない", "저장 안 함", "不儲存"),
        ),
    )
}

internal fun buildVaultEntryActions(
    state: DriveScreenState,
    file: FileStat,
    revealAction: SheetAction?,
    intents: DriveItemActionIntents,
): List<SheetAction> = buildList {
    if (revealAction != null) add(revealAction)
    addAll(
        DriveActions.quickRow(
            desktop = intents.desktop,
            cut = null,
            copy = null,
            download = DriveActions.download { intents.onDownload(listOf(file), false) },
            share = null,
            rename = DriveActions.rename { intents.onRename(file) },
            star = DriveActions.star(
                starred = file.isStarred,
                onClick = { state.setStarred(file, !file.isStarred) },
            ),
        ),
    )
    add(DriveActions.restoreFromVault { intents.onRestoreVaultItems(listOf(file)) })
    if (!file.isFolder) add(DriveActions.copyDownloadLink { intents.onCopyDownloadLink(file) })
    addAll(
        DriveActions.sourceActions(
            file.source,
            onCopy = { intents.onCopySource(file) },
            onOpen = { file.sourceUrl?.let(intents.onOpenSourceUrl) },
        ),
    )
    add(DriveActions.removeFromVault { state.removeFromVault(listOf(file.id)) })
}

internal fun buildSelectionActions(
    state: DriveScreenState,
    files: List<FileStat>,
    commands: ItemCommands,
    intents: DriveItemActionIntents,
): List<SheetAction> = buildList {
    val inTrash = state.libraryView == DriveLibrary.TRASH
    val ids = files.map { it.id }
    val settled = files.filterNot { it.isUploading || it.isVaulted || it.isImportPreview }
    val putOnClipboard: ((Boolean) -> Unit)? = if (commands.cutCopy) {
        { cut -> state.putOnClipboard(ids, cut) }
    } else null
    addAll(
        DriveActions.quickRow(
            desktop = intents.desktop,
            cut = null,
            copy = null,
            download = DriveActions.download { intents.onDownload(files.filterNot { it.isUploading }, false) }.takeIf { commands.download },
            share = DriveActions.share { intents.onShare(settled) }.takeIf { commands.share },
            rename = DriveActions.batchRename { intents.onBatchRename(settled, false) }.takeIf { commands.rename },
            star = DriveActions.star(
                starred = settled.all { it.isStarred },
                onClick = {
                    val nextStarred = !settled.all { it.isStarred }
                    state.setStarred(settled, nextStarred)
                    state.exitSelection()
                },
            ).takeIf { !inTrash && state.archiveView == null && settled.isNotEmpty() },
        ),
    )
    val galleryCandidates = files.filter {
        (it.isPlayableVideo() || (intents.galleryShowImages && it.isPreviewableImage())) &&
            !it.isUploading && !it.isImportPreview
    }
    if (!inTrash && state.archiveView == null && galleryCandidates.isNotEmpty()) {
        add(
            SheetAction(
                Icons.Outlined.PhotoLibrary,
                selectedGalleryLabelOf(galleryCandidates, intents.galleryShowImages),
                { intents.onOpenSelectedGallery(files) },
                group = ActionGroup.Open,
            ),
        )
    }
    if (!inTrash && state.archiveView == null && files.any { it.isPlayableVideo() && !it.isUploading && !it.isImportPreview }) {
        val openFeed = intents.onOpenSelectedFeed
        if (openFeed != null && intents.feedSupported) {
            add(
                SheetAction(
                    Icons.Outlined.SwipeVertical,
                    tr("以信息流浏览所选视频", "Browse selected in feed", "選択した動画をフィードで表示", "선택한 동영상을 피드에서 보기", "以資訊流瀏覽所選影片"),
                    { openFeed(files) },
                    group = ActionGroup.Open,
                ),
            )
        }
    }
    files.filter { it.isVaulted }.takeIf { it.isNotEmpty() }?.let { vaulted ->
        add(DriveActions.restoreFromVault { intents.onRestoreVaultItems(vaulted) })
    }
    if (commands.vault) add(DriveActions.vault { intents.onVault(files) })
    if (commands.extract) {
        val archives = files.filter { !it.isUploading && !it.isVaulted && !it.isImportPreview && (it.isExtractableArchive || it.isArchiveVolume) }
        add(
            DriveActions.extract {
                intents.onExtractArchives(archives)
                state.exitSelection()
            },
        )
    }
    if (commands.downloadAtQuality) add(DriveActions.downloadAtQuality { intents.onDownload(files, true) })
    if (commands.moveCopyTo) {
        add(DriveActions.moveTo { intents.onMove(ids.toSet()) })
        add(DriveActions.copyTo { intents.onCopy(ids.toSet()) })
    }
    if (commands.rename && settled.any { !it.isFolder && hasAvCode(it.name) }) {
        add(DriveActions.canonicalName { intents.onBatchRename(settled, true) })
    }
    if (putOnClipboard != null) {
        add(DriveActions.cut { putOnClipboard(true) })
        add(DriveActions.copy { putOnClipboard(false) })
    }
    if (commands.removeRecord) add(DriveActions.removeRecord { state.removeFromLibrary(ids.toSet()) })
    if (commands.moveToTrash) add(DriveActions.moveToTrash { state.moveToTrash(ids) })
}

internal fun buildDriveOperations(
    state: DriveScreenState,
    file: FileStat,
    targets: List<FileStat>,
    intents: DriveItemActionIntents,
): List<SheetAction> {
    val library = state.libraryView
    if (library == DriveLibrary.DUPLICATES && file.isFolder) {
        return listOf(DriveActions.revealInDrive { intents.onRevealFromGalleryOrLibrary(file) })
    }
    if (targets.any { it.isLocal }) return buildList {
        if (targets.size == 1 && intents.galleryActive && file.parentId != intents.activeFolderId) {
            add(
                SheetAction(
                    Icons.Outlined.FolderOpen,
                    tr("在所在文件夹中显示", "Show in containing folder", "保存先フォルダーで表示", "포함된 폴더에서 보기", "在所在資料夾中顯示"),
                    { intents.onRevealFromGalleryOrLibrary(file) },
                    group = ActionGroup.Open,
                ),
            )
        }
        if (targets.size > 1) {
            val galleryItems = targets.filter { it.isPlayableVideo() || (intents.galleryShowImages && it.isPreviewableImage()) }
            if (galleryItems.isNotEmpty()) {
                add(
                    SheetAction(
                        Icons.Outlined.PhotoLibrary,
                        selectedGalleryLabelOf(galleryItems, intents.galleryShowImages),
                        { intents.onOpenSelectedGallery(galleryItems) },
                        group = ActionGroup.Open,
                    ),
                )
            }
            val videos = targets.filter { it.isPlayableVideo() }
            val openFeed = intents.onOpenSelectedFeed
            if (videos.isNotEmpty() && openFeed != null && intents.feedSupported) {
                add(
                    SheetAction(
                        Icons.Outlined.SwipeVertical,
                        tr("以信息流浏览所选视频", "Browse selected videos in feed", "選択した動画をフィードで表示", "선택한 동영상을 피드에서 보기", "以資訊流瀏覽所選影片"),
                        { openFeed(videos) },
                        group = ActionGroup.Open,
                    ),
                )
            }
        } else if (file.isLocal) {
            if (file.isPlayableVideo()) {
                add(
                    SheetAction(
                        Icons.Outlined.PlayArrow,
                        tr("播放", "Play", "再生", "재생", "播放"),
                        { intents.onPlayVideo(file, intents.displayedFiles.filter { it.isPlayableVideo() }) },
                        group = ActionGroup.Open,
                    ),
                )
            }
            if (file.isPreviewableImage() && file.previewImageCoverUrl().isNotBlank()) {
                add(
                    SheetAction(
                        Icons.Outlined.Image,
                        tr("查看图片", "View image", "画像を表示", "이미지 보기", "檢視圖片"),
                        { intents.onPreviewImage(file) },
                        group = ActionGroup.Open,
                    ),
                )
            }
            if (file.isFolder) {
                add(SheetAction(Icons.Outlined.FolderOpen, tr("打开", "Open", "開く", "열기", "開啟"), { state.openFolder(file.id, file.name) }, group = ActionGroup.Open))
                intents.onOpenInNewTab?.let { open -> add(DriveActions.openInNewTab { open(file) }) }
                add(
                    SheetAction(
                        Icons.Outlined.PhotoLibrary,
                        tr("以瀑布流浏览", "Browse in gallery", "ギャラリーで表示", "갤러리에서 보기", "以瀑布流瀏覽"),
                        { intents.onOpenFolderInGallery(file) },
                        group = ActionGroup.Open,
                    ),
                )
                val openFolderFeed = intents.onOpenFolderInFeed
                if (openFolderFeed != null && intents.feedSupported) {
                    add(
                        SheetAction(
                            Icons.Outlined.SwipeVertical,
                            tr("以信息流浏览", "Browse in feed", "フィードで表示", "피드에서 보기", "以資訊流瀏覽"),
                            { openFolderFeed(file) },
                            group = ActionGroup.Open,
                        ),
                    )
                }
                if (file.isLocalMountRoot) {
                    add(
                        DriveActions.pin(
                            pinned = intents.pinnedFolders.any { it.id == file.id },
                            onClick = { intents.onTogglePin(file) },
                        ),
                    )
                }
            }
            val rawLocalPath = file.params["local_path"].orEmpty()
            if (!rawLocalPath.startsWith("content://")) {
                add(
                    SheetAction(
                        Icons.Outlined.FolderOpen,
                        tr("在文件管理器中显示", "Show in file manager", "ファイルマネージャーで表示", "파일 관리자에서 표시", "在檔案管理員中顯示"),
                        { intents.onRevealLocalInFileManager(file.id) },
                        group = ActionGroup.Open,
                    ),
                )
            }
            if (!file.isFolder && intents.isSpoilerBlurEnabled && file.previewImageCoverUrl().isNotEmpty()) {
                add(DriveActions.previewVisibility(hidden = file.id !in state.revealedFileIds, onClick = { state.toggleSpoiler(file.id) }))
            }
            if (file.isLocalMountRoot) {
                add(
                    SheetAction(
                        Icons.Outlined.LinkOff,
                        tr("取消挂载", "Unmount", "マウント解除", "마운트 해제", "取消掛載"),
                        { intents.onUnmountLocal(file.id) },
                        destructive = true,
                    ),
                )
            }
        }
    }
    if (targets.all { it.isImportPreview }) return buildImportPreviewActions(state, targets, intents)
    val commands = itemCommands(commandPlaceOf(state, intents.folderStack.size), targets, intents.fullyVaultedFolders)
    if (commands.restoreOrDelete) {
        val ids = targets.map { it.id }
        return trashActions(
            onRestore = { state.restoreFromTrash(ids) },
            onDelete = { intents.onLibraryConfirm(LibraryConfirm.DeleteForever(ids, emptying = false)) },
        )
    }
    if (state.archiveView != null) return buildArchiveEntryActions(state, targets, commands, intents)
    if (targets.size > 1) return buildSelectionActions(state, targets, commands, intents)
    val showReveal = library != null || (intents.galleryActive && file.parentId != intents.activeFolderId)
    val revealAction = if (!showReveal) null else DriveActions.revealInDrive {
        intents.onRevealFromGalleryOrLibrary(file)
    }
    if (file.isVaulted) return buildVaultEntryActions(state, file, revealAction, intents)
    val reveal = listOfNotNull(revealAction)
    val removeRecord = listOfNotNull(
        DriveActions.removeRecord { state.removeFromLibrary(setOf(file.id)) }.takeIf { commands.removeRecord },
    )
    val handlers = FileActionHandlers(
        toggleStar = { state.setStarred(file, starred = !file.isStarred) },
        download = { intents.onDownload(listOf(file), false) },
        downloadAtQuality = { intents.onDownload(listOf(file), true) },
        share = { intents.onShare(listOf(file)) },
        rename = { intents.onRename(file) },
        move = { intents.onMove(setOf(file.id)) },
        copy = { intents.onCopy(setOf(file.id)) },
        trash = { state.moveToTrash(listOf(file.id)) },
        extract = { intents.onExtractArchives(listOf(file)) },
        findDuplicates = if (intents.isWorkspaceBrowser) ({ intents.onFindDuplicates(PathBreadcrumb(file.id, file.name)) }) else null,
        canonicalNameFolder = if (library == null) ({ intents.onNameByCode(listOf(file)) }) else null,
        viewSpaceMap = if (commands.viewSpaceMap && intents.onOpenSpaceMap != null) ({ intents.onOpenSpaceMap.invoke(file.id, file.name) }) else null,
        downloadSegment = { intents.onOpenSegmentDownload(file) },
        prepareQualities = { intents.onPrefetchQualities(file.id) },
        copySource = { intents.onCopySource(file) },
        openSource = { file.sourceUrl?.let(intents.onOpenSourceUrl) },
        openInExternalPlayer = if (intents.hasExternalPlayer) ({ intents.onOpenInExternalPlayer(file) }) else null,
        openInNewTab = intents.onOpenInNewTab?.takeIf { !file.isImportPreview }?.let { { it(file) } },
        togglePin = { intents.onTogglePin(file) },
        isPinned = intents.pinnedFolders.any { it.id == file.id },
        vault = { intents.onVault(listOf(file)) },
        unvault = if (file.id in intents.vaultedFolders) ({ intents.onRestoreVaultFolder(file) }) else null,
        previewHidden = if (intents.isSpoilerBlurEnabled && file.thumbnailLink.isNotEmpty()) file.id !in state.revealedFileIds else null,
        togglePreview = { state.toggleSpoiler(file.id) },
        putOnClipboard = { cut -> state.putOnClipboard(listOf(file.id), cut) },
        copyDownloadLink = if (!file.isFolder && !file.isVaulted && !file.isUploading) ({ intents.onCopyDownloadLink(file) }) else null,
    )
    return reveal + removeRecord + fileActions(file, commands, handlers)
}

internal fun buildSelectionSheetActions(
    state: DriveScreenState,
    files: List<FileStat>,
    intents: DriveItemActionIntents,
    onShowProperties: () -> Unit,
): List<SheetAction> {
    val commands = itemCommands(commandPlaceOf(state, intents.folderStack.size), files, intents.fullyVaultedFolders)
    val base = when {
        files.all { it.isImportPreview } -> buildImportPreviewActions(state, files, intents)
        commands.restoreOrDelete -> trashActions(
            onRestore = { state.restoreFromTrash(files.map { it.id }) },
            onDelete = { intents.onLibraryConfirm(LibraryConfirm.DeleteForever(files.map { it.id }, emptying = false)) },
        )
        state.archiveView != null -> buildArchiveEntryActions(state, files, commands, intents)
        else -> buildSelectionActions(state, files, commands, intents)
    }
    return base + DriveActions.properties(onShowProperties)
}

internal fun buildDriveBackgroundActions(
    state: DriveScreenState,
    folderStackSize: Int,
    viewMode: DriveViewMode,
    galleryActive: Boolean,
    galleryShowImages: Boolean,
    showExtensions: Boolean,
    hasClipboard: Boolean,
    mountAction: SheetAction?,
    onSelectViewMode: (DriveViewMode) -> Unit,
    onToggleGallery: (Boolean) -> Unit,
    onToggleGalleryImages: (Boolean) -> Unit,
    onToggleExtensions: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onNewFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenAddLink: () -> Unit,
    onSelectAll: () -> Unit,
    onLibraryConfirm: (LibraryConfirm) -> Unit,
    onShowFolderProperties: () -> Unit,
): List<SheetAction> = buildList {
    DriveViewMode.entries.forEach { mode ->
        add(
            SheetAction(
                mode.icon(selected = !galleryActive && mode == viewMode),
                mode.paletteLabel,
                { onSelectViewMode(mode) },
                group = ActionGroup.View,
                checked = !galleryActive && mode == viewMode,
            ),
        )
    }
    val place = commandPlaceOf(state, folderStackSize)
    if (place == CommandPlace.ROOT || place == CommandPlace.FOLDER || place == CommandPlace.LOCAL || galleryActive) {
        add(
            SheetAction(
                if (galleryActive) Icons.Filled.PhotoLibrary else Icons.Outlined.PhotoLibrary,
                tr("瀑布流", "Gallery", "ギャラリー", "갤러리", "瀑布流"),
                { onToggleGallery(!galleryActive) },
                group = ActionGroup.View,
                checked = galleryActive,
            ),
        )
        if (galleryActive) {
            add(
                SheetAction(
                    Icons.Outlined.Image,
                    tr("显示图片", "Show images", "画像を表示", "이미지 표시", "顯示圖片"),
                    { onToggleGalleryImages(!galleryShowImages) },
                    group = ActionGroup.View,
                    checked = galleryShowImages,
                ),
            )
        }
    }
    add(
        SheetAction(
            Icons.Outlined.TextFields,
            tr("文件扩展名", "File extension", "拡張子", "파일 확장자", "副檔名"),
            { onToggleExtensions(!showExtensions) },
            group = ActionGroup.View,
            checked = showExtensions,
        ),
    )
    add(SheetAction(Icons.Outlined.Refresh, tr("刷新", "Refresh", "更新", "새로고침", "重新整理"), onRefresh, group = ActionGroup.Refresh))
    if (!state.isVirtualPlace) {
        if (hasClipboard) add(SheetAction(Icons.Outlined.ContentPaste, tr("粘贴", "Paste", "貼り付け", "붙여넣기", "貼上"), state::paste, group = ActionGroup.Refresh))
        add(SheetAction(Icons.Outlined.CreateNewFolder, tr("新建文件夹", "New folder", "新規フォルダー", "새 폴더", "新增資料夾"), onNewFolder, group = ActionGroup.Create))
        mountAction?.let(::add)
        add(SheetAction(Icons.Outlined.UploadFile, tr("上传文件", "Upload files", "ファイルをアップロード", "파일 업로드", "上傳檔案"), onPickFiles, group = ActionGroup.Create))
        add(SheetAction(Icons.Outlined.DriveFolderUpload, tr("上传文件夹", "Upload folder", "フォルダーをアップロード", "폴더 업로드", "上傳資料夾"), onPickFolder, group = ActionGroup.Create))
        add(SheetAction(Icons.Outlined.Bolt, tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"), onOpenAddLink, group = ActionGroup.Create))
    }
    add(SheetAction(Icons.Outlined.SelectAll, tr("全选", "Select all", "すべて選択", "모두 선택", "全選"), onSelectAll, group = ActionGroup.Select))
    addAll(libraryPageActions(state.libraryView, state.files.isEmpty(), onLibraryConfirm) { state.files.map { it.id } })
    add(DriveActions.properties(onShowFolderProperties))
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
        primary && event.key == Key.X && itemCommands(place, commandTargets).cutCopy ->
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
                targets.size > 1 -> {
                    val realTargets = targets.filterNot { it.isVaulted }
                    if (realTargets.size == 1) onStartRename(realTargets.single())
                    else if (realTargets.size > 1) onBatchRename(realTargets)
                }
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
