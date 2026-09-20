package dev.piko.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.rememberNavBackStack
import dev.piko.PikoApplication
import dev.piko.download.DownloadStatus
import dev.piko.ui.navigation.MainTab
import dev.piko.ui.navigation.Screen
import dev.piko.ui.screens.drive.DriveScreen
import dev.piko.ui.screens.files.FilesScreen
import dev.piko.ui.screens.player.MediampVideoPlayerScreen
import dev.piko.ui.screens.settings.SettingsScreen
import dev.piko.ui.screens.trash.TrashScreen
import dev.piko.ui.screens.transfers.TransfersScreen
import dev.piko.ui.theme.PikoMotion
import java.io.File

/**
 * Main application scaffold managing Adaptive Navigation and persistent overlay destinations.
 *
 * Documentation References:
 * - Android Navigation 3: android-docs-mirror/pages/guide/navigation/navigation-3/save-state.md
 *   "Use rememberNavBackStack to ensure back stack persists across configuration changes and process death."
 * - Android Compose State: android-docs-mirror/pages/develop/ui/compose/state.md
 *   "Consuming flows safely in Jetpack Compose with collectAsStateWithLifecycle."
 * - Material 3 Adaptive Navigation Suite: m3-material-mirror/pages/components/navigation-bar.md
 *   and m3-material-mirror/pages/components/navigation-rail.md
 */
@Composable
fun PikoMainScaffold(
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.FILES) }
    val backStack = rememberNavBackStack()

    val activeOverlayScreen = backStack.lastOrNull() as? Screen

    // 监听外部传入的磁力链接，自动切到文件页并关闭覆盖层
    val pendingMagnet by PikoApplication.instance.instantMagnetRepository.pendingMagnetFlow.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMagnet) {
        if (pendingMagnet != null) {
            currentTab = MainTab.FILES
            backStack.clear()
        }
    }

    // 若当前不在文件主页且未打开覆盖页面，按下返回键优先回到文件页
    BackHandler(enabled = currentTab != MainTab.FILES && activeOverlayScreen == null) {
        currentTab = MainTab.FILES
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

    Box(modifier = modifier.fillMaxSize()) {
        // 全屏覆盖层是这个 Box 的兄弟节点且画在后面，把导航栏一并盖住，所以主内容
        // 始终留在 NavigationSuiteScaffold 里调用，不搬到外面去。搬动意味着换组合
        // 位置，整棵子树会被销毁重建，列表滚动位置与已加载的文件全部丢失。
        val mainContent: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(frozenSizeModifier),
            ) {
                when (currentTab) {
                    MainTab.FILES -> {
                        FilesScreen(
                            onNavigateToVideoPlayer = { id, name ->
                                val downloadManager = PikoApplication.instance.downloadManager
                                val localTask = downloadManager.tasks.value.values.find {
                                    it.fileId == id && it.status == DownloadStatus.COMPLETED && !it.isSegment
                                }
                                val localPath = localTask?.destinationPath?.takeIf { File(it).exists() }
                                backStack.add(Screen.VideoPlayer(id, name, localPath))
                            },
                        )
                    }
                    MainTab.TRANSFERS -> {
                        TransfersScreen(
                            onNavigateToInstant = {
                                currentTab = MainTab.FILES
                            },
                            onNavigateToVideoPlayer = { fileId, fileName, localPath ->
                                backStack.add(Screen.VideoPlayer(fileId, fileName, localPath))
                            },
                        )
                    }
                    MainTab.SETTINGS -> {
                        SettingsScreen(
                            onLogout = onLogout,
                            onNavigateToTrash = { backStack.add(Screen.Trash) },
                        )
                    }
                }
            }
        }

        NavigationSuiteScaffold(
            navigationSuiteItems = {
                item(
                    selected = currentTab == MainTab.FILES,
                    onClick = { currentTab = MainTab.FILES },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == MainTab.FILES) Icons.Filled.Folder else Icons.Outlined.Folder,
                            contentDescription = "文件",
                        )
                    },
                    label = { Text("文件") },
                )
                item(
                    selected = currentTab == MainTab.TRANSFERS,
                    onClick = { currentTab = MainTab.TRANSFERS },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == MainTab.TRANSFERS) Icons.Filled.SyncAlt else Icons.Outlined.SyncAlt,
                            contentDescription = "传输",
                        )
                    },
                    label = { Text("传输") },
                )
                item(
                    selected = currentTab == MainTab.SETTINGS,
                    onClick = { currentTab = MainTab.SETTINGS },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == MainTab.SETTINGS) Icons.Filled.Person else Icons.Outlined.Person,
                            contentDescription = "我的",
                        )
                    },
                    label = { Text("我的") },
                )
            },
            content = mainContent,
        )

        // 压栈页面 (子目录或全屏播放器)
        activeOverlayScreen?.let { screen ->
            BackHandler {
                backStack.removeLastOrNull()
            }

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
                            onNavigateToVideoPlayer = { id, name ->
                                val downloadManager = PikoApplication.instance.downloadManager
                                val localTask = downloadManager.tasks.value.values.find {
                                    it.fileId == id && it.status == DownloadStatus.COMPLETED && !it.isSegment
                                }
                                val localPath = localTask?.destinationPath?.takeIf { File(it).exists() }
                                backStack.add(Screen.VideoPlayer(id, name, localPath))
                            },
                        )
                    }
                    is Screen.Trash -> {
                        TrashScreen(
                            onBackClick = { backStack.removeLastOrNull() },
                        )
                    }
                    is Screen.VideoPlayer -> {
                        MediampVideoPlayerScreen(
                            initialFileId = screen.fileId,
                            initialFileName = screen.fileName,
                            initialLocalPath = screen.localPath,
                            onBackClick = {
                                backStack.removeLastOrNull()
                            },
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}
