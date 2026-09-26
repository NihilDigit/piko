package dev.piko.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.savedstate.serialization.SavedStateConfiguration
import dev.piko.data.repository.isPlayableVideo
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.navigation.MainTab
import dev.piko.ui.navigation.Screen
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.drive.DriveScreen
import dev.piko.ui.screens.files.FilesScreen
import dev.piko.ui.screens.history.PlayHistoryScreen
import dev.piko.ui.screens.settings.ProfileScreen
import dev.piko.ui.screens.settings.SettingsScreen
import dev.piko.ui.screens.starred.StarredScreen
import dev.piko.ui.screens.trash.TrashScreen
import dev.piko.ui.screens.transfers.TransfersScreen
import dev.piko.ui.theme.PikoMotion
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/** 一次播放请求。[playlist] 是同目录可播的视频，桌面播放器用它做选集；来自传输页时为空。 */
class VideoPlayerRequest(
    val fileId: String,
    val fileName: String,
    val localPath: String?,
    val playlist: List<FileStat> = emptyList(),
)

/**
 * 播放器怎么呈现由平台决定。Android 在应用内压一层全屏页，播放器代码暂时留在 app 模块；
 * 桌面端开独立窗口，可以一边播一边继续浏览网盘。
 */
sealed interface VideoPlayerHost {
    class InApp(
        val content: @Composable (request: Screen.VideoPlayer, onClose: () -> Unit) -> Unit,
    ) : VideoPlayerHost

    class Detached(val open: (VideoPlayerRequest) -> Unit) : VideoPlayerHost
}

// 非 Android 端没有反射可用，返回栈里的每种 NavKey 都要登记序列化器才能存取
private val NavKeyConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(Screen.SubDrive::class)
            subclass(Screen.Trash::class)
            subclass(Screen.Starred::class)
            subclass(Screen.PlayHistory::class)
            subclass(Screen.Settings::class)
            subclass(Screen.VideoPlayer::class)
        }
    }
}

/**
 * 主界面：导航套件加压栈页面。
 *
 * 导航随窗口宽度变化：compact 是底部导航栏，medium 与 expanded 换成侧边导航栏。
 * 回收站与设置是「我的」的两个详情页：compact 上是盖住整个窗口的压栈页；更宽时留在导航栏右侧的
 * 内容区里，expanded 窗口下与「我的」并排成列表加详情两栏。
 */
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
    val backStack = rememberNavBackStack(NavKeyConfiguration)
    val widthClass = currentWidthClass()
    val coroutineScope = rememberCoroutineScope()

    val topScreen = backStack.lastOrNull() as? Screen
    // 「我的」的详情页（星标、播放历史、回收站、设置）在宽窗口下不盖住导航栏，而是进内容区；
    // 只有 compact 与播放器仍是整窗覆盖层
    val profilePane = topScreen?.takeIf { it in ProfilePanes }
    val profilePaneInline = profilePane != null && widthClass != WidthClass.Compact
    val activeOverlayScreen = topScreen?.takeUnless { profilePaneInline }

    fun closeTop() {
        backStack.removeLastOrNull()
    }

    // 两个详情页互相替换，不叠在一起：从回收站点到设置，返回应当回到「我的」而不是回收站
    fun openProfilePane(screen: Screen) {
        if (topScreen == screen) return
        if (profilePane != null) closeTop()
        backStack.add(screen)
    }

    // 监听外部传入的磁力链接，自动切到文件页并关闭覆盖层
    val pendingMagnet by services.instantMagnetRepository.pendingMagnetFlow.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMagnet) {
        if (pendingMagnet != null) {
            currentTab = MainTab.FILES
            backStack.clear()
        }
    }

    val openDriveRequested by services.driveRepository.openDriveRequested.collectAsStateWithLifecycle()
    LaunchedEffect(openDriveRequested) {
        if (openDriveRequested) {
            currentTab = MainTab.FILES
            backStack.clear()
            services.driveRepository.consumeOpenDriveRequest()
        }
    }

    BackHandler(enabled = profilePaneInline) { closeTop() }

    // 若当前不在文件主页且未打开覆盖页面，按下返回键优先回到文件页
    BackHandler(enabled = currentTab != MainTab.FILES && topScreen == null) {
        currentTab = MainTab.FILES
    }

    // 已下完的本地副本优先：省流量，也不受网络波动影响
    fun playVideo(file: FileStat, playlist: List<FileStat>) {
        val localTask = services.downloadManager.tasks.value.values.find {
            it.fileId == file.id && it.status == DownloadStatus.COMPLETED && !it.isSegment
        }
        val localPath = localTask?.destinationPath?.takeIf(localFiles::exists)
        when (videoPlayer) {
            is VideoPlayerHost.InApp -> backStack.add(Screen.VideoPlayer(file.id, file.name, localPath))
            is VideoPlayerHost.Detached -> videoPlayer.open(VideoPlayerRequest(file.id, file.name, localPath, playlist))
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
                backStack.clear()
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

    // 覆盖层盖住主内容时，主内容这棵树仍留在组合里，也仍会被重新测量：播放器把
    // Activity 转成横屏，底下的列表就按横屏尺寸重排，LazyList 的滚动锚点跟着挪，
    // 退出播放器回来看到的已经不是刚才那一条。这里在覆盖层期间把尺寸钉在进去之前
    // 那一次——反正此刻它一个像素也不显示。
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val frozenSizeModifier = if (activeOverlayScreen != null && contentSize != IntSize.Zero) {
        with(density) { Modifier.requiredSize(contentSize.width.toDp(), contentSize.height.toDp()) }
    } else {
        Modifier.onSizeChanged { contentSize = it }
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
        // 宽窗口里详情页挂在「我的」下面，换页时一并收起
        if (profilePaneInline) closeTop()
    }

    // 快捷键的兜底落点：网盘页有自己的焦点目标，其余页面没有可聚焦的内容时，
    // 按键要有个地方落，Ctrl+数字切换页面才能生效
    val shortcutFocus = remember { FocusRequester() }
    LaunchedEffect(currentTab) {
        if (currentTab != MainTab.FILES) runCatching { shortcutFocus.requestFocus() }
    }


    Box(
        modifier = modifier
            .fillMaxSize()
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
                backStack.clear()
                true
            },
    ) {
        // 全屏覆盖层是这个 Box 的兄弟节点且画在后面，把导航栏一并盖住，所以主内容
        // 始终留在 NavigationSuiteScaffold 里调用，不搬到外面去。搬动意味着换组合
        // 位置，整棵子树会被销毁重建，列表滚动位置与已加载的文件全部丢失。
        val mainContent: @Composable () -> Unit = {
            // M3 的 top level 模式：旧页淡出走完再淡入新页，见 PikoMotion.TopLevelEnterFade。
            // 原来是 when 直接换子树，跳切被规范单列为要避免的做法：读者得不到任何线索说明换了页
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { fadeIn(PikoMotion.TopLevelEnterFade) togetherWith fadeOut(PikoMotion.TopLevelExitFade) },
                modifier = Modifier
                    .fillMaxSize()
                    .then(frozenSizeModifier),
                label = "mainTab",
            ) { tab ->
                when (tab) {
                    MainTab.FILES -> {
                        FilesScreen(onNavigateToVideoPlayer = ::playVideo, scrollToTopRequests = filesScrollToTop)
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
                        ProfileWithPanes(
                            onLogout = onLogout,
                            onOpenPane = ::openProfilePane,
                            onClosePane = ::closeTop,
                            onOpenFile = ::openFromProfile,
                            onPlayFile = { playVideo(it, listOf(it)) },
                            onLocateFile = ::locateInDrive,
                            openPane = profilePane.takeIf { profilePaneInline },
                            twoPane = widthClass == WidthClass.Expanded,
                        )
                    }
                }
            }
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

        // 压栈页面 (子目录、compact 下的回收站、应用内播放器)
        activeOverlayScreen?.let { screen ->
            BackHandler { closeTop() }

            AnimatedVisibility(
                visible = true,
                enter = slideInHorizontally(
                    animationSpec = PikoMotion.ForwardEnterSlide,
                    initialOffsetX = { it / PikoMotion.ForwardSlideFraction },
                ) + fadeIn(animationSpec = PikoMotion.ForwardEnterFade),
                exit = slideOutHorizontally(
                    animationSpec = PikoMotion.ForwardExitSlide,
                    targetOffsetX = { it / PikoMotion.ForwardSlideFraction },
                ) + fadeOut(animationSpec = PikoMotion.ForwardExitFade),
            ) {
                when (screen) {
                    is Screen.SubDrive -> {
                        DriveScreen(
                            currentFolderId = screen.folderId,
                            currentFolderName = screen.folderName,
                            onNavigateToFolder = { id, name ->
                                backStack.add(Screen.SubDrive(id, name))
                            },
                            onNavigateToVideoPlayer = ::playVideo,
                        )
                    }
                    is Screen.Trash -> TrashScreen(onBackClick = ::closeTop)
                    is Screen.Starred -> StarredScreen(onBackClick = ::closeTop, onOpen = ::openFromProfile, onLocate = ::locateInDrive)
                    is Screen.PlayHistory -> PlayHistoryScreen(
                        onBackClick = ::closeTop,
                        onPlay = { playVideo(it, listOf(it)) },
                        onLocate = ::locateInDrive,
                    )
                    is Screen.Settings -> SettingsScreen(onBackClick = ::closeTop)
                    is Screen.VideoPlayer -> {
                        (videoPlayer as? VideoPlayerHost.InApp)?.content?.invoke(screen, ::closeTop)
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** 「我的」的详情页。它们互相替换，不叠在一起。 */
private val ProfilePanes = setOf<Screen>(Screen.Starred, Screen.PlayHistory, Screen.Trash, Screen.Settings)

/**
 * 「我的」与它的详情页（星标、播放历史、回收站、设置）。medium 窗口里详情页替换掉「我的」；expanded 窗口里
 * 两者并排，右栏没有打开的详情页时显示设置，免得半边空着。并排时设置页不给返回按钮：它本就是右栏的默认内容。
 */
@Composable
private fun ProfileWithPanes(
    onLogout: () -> Unit,
    onOpenPane: (Screen) -> Unit,
    onClosePane: () -> Unit,
    onOpenFile: (FileStat) -> Unit,
    onPlayFile: (FileStat) -> Unit,
    onLocateFile: (FileStat) -> Unit,
    openPane: Screen?,
    twoPane: Boolean,
) {
    val profile: @Composable (Screen?, Modifier) -> Unit = { selected, modifier ->
        ProfileScreen(
            onLogout = onLogout,
            onOpenPane = onOpenPane,
            selectedPane = selected,
            modifier = modifier,
        )
    }

    @Composable
    fun Pane(screen: Screen, onBackClick: (() -> Unit)?, modifier: Modifier) {
        when (screen) {
            Screen.Starred -> StarredScreen(onBackClick, onOpen = onOpenFile, onLocate = onLocateFile, modifier = modifier)
            Screen.PlayHistory -> PlayHistoryScreen(onBackClick, onPlay = onPlayFile, onLocate = onLocateFile, modifier = modifier)
            Screen.Trash -> TrashScreen(onBackClick = onClosePane, modifier = modifier)
            else -> SettingsScreen(onBackClick = onBackClick, modifier = modifier)
        }
    }

    if (!twoPane) {
        if (openPane != null) Pane(openPane, onClosePane, Modifier) else profile(null, Modifier)
        return
    }
    val shown = openPane ?: Screen.Settings
    Row(modifier = Modifier.fillMaxSize()) {
        profile(shown, Modifier.weight(1f))
        VerticalDivider()
        // 右栏的默认内容（设置）没有可返回的地方，其余详情页返回即回到设置
        Pane(shown, onBackClick = onClosePane.takeIf { openPane != null }, modifier = Modifier.weight(1f).fillMaxHeight())
    }
}

private fun MainTab.icon(selected: Boolean) = when (this) {
    MainTab.FILES -> if (selected) Icons.Filled.Folder else Icons.Outlined.Folder
    MainTab.TRANSFERS -> if (selected) Icons.Filled.SyncAlt else Icons.Outlined.SyncAlt
    MainTab.SETTINGS -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
}
