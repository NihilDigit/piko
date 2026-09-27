package dev.piko.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SyncAlt
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
import androidx.compose.ui.graphics.Color
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
import dev.piko.ui.components.SidePanelLayout
import dev.piko.ui.components.sidePanelFits
import dev.piko.ui.components.trackInputModality
import dev.piko.ui.navigation.MainTab
import dev.piko.ui.navigation.Screen
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.files.FilesScreen
import dev.piko.ui.screens.history.PlayHistoryScreen
import dev.piko.ui.screens.settings.ProfileScreen
import dev.piko.ui.screens.settings.SettingsScreen
import dev.piko.ui.screens.clips.ClipFeedScreen
import dev.piko.ui.screens.share.MySharesScreen
import dev.piko.ui.screens.starred.StarredScreen
import dev.piko.ui.screens.trash.TrashScreen
import dev.piko.ui.screens.transfers.TransfersScreen
import dev.piko.ui.theme.PikoMotion
import io.github.nihildigit.pikpak.FileStat
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
            subclass(Screen.Trash::class)
            subclass(Screen.Starred::class)
            subclass(Screen.PlayHistory::class)
            subclass(Screen.MyShares::class)
            subclass(Screen.Settings::class)
            subclass(Screen.VideoPlayer::class)
        }
    }
}

/** 「我的」与它的详情页拼成的两栏，两者的 scene key 相同才会并排。 */
private const val ProfileScene = "profile"

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
    val twoPane = widthClass == WidthClass.Expanded
    val coroutineScope = rememberCoroutineScope()

    val topScreen = backStack.lastOrNull() as? Screen
    val onHome = backStack.size <= 1

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
        while (backStack.size > 1 && (backStack.lastOrNull() in ProfilePanes || backStack.lastOrNull() == Screen.Profile)) {
            backStack.removeLastOrNull()
        }
    }

    // 两个详情页互相替换，不叠在一起：从回收站点到设置，返回应当回到「我的」而不是回收站。
    // 两栏时先垫一个「我的」作列表栏
    fun openProfilePane(screen: Screen) {
        if (topScreen == screen) return
        if (topScreen in ProfilePanes) backStack.removeLastOrNull()
        if (twoPane && backStack.lastOrNull() != Screen.Profile) backStack.add(Screen.Profile)
        backStack.add(screen)
    }

    // 窄窗口里打开的详情页下面没有列表栏，窗口拉宽后补上，否则两栏的左边是空的
    LaunchedEffect(twoPane, topScreen) {
        if (twoPane && topScreen in ProfilePanes && backStack.getOrNull(backStack.lastIndex - 1) != Screen.Profile) {
            backStack.add(backStack.lastIndex, Screen.Profile)
        }
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

    // 星标与播放历史里的条目：跳到网盘里它所在的位置并高亮它。文件夹则直接进入
    fun locateInDrive(file: FileStat) {
        val driveRepo = services.driveRepository
        coroutineScope.launch {
            driveRepo.locateFolder(file.id).onSuccess { parents ->
                val stack = if (file.isFolder) parents + PikoPathBreadcrumb(file.id, file.name) else parents
                // 先设好栈再切页：网盘页重新组合时直接加载栈顶目录
                driveRepo.updateFolderStack(stack)
                if (!file.isFolder) driveRepo.requestHighlight(setOf(file.id))
                resetToHome()
                currentTab = MainTab.FILES
            }
        }
    }

    // 文件夹进入，视频播放，其余文件在网盘里找到它
    fun openFromProfile(file: FileStat) {
        if (file.isPlayableVideo()) playVideo(file, listOf(file)) else locateInDrive(file)
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
    val density = LocalDensity.current

    // 信息流：网盘页顶栏上的开关，单独一个状态，不动存下的视图，关掉即回到原来的列表。
    // 宽窗口放得下主区与侧栏时开在右侧侧栏，放不下时盖住整个网盘页；窗口缩放时两种形态随之互换。
    // 桌面端还能弹出到独立窗口，那时应用内两种形态都收起，开关仍是开着的
    val clipFeedSession = services.clipFeedSession
    val preferences = services.preferences
    val initialPanelPrefs = remember { runBlocking { preferences.clipPanelFlow.first() } }
    val panelPrefs by preferences.clipPanelFlow.collectAsStateWithLifecycle(initialPanelPrefs)
    val contentWidth = with(density) { contentSize.width.toDp() }.takeIf { contentSize != IntSize.Zero }
    val panelFits = contentWidth != null && widthClass == WidthClass.Expanded && sidePanelFits(contentWidth, ClipPanelMinWidth)
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
    // 关掉信息流连同它的窗口一起关：开关是它唯一的总开关，不管它此刻在哪一处
    fun setFeedShown(shown: Boolean) {
        feedShownState = shown
        if (!shown && feedPoppedOut) detachedHost?.closeClipFeed?.invoke()
        coroutineScope.launch { preferences.setClipPanelOpen(shown) }
    }
    val feedOnFilesTab = feedShown && !feedPoppedOut && currentTab == MainTab.FILES
    val feedInPanel = feedOnFilesTab && panelFits
    val feedFullScreen = feedOnFilesTab && !panelFits

    // 信息流是订阅，不跟着网盘走：刷的是哪个文件夹由范围菜单与文件夹上的「在信息流中刷」决定，
    // 网盘里进进出出、从信息流里定位到某个文件，都不换掉正在刷的这一批。
    // 头一回打开还没订阅过，取眼前的文件夹；此后是上次刷的那个，重启后也接着
    val folderStack by services.driveRepository.folderStackFlow.collectAsStateWithLifecycle()
    // 头一次 open 完成前会话里是空的，此时组合 ClipFeedScreen 会闪一下「没有可播放的视频」
    var feedOpened by remember { mutableStateOf(false) }
    LaunchedEffect(feedShown && !feedPoppedOut) {
        if (!feedShown || feedPoppedOut) return@LaunchedEffect
        val folder = clipFeedSession.lastFolder() ?: folderStack.lastOrNull() ?: return@LaunchedEffect
        clipFeedSession.open(folder)
        feedOpened = true
    }

    fun browseInFeed(folder: PikoPathBreadcrumb) {
        coroutineScope.launch {
            clipFeedSession.open(folder)
            feedOpened = true
        }
        setFeedShown(true)
    }

    fun playFromFeed(file: FileStat, startMillis: Long) = playVideo(file, listOf(file), startMillis)

    // 全屏形态盖着网盘页，要看到定位的结果得先收起；侧栏形态下列表就在旁边
    fun locateFromFeed(file: FileStat) {
        if (feedFullScreen) setFeedShown(false)
        locateInDrive(file)
    }

    fun popOutFeed() {
        detachedHost?.openClipFeed(
            ClipFeedLinks(
                playFull = ::playFromFeed,
                locate = ::locateInDrive,
                dock = { detachedHost.closeClipFeed() },
                close = { setFeedShown(false) },
            ),
        )
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

    val feedFrame: @Composable (@Composable () -> Unit) -> Unit = { drive ->
        SidePanelLayout(
            // 不看当前页：切走时网盘页随淡出一起消失，侧栏不必先收起
            open = feedShown && panelFits && !feedPoppedOut,
            savedWidthDp = panelPrefs.widthDp,
            title = "信息流",
            closeDescription = "关闭信息流",
            onClose = { setFeedShown(false) },
            onWidthChange = { coroutineScope.launch { preferences.setClipPanelWidth(it) } },
            defaultWidth = ClipPanelDefaultWidth,
            minWidth = ClipPanelMinWidth,
            ready = feedShownState != null,
            // 整张卡是黑底的竖屏画面，范围、静音、弹出与关闭都在它自己的顶栏上
            showHeader = false,
            main = drive,
            panel = { FeedContent(compact = true, visible = feedInPanel) },
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

    @Composable
    fun HomeContent() {
        val mainContent: @Composable () -> Unit = {
            // M3 的 top level 模式：旧页淡出走完再淡入新页，见 PikoMotion.TopLevelEnterFade。
            // 原来是 when 直接换子树，跳切被规范单列为要避免的做法：读者得不到任何线索说明换了页
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { fadeIn(PikoMotion.TopLevelEnterFade) togetherWith fadeOut(PikoMotion.TopLevelExitFade) },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { contentSize = it },
                label = "mainTab",
            ) { tab ->
                when (tab) {
                    MainTab.FILES -> {
                        FilesScreen(
                            onNavigateToVideoPlayer = { file, playlist -> playVideo(file, playlist) },
                            scrollToTopRequests = filesScrollToTop,
                            onOpenTransfers = ::openTransfers,
                            feedShown = feedShown,
                            onFeedShownChange = ::setFeedShown,
                            onBrowseInFeed = ::browseInFeed,
                            feedFrame = feedFrame,
                        )
                    }
                    MainTab.TRANSFERS -> {
                        TransfersScreen(
                            scrollToTopRequests = transfersScrollToTop,
                            onNavigateToInstant = {
                                currentTab = MainTab.FILES
                            },
                            onOpenCloudFile = { fileId, _ ->
                                val driveRepo = services.driveRepository
                                driveRepo.locateFolder(fileId)
                                    .onSuccess { stack ->
                                        // 先设好栈再切页：网盘页重新组合时直接加载栈顶目录
                                        driveRepo.updateFolderStack(stack)
                                        driveRepo.requestHighlight(setOf(fileId))
                                        currentTab = MainTab.FILES
                                    }
                                    .isSuccess
                            },
                            onNavigateToVideoPlayer = ::playLocal,
                        )
                    }
                    MainTab.SETTINGS -> {
                        ProfileScreen(
                            onLogout = onLogout,
                            onOpenPane = ::openProfilePane,
                            selectedPane = null,
                        )
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            // 若当前不在文件主页，按下返回键优先回到文件页
            BackHandler(enabled = currentTab != MainTab.FILES && !feedFullScreen) {
                currentTab = MainTab.FILES
            }

            // 导航栏的款式交给库按窗口挑：compact 是 64dp 的 ShortNavigationBar，更宽是收起态的
            // WideNavigationRail。不用旧重载的 calculateFromAdaptiveInfo，它给的是 80dp 的 NavigationBar
            // 与 NavigationRail，M3 Expressive 已把这两款标为不再推荐
            val navigationSuiteType = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())
            NavigationSuiteScaffold(
                navigationItems = {
                    MainTab.entries.forEach { tab ->
                        val selected = currentTab == tab
                        NavigationSuiteItem(
                            selected = selected,
                            onClick = { onTabClick(tab) },
                            navigationSuiteType = navigationSuiteType,
                            // 图标不写 contentDescription：每项都有文字标签，图标再给一次会被读屏念两遍
                            icon = { Icon(imageVector = tab.icon(selected), contentDescription = null) },
                            // M3 要求选中项的标签加粗，而导航项的样式只有一档字重，不分选中态
                            label = { Text(tab.title, fontWeight = if (selected) FontWeight.Bold else null) },
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
                enter = slideInHorizontally(
                    animationSpec = PikoMotion.ForwardEnterSlide,
                    initialOffsetX = { it / PikoMotion.ForwardSlideFraction },
                ) + fadeIn(animationSpec = PikoMotion.ForwardEnterFade),
                exit = slideOutHorizontally(
                    animationSpec = PikoMotion.ForwardExitSlide,
                    targetOffsetX = { it / PikoMotion.ForwardSlideFraction },
                ) + fadeOut(animationSpec = PikoMotion.ForwardExitFade),
            ) {
                FeedContent(compact = false, visible = feedFullScreen)
            }
        }
    }

    // 分几栏不用库默认的 calculatePaneScaffoldDirective：那一套按 WindowSizeClass 算，与全应用按
    // currentWidthClass 取的断点各算各的。列表栏宽取 M3 布局规范 expanded 档固定栏的 360dp；
    // 两栏各是一整页，自带顶栏与底色，中间不再留空隙也不画分隔线
    val listDetailDirective = remember(twoPane) {
        PaneScaffoldDirective(
            maxHorizontalPartitions = if (twoPane) 2 else 1,
            horizontalPartitionSpacerSize = 0.dp,
            maxVerticalPartitions = 1,
            verticalPartitionSpacerSize = 0.dp,
            defaultPanePreferredWidth = ProfilePaneWidth,
            excludedBounds = emptyList(),
        )
    }
    // 返回一次出一条（详情连同垫着的列表栏，见 popBack）。默认的 PopUntilScaffoldValueChange 在两栏时
    // 出栈不改变分栏形态，会把整组一次退光
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(
        backNavigationBehavior = BackNavigationBehavior.PopLatest,
        directive = listDetailDirective,
    )
    // 两栏时详情页不给返回：返回按钮只属于单栏的详情页（M3 canonical layouts 的 list-detail），
    // 退出两栏在列表栏的顶栏上
    val paneBack: (() -> Unit)? = if (twoPane) null else ::popBack
    val selectedPane = topScreen?.takeIf { it in ProfilePanes }

    Box(
        modifier = modifier
            .fillMaxSize()
            .trackInputModality()
            .focusRequester(shortcutFocus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !shortcutModifier.isPressed(event)) return@onKeyEvent false
                val tab = when (event.key) {
                    Key.One -> MainTab.FILES
                    Key.Two -> MainTab.TRANSFERS
                    Key.Three -> MainTab.SETTINGS
                    else -> return@onKeyEvent false
                }
                currentTab = tab
                resetToHome()
                true
            },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = ::popBack,
            sceneStrategies = listOf(listDetailStrategy),
            // 压栈与返回：新页从右侧滑入五分之一屏并淡入，旧页反向让开。走满整屏是 lateral 的做法，
            // 规范明说别拿它做层级导航
            transitionSpec = {
                (
                    slideInHorizontally(PikoMotion.ForwardEnterSlide) { it / PikoMotion.ForwardSlideFraction } +
                        fadeIn(PikoMotion.ForwardEnterFade)
                    ) togetherWith (
                    slideOutHorizontally(PikoMotion.ForwardExitSlide) { -it / PikoMotion.ForwardSlideFraction } +
                        fadeOut(PikoMotion.ForwardExitFade)
                    )
            },
            popTransitionSpec = {
                (
                    slideInHorizontally(PikoMotion.ForwardEnterSlide) { -it / PikoMotion.ForwardSlideFraction } +
                        fadeIn(PikoMotion.ForwardEnterFade)
                    ) togetherWith (
                    slideOutHorizontally(PikoMotion.ForwardExitSlide) { it / PikoMotion.ForwardSlideFraction } +
                        fadeOut(PikoMotion.ForwardExitFade)
                    )
            },
            predictivePopTransitionSpec = { _ ->
                (
                    slideInHorizontally(PikoMotion.ForwardEnterSlide) { -it / PikoMotion.ForwardSlideFraction } +
                        fadeIn(PikoMotion.ForwardEnterFade)
                    ) togetherWith (
                    slideOutHorizontally(PikoMotion.ForwardExitSlide) { it / PikoMotion.ForwardSlideFraction } +
                        fadeOut(PikoMotion.ForwardExitFade)
                    )
            },
            entryProvider = entryProvider {
                entry<Screen.Home> { HomeContent() }
                entry<Screen.Profile>(metadata = ListDetailSceneStrategy.listPane(sceneKey = ProfileScene)) {
                    ProfileScreen(
                        onLogout = onLogout,
                        onOpenPane = ::openProfilePane,
                        selectedPane = selectedPane,
                        onBackClick = ::closeProfile,
                    )
                }
                val detail = ListDetailSceneStrategy.detailPane(ProfileScene)
                entry<Screen.Starred>(metadata = detail) {
                    StarredScreen(onBackClick = paneBack, onOpen = ::openFromProfile, onLocate = { locateInDrive(it) })
                }
                entry<Screen.PlayHistory>(metadata = detail) {
                    PlayHistoryScreen(
                        onBackClick = paneBack,
                        onPlay = { playVideo(it, listOf(it)) },
                        onLocate = { locateInDrive(it) },
                    )
                }
                entry<Screen.MyShares>(metadata = detail) { MySharesScreen(onBackClick = paneBack) }
                entry<Screen.Trash>(metadata = detail) { TrashScreen(onBackClick = paneBack) }
                entry<Screen.Settings>(metadata = detail) { SettingsScreen(onBackClick = paneBack) }
                entry<Screen.VideoPlayer> { screen ->
                    (videoPlayer as? VideoPlayerHost.InApp)?.content?.invoke(screen, ::popBack)
                }
            },
        )
    }
}

/** 侧栏的宽度下限：竖排的片段控件与横屏画面在这个宽度里还放得开。 */
private val ClipPanelMinWidth = 360.dp
private val ClipPanelDefaultWidth = 420.dp

/** 「我的」的详情页。它们互相替换，不叠在一起。 */
private val ProfilePanes = setOf<NavKey?>(Screen.Starred, Screen.PlayHistory, Screen.MyShares, Screen.Trash, Screen.Settings)

/** 两栏时「我的」列表栏的宽度：M3 布局规范 expanded 档固定栏的默认宽度。 */
private val ProfilePaneWidth = 360.dp

private fun MainTab.icon(selected: Boolean) = when (this) {
    MainTab.FILES -> if (selected) Icons.Filled.Folder else Icons.Outlined.Folder
    MainTab.TRANSFERS -> if (selected) Icons.Filled.SyncAlt else Icons.Outlined.SyncAlt
    MainTab.SETTINGS -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
}
