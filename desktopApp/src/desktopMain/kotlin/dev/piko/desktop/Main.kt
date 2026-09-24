package dev.piko.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.piko.desktop.ui.player.VideoPlayerWindow
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.ui.PikoApp
import dev.piko.ui.PikoServices
import dev.piko.ui.VideoPlayerHost
import dev.piko.ui.VideoPlayerRequest
import dev.piko.ui.theme.appearanceFlow
import java.awt.Dimension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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

    val settings = DesktopSettingsStore()
    val preferences = DesktopPikoPreferences(settings)
    val platform = DesktopPikoPlatform(settings)
    val services = createServices(settings, preferences)
    magnetArg?.let(services.instantMagnetRepository::onIncomingMagnet)

    val appearanceFlow = preferences.appearanceFlow(platform.supportsDynamicColor)
    // 偏好在内存里，同步读很快；先读好再开窗，首帧就是用户选的主题
    val initialAppearance = runBlocking { appearanceFlow.first() }

    application {
        // 任务栏/标题栏图标：desktopMain/resources/app-icon.png（docs/icon.svg 同源）。
        val appIcon = remember {
            object {}.javaClass.getResourceAsStream("/app-icon.png")
                ?.use { BitmapPainter(loadImageBitmap(it)) }
        }
        val appearance by appearanceFlow.collectAsState(initialAppearance)
        val players = remember { mutableStateListOf<VideoPlayerRequest>() }
        val videoPlayer = remember { VideoPlayerHost.Detached { players += it } }

        DownloadNotifications(services.downloadManager)

        Window(
            onCloseRequest = ::exitApplication,
            title = "Piko",
            icon = appIcon,
            state = rememberWindowState(width = 1120.dp, height = 760.dp),
        ) {
            // 再窄就放不下 compact 布局的底部导航与列表了；宽度下限等于一台窄手机
            LaunchedEffect(Unit) { window.minimumSize = Dimension(360, 560) }
            PikoApp(
                services = services,
                platform = platform,
                appearance = appearance,
                videoPlayer = videoPlayer,
            )
        }

        // 每个播放请求一个独立窗口，可以边播边浏览网盘
        players.forEach { request ->
            key(request) {
                VideoPlayerWindow(
                    request = request,
                    mediaRepository = services.mediaRepository,
                    downloadCoordinator = services.downloadManager,
                    appearance = appearance,
                    onClose = { players.remove(request) },
                )
            }
        }
    }
}

private fun createServices(settings: DesktopSettingsStore, preferences: DesktopPikoPreferences): PikoServices {
    // 进程级作用域，与 Android 的 appScope 对应：下载与会话刷新不随某个窗口的组合结束
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clientManager = PikoClientManager(FilePikoSessionStore(), appScope)
    val mediaRepository = PikoMediaRepository(clientManager, preferences)
    return PikoServices(
        preferences = preferences,
        clientManager = clientManager,
        mediaRepository = mediaRepository,
        downloadManager = PikoDownloadCoordinator(
            clientManager,
            preferences,
            DesktopPikoDownloadStorage { settings.downloadDirectory },
            appScope,
            segmentDownloader = DesktopPikoSegmentDownloader(),
            mediaRepository = mediaRepository,
        ),
    )
}

/** 下载完成或失败时发系统 Toast。应用内的列表已有状态，Toast 是给切到别处的人看的。 */
@Composable
private fun DownloadNotifications(downloadCoordinator: PikoDownloadCoordinator) {
    LaunchedEffect(downloadCoordinator) {
        var previousStatuses = emptyMap<String, DownloadStatus>()
        downloadCoordinator.tasks.collect { tasks ->
            tasks.forEach { (id, task) ->
                val previous = previousStatuses[id]
                if (previous == null || previous == task.status) return@forEach
                val (title, message) = when (task.status) {
                    DownloadStatus.COMPLETED -> "下载完成" to "${task.fileName} 已下载到本机"
                    DownloadStatus.FAILED -> "下载失败" to "${task.fileName}：${task.errorMessage ?: "未知错误"}"
                    else -> return@forEach
                }
                // Toast 在 WinRT 专用线程上同步等结果，最长 15 秒，不能压在界面线程上
                withContext(Dispatchers.IO) { WinRTSupport.showNotification(title, message) }
            }
            previousStatuses = tasks.mapValues { it.value.status }
        }
    }
}
