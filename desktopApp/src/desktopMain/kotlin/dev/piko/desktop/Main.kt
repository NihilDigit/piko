package dev.piko.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
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
import io.github.composefluent.component.Icon
import io.github.composefluent.component.NavigationDisplayMode
import io.github.composefluent.component.NavigationView
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.Text
import io.github.composefluent.component.menuItem
import io.github.composefluent.component.rememberNavigationState
import io.github.composefluent.darkColors
import io.github.composefluent.lightColors

fun main(args: Array<String>) {
    // magnet: 链接经 MSI 注册的协议唤起时，URL 以启动参数进来。
    val magnetArg = args.firstOrNull { it.startsWith("magnet:", ignoreCase = true) }
    if (WinRTSupport.isWindows) {
        // 进程级 AUMID 必须在建窗口/发 Toast 之前设置；协议注册放后台线程，不挡启动。
        runCatching { WinRTSupport.ensureAppUserModelId() }
        Thread(
            { runCatching { WinRTSupport.ensureMagnetProtocolHandler() } },
            "Piko-Win32-Setup",
        ).apply { isDaemon = true; start() }
    }
    pikoApplication(magnetArg)
}

private fun pikoApplication(initialMagnetUrl: String?) = application {
    // 任务栏/标题栏图标：desktopMain/resources/app-icon.png（docs/icon.svg 同源）。
    // 用 classloader 直读，不走 compose resources codegen（桌面独占资源）。
    val appIcon = remember {
        object {}.javaClass.getResourceAsStream("/app-icon.png")
            ?.use { BitmapPainter(loadImageBitmap(it)) }
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Piko",
        icon = appIcon,
    ) {
        val settingsStore = remember { DesktopSettingsStore() }
        var themeVersion by remember { mutableStateOf(0) }

        // Fluent 行为：窗口每次获焦重读系统强调色/深浅色，跟随系统设置变化。
        // （UISettings.ColorValuesChanged 需要 STA 消息泵，JVM 上先用获焦刷新代替。）
        DisposableEffect(Unit) {
            val focusListener = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent) {
                    themeVersion++
                }
                override fun windowLostFocus(e: java.awt.event.WindowEvent) = Unit
            }
            window.addWindowFocusListener(focusListener)
            onDispose { window.removeWindowFocusListener(focusListener) }
        }

        val systemAccent = remember(themeVersion) { WinRTSupport.getSystemAccentColor() }
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
            // Mica 注：compose-fluent 单参 Mica() 目前只是纯色 mica.base 兜底，
            // 真云母需要 DWM 窗口级集成（alterWindowBackground / backdrop），待上游支持后再接。
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
                            initialMagnetUrl = initialMagnetUrl,
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
    initialMagnetUrl: String? = null,
) {
    val scope = rememberCoroutineScope()
    val preferences = remember(settingsStore) { DesktopPikoPreferences(settingsStore) }
    val storage = remember(settingsStore.downloadDirectory) {
        DesktopPikoDownloadStorage(settingsStore.downloadDirectory)
    }
    val mediaRepo = remember(manager, preferences) { PikoMediaRepository(manager, preferences) }
    val downloadCoordinator = remember(manager, preferences, storage, mediaRepo) {
        PikoDownloadCoordinator(
            manager,
            preferences,
            storage,
            scope,
            segmentDownloader = DesktopPikoSegmentDownloader(),
            mediaRepository = mediaRepo,
        )
    }
    val driveRepo = remember(manager, preferences) { PikoDriveRepository(manager, preferences) }

    // 协议唤起（magnet: 链接）直接落到离线任务页，带着这条链打开秒传对话框。
    var currentSection by remember {
        mutableStateOf(if (initialMagnetUrl != null) NavSection.TASKS else NavSection.DRIVE)
    }
    var pendingMagnet by remember { mutableStateOf(initialMagnetUrl) }
    // 「我的」里点回收站：先切到网盘页，再用这个信号让 DriveView 翻到回收站。
    var trashSignal by remember { mutableIntStateOf(0) }
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

    // 画廊同款 NavigationView（Left）：选中指示器、展开/收起、Fluent 动效
    // 全部由组件内部处理，不再手写 Row + SideNav + 宽度动画。
    NavigationView(
        menuItems = {
            // NavSection 自带标题与图标（DRIVE/DOWNLOADS/TASKS），SETTINGS 走 footer。
            NavSection.entries.filter { it != NavSection.SETTINGS }.forEach { section ->
                menuItem(
                    selected = currentSection == section,
                    onClick = { currentSection = section },
                    text = { Text(section.title) },
                    icon = {
                        Icon(
                            imageVector = section.icon,
                            contentDescription = section.title,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        },
        footerItems = {
            menuItem(
                selected = currentSection == NavSection.SETTINGS,
                onClick = { currentSection = NavSection.SETTINGS },
                text = { Text(NavSection.SETTINGS.title) },
                icon = {
                    Icon(
                        imageVector = NavSection.SETTINGS.icon,
                        contentDescription = NavSection.SETTINGS.title,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        },
        title = {
            Text(
                text = "Piko",
                style = FluentTheme.typography.subtitle,
                modifier = Modifier.padding(start = 4.dp),
            )
        },
        displayMode = NavigationDisplayMode.LeftCompact,
        state = rememberNavigationState(),
        pane = {
            when (currentSection) {
                NavSection.DRIVE -> {
                    DriveView(
                        manager = manager,
                        repository = driveRepo,
                        mediaRepository = mediaRepo,
                        downloadCoordinator = downloadCoordinator,
                        preferences = preferences,
                        themeColors = themeColors,
                        openTrashSignal = trashSignal,
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
                        driveRepository = driveRepo,
                        preferences = preferences,
                        onOpenFolder = { target ->
                            // 经仓库层的全局目录栈切过去，DriveView 重建时从栈顶加载
                            driveRepo.navigateToFolder(target)
                            currentSection = NavSection.DRIVE
                        },
                        pendingMagnet = pendingMagnet,
                        onPendingMagnetConsumed = { pendingMagnet = null },
                    )
                }
                NavSection.SETTINGS -> {
                    SettingsView(
                        manager = manager,
                        settingsStore = settingsStore,
                        preferences = preferences,
                        driveRepository = driveRepo,
                        onThemeChanged = onThemeChanged,
                        onNavigateToTrash = {
                            currentSection = NavSection.DRIVE
                            trashSignal += 1
                        },
                    )
                }
            }
        },
    )
}
