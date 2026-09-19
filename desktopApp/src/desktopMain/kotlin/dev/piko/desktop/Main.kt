package dev.piko.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.piko.desktop.ui.navigation.NavSection
import dev.piko.desktop.ui.screens.DownloadsView
import dev.piko.desktop.ui.screens.DriveView
import dev.piko.desktop.ui.screens.LoginView
import dev.piko.desktop.ui.screens.SettingsView
import dev.piko.desktop.ui.screens.TasksView
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import io.github.composefluent.FluentTheme
import io.github.composefluent.background.Mica
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.width
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.SideNav
import io.github.composefluent.component.SideNavItem
import io.github.composefluent.component.Text
import io.github.composefluent.darkColors
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowDownload
import io.github.composefluent.icons.regular.Cloud
import io.github.composefluent.icons.regular.Folder
import io.github.composefluent.icons.regular.Settings
import io.github.composefluent.lightColors

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Piko",
    ) {
        val settingsStore = remember { DesktopSettingsStore() }
        var themeVersion by remember { mutableStateOf(0) }

        val systemAccent = remember { WinRTSupport.getSystemAccentColor() }
        val accent = systemAccent ?: Color(0xFF0078D4)

        val isDark = remember(themeVersion, settingsStore.themeMode) {
            when (settingsStore.themeMode) {
                "dark" -> true
                "light" -> false
                else -> WinRTSupport.isSystemInDarkMode()
            }
        }

        val colors = remember(isDark, accent) {
            if (isDark) darkColors(accent) else lightColors(accent)
        }

        FluentTheme(colors = colors) {
            Mica(Modifier.fillMaxSize()) {
                val scope = rememberCoroutineScope()
                val manager = remember { PikoClientManager(FilePikoSessionStore(), scope) }
                val client by manager.currentClient.collectAsState()
                val initializing by manager.isInitializing.collectAsState()

                when {
                    initializing -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ProgressRing(size = ProgressRingSize.Large)
                        }
                    }
                    client == null -> {
                        LoginView(manager = manager)
                    }
                    else -> {
                        MainAppContent(
                            manager = manager,
                            settingsStore = settingsStore,
                            themeColors = colors,
                            onThemeChanged = { themeVersion++ },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainAppContent(
    manager: PikoClientManager,
    settingsStore: DesktopSettingsStore,
    themeColors: io.github.composefluent.Colors,
    onThemeChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val preferences = remember(settingsStore) { DesktopPikoPreferences(settingsStore) }
    val storage = remember(settingsStore.downloadDirectory) {
        DesktopPikoDownloadStorage(settingsStore.downloadDirectory)
    }
    val downloadCoordinator = remember(manager, preferences, storage) {
        PikoDownloadCoordinator(manager, preferences, storage, scope)
    }
    val driveRepo = remember(manager) { PikoDriveRepository(manager) }
    val mediaRepo = remember(manager) { PikoMediaRepository(manager) }

    var currentSection by remember { mutableStateOf(NavSection.DRIVE) }
    var isSideNavExpanded by remember { mutableStateOf(true) }

    // WinRT Toast notification integration for completed or failed downloads
    var previousStatuses by remember { mutableStateOf<Map<String, DownloadStatus>>(emptyMap()) }
    LaunchedEffect(downloadCoordinator) {
        downloadCoordinator.tasks.collect { tasksMap ->
            tasksMap.forEach { (id, task) ->
                val prev = previousStatuses[id]
                if (prev != null && prev != task.status) {
                    if (task.status == DownloadStatus.COMPLETED) {
                        WinRTSupport.showNotification(
                            title = "下载完成",
                            message = "${task.fileName} 已成功下载至本地。",
                        )
                    } else if (task.status == DownloadStatus.FAILED) {
                        WinRTSupport.showNotification(
                            title = "下载失败",
                            message = "${task.fileName} 下载遇到问题: ${task.errorMessage ?: "未知错误"}",
                        )
                    }
                }
            }
            previousStatuses = tasksMap.mapValues { it.value.status }
        }
    }

    val sideNavWidth by animateDpAsState(
        targetValue = if (isSideNavExpanded) 200.dp else 48.dp,
        animationSpec = tween(
            durationMillis = 150,
            easing = FastOutSlowInEasing,
        ),
    )

    Row(Modifier.fillMaxSize()) {
        SideNav(
            expanded = isSideNavExpanded,
            onExpandStateChange = { isSideNavExpanded = it },
            modifier = Modifier.width(sideNavWidth),
            title = {
                Text(
                    text = "Piko",
                    style = FluentTheme.typography.subtitle,
                    modifier = Modifier.padding(start = 4.dp),
                )
            },
            content = {
                SideNavItem(
                    selected = currentSection == NavSection.DRIVE,
                    onClick = { currentSection = NavSection.DRIVE },
                    icon = {
                        Icon(
                            imageVector = Icons.Regular.Folder,
                            contentDescription = "网盘",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) {
                    Text("网盘")
                }
                SideNavItem(
                    selected = currentSection == NavSection.DOWNLOADS,
                    onClick = { currentSection = NavSection.DOWNLOADS },
                    icon = {
                        Icon(
                            imageVector = Icons.Regular.ArrowDownload,
                            contentDescription = "下载",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) {
                    Text("下载")
                }
                SideNavItem(
                    selected = currentSection == NavSection.TASKS,
                    onClick = { currentSection = NavSection.TASKS },
                    icon = {
                        Icon(
                            imageVector = Icons.Regular.Cloud,
                            contentDescription = "云端离线",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) {
                    Text("云端离线")
                }
            },
            footer = {
                SideNavItem(
                    selected = currentSection == NavSection.SETTINGS,
                    onClick = { currentSection = NavSection.SETTINGS },
                    icon = {
                        Icon(
                            imageVector = Icons.Regular.Settings,
                            contentDescription = "设置",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) {
                    Text("设置")
                }
            },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            when (currentSection) {
                NavSection.DRIVE -> {
                    DriveView(
                        manager = manager,
                        repository = driveRepo,
                        mediaRepository = mediaRepo,
                        downloadCoordinator = downloadCoordinator,
                        themeColors = themeColors,
                    )
                }
                NavSection.DOWNLOADS -> {
                    DownloadsView(
                        downloadCoordinator = downloadCoordinator,
                        settingsStore = settingsStore,
                    )
                }
                NavSection.TASKS -> {
                    TasksView(
                        manager = manager,
                    )
                }
                NavSection.SETTINGS -> {
                    SettingsView(
                        manager = manager,
                        settingsStore = settingsStore,
                        onThemeChanged = onThemeChanged,
                    )
                }
            }
        }
    }
}
