package dev.piko.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.savedstate.serialization.SavedStateConfiguration
import dev.piko.data.repository.isHtmlDocument
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.proxy.HtmlDocumentProxySessions
import dev.piko.shared.presentation.FileWorkspace
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.ui.components.PaletteItem
import dev.piko.ui.navigation.MainTab
import dev.piko.ui.navigation.Screen
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.screens.drive.icon
import dev.piko.ui.workbench.WorkspaceViewModel
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

internal val NavKeyConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(Screen.Home::class)
            subclass(Screen.Library::class)
            subclass(Screen.MyShares::class)
            subclass(Screen.SpaceMap::class)
            subclass(Screen.Settings::class)
            subclass(Screen.WebDav::class)
            subclass(Screen.PlayerCustomization::class)
            subclass(Screen.DriveAppearanceCustomization::class)
            subclass(Screen.VideoPlayer::class)
            subclass(Screen.DocumentReader::class)
            subclass(Screen.HtmlViewer::class)
        }
    }
}

@Stable
internal class MainBackStackCoordinator(
    val backStack: NavBackStack<NavKey>,
    private val account: String,
    private val services: PikoServices,
    private val workspaceModel: WorkspaceViewModel,
    private val platform: PikoPlatform,
    private val videoPlayer: VideoPlayerHost,
    private val coroutineScope: CoroutineScope,
    private val isDesktopProvider: () -> Boolean,
    private val currentTabProvider: () -> MainTab,
    private val onSetCurrentTab: (MainTab) -> Unit,
    private val onIncrementLibraryReset: () -> Unit,
    private val onIncrementSpaceMapReset: () -> Unit,
) {
    private val fileWorkspace: FileWorkspace get() = workspaceModel.fileWorkspace
    private val driveWorkspace get() = workspaceModel.driveWorkspace
    private val localFiles get() = platform.localFiles
    private val shortcutModifier: ShortcutModifier get() = platform.shortcutModifier

    fun resetToHome() {
        while (backStack.size > 1) backStack.removeLastOrNull()
    }

    fun popBack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    fun leaveFileEntry(): Boolean {
        val origin = workspaceModel.returnFromFileEntry() ?: return false
        onSetCurrentTab(origin.tab)
        resetToHome()
        origin.screens.drop(1).forEach(backStack::add)
        return true
    }

    fun openProfilePane(screen: Screen) {
        while (backStack.size > 1 && (backStack.lastOrNull() in SettingsSubpages || isReaderScreen(backStack.lastOrNull()))) {
            backStack.removeLastOrNull()
        }
        val top = backStack.lastOrNull()
        if (top == screen) {
            if (screen is Screen.SpaceMap) onIncrementSpaceMapReset()
            return
        }
        if (isProfilePane(top) && backStack.size > 1) backStack.removeLastOrNull()
        backStack.add(screen)
    }

    fun openSpaceMapFromDrive(folderId: String, folderName: String) {
        openProfilePane(Screen.SpaceMap(folderId, folderName))
    }

    fun pushLibrary(library: DriveLibrary) {
        val target = Screen.Library(library.id)
        if (backStack.lastOrNull() == target) {
            onIncrementLibraryReset()
            return
        }
        openProfilePane(target)
    }

    fun openTransfers() {
        onSetCurrentTab(MainTab.TRANSFERS)
        resetToHome()
    }

    fun openPage(screen: Screen) {
        if (!isDesktopProvider()) onSetCurrentTab(MainTab.SETTINGS)
        resetToHome()
        openProfilePane(screen)
    }

    fun playVideo(file: FileStat, playlist: List<FileStat>, startMillis: Long? = null) {
        val localTask = services.downloadManager.tasks.value.values.find {
            it.fileId == file.id && it.status == DownloadStatus.COMPLETED && !it.isSegment
        }
        val localPath = localTask?.destinationPath?.takeIf(localFiles::exists)
        if (videoPlayer is VideoPlayerHost.Detached && videoPlayer.openImage != null &&
            file.isPreviewableImage() && !file.isPlayableVideo()
        ) {
            val imageFile = if (localPath != null && file.params["local_path"].isNullOrBlank()) {
                file.copy(params = file.params + ("local_path" to localPath))
            } else file
            val imagePeers = playlist.filter { it.isPreviewableImage() && !it.isPlayableVideo() }.ifEmpty { listOf(imageFile) }
            val startIndex = imagePeers.indexOfFirst { it.id == imageFile.id }.coerceAtLeast(0)
            videoPlayer.openImage.invoke(ImageViewerRequest(images = imagePeers, initialIndex = startIndex))
            return
        }
        when (videoPlayer) {
            is VideoPlayerHost.InApp -> backStack.add(Screen.VideoPlayer(file.id, file.name, localPath, startMillis))
            is VideoPlayerHost.Detached -> videoPlayer.open(VideoPlayerRequest(file.id, file.name, localPath, playlist, startMillis))
        }
    }

    fun playLocal(fileId: String, fileName: String, localPath: String?) {
        val candidate = FileStat(
            id = fileId,
            name = fileName,
            params = if (!localPath.isNullOrBlank()) mapOf("local_path" to localPath) else emptyMap(),
        )
        if (videoPlayer is VideoPlayerHost.Detached && videoPlayer.openImage != null &&
            candidate.isPreviewableImage() && !candidate.isPlayableVideo()
        ) {
            videoPlayer.openImage.invoke(ImageViewerRequest(images = listOf(candidate), initialIndex = 0))
            return
        }
        when (videoPlayer) {
            is VideoPlayerHost.InApp -> backStack.add(Screen.VideoPlayer(fileId, fileName, localPath))
            is VideoPlayerHost.Detached -> videoPlayer.open(VideoPlayerRequest(fileId, fileName, localPath))
        }
    }

    fun openDocument(file: FileStat) {
        val localTask = services.downloadManager.tasks.value.values.find {
            it.fileId == file.id && it.status == DownloadStatus.COMPLETED && !it.isSegment
        }
        val localPath = localTask?.destinationPath?.takeIf(localFiles::exists)
            ?: file.params["local_path"]?.takeIf { it.isNotBlank() && !it.startsWith("content://") }
        val parentFolderId = file.parentId.takeIf { it.isNotBlank() }
        val sizeBytes = file.size.toLongOrNull() ?: 0L
        when {
            file.isHtmlDocument() -> {
                if (platform.htmlViewer != null) {
                    backStack.add(
                        Screen.HtmlViewer(
                            fileId = file.id,
                            fileName = file.name,
                            parentFolderId = parentFolderId,
                            localPath = localPath,
                        ),
                    )
                } else {
                    coroutineScope.launch {
                        HtmlDocumentProxySessions.openForExternalBrowser(
                            fileId = file.id,
                            fileName = file.name,
                            parentFolderId = parentFolderId,
                            localPath = localPath,
                            driveRepository = services.driveRepository,
                            mediaRepository = services.mediaRepository,
                        ).onSuccess { handle ->
                            platform.openUrl(handle.url)
                        }.onFailure { err ->
                            services.postAppNotice(
                                err.message ?: tr("无法打开网页文档", "Unable to open HTML document", "HTML ドキュメントを開けませんでした", "HTML 문서를 열 수 없습니다", "無法開啟網頁文件"),
                            )
                        }
                    }
                }
            }
            else -> {
                backStack.add(
                    Screen.DocumentReader(
                        fileId = file.id,
                        fileName = file.name,
                        parentFolderId = parentFolderId,
                        localPath = localPath,
                        sizeBytes = sizeBytes,
                    ),
                )
            }
        }
    }

    fun goToDrive(stack: List<PikoPathBreadcrumb>, then: () -> Unit = {}) {
        if (services.driveRepository.currentAccount != account) return
        workspaceModel.activatePrimaryFileBrowser()
        fileWorkspace.navigateThen(stack) {
            if (services.driveRepository.currentAccount != account) return@navigateThen
            fileWorkspace.updateFolderStack(stack)
            then()
            resetToHome()
            onSetCurrentTab(MainTab.FILES)
        }
    }

    fun showInDrive(stack: List<PikoPathBreadcrumb>, fileId: String, activatePrimary: Boolean = true) {
        if (services.driveRepository.currentAccount != account) return
        if (activatePrimary) workspaceModel.activatePrimaryFileBrowser()
        fileWorkspace.navigateThen(stack) {
            if (services.driveRepository.currentAccount != account) return@navigateThen
            fileWorkspace.updateFolderStack(stack)
            fileWorkspace.requestHighlight(setOf(fileId))
            resetToHome()
            onSetCurrentTab(MainTab.FILES)
        }
    }

    suspend fun revealInDrive(fileId: String, knownParentStack: List<PikoPathBreadcrumb> = emptyList()): Boolean {
        val originTab = currentTabProvider()
        val originScreens = backStack.filterIsInstance<Screen>()
        val originContextId = workspaceModel.contexts.activeContextId.value
        val stack = when {
            fileId.isEmpty() -> listOf(PikoDriveRepository.ROOT_BREADCRUMB)
            knownParentStack.isNotEmpty() && knownParentStack.first().id.isEmpty() -> knownParentStack
            else -> (workspaceModel.awaitPathLookup(fileId) ?: return true).getOrElse { return false }
        }
        if (services.driveRepository.currentAccount != account) return true
        workspaceModel.enterFromOutside(stack, originTab, originScreens, originContextId)
        if (fileId.isNotEmpty()) driveWorkspace.requestHighlight(setOf(fileId))
        resetToHome()
        onSetCurrentTab(MainTab.FILES)
        return true
    }

    fun openDuplicateReport() {
        val reports = workspaceModel.contexts.contextsFlow.value.filterIsInstance<WorkspaceContext.Duplicate>()
        if (reports.size == 1) workspaceModel.activateContext(reports.single().id, rememberOrigin = true)
        else if (reports.isNotEmpty()) {
            onSetCurrentTab(MainTab.FILES)
            resetToHome()
            if (!isDesktopProvider()) workspaceModel.ongoingWorkShown = true
        }
    }

    fun tabDestinations(): List<PaletteItem> {
        val desktop = isDesktopProvider()
        val label = shortcutModifier::label
        return listOfNotNull(
            PaletteItem(tr("文件", "Files", "ファイル", "파일", "檔案"), Icons.Outlined.Folder, tr("前往", "Go", "移動", "이동", "前往"), detail = label("1"), keywords = "files drive") { onSetCurrentTab(MainTab.FILES); resetToHome() },
            PaletteItem(tr("任务", "Tasks", "タスク", "작업", "任務"), Icons.Outlined.SyncAlt, tr("前往", "Go", "移動", "이동", "前往"), detail = label("2"), keywords = "tasks transfers downloads uploads") { openTransfers() },
            if (desktop) null else PaletteItem(tr("我的", "Me", "マイページ", "내 정보", "我的"), Icons.Outlined.Person, tr("前往", "Go", "移動", "이동", "前往"), detail = label("3"), keywords = "profile me") { onSetCurrentTab(MainTab.SETTINGS); resetToHome() },
        )
    }

    private fun libraryDestination(library: DriveLibrary, keywords: String) =
        PaletteItem(library.title, library.icon, tr("页面", "Page", "ページ", "페이지", "頁面"), keywords = keywords) { pushLibrary(library) }

    fun pageDestinations(): List<PaletteItem> = listOf(
        libraryDestination(DriveLibrary.STARRED, "starred"),
        libraryDestination(DriveLibrary.RECENT, "recent added uploads 新增"),
        libraryDestination(DriveLibrary.HISTORY, "history"),
        PaletteItem(tr("我的分享", "My shares", "自分の共有", "내 공유", "我的分享"), Icons.Outlined.Share, tr("页面", "Page", "ページ", "페이지", "頁面"), keywords = "shares") { openPage(Screen.MyShares) },
        PaletteItem(tr("空间分布", "Space usage", "ストレージ内訳", "공간 분포", "空間分佈"), Icons.Outlined.PieChart, tr("页面", "Page", "ページ", "페이지", "頁面"), keywords = "space usage storage disk treemap 容量 空间 占用") { openPage(Screen.SpaceMap()) },
        libraryDestination(DriveLibrary.TRASH, "trash bin"),
        PaletteItem(tr("设置", "Settings", "設定", "설정", "設定"), Icons.Outlined.Settings, tr("页面", "Page", "ページ", "페이지", "頁面"), keywords = "settings preferences") { openPage(Screen.Settings) },
    )
}

@Composable
internal fun rememberMainBackStackCoordinator(
    account: String,
    services: PikoServices,
    workspaceModel: WorkspaceViewModel,
    videoPlayer: VideoPlayerHost,
    isDesktopProvider: () -> Boolean,
    currentTabProvider: () -> MainTab,
    onSetCurrentTab: (MainTab) -> Unit,
    onIncrementLibraryReset: () -> Unit,
    onIncrementSpaceMapReset: () -> Unit,
): MainBackStackCoordinator {
    val backStack = rememberNavBackStack(NavKeyConfiguration, *workspaceModel.lastNavigation.toTypedArray())
    val platform = LocalPikoPlatform.current
    val coroutineScope = rememberCoroutineScope()
    return remember(backStack, account, services, workspaceModel, platform, videoPlayer, coroutineScope) {
        MainBackStackCoordinator(
            backStack = backStack,
            account = account,
            services = services,
            workspaceModel = workspaceModel,
            platform = platform,
            videoPlayer = videoPlayer,
            coroutineScope = coroutineScope,
            isDesktopProvider = isDesktopProvider,
            currentTabProvider = currentTabProvider,
            onSetCurrentTab = onSetCurrentTab,
            onIncrementLibraryReset = onIncrementLibraryReset,
            onIncrementSpaceMapReset = onIncrementSpaceMapReset,
        )
    }
}

private val LibraryScreens: Set<NavKey?> = DriveLibrary.entries.mapTo(mutableSetOf()) { Screen.Library(it.id) }
private val ProfilePanes = setOf<NavKey?>(Screen.MyShares, Screen.Settings) + LibraryScreens
internal fun isProfilePane(key: NavKey?): Boolean = key in ProfilePanes || key is Screen.SpaceMap
private val DesktopTopLevelScreens = ProfilePanes + Screen.Home
private val DesktopTopLevelContentKeys = DesktopTopLevelScreens.mapTo(mutableSetOf()) { it.toString() }
internal fun isDesktopTopLevelContentKey(key: Any?): Boolean =
    key in DesktopTopLevelContentKeys || key?.toString()?.startsWith("SpaceMap(") == true
internal val SettingsSubpages = setOf<NavKey?>(Screen.WebDav, Screen.PlayerCustomization, Screen.DriveAppearanceCustomization)
internal fun isReaderScreen(key: NavKey?): Boolean = key is Screen.DocumentReader || key is Screen.HtmlViewer
