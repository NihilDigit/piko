package dev.piko.ui

import dev.piko.ui.workbench.rememberTransferActivity
import androidx.compose.material.icons.outlined.Keyboard
import dev.piko.ui.workbench.ShortcutsDialog
import dev.piko.ui.workbench.ClaimDialog
import dev.piko.ui.workbench.LocalTaskSlot
import dev.piko.ui.workbench.TaskSlot
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import dev.piko.ui.workbench.FloatingTasks
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.fillMaxWidth
import dev.piko.ui.workbench.SidebarTransferReadout
import androidx.compose.foundation.layout.Spacer
import dev.piko.ui.workbench.SidebarAccountRow
import dev.piko.ui.workbench.AccountSettings
import dev.piko.ui.screens.drive.SectionLabel
import dev.piko.ui.screens.drive.icon
import dev.piko.ui.screens.drive.selectedIcon
import dev.piko.shared.data.isPikoInternalFolder
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.library
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.ui.theme.ThemeMode
import dev.piko.ui.components.LocalPaletteRegistry
import dev.piko.ui.components.LocalShowExtensions
import dev.piko.ui.components.PaletteRegistry
import dev.piko.ui.components.PaletteItem
import dev.piko.ui.components.CommandPalette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.History
import dev.piko.ui.components.LocalFileDrag
import dev.piko.ui.components.FileDragState
import dev.piko.ui.components.FileDragOverlay
import dev.piko.ui.components.fileDragHost
import androidx.compose.runtime.CompositionLocalProvider
import dev.piko.ui.screens.drive.highlights
import dev.piko.ui.screens.drive.SidebarWidth
import dev.piko.ui.screens.drive.SidebarItem
import dev.piko.ui.screens.drive.QuickAccessSection
import dev.piko.shared.state.QuickAccessState
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.workbench.TransferActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import dev.piko.ui.screens.instant.DockedAddLink
import dev.piko.ui.screens.instant.FloatingAddLinkSheet
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import dev.piko.data.repository.isPlayableVideo
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.adaptive.isHeightCompact
import dev.piko.ui.components.SidePanelLayout
import dev.piko.ui.components.sidePanelFits
import dev.piko.ui.components.trackInputModality
import dev.piko.ui.components.FocusFallback
import dev.piko.ui.components.LocalFocusFallback
import dev.piko.ui.components.focusFallbackRoot
import dev.piko.ui.navigation.MainTab
import dev.piko.ui.navigation.Screen
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.files.FilesScreen
import androidx.compose.material.icons.filled.SwapVerticalCircle
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.SwapVerticalCircle
import androidx.compose.material.icons.outlined.NewReleases
import dev.piko.ui.screens.settings.ProfileScreen
import dev.piko.ui.screens.settings.SettingsScreen
import dev.piko.ui.screens.clips.ClipFeedScreen
import dev.piko.ui.screens.clips.FeedResumeBar
import androidx.compose.ui.Alignment
import dev.piko.ui.screens.share.MySharesScreen
import dev.piko.ui.screens.settings.WebDavScreen
import dev.piko.ui.screens.transfers.TransfersScreen
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.components.PikoBrand
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.screens.drive.LocalSidebarCollapsed
import dev.piko.ui.screens.drive.SidebarRailWidth
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material3.LocalContentColor
import dev.piko.data.auth.SnailMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.platform.LocalWindowCaption
import dev.piko.ui.platform.LocalWindowResizing
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.height
import dev.piko.ui.theme.FrameCardShape
import dev.piko.ui.theme.FrameContentShape
import dev.piko.ui.theme.IslandGap
import dev.piko.ui.theme.LocalFramed
import dev.piko.ui.theme.SidebarPushMinWindowWidth
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import dev.piko.ui.theme.frame
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import dev.piko.ui.workbench.LocatingOverlay
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/** 一次播放请求。[playlist] 是同目录可播的视频，桌面播放器用它做选集；来自传输页时为空。 */
class VideoPlayerRequest(
    val fileId: String,
    val fileName: String,
    val localPath: String?,
    val playlist: List<FileStat> = emptyList(),
    /** 从这里开播，不查续播记录。 */
    val startMillis: Long? = null,
)

/**
 * 播放器怎么呈现由平台决定。Android 在应用内压一层全屏页，播放器代码暂时留在 app 模块；
 * 桌面端开独立窗口，可以一边播一边继续浏览网盘。
 *
 * 信息流两端都在网盘页里（宽窗口是右侧侧栏，窄窗口盖住网盘页），桌面端另可弹出到独立窗口。
 */
sealed interface VideoPlayerHost {
    class InApp(
        val content: @Composable (request: Screen.VideoPlayer, onClose: () -> Unit) -> Unit,
    ) : VideoPlayerHost

    class Detached(
        val open: (VideoPlayerRequest) -> Unit,
        /** 把信息流弹出到独立窗口，已开着时调到前台。 */
        val openClipFeed: (ClipFeedLinks) -> Unit,
        /**
         * 信息流窗口开着没有。读的是 Compose 状态，在组合里调用即可随之重组：窗口开着时应用内的侧栏收起，
         * 收回（[ClipFeedLinks.dock]）时再出现。
         */
        val isClipFeedOpen: () -> Boolean,
        val closeClipFeed: () -> Unit,
    ) : VideoPlayerHost
}

/**
 * 独立窗口里的信息流要借主界面做的事：看完整、在网盘中显示，收回主窗口（关掉窗口、回到侧栏或全屏形态），
 * 以及关掉信息流（关窗即是关掉它，不是收回）。
 */
class ClipFeedLinks(
    val playFull: (file: FileStat, startMillis: Long) -> Unit,
    val locate: (FileStat) -> Unit,
    val dock: () -> Unit,
    val close: () -> Unit,
)

// 非 Android 端没有反射可用，返回栈里的每种 NavKey 都要登记序列化器才能存取
private val NavKeyConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(Screen.Home::class)
            subclass(Screen.Profile::class)
            subclass(Screen.MyShares::class)
            subclass(Screen.Settings::class)
            subclass(Screen.WebDav::class)
            subclass(Screen.VideoPlayer::class)
        }
    }
}


/**
 * 主界面：Navigation 3 的返回栈加导航套件。
 *
 * 栈底是 [Screen.Home]：导航栏与三个根页面，导航随窗口宽度变化，compact 是底部导航栏，
 * medium 与 expanded 换成侧边导航栏。其余页面压在它上面、盖住整个窗口：「我的」的详情页
 * （星标、播放历史、我的分享、回收站、设置）与 Android 的应用内播放器。expanded 窗口里详情页
 * 与「我的」并排成列表加详情两栏（material3 adaptive 的 ListDetailSceneStrategy）。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun PikoMainScaffold(
    onLogout: () -> Unit,
    videoPlayer: VideoPlayerHost,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val localFiles = LocalPikoPlatform.current.localFiles
    val shortcutModifier = LocalPikoPlatform.current.shortcutModifier
    var currentTab by rememberSaveable { mutableStateOf(MainTab.FILES) }
    val backStack = rememberNavBackStack(NavKeyConfiguration, Screen.Home)
    val widthClass = currentWidthClass()
    val coroutineScope = rememberCoroutineScope()
    // 窄窗口里同一时刻只做一件占位置的事，再开一件先确认结束眼前的，见 TaskSlot
    val taskSlot = remember { TaskSlot() }

    val topScreen = backStack.lastOrNull() as? Screen
    val onHome = backStack.size <= 1

    // 桌面任何宽度都是一整条侧边栏：上面是去处，下面是网盘的快捷访问与库，照 Finder 的边栏与资源管理器的导航窗格。
    // 移动端是底部导航栏，宽了换成 Rail，三项与底栏相同，见 FormFactor。
    // 侧边栏在返回栈外面，打开设置、我的分享时不被盖住
    val desktop = isDesktopLayout()
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val sidebarWindow = desktop
    // 只用于移动端横握时收起系统栏
    val heightCompact = isHeightCompact()
    // 窄窗口里只留窄轨的位置，展开时浮在内容上，见 SidebarPushMinWindowWidth
    val sidebarOverlays = windowWidth < SidebarPushMinWindowWidth

    fun resetToHome() {
        while (backStack.size > 1) backStack.removeLastOrNull()
    }

    // 详情页出栈时，垫在它下面的「我的」列表栏一起出：只剩列表栏的话，返回回到的是一页
    // 与导航栏里「我的」一模一样的页面
    fun popBack() {
        val popped = backStack.removeLastOrNull()
        if (popped in ProfilePanes && backStack.lastOrNull() == Screen.Profile) backStack.removeLastOrNull()
        if (backStack.isEmpty()) backStack.add(Screen.Home)
    }

    fun closeProfile() {
        while (backStack.size > 1 && (backStack.lastOrNull() in ProfilePanes || backStack.lastOrNull() in SettingsSubpages || backStack.lastOrNull() == Screen.Profile)) {
            backStack.removeLastOrNull()
        }
    }

    // 两个详情页互相替换，不叠在一起：从回收站点到设置，返回应当回到「我的」而不是回收站。
    // 读栈的当下，不读组合时的 topScreen：openPage 先 resetToHome 再调这里，topScreen 还是清栈前的那一页，
    // 照它弹栈弹掉的是栈底的 Home，此后「文件」再也回不去（2026-09-28）
    fun openProfilePane(screen: Screen) {
        // 设置的下一级随设置一起换走，否则新开的详情页叠在它上面，返回时又回到这一级
        while (backStack.size > 1 && backStack.lastOrNull() in SettingsSubpages) backStack.removeLastOrNull()
        val top = backStack.lastOrNull()
        if (top == screen) return
        if (top in ProfilePanes && backStack.size > 1) backStack.removeLastOrNull()
        backStack.add(screen)
    }

    // 监听外部传入的磁力链接，自动切到文件页并关闭压栈页
    val pendingMagnet by services.instantMagnetRepository.pendingMagnetFlow.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMagnet) {
        if (pendingMagnet != null) {
            currentTab = MainTab.FILES
            resetToHome()
        }
    }

    val openDriveRequested by services.driveRepository.openDriveRequested.collectAsStateWithLifecycle()
    LaunchedEffect(openDriveRequested) {
        if (openDriveRequested) {
            currentTab = MainTab.FILES
            resetToHome()
            services.driveRepository.consumeOpenDriveRequest()
        }
    }

    // 上传、下载与离线任务提交后切到传输页看进度，同快捷键切页一样收起压栈页。
    // 秒传不来这里：文件当场就在网盘里，网盘页自己定位过去
    fun openTransfers() {
        currentTab = MainTab.TRANSFERS
        resetToHome()
    }

    // 订阅调度器而不是在各上传入口各跳一次：应用外进来的上传在主界面之外确认，
    // 而且要等任务真的排进队列，所选文件读不出来时只该有一条失败提示
    LaunchedEffect(services.uploadManager) {
        services.uploadManager.enqueued.collect { openTransfers() }
    }

    val openTransfersRequested by services.openTransfersRequested.collectAsStateWithLifecycle()
    LaunchedEffect(openTransfersRequested) {
        if (openTransfersRequested) {
            openTransfers()
            services.consumeOpenTransfersRequest()
        }
    }

    // 已下完的本地副本优先：省流量，也不受网络波动影响
    fun playVideo(file: FileStat, playlist: List<FileStat>, startMillis: Long? = null) {
        val localTask = services.downloadManager.tasks.value.values.find {
            it.fileId == file.id && it.status == DownloadStatus.COMPLETED && !it.isSegment
        }
        val localPath = localTask?.destinationPath?.takeIf(localFiles::exists)
        when (videoPlayer) {
            is VideoPlayerHost.InApp -> backStack.add(Screen.VideoPlayer(file.id, file.name, localPath, startMillis))
            is VideoPlayerHost.Detached -> videoPlayer.open(VideoPlayerRequest(file.id, file.name, localPath, playlist, startMillis))
        }
    }

    // 正在向服务端查路径，见 lookUpPath。不为 null 时整个窗口盖着 LocatingOverlay
    var pathLookup by remember { mutableStateOf<Deferred<Result<List<PikoPathBreadcrumb>>>?>(null) }

    /**
     * 向服务端查 [fileId] 所在目录的路径栈。服务端只能沿 parent_id 逐级查，慢的时候要好几秒，
     * 这期间整个窗口盖一层等待、不接输入，否则人在原处做的事会被随后落下的跳转盖掉。
     * 用户取消时返回 null。
     */
    suspend fun lookUpPath(fileId: String): Result<List<PikoPathBreadcrumb>>? {
        val lookup = coroutineScope.async { services.driveRepository.locateFolder(fileId) }
        pathLookup = lookup
        return try {
            lookup.await()
        } catch (e: CancellationException) {
            // 调用方自己被取消时照常抛出，只有查询被取消（遮罩上的取消）才算用户放弃
            currentCoroutineContext().ensureActive()
            null
        } finally {
            lookup.cancel()
            if (pathLookup === lookup) pathLookup = null
        }
    }

    /**
     * 从别的页跳进网盘的 [stack]，[then] 是跳过去之后的一步（标出某一项）。窄窗口里会结束查找重复的先问，
     * 问的是整个动作：取消就留在原来的页面，确认才切页、换栈并做完 [then]，见 PikoDriveRepository.navigateThen。
     * 先设好栈再切页：网盘页重新组合时直接加载栈顶目录。
     */
    fun goToDrive(stack: List<PikoPathBreadcrumb>, then: () -> Unit = {}) = services.driveRepository.navigateThen(stack) {
        services.driveRepository.updateFolderStack(stack)
        then()
        resetToHome()
        currentTab = MainTab.FILES
    }

    fun showInDrive(stack: List<PikoPathBreadcrumb>, fileId: String) = goToDrive(stack) {
        services.driveRepository.requestHighlight(setOf(fileId))
    }

    /**
     * 打开一个库，照开关的用法：人已停在这个库里时再点一下是关掉它，退回打开之前的位置；
     * 没有可退的（启动时就恢复在库里）回到网盘根目录。人在库里的子文件夹时点它回到库本身。
     */
    fun toggleLibrary(library: DriveLibrary) {
        val driveRepo = services.driveRepository
        val stack = driveRepo.folderStackFlow.value
        val showing = onHome && currentTab == MainTab.FILES && stack.singleOrNull()?.id == library.id
        // 先按要去的地方问一声，取消时连切页也不发生，见 goToDrive
        val next = if (showing) driveRepo.historyFlow.value.back.lastOrNull() ?: listOf(PikoDriveRepository.ROOT_BREADCRUMB) else listOf(library.crumb)
        driveRepo.navigateThen(next) {
            resetToHome()
            currentTab = MainTab.FILES
            when {
                !showing -> driveRepo.updateFolderStack(listOf(library.crumb))
                !driveRepo.goBack() -> driveRepo.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
            }
        }
    }

    /**
     * 在网盘里打开条目所在的文件夹并标出它，文件夹也是在上级里标出。传输与我的分享用：手上只有 ID。
     * 找不到（已删除、在回收站里）返回 false，由调用方提示；用户取消了不算找不到。
     */
    suspend fun revealInDrive(fileId: String): Boolean {
        val stack = (lookUpPath(fileId) ?: return true).getOrElse { return false }
        showInDrive(stack, fileId)
        return true
    }

    fun playLocal(fileId: String, fileName: String, localPath: String?) {
        when (videoPlayer) {
            is VideoPlayerHost.InApp -> backStack.add(Screen.VideoPlayer(fileId, fileName, localPath))
            is VideoPlayerHost.Detached -> videoPlayer.open(VideoPlayerRequest(fileId, fileName, localPath))
        }
    }

    // Home 被压栈页盖住时整个离开组合，回来时重建。网盘页经得起：目录内容与滚动位置都记在仓库里，
    // 重建后首帧即是原样。内容区的尺寸记在这里而不是 Home 里，侧栏放不放得下在重建前后一致
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    // 拖着窗口边框时量到的尺寸先存着，松手才算数：侧栏放不放得下决定信息流与面板的形态，拖动中途不来回换
    val resizing = LocalWindowResizing.current
    val latestResizing by rememberUpdatedState(resizing)
    var pendingContentSize by remember { mutableStateOf<IntSize?>(null) }
    LaunchedEffect(resizing) {
        if (!resizing) pendingContentSize?.let { contentSize = it }
        pendingContentSize = null
    }
    val density = LocalDensity.current

    // 信息流：网盘页顶栏上的开关，单独一个状态，不动存下的视图，关掉即回到原来的列表。
    // 宽窗口放得下主区与侧栏时开在右侧侧栏，放不下时盖住整个网盘页；窗口缩放时两种形态随之互换。
    // 桌面端还能弹出到独立窗口，那时应用内两种形态都收起，开关仍是开着的
    val clipFeedSession = services.clipFeedSession
    val preferences = services.preferences
    // 头一帧就要拿到存过的收起状态，否则侧边栏先按展开画出来再收一下
    val initialSidebarCollapsed = remember { runBlocking { preferences.sidebarCollapsedFlow.first() } }
    val sidebarCollapsed by preferences.sidebarCollapsedFlow.collectAsStateWithLifecycle(initialSidebarCollapsed)
    // 同样要头一帧就对：先按不带扩展名排出来再换，名字会整排跳一下
    val initialShowExtensions = remember { runBlocking { preferences.showExtensionsFlow.first() } }
    val showExtensions by preferences.showExtensionsFlow.collectAsStateWithLifecycle(initialShowExtensions)
    val initialPanelPrefs = remember { runBlocking { preferences.clipPanelFlow.first() } }
    val panelPrefs by preferences.clipPanelFlow.collectAsStateWithLifecycle(initialPanelPrefs)
    val contentWidth = with(density) { contentSize.width.toDp() }.takeIf { contentSize != IntSize.Zero }
    // 右栏只在桌面：移动端的信息流一律全屏，平板横握也是，与手机同一套
    val panelFits = contentWidth != null && desktop && sidePanelFits(contentWidth, ClipPanelMinWidth)
    // null 是还没量出内容区宽度。上次开着侧栏退出的，只在这回仍放得下侧栏时照样打开；
    // 放不下就是全屏形态，窄窗口一启动就开始播不是谁想要的
    var feedShownState by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(contentWidth != null) {
        if (feedShownState == null && contentWidth != null) feedShownState = initialPanelPrefs.open && panelFits
    }
    val feedShown = feedShownState == true
    val detachedHost = videoPlayer as? VideoPlayerHost.Detached
    // 同一时刻只能有一个 ClipFeedScreen：每个都自带播放器与预取的流。窗口开着时应用内的形态都收起
    val feedPoppedOut = detachedHost?.isClipFeedOpen?.invoke() == true
    // 从信息流跳去网盘看一个文件时记下的出发点。不为 null 时信息流挂起：队列与看到哪一段都留着，应用内不画它，
    // 网盘里留一个「继续刷」；这期间的浏览是临时的，继续刷就整段丢掉、回到这里，见 resumeFeed
    var feedDetour by remember { mutableStateOf<PikoDriveRepository.DriveLocation?>(null) }
    val feedSuspended = feedShown && feedDetour != null
    // 有标签栏时，从信息流跳出来的浏览开在单独的一个标签里，见 locateFromFeed。「继续刷」只切回原来的标签，
    // 不关它：原来的标签没被碰过，关掉它还原不了什么，只会把刚才在看的丢掉；下次跳出来仍用它，始终只有这一个
    var feedDetourTab by remember { mutableStateOf<Long?>(null) }
    // 头一次 open 完成前会话里是空的，此时组合 ClipFeedScreen 会闪一下「没有可播放的视频」
    var feedOpened by remember { mutableStateOf(false) }
    // 关掉信息流连同它的窗口一起关：开关是它唯一的总开关，不管它此刻在哪一处。
    // 关掉才清空队列；挂起着关掉的，这期间的临时浏览就此留下，成为寻常的浏览
    fun setFeedShown(shown: Boolean) {
        feedShownState = shown
        if (!shown) {
            if (feedPoppedOut) detachedHost?.closeClipFeed?.invoke()
            feedDetour = null
            // 标签留下，成了寻常的标签
            feedDetourTab = null
            feedOpened = false
            clipFeedSession.close()
        }
        coroutineScope.launch { preferences.setClipPanelOpen(shown) }
    }
    val feedOnFilesTab = feedShown && !feedSuspended && !feedPoppedOut && currentTab == MainTab.FILES
    // 右栏同一时刻只放一样：信息流或添加链接，不做标签切换。添加链接挤得走信息流（信息流挂起让位，见下面
    // suspendFeed 那一处），信息流挤不走添加链接（「信息流」入口置灰）：添加链接是有未完成工作的任务，信息流只是浏览。
    // 添加链接收成右下角的浮动卡片后右栏就空出来了，信息流照常打开
    val instantSession = services.instantSession
    val addLinkDocked = panelFits && instantSession.state != null && instantSession.isSheetOpen
    val feedBlockedReason = if (addLinkDocked) "添加链接进行中，关闭后可打开信息流" else null
    val feedInPanel = feedOnFilesTab && panelFits && !addLinkDocked
    val feedFullScreen = feedOnFilesTab && !panelFits
    val sidebarMode = sidebarWindow &&
        (onHome || topScreen == Screen.Profile || topScreen in ProfilePanes || topScreen in SettingsSubpages)
    // 窄窗口里浮起来的那一层展开的侧边栏。只记这一回：它是临时借一下地方，不改存下的收起状态
    var sidebarFloatRequested by remember { mutableStateOf(false) }
    val sidebarFloating = sidebarFloatRequested && sidebarOverlays && sidebarMode
    // 去了别处就收回：点了浮层里的一项，或窗口拉宽到推得开
    LaunchedEffect(currentTab, topScreen, sidebarOverlays, sidebarMode) { sidebarFloatRequested = false }
    fun toggleSidebar() {
        if (sidebarOverlays) {
            sidebarFloatRequested = !sidebarFloating
        } else {
            coroutineScope.launch { preferences.setSidebarCollapsed(!sidebarCollapsed) }
        }
    }

    // 信息流刷的是打开时网盘页所在的文件夹，连同子文件夹
    val folderStack by services.driveRepository.folderStackFlow.collectAsStateWithLifecycle()
    // 浮着的侧边栏里点快速访问或库，换的只是文件夹，不经上面那几个键
    LaunchedEffect(folderStack) { sidebarFloatRequested = false }
    val quickAccess =remember { QuickAccessState(services.driveRepository, services.clientManager, coroutineScope) }
    val pinnedFolders by quickAccess.pinnedFolders.collectAsStateWithLifecycle(emptyList())
    // 快捷键一览，F1 或主修饰键+/
    var shortcutsOpen by remember { mutableStateOf(false) }
    // 只在打开的那一刻取文件夹：开着时进子文件夹不换掉正在刷的这一批
    LaunchedEffect(feedShown) {
        if (!feedShown) return@LaunchedEffect
        clipFeedSession.open(folderStack.ifEmpty { listOf(PikoDriveRepository.ROOT_BREADCRUMB) })
        feedOpened = true
    }
    // 信息流所在的那块地方里，人最后停在哪：离开时挂起，「继续刷」回到这里
    var feedHome by remember { mutableStateOf<PikoDriveRepository.DriveLocation?>(null) }

    /**
     * 信息流挂起，与「在网盘中显示」同一种状态：队列与看到哪一段都留着，应用内不画它，网盘里留一个「继续刷」，
     * 继续刷就回到 [from]。凡是让它离开那一栏或那块地方的都走这里，不再各有各的收起：
     * 离开开始时的文件夹、从信息流跳去网盘看文件、右栏让给添加链接。
     * 独立窗口不挂起，它本来就在旁边，不挡网盘。
     */
    fun suspendFeed(from: PikoDriveRepository.DriveLocation) {
        if (!feedShown || !feedOpened || feedDetour != null || feedPoppedOut) return
        feedDetour = from
    }

    // 添加链接一停进右栏，信息流就让位挂起；添加链接关掉后不自动回来，命令栏的小圆点提示它还在，点了才继续：
    // 右栏里的东西不自己跳回来。也看弹出窗口：窗口收回主窗口的那一刻信息流要回右栏，右栏若被添加链接占着同样挂起
    LaunchedEffect(addLinkDocked, feedPoppedOut, feedOpened) {
        if (addLinkDocked) suspendFeed(services.driveRepository.currentLocation())
    }

    // 进它的子文件夹不算离开，路径栈里仍有它。离开时挂起而不是收起：人多半只是去别处看一眼
    LaunchedEffect(folderStack) {
        val feedRoot = clipFeedSession.root ?: return@LaunchedEffect
        if (!feedShown || feedDetour != null) return@LaunchedEffect
        if (folderStack.any { it.id == feedRoot.id }) {
            feedHome = services.driveRepository.currentLocation()
        } else {
            suspendFeed(feedHome ?: services.driveRepository.currentLocation())
        }
    }

    fun playFromFeed(file: FileStat, startMillis: Long) = playVideo(file, listOf(file), startMillis)

    /**
     * 刷到一段想细看，跳去网盘里它所在的地方：信息流挂起，左边成了一段临时浏览，爱怎么走怎么走。
     * 出发点只记头一回的：挂起期间从独立窗口再定位一次，继续刷回的仍是最初刷的地方。
     * 独立窗口不挂起，它本来就在旁边，不挡网盘。
     *
     * 路径由信息流遍历时记下的上级当场拼出，不问服务端；拼不出（存盘恢复的段还没重新列到）才去查。
     * 有标签栏时临时浏览开在新标签里（[feedDetourTab]），挂起期间再定位就在那个标签里换位置。
     */
    fun locateFromFeed(file: FileStat) {
        val driveRepo = services.driveRepository
        val origin = driveRepo.currentLocation()
        coroutineScope.launch {
            val stack = clipFeedSession.pathTo(file.parentId)
                ?: lookUpPath(file.id)?.getOrNull()
                ?: return@launch
            suspendFeed(origin)
            val detourTab = feedDetourTab?.takeIf { id -> driveRepo.tabsFlow.value.any { it.id == id } }
            when {
                detourTab != null -> driveRepo.switchTab(detourTab)
                feedDetour != null && desktop -> feedDetourTab = driveRepo.openTab(stack)
            }
            showInDrive(stack, file.id)
        }
    }

    fun popOutFeed() {
        detachedHost?.openClipFeed(
            ClipFeedLinks(
                playFull = ::playFromFeed,
                locate = ::locateFromFeed,
                dock = { detachedHost.closeClipFeed() },
                close = { setFeedShown(false) },
            ),
        )
    }

    /** 继续刷：丢掉临时浏览，连同后退与前进回到出发的地方，信息流从挂起前的那一段接着放。 */
    fun resumeFeed() {
        val detour = feedDetour ?: return
        services.driveRepository.returnTo(detour)
        feedDetour = null
        resetToHome()
        currentTab = MainTab.FILES
        if (feedPoppedOut) popOutFeed()
    }

    val latestSetFeedShown by rememberUpdatedState(::setFeedShown)
    SideEffect {
        taskSlot.exclusive = !desktop
        taskSlot.occupants = listOf(
            TaskSlot.Occupant(TaskSlot.Task.DUPLICATES, "查找重复", "扫描结果不会保存。", { services.duplicateSession.state != null }, services.duplicateSession::end),
            TaskSlot.Occupant(
                TaskSlot.Task.CANONICAL_NAMES, "按番号规范命名", "未应用的改名建议将被丢弃。",
                { services.canonicalNamingSession.state != null }, services.canonicalNamingSession::end,
            ),
            TaskSlot.Occupant(TaskSlot.Task.ADD_LINK, "添加链接", "尚未保存的链接将被丢弃。", { services.instantSession.state != null }, services.instantSession::end),
            TaskSlot.Occupant(TaskSlot.Task.FEED, "信息流", "再次打开时将从头开始。", { feedShownState == true && feedDetour != null }) { latestSetFeedShown(false) },
        )
    }
    // 窄窗口里人往查找重复或规范命名所在的那棵树外走（返回、面包屑、从别处跳进网盘、快速访问、「我的」里的库、命令面板）之前先确认，
    // 在仓库换栈之前拦下，见 TaskSlot.allowsLeaving。切到别的底部标签不改路径栈，不算离开
    DisposableEffect(taskSlot) {
        val driveRepo = services.driveRepository
        driveRepo.leaveGuard = PikoDriveRepository.LeaveGuard(taskSlot::allowsLeaving)
        // 有标签栏时会话标签由会话占着，往别处走另开标签，见 PikoDriveRepository.sessionTabHeld
        driveRepo.sessionTabHeld = { place ->
            !taskSlot.exclusive && when (place) {
                DriveLibrary.DUPLICATES -> services.duplicateSession.state != null
                DriveLibrary.CANONICAL_NAMES -> services.canonicalNamingSession.state != null
                else -> false
            }
        }
        onDispose {
            driveRepo.leaveGuard = null
            driveRepo.sessionTabHeld = { false }
        }
    }
    // 不经守卫的换栈（换账号、切标签、信息流的继续刷）离开了那棵树，静默结束。放在这里而不是网盘页：
    // 切走时网盘页不在组合里，路径栈照样会被改
    LaunchedEffect(taskSlot) {
        snapshotFlow { Triple(taskSlot.activePlaceTask(), folderStack, taskSlot.exclusive) }
            .collect { (task, stack, exclusive) ->
                // 宽窗口里查找重复与规范命名在标签上，关标签即结束，不看位置。结束时不清锚点：换起点是先结束旧的、
                // 再记下新锚点，这里晚一步看到「结束」会把新记的清掉；窄窗口里开始的入口都会重记
                if (!exclusive) taskSlot.placeTaskAnchor = null
                if (task == null || !exclusive) return@collect
                val anchor = taskSlot.placeTaskAnchor
                when {
                    anchor == null -> taskSlot.placeTaskAnchor = stack
                    stack.size < anchor.size || stack.take(anchor.size).map { it.id } != anchor.map { it.id } -> task.end()
                }
            }
    }

    @Composable
    fun FeedContent(compact: Boolean, visible: Boolean) {
        when {
            // 形态互换与收起的动画期间旧的一处还在组合，只让眼下该播的那一处播；弹出到窗口的那一刻也是，
            // 两个播放器不同时在放。压栈页进来的转场期间 Home 仍在组合里，也停，否则 Android 上看完整的播放器
            // 底下还放着一段
            !visible || !feedOpened || !onHome -> Box(Modifier.fillMaxSize().background(Color.Black))
            else -> ClipFeedScreen(
                onBackClick = { setFeedShown(false) },
                onPlayFull = ::playFromFeed,
                onLocate = ::locateFromFeed,
                compact = compact,
                onPopOut = detachedHost?.let { ::popOutFeed },
            )
        }
    }

    // 右栏眼下放的是哪一样。收起的动画期间两者都不该在了，仍画最后放着的那一样，不让它在收窄途中换成别的
    val columnContent = when {
        addLinkDocked -> SideColumnContent.AddLink
        // 不看当前页：切走时网盘页随淡出一起消失，侧栏不必先收起
        feedShown && !feedSuspended && panelFits && !feedPoppedOut -> SideColumnContent.Feed
        else -> null
    }
    var lastColumnContent by remember { mutableStateOf(SideColumnContent.Feed) }
    SideEffect { if (columnContent != null) lastColumnContent = columnContent }
    val columnShows = columnContent ?: lastColumnContent

    val feedFrame: @Composable (@Composable () -> Unit) -> Unit = { drive ->
        val addLink = columnShows == SideColumnContent.AddLink
        SidePanelLayout(
            open = columnContent != null,
            // 两者共用一个宽度：同一栏，拖宽一次两样都生效
            savedWidthDp = panelPrefs.widthDp,
            title = if (addLink) "添加链接" else "信息流",
            closeDescription = if (addLink) "收起添加链接" else "关闭信息流",
            onClose = { if (addLink) instantSession.collapse() else setFeedShown(false) },
            onWidthChange = { coroutineScope.launch { preferences.setClipPanelWidth(it) } },
            defaultWidth = ClipPanelDefaultWidth,
            minWidth = ClipPanelMinWidth,
            ready = feedShownState != null,
            // 信息流整张卡是黑底的竖屏画面，范围、静音、弹出与关闭都在它自己的顶栏上；添加链接用栏名那一行
            showHeader = addLink,
            main = {
                Box(Modifier.fillMaxSize()) {
                    drive()
                    FeedResumeBar(
                        // 桌面上挂起的信息流由命令栏「信息流」按钮上的小圆点提示，点它继续，不再另挂一条
                        visible = feedSuspended && !desktop,
                        folderName = clipFeedSession.root?.name,
                        onResume = ::resumeFeed,
                        onClose = { setFeedShown(false) },
                        // 浮在列表上，与网盘页的 FAB 同一行、在它左边：列表末尾本来就为那一行留了空（DriveScreen 的
                        // FabClearance），条不多盖住文件。曾在底下另让出一条，没并进外框时那一条露出窗口的黑底；
                        // 叠在 FAB 上方则又要列表多留一段
                        modifier = Modifier.align(Alignment.BottomStart).padding(end = FeedResumeBarFabWidth),
                    )
                }
            },
            panel = {
                when (columnShows) {
                    SideColumnContent.AddLink -> EscClosesWhenFocused(onClose = instantSession::collapse) {
                        instantSession.state?.let { state ->
                            DockedAddLink(state, onPreview = { fileId, fileName -> playVideo(FileStat(id = fileId, name = fileName), emptyList()) })
                        }
                    }
                    // 信息流不拦 Esc：它一打开就取得焦点，拦下的话 Esc 关掉信息流、清空刷了半天的队列，人本想回上一级
                    SideColumnContent.Feed -> FeedContent(compact = true, visible = feedInPanel)
                }
            },
        )
    }

    // 再点一次当前页回到列表顶部，M3 导航栏的明文要求。每页一个计数器：共用一个的话，在网盘页
    // 连点几下再切到传输页，传输页看到的是变过的计数，会跟着滚一次它自己没被点过的
    var filesScrollToTop by remember { mutableIntStateOf(0) }
    var transfersScrollToTop by remember { mutableIntStateOf(0) }
    fun onTabClick(tab: MainTab) {
        if (tab == currentTab) {
            when (tab) {
                MainTab.FILES -> filesScrollToTop++
                MainTab.TRANSFERS -> transfersScrollToTop++
                MainTab.SETTINGS -> Unit
            }
        }
        currentTab = tab
    }

    // 快捷键的兜底落点：网盘页有自己的焦点目标，其余页面没有可聚焦的内容时，
    // 按键要有个地方落，Ctrl+数字切换页面才能生效
    val shortcutFocus = remember { FocusRequester() }
    LaunchedEffect(currentTab, onHome) {
        if (currentTab != MainTab.FILES || !onHome) runCatching { shortcutFocus.requestFocus() }
    }

    // 桌面没有「我的」这一页：设置与我的分享压在当前页上，出栈即回到原来那一页，侧边栏在打开期间只亮这一项。
    // 移动端它们是「我的」的下一级，先切到「我的」，返回才回得去
    fun openPage(screen: Screen) {
        if (!desktop) currentTab = MainTab.SETTINGS
        resetToHome()
        openProfilePane(screen)
    }
    // 旧版在宽窗口里也会停在「我的」，恢复出来的就回到文件
    LaunchedEffect(desktop) { if (desktop && currentTab == MainTab.SETTINGS) currentTab = MainTab.FILES }

    // 应用里的各个去处。命令面板与网盘页的地址栏共用这一份：怎么打开它们（压不压一栏「我的」、切不切页）只有这里知道
    fun tabDestinations(): List<PaletteItem> {
        val label = shortcutModifier::label
        return listOfNotNull(
            PaletteItem("文件", Icons.Outlined.Folder, "前往", detail = label("1"), keywords = "files drive") { currentTab = MainTab.FILES; resetToHome() },
            PaletteItem("传输", Icons.Outlined.SyncAlt, "前往", detail = label("2"), keywords = "transfers downloads uploads") { openTransfers() },
            if (desktop) null else PaletteItem("我的", Icons.Outlined.Person, "前往", detail = label("3"), keywords = "profile me") { currentTab = MainTab.SETTINGS; resetToHome() },
        )
    }

    // 库从这里打开时不当开关用：从命令面板与地址栏去一个地方，人已在那里也不该被带走
    fun openLibrary(library: DriveLibrary, then: () -> Unit = {}) = goToDrive(listOf(library.crumb), then)

    // 从「我的」打开的库，记下是哪一个：从它退出时回到「我的」，而不是留在网盘里。
    // 库只是网盘路径栈的第一级，退出时网盘照旧回到打开之前的位置，要补的只有切回哪一页。
    // 经别的路离开这个库（地址栏、在网盘中显示、侧边栏）或切去别的页，就不再是从「我的」来的那一趟，随即忘掉
    var libraryFromProfile by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(folderStack) {
        if (folderStack.firstOrNull()?.id != libraryFromProfile) libraryFromProfile = null
    }
    LaunchedEffect(currentTab) {
        if (currentTab != MainTab.FILES) libraryFromProfile = null
    }

    // 取消时不记：人还在「我的」里
    fun openLibraryFromProfile(library: DriveLibrary) = openLibrary(library) { libraryFromProfile = library.id }

    fun onLibraryLeft() {
        if (libraryFromProfile == null) return
        libraryFromProfile = null
        currentTab = MainTab.SETTINGS
    }

    fun libraryDestination(library: DriveLibrary, keywords: String) =
        PaletteItem(library.title, library.icon, "页面", keywords = keywords) { openLibrary(library) }

    fun pageDestinations(): List<PaletteItem> = listOf(
        libraryDestination(DriveLibrary.STARRED, "starred"),
        libraryDestination(DriveLibrary.RECENT, "recent added uploads 新增"),
        libraryDestination(DriveLibrary.HISTORY, "history"),
        PaletteItem("我的分享", Icons.Outlined.Share, "页面", keywords = "shares") { openPage(Screen.MyShares) },
        libraryDestination(DriveLibrary.TRASH, "trash bin"),
        PaletteItem("设置", Icons.Outlined.Settings, "页面", keywords = "settings preferences") { openPage(Screen.Settings) },
    )

    @Composable
    fun HomeContent() {
        val motion = LocalPikoMotion.current
        val mainContent: @Composable () -> Unit = {
            // M3 的 top level 模式：旧页淡出走完再淡入新页，见 PikoMotion.topLevel。
            // 原来是 when 直接换子树，跳切被规范单列为要避免的做法：读者得不到任何线索说明换了页
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { motion.topLevel() },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { if (latestResizing) pendingContentSize = it else contentSize = it },
                label = "mainTab",
            ) { tab ->
                when (tab) {
                    MainTab.FILES -> {
                        FilesScreen(
                            onNavigateToVideoPlayer = { file, playlist -> playVideo(file, playlist) },
                            scrollToTopRequests = filesScrollToTop,
                            onOpenTransfers = ::openTransfers,
                            // 挂起时开关显示为关着，按下即继续刷
                            feedShown = feedShown && !feedSuspended,
                            onFeedShownChange = { shown ->
                                when {
                                    shown && feedSuspended -> resumeFeed()
                                    shown -> taskSlot.claim("打开信息流", "打开信息流", TaskSlot.Task.FEED) { setFeedShown(true) }
                                    else -> setFeedShown(false)
                                }
                            },
                            feedStashed = feedSuspended,
                            feedBlockedReason = feedBlockedReason,
                            feedTabId = feedDetourTab,
                            feedFrame = feedFrame,
                            // 「文件」就是网盘页自己，地址栏里不列
                            addressDestinations = tabDestinations().drop(1) + pageDestinations(),
                            onLibraryLeft = ::onLibraryLeft,
                        )
                    }
                    MainTab.TRANSFERS -> {
                        TransfersScreen(
                            scrollToTopRequests = transfersScrollToTop,
                            onNavigateToInstant = {
                                currentTab = MainTab.FILES
                            },
                            onOpenCloudFile = { fileId, _ -> revealInDrive(fileId) },
                            onNavigateToVideoPlayer = ::playLocal,
                        )
                    }
                    MainTab.SETTINGS -> {
                        ProfileScreen(
                            onLogout = onLogout,
                            onOpenPane = ::openProfilePane,
                            selectedPane = null,
                            onOpenLibrary = ::openLibraryFromProfile,
                        )
                    }
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 侧边栏模式下导航栏不画，侧边栏在返回栈外面，见 sidebarMode
            val sidebar = sidebarMode
            // 若当前不在文件主页，按下返回键优先回到文件页
            BackHandler(enabled = currentTab != MainTab.FILES && !feedFullScreen) {
                currentTab = MainTab.FILES
            }

            // 移动端窄时是 64dp 的 ShortNavigationBar，宽了（横握的手机、平板）换成收起的 Rail，照 M3 的断点。
            // 写死而不交给库挑：库还看窗口高度，横握的手机宽够了、高度不够，给的是一条占地方的横向底栏
            val navigationSuiteType = when {
                sidebar -> NavigationSuiteType.None
                widthClass == WidthClass.Compact -> NavigationSuiteType.ShortNavigationBarCompact
                else -> NavigationSuiteType.WideNavigationRailCollapsed
            }
            NavigationSuiteScaffold(
                navigationItems = {
                    // 侧边栏的「传输」按钮上写速度或项数；底部导航栏放不下，只挂一个项数的徽标。
                    // 解压与归档的进度只在传输页，徽标就是从别处找过去的线索
                    val activity = if (sidebar) null else rememberTransferActivity(pollCloud = false)
                    MainTab.entries.forEach { tab ->
                        val selected = currentTab == tab
                        // 照 M3：进了这一页就不再挂徽标
                        val badgeCount = activity?.count?.takeIf { tab == MainTab.TRANSFERS && !selected && it > 0 }
                        NavigationSuiteItem(
                            selected = selected,
                            onClick = { onTabClick(tab) },
                            navigationSuiteType = navigationSuiteType,
                            // 图标不写 contentDescription：每项都有文字标签，图标再给一次会被读屏念两遍
                            icon = { Icon(imageVector = tab.icon(selected), contentDescription = null) },
                            // M3 要求选中项的标签加粗，而导航项的样式只有一档字重，不分选中态
                            label = { Text(tab.title, fontWeight = if (selected) FontWeight.Bold else null) },
                            badge = badgeCount?.let { count -> { Badge { Text(if (count > 99) "99+" else "$count") } } },
                        )
                    }
                },
                navigationSuiteType = navigationSuiteType,
                // 侧栏的三格居中：平板横握时手在两侧中部，贴顶的话要伸到最远处
                navigationItemVerticalArrangement = Arrangement.Center,
                content = mainContent,
            )

            // 全屏形态的信息流：盖住网盘页连同导航栏。不进返回栈，形态由窗口宽度随时推出来，窗口拉宽即换成侧栏。
            // 压栈页（看完整的播放器）盖住 Home 时它随 Home 一起离开组合，两个播放器不同时在放；
            // 退出播放器后重新进入组合，首帧即可见，也不播进场动画，接着看刚才那一段
            BackHandler(enabled = feedFullScreen) { setFeedShown(false) }
            AnimatedVisibility(
                visible = feedFullScreen,
                enter = motion.overlayEnter(),
                exit = motion.overlayExit(),
            ) {
                // 横握手机刷信息流时收起状态栏与导航条，只按窗口形状决定，不锁方向：竖着刷照旧留着系统栏
                if (!desktop && heightCompact && feedFullScreen) LocalPikoPlatform.current.HideSystemBars()
                FeedContent(compact = false, visible = feedFullScreen)
            }
        }
    }

    // 侧边栏在时详情页不给返回：出口就是侧边栏
    val paneBack: (() -> Unit)? = if (sidebarWindow) null else ::popBack
    val selectedPane = if (topScreen in SettingsSubpages) Screen.Settings else topScreen?.takeIf { it in ProfilePanes }

    fun openFolderStack(stack: List<PikoPathBreadcrumb>) = goToDrive(stack)

    // 命令面板的内容。没输入时按这里的顺序列出前面几项：最近去过的文件夹在最前
    fun paletteItems(
        recent: List<List<PikoPathBreadcrumb>>,
        pinned: List<PikoPathBreadcrumb>,
        subfolders: List<FileStat>,
        contributed: List<PaletteItem>,
    ): List<PaletteItem> = buildList {
        val here = folderStack.lastOrNull()?.id
        fun path(stack: List<PikoPathBreadcrumb>) = stack.joinToString(" › ") { it.name }
        recent.filter { it.last().id != here }.forEach { stack ->
            add(PaletteItem(stack.last().name, Icons.Outlined.History, "最近", detail = path(stack)) { openFolderStack(stack) })
        }
        val label = shortcutModifier::label
        addAll(tabDestinations())
        pinned.forEach { folder ->
            add(PaletteItem(folder.name, Icons.Outlined.PushPin, "快速访问") {
                quickAccess.locate(folder) { stack -> goToDrive(stack) }
            })
        }
        folderStack.takeIf { it.isNotEmpty() }?.let { stack ->
            subfolders.forEach { folder ->
                val target = stack + PikoPathBreadcrumb(folder.id, folder.name)
                add(PaletteItem(folder.name, Icons.Outlined.Folder, "当前文件夹", detail = path(target)) { openFolderStack(target) })
            }
        }
        addAll(contributed)
        add(PaletteItem("添加链接", Icons.Outlined.Bolt, "操作", keywords = "magnet link offline 磁力 离线") {
            taskSlot.claim("添加链接", "添加链接", TaskSlot.Task.ADD_LINK) {
                currentTab = MainTab.FILES
                resetToHome()
                services.instantSession.run { if (state != null) reopen() else start() }
            }
        })
        add(PaletteItem("撤销", Icons.AutoMirrored.Outlined.Undo, "操作", detail = label("Z"), keywords = "undo") { services.driveRepository.changes.undoLast() })
        if (feedSuspended) {
            add(PaletteItem("继续刷信息流", Icons.Outlined.SwipeVertical, "操作", keywords = "feed clips resume", disabledReason = feedBlockedReason) { resumeFeed() })
        }
        // 信息流刷的是网盘里的一个文件夹，库与压缩包都不是
        if (feedShown || isDriveFolderId(folderStack.lastOrNull()?.id.orEmpty())) {
            add(PaletteItem(
                if (feedShown) "关闭信息流" else "打开信息流", Icons.Outlined.SwipeVertical, "操作", keywords = "feed clips",
                disabledReason = feedBlockedReason.takeIf { !feedShown },
            ) {
                if (feedShown) {
                    currentTab = MainTab.FILES
                    resetToHome()
                    setFeedShown(false)
                } else {
                    taskSlot.claim("打开信息流", "打开信息流", TaskSlot.Task.FEED) {
                        currentTab = MainTab.FILES
                        resetToHome()
                        setFeedShown(true)
                    }
                }
            })
        }
        add(PaletteItem("快捷键一览", Icons.Outlined.Keyboard, "操作", detail = "F1", keywords = "shortcuts keyboard help 帮助") { shortcutsOpen = true })
        add(PaletteItem("立即同步设置", Icons.Outlined.CloudSync, "操作", keywords = "sync settings") { coroutineScope.launch { services.settingsSync.syncNow() } })
        ThemeMode.entries.forEach { mode ->
            add(PaletteItem("主题：${mode.label}", Icons.Outlined.Palette, "操作", keywords = "theme ${mode.name.lowercase()}") {
                coroutineScope.launch { preferences.setThemeMode(mode.name) }
            })
        }
        addAll(pageDestinations())
    }

    // 应用内拖放网盘条目：网盘页拖出，侧边栏、路径栏与文件夹接住，见 FileDragState
    val fileDrag = remember { FileDragState() }
    // 命令面板（主修饰键+K）：跳到文件夹或执行命令，各页经 ContributePaletteItems 往里放自己的命令
    val palette = remember { PaletteRegistry() }
    var paletteOpen by remember { mutableStateOf(false) }
    val focusFallback = remember { FocusFallback(shortcutFocus) }
    CompositionLocalProvider(
        LocalFileDrag provides fileDrag,
        LocalPaletteRegistry provides palette,
        LocalFocusFallback provides focusFallback,
        LocalShowExtensions provides showExtensions,
        LocalTaskSlot provides taskSlot,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .trackInputModality()
                .fileDragHost(fileDrag)
                .focusFallbackRoot(focusFallback)
                .focusRequester(shortcutFocus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.F1 || (shortcutModifier.isPressed(event) && event.key == Key.Slash))) {
                        shortcutsOpen = true
                        return@onKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown || !shortcutModifier.isPressed(event)) return@onKeyEvent false
                    if (event.key == Key.K) {
                        paletteOpen = true
                        return@onKeyEvent true
                    }
                    // 桌面应用打开设置的惯例（VS Code、浏览器、mac 上的 ⌘,）
                    if (event.key == Key.Comma) {
                        openPage(Screen.Settings)
                        return@onKeyEvent true
                    }
                    // 收起、展开侧边栏，与 VS Code 同一个键
                    if (event.key == Key.B && sidebarMode) {
                        toggleSidebar()
                        return@onKeyEvent true
                    }
                    val tab = when (event.key) {
                        Key.One -> MainTab.FILES
                        Key.Two -> MainTab.TRANSFERS
                        Key.Three -> if (desktop) return@onKeyEvent false else MainTab.SETTINGS
                        else -> return@onKeyEvent false
                    }
                    currentTab = tab
                    resetToHome()
                    true
                },
        ) {
            val frameModifier = if (sidebarMode) Modifier.background(MaterialTheme.colorScheme.frame) else Modifier
            // 主界面一律接管标题栏，手机宽度的桌面窗口也是：各页顶栏贴着右上角时画窗口按钮、空白处能拖。
            // 只有外框时接管的话，窄窗口顶上叠着系统的一条标题栏，再下面才是页面的顶栏
            LocalWindowCaption.current?.Host()
            // 侧边栏不能拖宽，只有展开与收起两档：拖宽的话，宽了挤内容，窄了文件夹名只剩几个字，要的其实是让出地方，
            // 那就整个收成只剩图标的窄轨
            val sidebarWidth by animateDpAsState(
                if (sidebarCollapsed) SidebarRailWidth else SidebarWidth,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "sidebarWidth",
            )
            @Composable
            fun Sidebar(width: Dp, collapsed: Boolean) {
                MainSidebar(
                    width = width,
                    currentTab = currentTab,
                    onTabClick = { tab ->
                        resetToHome()
                        onTabClick(tab)
                    },
                    quickAccess = quickAccess,
                    pinned = pinnedFolders,
                    folderStack = folderStack,
                    onOpenQuickAccess = { stack -> goToDrive(stack) },
                    selectedPage = selectedPane,
                    onOpenPage = ::openPage,
                    onToggleLibrary = ::toggleLibrary,
                    collapsed = collapsed,
                    onToggleCollapsed = ::toggleSidebar,
                )
            }
            BackHandler(enabled = sidebarFloating) { sidebarFloatRequested = false }
            CompositionLocalProvider(LocalFramed provides sidebarMode) { Box(Modifier.fillMaxSize()) { Row(Modifier.fillMaxSize().then(frameModifier)) {
                if (sidebarMode) {
                    // 侧边栏是一块岛，与页面同色，浮在外框色上，见 IslandGap
                    Box(
                        Modifier
                            .padding(start = IslandGap, top = IslandGap, bottom = IslandGap)
                            .clip(FrameCardShape)
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        // 窄窗口里这一条恒为窄轨，展开的那一份浮在上面，见下
                        if (sidebarOverlays) Sidebar(SidebarRailWidth, collapsed = true) else Sidebar(sidebarWidth, sidebarCollapsed)
                    }
                    Spacer(Modifier.width(IslandGap))
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // 有外框时各页嵌成一张卡片。页面自己铺底色，这里只裁出形状；网盘页的页眉取的也是外框色，
                    // 所以在网盘页上看不出卡片的上沿，页眉与侧边栏、标题栏连成一片
                    val cardModifier = if (sidebarMode) Modifier.clip(FrameContentShape) else Modifier
                    Box(Modifier.weight(1f).fillMaxWidth().then(cardModifier)) {
                        val motion = LocalPikoMotion.current
                        NavDisplay(
                            backStack = backStack,
                            onBack = ::popBack,
                            // 压栈与返回，见 PikoMotion.forward
                            transitionSpec = { motion.forward() },
                            popTransitionSpec = { motion.backward() },
                            predictivePopTransitionSpec = { _ -> motion.backward() },
                            entryProvider = entryProvider {
                                entry<Screen.Home> { HomeContent() }
                                // 原来的两栏布局才压这一页，只为恢复旧版存下的返回栈而留着
                                entry<Screen.Profile> {
                                    ProfileScreen(
                                        onLogout = onLogout,
                                        onOpenPane = ::openProfilePane,
                                        selectedPane = selectedPane,
                                        onOpenLibrary = ::openLibraryFromProfile,
                                        // 侧边栏在时它的「我的」就是出口，列表栏不再给返回
                                        onBackClick = if (sidebarMode) null else ::closeProfile,
                                    )
                                }
                                entry<Screen.MyShares> { MySharesScreen(onBackClick = paneBack, onLocate = ::revealInDrive) }
                                entry<Screen.Settings> {
                                    SettingsScreen(
                                        onBackClick = paneBack,
                                        onOpenWebDav = { backStack.add(Screen.WebDav) },
                                        // 有侧边栏时没有「我的」页，账号、退出登录与关于放在设置里
                                        account = if (sidebarWindow) ({ AccountSettings(onLogout) }) else null,
                                        showAbout = sidebarWindow,
                                    )
                                }
                                // 有侧边栏时也给返回：详情页的出口是侧边栏，这一页的出口是它下面的设置
                                entry<Screen.WebDav> { WebDavScreen(onBackClick = ::popBack) }
                                entry<Screen.VideoPlayer> { screen ->
                                    (videoPlayer as? VideoPlayerHost.InApp)?.content?.invoke(screen, ::popBack)
                                }
                            },
                        )
                    }
                }
            }
                // 窄窗口里展开的侧边栏：浮在内容上、压一层遮罩，照 M3 的模态抽屉，点遮罩、返回或去了别处都收回
                if (sidebarMode && sidebarOverlays) {
                    val scrimAlpha by animateFloatAsState(
                        if (sidebarFloating) SidebarScrimAlpha else 0f,
                        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        label = "sidebarScrim",
                    )
                    if (scrimAlpha > 0f) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                                .clickable(interactionSource = null, indication = null, enabled = sidebarFloating) {
                                    sidebarFloatRequested = false
                                },
                        )
                    }
                    AnimatedVisibility(
                        visible = sidebarFloating,
                        enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { -it },
                        exit = slideOutHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { -it },
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.frame,
                            shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                            shadowElevation = 6.dp,
                        ) {
                            Sidebar(SidebarWidth, collapsed = false)
                        }
                    }
                }
                // 后台任务的浮动卡片只在桌面。移动端解压与归档的进度在传输页，查重与添加链接在网盘页底部的 sheet 里，一次一件（TaskSlot）
                if (desktop) {
                    FloatingTasks(
                        archive = services.archiveExtractSession,
                        vault = services.folderVaultSession,
                        instant = services.instantSession,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )
                    // 窗口窄得放不下右栏时，添加链接退回浮动的侧边面板。只在网盘页上：入口都先切过去，与右栏一致
                    val instantState = instantSession.state
                    if (instantState != null && instantSession.isSheetOpen && !addLinkDocked && currentTab == MainTab.FILES && onHome) {
                        FloatingAddLinkSheet(
                            session = instantSession,
                            state = instantState,
                            onPreview = { fileId, fileName -> playVideo(FileStat(id = fileId, name = fileName), emptyList()) },
                        )
                    }
                }
            } }
            // 拖动网盘条目时指针旁的说明，盖在一切之上
            FileDragOverlay(fileDrag)
            if (shortcutsOpen) ShortcutsDialog(shortcutModifier, onDismiss = { shortcutsOpen = false })
            ClaimDialog(taskSlot)
            // 窗口缩到没有侧边栏时不画：那时设置回到「我的」里，开着的面板随之收起
            if (paletteOpen) {
                val recent by services.driveRepository.recentFoldersFlow.collectAsStateWithLifecycle()
                CommandPalette(
                    items = paletteItems(
                        recent = recent,
                        pinned = pinnedFolders,
                        subfolders = folderStack.lastOrNull()
                            ?.let { here ->
                                services.driveRepository.cachedFiles(here.id, PikoFileSortOrder.NAME_ASC)
                                    ?.filter { it.isFolder && !isPikoInternalFolder(it, here.id) }
                            }
                            .orEmpty(),
                        contributed = palette.items(),
                    ),
                    onDismiss = { paletteOpen = false },
                )
            }
            pathLookup?.let { lookup -> LocatingOverlay(onCancel = { lookup.cancel() }) }
        }
    }
}

/** 网盘页右栏放着的那一样，同一时刻只有一样，见 addLinkDocked 那一处。 */
private enum class SideColumnContent { Feed, AddLink }

/**
 * 焦点在右栏的添加链接里时 Esc 把它收起。只看焦点：焦点在列表里时 Esc 照旧归网盘页（退出多选、关属性卡片、回上一级），
 * 目录图与浮动卡片各管各的。
 *
 * BackHandler 写在内容之前：后登记的先收到，添加链接里展开的一行（InstantBatchSection）有自己的返回，要先于这里。
 */
@Composable
private fun EscClosesWhenFocused(onClose: () -> Unit, content: @Composable () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    BackHandler(enabled = hasFocus, onBack = onClose)
    Box(Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }) { content() }
}

/** 侧栏的宽度下限：竖排的片段控件与横屏画面在这个宽度里还放得开。 */
private val ClipPanelMinWidth = 360.dp
private val ClipPanelDefaultWidth = 420.dp

// 「继续刷」条右边让出网盘页的 FAB：56dp 宽加它离屏幕边的 16dp，条自己的 16dp 外边距就是两者的间隔
private val FeedResumeBarFabWidth = 56.dp + 16.dp

/** 「我的」的详情页。它们互相替换，不叠在一起。 */
private val ProfilePanes = setOf<NavKey?>(Screen.MyShares, Screen.Settings)

/** 设置的下一级，压在设置上面。侧边栏照旧在，亮着的仍是设置。 */
private val SettingsSubpages = setOf<NavKey?>(Screen.WebDav)


private fun MainTab.icon(selected: Boolean) = when (this) {
    MainTab.FILES -> if (selected) Icons.Filled.Folder else Icons.Outlined.Folder
    // SyncAlt 的实心与描边长得一样，选中看不出变化；这一款描边是空心的圆，选中时填实
    MainTab.TRANSFERS -> if (selected) Icons.Filled.SwapVerticalCircle else Icons.Outlined.SwapVerticalCircle
    MainTab.SETTINGS -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
}

/** 浮起的侧边栏下面那层遮罩，M3 模态抽屉的 32%。 */
private const val SidebarScrimAlpha = 0.32f

private val SidebarFadeLength = 24.dp

/** 还能往哪边滚，那一边的边缘渐隐。挂在 verticalScroll 之前，画在视口上而不是随内容滚走。 */
private fun Modifier.scrollFadeEdges(state: ScrollState, length: Dp): Modifier = this
    // 渐隐是用 DstIn 擦掉已画的内容，要在单独的图层里擦，否则连底下侧边栏岛的底色一起擦掉
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = length.toPx().coerceAtMost(size.height / 2)
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = fade),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fade, endY = size.height),
                topLeft = Offset(0f, size.height - fade),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun MainSidebar(
    width: Dp,
    currentTab: MainTab,
    onTabClick: (MainTab) -> Unit,
    quickAccess: QuickAccessState,
    pinned: List<PikoPathBreadcrumb>,
    folderStack: List<PikoPathBreadcrumb>,
    onOpenQuickAccess: (List<PikoPathBreadcrumb>) -> Unit,
    /** 眼前打开的星标、回收站这类页，对应的一项亮起；在网盘或传输页时为 null。 */
    selectedPage: Screen?,
    onOpenPage: (Screen) -> Unit,
    onToggleLibrary: (DriveLibrary) -> Unit,
    /** 收成只剩图标的窄轨，见 LocalSidebarCollapsed。 */
    collapsed: Boolean,
    /** 收起与展开的开关。为 null 时不能展开（横握的手机），省掉开关所在的那一行，把高度留给各项。 */
    onToggleCollapsed: (() -> Unit)?,
) {
    // 上面随内容多少滚动，左下角的账号与设置钉在底部，照桌面应用的通行做法（VS Code、Discord）
    CompositionLocalProvider(LocalSidebarCollapsed provides collapsed) { Column(modifier = Modifier.width(width).fillMaxHeight()) {
        // 与网盘页地址栏那一行同高，图标与后退按钮落在同一条水平线上。标题栏并进内容时，拖动窗口主要靠这一行。
        // 收起与展开的开关在这一行：展开时在 Piko 字样右端，收起时只剩它
        if (onToggleCollapsed != null) Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
        ) {
            // 图标与名字只在标题栏并进内容时画：单独的标题栏上已经画着图标与标题
            if (!collapsed) {
                if (LocalWindowCaption.current != null) {
                    PikoBrand(Modifier.weight(1f).fillMaxHeight().windowDragArea().padding(start = 24.dp))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            TooltipIconButton(
                icon = if (collapsed) Icons.Outlined.Menu else Icons.AutoMirrored.Outlined.MenuOpen,
                label = if (collapsed) "展开侧边栏" else "收起侧边栏",
                onClick = onToggleCollapsed,
                shortcut = LocalPikoPlatform.current.shortcutModifier.label("B"),
                modifier = if (collapsed) Modifier else Modifier.padding(end = 8.dp),
            )
        }
        val itemsScroll = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                // 横握的手机只有三百多 dp 高，窄轨放不下全部入口，靠滚动；不渐隐的话最后一项被截在账号行上沿，
                // 看着像被头像压住，也看不出还能滚
                .scrollFadeEdges(itemsScroll, SidebarFadeLength)
                .verticalScroll(itemsScroll)
                .padding(start = if (collapsed) 0.dp else 12.dp, end = if (collapsed) 0.dp else 12.dp, bottom = 16.dp),
        ) {
            // 「我的」不在这里：它在手机上装的账号、库与设置，桌面上分别散到下面的「库」与左下角
            MainTabSwitch(
                currentTab = currentTab.takeIf { selectedPage == null },
                onTabClick = onTabClick,
                activity = rememberTransferActivity(),
                collapsed = collapsed,
            )
            QuickAccessSection(
                state = quickAccess,
                pinned = pinned,
                currentStack = folderStack,
                onFilesTab = selectedPage == null && currentTab == MainTab.FILES,
                onOpen = onOpenQuickAccess,
            )
            SectionLabel("库")
            val onFilesTab = selectedPage == null && currentTab == MainTab.FILES
            for (entry in LibraryEntries) {
                val library = entry.library
                SidebarItem(
                    icon = entry.icon,
                    selectedIcon = entry.selectedIcon,
                    label = entry.label,
                    selected = if (library != null) onFilesTab && folderStack.library == library else selectedPage == entry.screen,
                    onClick = { if (library != null) onToggleLibrary(library) else entry.screen?.let(onOpenPage) },
                )
            }
        }
        SidebarAccountRow(selected = selectedPage == Screen.Settings, onOpenSettings = { onOpenPage(Screen.Settings) }, collapsed = collapsed)
    } }
}

/**
 * 侧边栏顶上的「文件」与「传输」，一组连体按钮。它们是两个去处，下面快速访问里的是文件页里的位置：
 * 做成与列表项同样的两行时，人在某个固定的文件夹里「文件」与那个文件夹同时亮着，看着像选了两处；
 * 做成按钮组，按钮亮表示在哪一页，列表项亮表示在哪个文件夹，两者说的不是一回事。
 *
 * 传输有速度时按钮上写速度（上行、下行取快的那一边），不写「传输」二字；没有速度但有任务在跑时写项数。
 * 蜗牛模式开着时「传输」这一个按钮整个用强调色，有没有传输都是：限速是一直生效的设置，
 * 只在有读数时才显出来的话，没在传的时候看不出它开着。
 *
 * 收起成窄轨时两个去处各是一项图标，速度写在传输图标下面。
 */
@Composable
private fun MainTabSwitch(currentTab: MainTab?, onTabClick: (MainTab) -> Unit, activity: TransferActivity, collapsed: Boolean) {
    val tabs = listOf(MainTab.FILES, MainTab.TRANSFERS)
    val snail by LocalPikoServices.current.preferences.snailModeFlow.collectAsStateWithLifecycle(initialValue = SnailMode())
    if (collapsed) {
        tabs.forEach { tab ->
            val selected = tab == currentTab
            val snailed = tab == MainTab.TRANSFERS && snail.enabled
            SidebarItem(
                icon = tab.icon(false),
                selectedIcon = tab.icon(true),
                label = if (snailed) "传输（蜗牛模式）" else tab.title,
                selected = selected,
                onClick = { onTabClick(tab) },
                trailing = if (tab == MainTab.TRANSFERS && activity.hasReadout) {
                    { SidebarTransferReadout(activity, checked = selected) }
                } else {
                    null
                },
                accent = snailed,
            )
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = tab == currentTab
            ToggleButton(
                checked = selected,
                onCheckedChange = { onTabClick(tab) },
                shapes = connectedToggleShapes(index, tabs.size),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
            ) {
                // 按钮选中时底色已是主色，强调色压在上面读不清，沿用按钮自己的文字色
                val snailed = tab == MainTab.TRANSFERS && snail.enabled && !selected
                CompositionLocalProvider(LocalContentColor provides if (snailed) MaterialTheme.colorScheme.tertiary else LocalContentColor.current) {
                    val readout = tab == MainTab.TRANSFERS && activity.hasReadout
                    // 写着速度时让出图标：半个按钮放不下图标加一串速度，速度前的箭头已表明这是传输。
                    // 项数短，又没有箭头，图标留着
                    val showsSpeed = readout && maxOf(activity.downloadSpeed, activity.uploadSpeed) > 0
                    if (!showsSpeed) {
                        Icon(tab.icon(selected), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    if (readout) {
                        SidebarTransferReadout(activity, checked = selected)
                    } else {
                        Text(tab.title, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 「库」里的一项：网盘页里的一个库（[library]），或是单独的一页（[screen]，只有我的分享）。 */
private class LibraryEntry(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val library: DriveLibrary? = null,
    val screen: Screen? = null,
)

/**
 * 侧边栏「库」一组：看内容的在前，管理的在后，与「我的」页的顺序一致。
 * 选中时换实心图标，所以每一项挑的都是实心与描边长得不一样的：Share 两种写法同形，换成 FolderShared；
 * 库的图标见 DriveLibrary.icon。
 * 我的分享不并进网盘页：它列的是分享链接，不是文件，打开、预览、移动都无从谈起。
 */
private val LibraryEntries = listOf(
    LibraryEntry(DriveLibrary.RECENT),
    LibraryEntry(DriveLibrary.STARRED),
    LibraryEntry(DriveLibrary.HISTORY),
    LibraryEntry("我的分享", Icons.Outlined.FolderShared, Icons.Filled.FolderShared, screen = Screen.MyShares),
    LibraryEntry(DriveLibrary.TRASH),
)

private fun LibraryEntry(library: DriveLibrary) = LibraryEntry(library.title, library.icon, library.selectedIcon, library = library)
