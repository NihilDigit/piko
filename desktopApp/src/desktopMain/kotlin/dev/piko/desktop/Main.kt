package dev.piko.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Tray
import dev.piko.desktop.ui.player.VideoPlayerWindow
import dev.piko.desktop.update.WindowsInstaller
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.UploadStatus
import dev.piko.shared.upload.UploadTask
import dev.piko.ui.PikoApp
import dev.piko.ui.PikoServices
import dev.piko.ui.VideoPlayerHost
import dev.piko.ui.VideoPlayerRequest
import dev.piko.ui.theme.appearanceFlow
import dev.piko.ui.theme.isDark
import java.awt.Dimension
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.openani.mediamp.mpv.MpvMediampPlayer

/**
 * 打包 release 时 Compose 以这个系统属性跑一遍 AOT 训练。训练进程要自己退出，JVM 在退出时
 * 写出 AOT 缓存；给它足够的时间走完开窗、首屏与列表这段启动路径。
 */
private const val AOT_TRAINING_PROPERTY = "compose.aot.training-run"
private const val AOT_TRAINING_MILLIS = 12_000L

fun main(args: Array<String>) {
    val isAotTraining = System.getProperty(AOT_TRAINING_PROPERTY) == "true"
    if (isAotTraining) {
        Thread({ Thread.sleep(AOT_TRAINING_MILLIS); exitProcess(0) }, "Piko-Aot-Training")
            .apply { isDaemon = true; start() }
    }
    // 打包机上可能正开着一个 Piko，训练进程不能把自己当成后来者转交后退出
    val singleInstance = if (isAotTraining) null else SingleInstance.acquireOrForward(args.toList()) ?: return
    if (WinRTSupport.isWindows) {
        // 进程级 AUMID 必须在建窗口/发 Toast 之前设置；协议注册放后台线程，不挡启动。
        runCatching { WinRTSupport.ensureAppUserModelId() }
        Thread(
            {
                // 只有 MSI 装的那份写登记：便携版、测试镜像与 gradle run 写的话，会盖掉安装版的协议与通知图标
                val installed = runCatching { WindowsInstaller.installedExecutable() }.getOrNull() ?: return@Thread
                runCatching { WinRTSupport.ensureMagnetProtocolHandler(installed) }
                // 图标随安装包放在资源目录里，与窗口图标同源
                val icon = System.getProperty("compose.application.resources.dir")?.let { File(it, "app-icon.png") }
                runCatching { WinRTSupport.ensureNotificationRegistration(icon) }
            },
            "Piko-Win32-Setup",
        ).apply { isDaemon = true; start() }
    }

    useBundledMpvRuntime()

    val settings = DesktopSettingsStore()
    val preferences = DesktopPikoPreferences(settings)
    val platform = DesktopPikoPlatform(settings)
    val services = createServices(settings, preferences)
    // magnet: 链接经 MSI 注册的协议唤起时，URL 以启动参数进来；已在运行时由后来的进程转交过来
    magnetIn(args.toList())?.let(services.instantMagnetRepository::onIncomingMagnet)
    val activations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    singleInstance?.listen { forwarded ->
        magnetIn(forwarded)?.let(services.instantMagnetRepository::onIncomingMagnet)
        activations.tryEmit(Unit)
    }

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
        val downloads by services.downloadManager.tasks.collectAsState()
        val uploads by services.uploadManager.tasks.collectAsState()
        val account = services.clientManager.currentClient.collectAsState().value?.account
        val hasActiveTransfers = downloads.values.any { it.status.isActive } || uploads.values.anyActiveFor(account)
        // 关窗时下载或上传还在跑，就藏进托盘传完再退出；传完之前随时可以从托盘叫回来或直接退出
        var isInBackground by remember { mutableStateOf(false) }

        DownloadNotifications(services.downloadManager)
        UploadNotifications(services.uploadManager, services.clientManager)

        LaunchedEffect(isInBackground, hasActiveTransfers) {
            if (isInBackground && !hasActiveTransfers) exitApplication()
        }
        // 更新脚本等本进程退出后才替换文件。下载会被中断，更新对话框已先征得用户同意
        LaunchedEffect(Unit) {
            platform.updater.exitRequests.collect { exitApplication() }
        }

        if (isInBackground) {
            Tray(
                icon = appIcon ?: BitmapPainter(ImageBitmap(16, 16)),
                tooltip = backgroundTooltip(downloads.values, uploads.values.filter { it.account == account }),
                onAction = { isInBackground = false },
                menu = {
                    Item("显示 Piko", onClick = { isInBackground = false })
                    Item("立即退出", onClick = ::exitApplication)
                },
            )
        }

        Window(
            onCloseRequest = {
                if (hasActiveTransfers) {
                    isInBackground = true
                    // Toast 同步等系统结果，不能压在界面线程上
                    Thread {
                        WinRTSupport.showNotification("Piko 在后台继续传输", "传输完成后自动退出，可从通知区域图标重新打开。")
                    }.start()
                } else {
                    exitApplication()
                }
            },
            visible = !isInBackground,
            title = "Piko",
            icon = appIcon,
            state = rememberRememberedWindowState(settings, "main", DpSize(1120.dp, 760.dp)),
        ) {
            // 再窄就放不下 compact 布局的底部导航与列表了；宽度下限等于一台窄手机
            LaunchedEffect(Unit) { window.minimumSize = Dimension(360, 560) }
            TitleBarThemeEffect(window, appearance.isDark())
            TaskbarDownloadProgress(window, services.downloadManager)
            LaunchedEffect(Unit) {
                activations.collect {
                    isInBackground = false
                    bringToFront(window)
                }
            }
            MagnetDropTarget(
                platform = platform,
                appearance = appearance,
                onMagnet = services.instantMagnetRepository::onIncomingMagnet,
                onUpload = services.uploadManager::request,
            ) {
                PikoApp(
                    services = services,
                    platform = platform,
                    appearance = appearance,
                    videoPlayer = videoPlayer,
                )
            }
        }

        // 每个播放请求一个独立窗口，可以边播边浏览网盘
        players.forEach { request ->
            key(request) {
                VideoPlayerWindow(
                    request = request,
                    services = services,
                    platform = platform,
                    settings = settings,
                    appearance = appearance,
                    icon = appIcon,
                    onClose = { players.remove(request) },
                )
            }
        }
    }
}

private val DownloadStatus.isActive: Boolean
    get() = this == DownloadStatus.DOWNLOADING || this == DownloadStatus.PENDING

/**
 * 上传队列只跑当前账号的任务，换号时正在传的转为暂停，其余账号排着的一直是 QUEUED。
 * 不按账号过滤的话，换过号就永远有活跃任务，藏进托盘后不会退出。
 */
private fun Collection<UploadTask>.anyActiveFor(account: String?): Boolean =
    any { it.account == account && it.status.isActive }

/**
 * 启动参数里的链接：magnet: 经注册的协议唤起时进来；分享链接没法注册成协议（https 归浏览器），
 * 但用命令行或快捷方式带着它启动时也认。交给添加链接面板，由它分辨两者。
 */
private fun magnetIn(args: List<String>): String? = args.firstOrNull {
    it.startsWith("magnet:", ignoreCase = true) || InstantSheetState.findShareLink(it) != null
}

private fun backgroundTooltip(downloads: Collection<DownloadTask>, uploads: Collection<UploadTask>): String {
    val activeDownloads = downloads.filter { it.status.isActive }
    val activeUploads = uploads.filter { it.status.isActive }
    val parts = listOfNotNull(
        transferSummary("下载", activeDownloads.size, activeDownloads.sumOf { it.downloadedBytes }, activeDownloads.sumOf { it.totalBytes }),
        transferSummary("上传", activeUploads.size, activeUploads.sumOf { it.processedBytes }, activeUploads.sumOf { it.size }),
    )
    return "Piko：" + parts.joinToString("；").ifEmpty { "正在后台传输" }
}

private fun transferSummary(verb: String, count: Int, doneBytes: Long, totalBytes: Long): String? = when {
    count == 0 -> null
    totalBytes <= 0 -> "正在${verb} $count 个文件"
    else -> "正在${verb} $count 个文件，${doneBytes * 100 / totalBytes}%"
}

/**
 * Windows 不让后台进程抢前台，toFront 通常只让任务栏图标闪烁。后来的进程转交参数前已放开
 * 前台权限（见 [SingleInstance]），这里还要先取消最小化，否则窗口在任务栏里不会弹出来。
 */
private fun bringToFront(window: java.awt.Frame) {
    if (window.extendedState and java.awt.Frame.ICONIFIED != 0) {
        window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
    }
    window.toFront()
    window.requestFocus()
}

/**
 * 安装包把 mpv 与 FFmpeg 的 DLL 放在资源目录的 mpv 子目录里，这里指给 mediamp，免得它每次
 * 首次播放都把 DLL 从 jar 解压到新的临时目录。资源目录里没有时（测试进程）沿用它的默认行为。
 */
private fun useBundledMpvRuntime() {
    val dir = System.getProperty("compose.application.resources.dir")?.let { File(it, "mpv") } ?: return
    if (!dir.resolve("mediampv.dll").isFile) return
    // 设置目录时 mediamp 会校验并加载封装层 DLL，连带 mpv 与 FFmpeg 一串依赖，放后台线程，不挡开窗
    Thread(
        { runCatching { MpvMediampPlayer.prepareLibraries(dir.absolutePath, false) } },
        "Piko-Mpv-Setup",
    ).apply { isDaemon = true; start() }
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
        uploadSources = DesktopPikoUploadSources(),
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

/**
 * 上传完成或失败时发 Toast。一个文件夹常有成百个文件，秒传时一秒完成好几个，逐个发会刷屏，
 * 所以攒到队列排空时汇总成一条；只有一个文件时写出文件名。
 */
@Composable
private fun UploadNotifications(uploadCoordinator: PikoUploadCoordinator, clientManager: PikoClientManager) {
    LaunchedEffect(uploadCoordinator) {
        val startedAtMs = System.currentTimeMillis()
        var previousStatuses = emptyMap<String, UploadStatus>()
        val finished = mutableMapOf<String, UploadTask>()
        uploadCoordinator.tasks.collect { tasks ->
            tasks.forEach { (id, task) ->
                val previous = previousStatuses[id]
                if (previous == task.status) return@forEach
                // 没见过的任务有两种：启动时读回的旧记录，不该报；刚入队就在两次收集之间秒传完的，要报
                if (previous == null && task.createdAtMs < startedAtMs) return@forEach
                if (task.status == UploadStatus.COMPLETED || task.status == UploadStatus.FAILED) {
                    finished[id] = task
                } else {
                    // 失败后重试，等它再次结束
                    finished -= id
                }
            }
            finished.keys.retainAll(tasks.keys)
            previousStatuses = tasks.mapValues { it.value.status }
            if (finished.isEmpty() || tasks.values.anyActiveFor(clientManager.currentClient.value?.account)) return@collect
            val (title, message) = uploadSummary(finished.values.toList())
            finished.clear()
            // Toast 在 WinRT 专用线程上同步等结果，最长 15 秒，不能压在界面线程上
            withContext(Dispatchers.IO) { WinRTSupport.showNotification(title, message) }
        }
    }
}

private fun uploadSummary(tasks: List<UploadTask>): Pair<String, String> {
    val completed = tasks.filter { it.status == UploadStatus.COMPLETED }
    val failed = tasks.filter { it.status == UploadStatus.FAILED }
    tasks.singleOrNull()?.let { task ->
        return when {
            failed.isNotEmpty() -> "上传失败" to "${task.fileName}：${task.errorMessage ?: "未知错误"}"
            task.isInstant -> "秒传完成" to "${task.fileName} 已秒传到「${task.parentName}」"
            else -> "上传完成" to "${task.fileName} 已上传到「${task.parentName}」"
        }
    }
    val title = when {
        failed.isEmpty() -> "上传完成"
        completed.isEmpty() -> "上传失败"
        else -> "上传结束"
    }
    val instantCount = completed.count { it.isInstant }
    val completedPart = when {
        completed.isEmpty() -> null
        instantCount == completed.size -> "${completed.size} 个文件已秒传"
        instantCount > 0 -> "${completed.size} 个文件已上传，其中 $instantCount 个秒传"
        else -> "${completed.size} 个文件已上传"
    }
    val failedPart = failed.takeIf { it.isNotEmpty() }?.let { "${it.size} 个失败" }
    return title to listOfNotNull(completedPart, failedPart).joinToString("；")
}
