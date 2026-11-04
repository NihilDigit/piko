package dev.piko.ui.screens.drive

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.ImportPreviewEntryId
import dev.piko.shared.data.ImportPreviewLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.proxy.openForExternalPlayer
import dev.piko.shared.naming.av.canonicalAvNameOf
import dev.piko.shared.presentation.ActionAvailability
import dev.piko.shared.presentation.ArchiveCommands
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.GallerySession
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.VaultScope
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.UploadEnqueueResult
import dev.piko.shared.upload.UploadSelection
import dev.piko.shared.upload.isUploading
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 网盘页互斥的用户触发模态对话框状态（Screen UI State）。 */
@Stable
internal sealed interface DriveDialog {
    data object NewFolder : DriveDialog

    data class SingleRename(
        val target: FileStat,
        val initialName: String,
        val startCanonical: Boolean,
    ) : DriveDialog

    data class BatchRename(
        val files: List<FileStat>,
        val startWithAvNaming: Boolean = false,
    ) : DriveDialog

    data class CanonicalFolder(val root: PathBreadcrumb) : DriveDialog

    data class Share(val files: List<FileStat>) : DriveDialog

    data class Move(val targetIds: Set<String>) : DriveDialog

    data class Copy(val targetIds: Set<String>) : DriveDialog

    data class Vault(val scope: VaultScope) : DriveDialog

    data class RestoreVault(val folder: FileStat) : DriveDialog

    data class LibraryAction(val confirm: LibraryConfirm) : DriveDialog

    data class CloseGalleryConfirm(val nextViewMode: DriveViewMode?) : DriveDialog

    data class ImagePreview(val initialFile: FileStat) : DriveDialog
}

/** 网盘页底部/侧边操作面板状态（Screen UI State）。 */
@Stable
internal sealed interface DriveSheet {
    data class SingleItemActions(val file: FileStat) : DriveSheet

    data class SelectionActions(val targetIds: Set<String>) : DriveSheet

    data object MobileGoToPath : DriveSheet
}

/** 由 ViewModel 发往 View 层执行的一次性宿主副作用（剪贴板、外部进程、页面跳转、Snackbar）。 */
internal sealed interface DriveUiEffect {
    data class ShowSnackbar(val message: String) : DriveUiEffect

    data class CopyToClipboard(
        val label: String,
        val text: String,
        val message: String,
    ) : DriveUiEffect

    data class OpenExternalPlayer(val url: String, val fileName: String) : DriveUiEffect

    data class OpenLocalContainingFolder(val localPath: String) : DriveUiEffect

    data class ShowExtractionBlocked(val reason: String) : DriveUiEffect

    data class EnqueuePlatformDownload(
        val targets: List<FileStat>,
        val chooseQuality: Boolean,
    ) : DriveUiEffect

    data object OpenTransfers : DriveUiEffect
}

/**
 * 网盘页的 Screen-level ViewModel（遵循 Compose UDF / MVVM 规范）：
 * - 持有网盘页模态框与面板的 Screen UI State，不持有任何 Compose UI 组件引用；
 * - 承接上传、下载准备、服务端解压、外链复制、外部播放器代理、本地目录挂载与偏好修改等业务事件；
 * - 仅在顶层 `DriveScreen` 中连接，不向下传递给子 Composable。
 */
internal class DriveViewModel(
    private val state: DriveScreenState,
    private val gallery: GallerySession,
    private val driveRepo: PikoDriveRepository,
    private val preferences: PikoUserPreferences,
    private val duplicateSession: DuplicateSession,
    private val archiveSession: ArchiveExtractSession,
    private val vaultSession: FolderVaultSession,
    private val downloadManager: PikoDownloadCoordinator,
    private val uploadManager: PikoUploadCoordinator,
    private val mediaRepository: PikoMediaRepository,
    private val instantSession: InstantSession,
) : ViewModel() {

    private val archiveCommands = ArchiveCommands(archiveSession)
    private val zoom = DriveZoom(preferences)

    var activeDialog by mutableStateOf<DriveDialog?>(null)
        private set

    var activeSheet by mutableStateOf<DriveSheet?>(null)
        private set

    private var pendingMountDestination: Pair<String, String>? = null

    private val _effects = MutableSharedFlow<DriveUiEffect>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<DriveUiEffect> = _effects.asSharedFlow()

    val isSpoilerBlurEnabled: StateFlow<Boolean> = preferences.spoilerBlurFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val galleryShowImages: StateFlow<Boolean> = preferences.galleryShowImagesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, runBlocking { preferences.galleryShowImagesFlow.first() })

    val viewMode: StateFlow<DriveViewMode> = preferences.driveViewModeFlow
        .map(DriveViewMode::of)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            DriveViewMode.of(runBlocking { preferences.driveViewModeFlow.first() }),
        )

    private val galleryTileSize: StateFlow<TileSize> = preferences.driveTileSizeFlow(GALLERY_TILE_PREF_KEY)
        .map(TileSize::of)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            TileSize.of(runBlocking { preferences.driveTileSizeFlow(GALLERY_TILE_PREF_KEY).first() }),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val browseTileSize: StateFlow<TileSize> = viewMode
        .flatMapLatest { mode -> preferences.driveTileSizeFlow(mode.name).map(TileSize::of) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            TileSize.of(runBlocking { preferences.driveTileSizeFlow(viewMode.value.name).first() }),
        )

    fun tileSizeFlow(galleryActive: Boolean): StateFlow<TileSize> =
        if (galleryActive) galleryTileSize else browseTileSize

    init {
        viewModelScope.launch {
            preferences.driveViewModeFlow.collect { raw ->
                if (raw == GALLERY_TILE_PREF_KEY) preferences.setDriveViewMode(DriveViewMode.LIST.name)
            }
        }
    }

    // ---- 对话框与面板开关事件 ----

    fun dismissDialog() {
        activeDialog = null
    }

    fun dismissSheet() {
        activeSheet = null
    }

    fun requestNewFolder() {
        activeDialog = DriveDialog.NewFolder
    }

    fun confirmNewFolder(name: String) {
        activeDialog = null
        state.createFolder(name)
    }

    fun requestRename(file: FileStat) {
        activeDialog = DriveDialog.SingleRename(
            target = file,
            initialName = file.name,
            startCanonical = false,
        )
    }

    fun requestCanonicalRename(file: FileStat) {
        activeDialog = DriveDialog.SingleRename(
            target = file,
            initialName = canonicalAvNameOf(file.name) ?: file.name,
            startCanonical = true,
        )
    }

    fun requestNameByCode(files: List<FileStat>) {
        val single = files.singleOrNull()
        activeDialog = when {
            single == null -> DriveDialog.BatchRename(
                files = state.withAttachedFiles(files),
                startWithAvNaming = true,
            )
            single.isFolder -> DriveDialog.CanonicalFolder(PathBreadcrumb(single.id, single.name))
            else -> DriveDialog.SingleRename(
                target = single,
                initialName = canonicalAvNameOf(single.name) ?: single.name,
                startCanonical = true,
            )
        }
    }

    fun requestBatchRename(settledFiles: List<FileStat>, startWithAvNaming: Boolean = false) {
        if (settledFiles.isEmpty()) return
        activeDialog = DriveDialog.BatchRename(
            files = state.withAttachedFiles(settledFiles),
            startWithAvNaming = startWithAvNaming,
        )
    }

    fun requestCanonicalFolder(root: PathBreadcrumb) {
        activeDialog = DriveDialog.CanonicalFolder(root)
    }

    fun confirmSingleRename(fileId: String, newName: String) {
        activeDialog = null
        state.rename(fileId, newName)
    }

    fun onBatchRenameFinished(message: String) {
        if (state.isSelectionMode) state.exitSelection()
        if (message.isNotBlank()) _effects.tryEmit(DriveUiEffect.ShowSnackbar(message))
    }

    fun requestShare(files: List<FileStat>) {
        if (files.isEmpty()) return
        activeDialog = DriveDialog.Share(files)
    }

    fun onShareCopied() {
        if (state.isSelectionMode) state.exitSelection()
        _effects.tryEmit(
            DriveUiEffect.ShowSnackbar(
                tr("已复制分享链接", "Share link copied", "共有リンクをコピーしました", "공유 링크를 복사했습니다", "已複製分享連結"),
            ),
        )
    }

    fun requestMove(ids: Set<String>) {
        if (ids.isNotEmpty()) activeDialog = DriveDialog.Move(ids)
    }

    fun confirmMove(ids: List<String>, targetId: String, targetName: String): Boolean =
        state.move(ids, targetId, targetName).also { accepted ->
            if (accepted) activeDialog = null
        }

    fun requestCopy(ids: Set<String>) {
        if (ids.isNotEmpty()) activeDialog = DriveDialog.Copy(ids)
    }

    fun confirmCopy(ids: List<String>, targetId: String, targetName: String): Boolean =
        state.copy(ids, targetId, targetName).also { accepted ->
            if (accepted) activeDialog = null
        }

    fun requestVault(files: List<FileStat>) {
        if (files.isEmpty()) return
        val (folders, singles) = files.partition { it.isFolder }
        val name = files.singleOrNull()?.let { state.itemName(it).headingText }
            ?: tr("所选 ${files.size} 项", "${files.size} selected items", "選択した ${files.size} 項目", "선택한 ${files.size}개 항목", "所選 ${files.size} 個項目")
        activeDialog = DriveDialog.Vault(
            VaultScope(name, folders.map { PathBreadcrumb(it.id, state.itemName(it).headingText) }, singles),
        )
    }

    fun requestRestoreVault(folder: FileStat) {
        activeDialog = DriveDialog.RestoreVault(folder)
    }

    fun requestLibraryConfirm(confirm: LibraryConfirm) {
        activeDialog = DriveDialog.LibraryAction(confirm)
    }

    fun confirmLibraryAction(confirm: LibraryConfirm) {
        activeDialog = null
        when (confirm) {
            is LibraryConfirm.DeleteForever -> state.deletePermanently(confirm.ids)
            LibraryConfirm.ClearHistory -> state.clearPlayHistory()
        }
    }

    fun requestImagePreview(file: FileStat) {
        activeDialog = DriveDialog.ImagePreview(file)
    }

    fun openSingleItemActions(file: FileStat, displayedFiles: List<FileStat>) {
        if (state.isSelectionMode && file.id in state.selectedFileIds) {
            openSelectionActions(displayedFiles)
        } else {
            activeSheet = DriveSheet.SingleItemActions(file)
        }
    }

    fun openSelectionActions(displayedFiles: List<FileStat>) {
        val ids = displayedFiles.filter { it.id in state.selectedFileIds }.mapTo(LinkedHashSet()) { it.id }
        activeSheet = if (ids.isNotEmpty()) DriveSheet.SelectionActions(ids) else null
    }

    fun openMobileGoToPath() {
        activeSheet = DriveSheet.MobileGoToPath
    }

    // ---- 视图、卡片缩放与瀑布流 ----

    fun setGalleryShowImages(show: Boolean) {
        viewModelScope.launch { preferences.setGalleryShowImages(show) }
    }

    fun setShowExtensions(show: Boolean) {
        viewModelScope.launch { preferences.setShowExtensions(show) }
    }

    fun selectViewMode(mode: DriveViewMode, galleryActive: Boolean) {
        if (galleryActive) {
            activeDialog = DriveDialog.CloseGalleryConfirm(mode)
        } else {
            viewModelScope.launch { preferences.setDriveViewMode(mode.name) }
        }
    }

    fun requestCloseGallery(galleryActive: Boolean, targetMode: DriveViewMode? = null) {
        if (galleryActive) {
            activeDialog = DriveDialog.CloseGalleryConfirm(targetMode)
        } else {
            gallery.close()
            if (targetMode != null) viewModelScope.launch { preferences.setDriveViewMode(targetMode.name) }
        }
    }

    fun confirmCloseGallery(nextViewMode: DriveViewMode?) {
        activeDialog = null
        gallery.close()
        if (nextViewMode != null) {
            viewModelScope.launch { preferences.setDriveViewMode(nextViewMode.name) }
        }
    }

    fun selectTileSize(size: TileSize, galleryActive: Boolean, viewMode: DriveViewMode) {
        viewModelScope.launch {
            if (galleryActive) preferences.setDriveTileSize(GALLERY_TILE_PREF_KEY, size.name)
            else zoom.select(viewMode, size)
        }
    }

    fun stepZoom(larger: Boolean) {
        viewModelScope.launch { zoom.step(larger) }
    }

    fun openSelectedGallery(
        files: List<FileStat>,
        folderStack: List<PathBreadcrumb>,
        galleryShowImages: Boolean,
    ) {
        val media = files.filter {
            (it.isPlayableVideo() || (galleryShowImages && it.isPreviewableImage())) &&
                !it.isUploading && !it.isImportPreview
        }
        if (media.isEmpty()) return
        state.exitSelection()
        gallery.open(folderStack, media, showImages = galleryShowImages)
    }

    fun refresh(galleryActive: Boolean) {
        if (state.libraryView == DriveLibrary.DUPLICATES) {
            duplicateSession.state?.rescan()
            return
        }
        if (galleryActive) gallery.refresh()
        state.load(refresh = true)
    }

    fun togglePinFolder(folder: FileStat, pinnedFolders: List<PathBreadcrumb>) {
        if (pinnedFolders.any { it.id == folder.id }) driveRepo.unpinFolder(folder.id)
        else driveRepo.pinFolder(PathBreadcrumb(folder.id, folder.name))
    }

    // ---- 外链、外部播放器、上传、下载、解压与本地挂载 ----

    fun copySource(file: FileStat) {
        val url = file.sourceUrl ?: return
        val toast = if (url.startsWith("magnet:", true)) {
            tr("已复制磁力链接", "Magnet link copied", "マグネットリンクをコピーしました", "마그넷 링크를 복사했습니다", "已複製磁力連結")
        } else {
            tr("已复制分享链接", "Share link copied", "共有リンクをコピーしました", "공유 링크를 복사했습니다", "已複製分享連結")
        }
        _effects.tryEmit(
            DriveUiEffect.CopyToClipboard(
                label = tr("来源链接", "Source link", "ソースリンク", "출처 링크", "來源連結"),
                text = url,
                message = toast,
            ),
        )
    }

    fun copyDownloadLink(file: FileStat) {
        viewModelScope.launch {
            val url = mediaRepository.fetchDownloadUrl(file.id).logFailure("Drive", "查文件下载链接失败").getOrNull()
            if (!url.isNullOrBlank()) {
                _effects.emit(
                    DriveUiEffect.CopyToClipboard(
                        label = tr("下载链接", "Download link", "ダウンロードリンク", "다운로드 링크", "下載連結"),
                        text = url,
                        message = tr("已复制下载链接", "Download link copied", "ダウンロードリンクをコピーしました", "다운로드 링크를 복사했습니다", "已複製下載連結"),
                    ),
                )
            } else {
                _effects.emit(
                    DriveUiEffect.ShowSnackbar(
                        tr("未能获取下载链接", "Failed to get download link", "ダウンロードリンクを取得できませんでした", "다운로드 링크를 가져오지 못했습니다", "無法取得下載連結"),
                    ),
                )
            }
        }
    }

    fun openInExternalPlayer(file: FileStat, requiresDirectUrl: Boolean = false) {
        viewModelScope.launch {
            val source = if (requiresDirectUrl) mediaRepository.fetchDownloadUrl(file.id)
                else mediaRepository.openForExternalPlayer(file.id)
            val url = source
                .logFailure(
                    "Drive",
                    tr("外部播放器取不到播放地址", "Playback address unavailable for external player", "外部プレーヤー用の再生アドレスを取得できません", "외부 플레이어의 재생 주소를 가져올 수 없습니다", "外部播放器無法取得播放位址"),
                )
                .getOrNull()
            if (url != null) {
                _effects.emit(DriveUiEffect.OpenExternalPlayer(url, file.name))
            } else {
                _effects.emit(
                    DriveUiEffect.ShowSnackbar(
                        tr("无法用外部播放器打开", "Unable to open in external player", "外部プレーヤーで開けません", "외부 플레이어에서 열 수 없습니다", "無法使用外部播放器開啟"),
                    ),
                )
            }
        }
    }

    fun prefetchDownloadQualities(fileId: String) {
        mediaRepository.prefetchDownloadQualities(fileId)
    }

    fun upload(selection: UploadSelection, activeFolder: PathBreadcrumb) {
        if (state.isVirtualPlace || !state.acceptsActions) return
        val unavailable = state.fileActions.receivingAvailability(activeFolder.id) as? ActionAvailability.Blocked
        if (unavailable != null) {
            uploadManager.request(selection)
            _effects.tryEmit(DriveUiEffect.ShowSnackbar(unavailable.reason))
            return
        }
        if (uploadManager.enqueue(selection, activeFolder.id, activeFolder.localizedName) is UploadEnqueueResult.Rejected) {
            uploadManager.request(selection)
        }
    }

    fun beginLocalMount(activeFolderId: String): Boolean {
        if (!isDriveFolderId(activeFolderId)) return false
        val account = driveRepo.currentAccount ?: return false
        pendingMountDestination = account to activeFolderId
        return true
    }

    fun completeLocalMount(path: String, name: String) {
        val destination = pendingMountDestination ?: return
        pendingMountDestination = null
        launchLocalAction {
            if (destination.first == driveRepo.currentAccount) {
                val outcome = driveRepo.localMounts.mountOrExisting(destination.second, path, name)
                state.highlight(setOf(outcome.mount.location.id))
                if (outcome.alreadyMounted) {
                    _effects.emit(
                        DriveUiEffect.ShowSnackbar(
                            tr("此文件夹已挂载在当前目录", "This folder is already mounted here", "このフォルダーは既にここにマウントされています", "이 폴더는 이미 현재 위치에 마운트되어 있습니다", "此資料夾已掛載於目前目錄"),
                        ),
                    )
                }
            }
        }
    }

    fun revealLocalInFileManager(fileId: String) {
        launchLocalAction {
            val resolved = driveRepo.localMounts.resolve(fileId)?.takeUnless { it.startsWith("content://") }
            if (resolved != null) _effects.emit(DriveUiEffect.OpenLocalContainingFolder(resolved))
        }
    }

    fun unmountLocalFolder(fileId: String) {
        launchLocalAction { driveRepo.localMounts.unmount(fileId) }
    }

    private fun launchLocalAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                PikoLog.w("LocalMount", "本地文件夹操作失败")
                _effects.emit(
                    DriveUiEffect.ShowSnackbar(
                        tr("本地文件夹操作失败", "Local folder action failed", "ローカルフォルダーの操作に失敗しました", "로컬 폴더 작업에 실패했습니다", "本機資料夾操作失敗"),
                    ),
                )
            }
        }
    }

    fun requestDownload(
        files: List<FileStat>,
        activeFolderId: String,
        chooseQuality: Boolean = false,
    ) {
        val importPreviewItems = files.filter { it.isImportPreview }
        if (importPreviewItems.isNotEmpty()) {
            val draft = instantSession.state
            val resolvedList = importPreviewItems.flatMap { item ->
                ImportPreviewEntryId.resolvedFileOf(item.id)?.let { listOf(it) }
                    ?: draft?.let { d ->
                        ImportPreviewLocation.of(item.id)?.let { location ->
                            val path = location.path.trim('/')
                            d.projectedSaveEntries.filter { (_, file) ->
                                path.isEmpty() || file.path.trim('/') == path || file.path.trim('/').startsWith("$path/")
                            }
                        }
                    }?.map { it.second }.orEmpty()
            }.filter { it.gcid != null }.distinct()
            if (resolvedList.isNotEmpty()) {
                downloadManager.enqueueResolved(resolvedList, draft?.target?.id ?: activeFolderId)
                _effects.tryEmit(DriveUiEffect.OpenTransfers)
            }
            val rest = files.filterNot { it.isImportPreview }
            if (rest.isEmpty()) return
        }
        val activeFiles = files.filterNot { it.isImportPreview }
        if (state.archiveView != null) {
            viewModelScope.launch {
                val ready = activeFiles.filter { !it.isFolder }.mapNotNull { state.prepareArchiveEntry(it) }
                downloadManager.enqueueFiles(ready)
                if (ready.isNotEmpty()) _effects.emit(DriveUiEffect.OpenTransfers)
            }
            return
        }
        val targets = activeFiles.filter { !it.isFolder && !it.isUploading } + activeFiles.filter { it.isFolder }
        if (targets.isNotEmpty()) {
            _effects.tryEmit(DriveUiEffect.EnqueuePlatformDownload(targets, chooseQuality))
        }
    }

    private fun checkExtractionAllowed(archives: List<FileStat>, start: () -> Unit) {
        if (!state.acceptsActions) return
        val availability = archiveCommands.startAvailability(archives)
        if (availability is ActionAvailability.Blocked) {
            _effects.tryEmit(DriveUiEffect.ShowExtractionBlocked(availability.reason))
        } else {
            start()
        }
    }

    fun extractArchives(archives: List<FileStat>) = checkExtractionAllowed(archives) {
        archiveSession.extract(archives)
        state.exitSelection()
    }

    fun extractFromArchive(items: List<FileStat>) {
        val (archive, password) = state.currentArchive() ?: return
        val mismatched = items.any { item ->
            val owner = ArchiveLocation.of(item.id)?.archiveId ?: ArchiveEntryId.pathOf(item.id)?.first
            owner != archive.id
        }
        if (mismatched) {
            _effects.tryEmit(
                DriveUiEffect.ShowExtractionBlocked(
                    tr("所在压缩包已变更，请从当前列表重新选择。", "The archive has changed. Select again from the current list.", "圧縮ファイルの内容が変更されました。現在の一覧から選択し直してください。", "압축 파일이 변경되었습니다. 현재 목록에서 다시 선택해 주세요.", "所在壓縮檔已變更，請從目前清單重新選取。"),
                ),
            )
            return
        }
        checkExtractionAllowed(listOf(archive)) {
            archiveSession.extractEntries(archive, state.archivePaths(items), password)
            state.exitSelection()
        }
    }

    fun restoreVaultItems(items: List<FileStat>) {
        if (state.isSelectionMode) state.exitSelection()
        vaultSession.restore(items)
    }
}
