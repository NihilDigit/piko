package dev.piko.desktop

import androidx.compose.material3.MaterialTheme
import dev.piko.shared.PikoHome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.awaitApplication
import dev.piko.desktop.motion.SystemReducedMotion
import dev.piko.ui.theme.PikoMotionScale
import androidx.compose.ui.window.Tray
import dev.piko.desktop.ui.clips.ClipFeedWindow
import dev.piko.desktop.ui.player.VideoPlayerWindow
import dev.piko.desktop.ui.player.MpvLogBridge
import dev.piko.desktop.update.WindowsInstaller
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.desktop.winrt.WindowsLinkAssociation
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.FilePikoCacheStore
import dev.piko.shared.sync.JvmSyncCipher
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.TorrentMagnet
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.log.LogLevel
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.FileClipCache
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.net.PikoProxySelector
import dev.piko.shared.upload.UploadTask
import dev.piko.ui.PikoApp
import dev.piko.ui.PikoServices
import dev.piko.ui.ClipFeedLinks
import dev.piko.ui.VideoPlayerHost
import dev.piko.ui.VideoPlayerRequest
import dev.piko.ui.anyActiveFor
import dev.piko.ui.workNotices
import dev.piko.ui.components.LocalAntiDiagonalResizeCursor
import dev.piko.ui.components.LocalDiagonalResizeCursor
import dev.piko.ui.components.LocalHorizontalResizeCursor
import dev.piko.ui.components.LocalVerticalResizeCursor
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.PikoTheme
import dev.piko.ui.theme.frame
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 打包 release 时 Compose 以这个系统属性跑一遍 AOT 训练。训练进程要自己退出，JVM 在退出时
 * 写出 AOT 缓存；给它足够的时间走完开窗、首屏与列表这段启动路径。
 */
private const val AOT_TRAINING_PROPERTY = "compose.aot.training-run"
private const val AOT_TRAINING_MILLIS = 12_000L

fun main(args: Array<String>) {
    // 最先做：之后任何一处碰到 JNA 都会让它按当时的属性初始化
    useBundledJnaDispatch()
    // 走系统代理：JVM 默认只认 http.proxyHost 一类属性，不看 Windows 与 macOS 设置里的代理，
    // 在系统里开了代理（例如代理软件的「系统代理」模式）也照样直连。这个属性在 ProxySelector
    // 首次初始化时读取，所以放在一切网络请求之前。Android 不用设，系统会把网络的代理同步给进程
    System.setProperty("java.net.useSystemProxies", "true")
    val isAotTraining = System.getProperty(AOT_TRAINING_PROPERTY) == "true"
    if (isAotTraining) {
        Thread({ Thread.sleep(AOT_TRAINING_MILLIS); exitProcess(0) }, "Piko-Aot-Training")
            .apply { isDaemon = true; start() }
    }
    System.getProperty(SELF_TEST_PROPERTY)?.let { exitProcess(runSelfTest(it)) }
    // 打包机上可能正开着一个 Piko，训练进程不能把自己当成后来者转交后退出
    val singleInstance = if (isAotTraining) null else SingleInstance.acquireOrForward(absoluteTorrentPaths(args.toList())) ?: return
    // 拿到单实例锁之后才装：转交完参数就退出的后来者不该和主实例写同一个文件
    installLog()
    restoreClasspathTimes()
    // 赶在建窗口之前，理由见 setWmClass
    if (isLinux) LinuxDesktop.setWmClass()
    if (WinRTSupport.isWindows) {
        // 进程级 AUMID 必须在建窗口/发 Toast 之前设置；通知登记放后台线程，不挡启动。
        // magnet 与种子的关联不在这里静默写入：那会每次启动都抢走别的下载工具的协议，改为首次启动时询问，
        // 见 LinkAssociationPrompt
        runCatching { WinRTSupport.ensureAppUserModelId() }
        Thread(
            {
                // 便携版同样有旧名缓存，放在只限安装版的登记之前
                WindowsLinkAssociation.forgetStaleName()
                // 只有 MSI 装的那份写登记：便携版、测试镜像与 gradle run 写的话，会盖掉安装版的通知图标
                runCatching { WindowsInstaller.installedExecutable() }.getOrNull() ?: return@Thread
                // 图标随安装包放在资源目录里，与窗口图标同源
                val icon = System.getProperty("compose.application.resources.dir")?.let { File(it, "app-icon.png") }
                runCatching { WinRTSupport.ensureNotificationRegistration(icon) }
            },
            "Piko-Win32-Setup",
        ).apply { isDaemon = true; start() }
    }

    BundledMpvRuntime.prepareInBackground()
    installImageLoader()

    val settings = DesktopSettingsStore()
    val preferences = DesktopPikoPreferences(settings, ::desktopPreferenceSecrets)
    // 赶在任何 OkHttpClient 建出来之前，理由见 PikoProxySelector
    PikoProxySelector.install(runBlocking { preferences.proxySettingFlow.first() })
    CoroutineScope(Dispatchers.Default).launch { preferences.proxySettingFlow.collect(PikoProxySelector::apply) }
    val motionScale = PikoMotionScale()
    SystemReducedMotion.follow(motionScale)
    motionScale.appReduced = runBlocking { preferences.reduceMotionFlow.first() }
    CoroutineScope(Dispatchers.Default).launch { preferences.reduceMotionFlow.collect { motionScale.appReduced = it } }
    val platform = DesktopPikoPlatform(settings, motionScale)
    val services = createServices(settings, preferences)
    // 外面送来的链接交给添加链接面板，四条路进来：启动参数、后来的进程转交、macOS 的 openURI 与 openFiles。
    // 记一行日志，只记哪一类、从哪条路来，不记链接本身：安装冒烟据此确认系统真把链接交给了 Piko
    fun deliverIncoming(args: List<String>, via: String): Boolean {
        val link = magnetIn(args) ?: return false
        PikoLog.i("IncomingLink", "收到外部链接：${linkKindOf(args)}，经$via")
        services.instantMagnetRepository.onIncomingMagnet(link)
        return true
    }
    // magnet: 链接与种子经登记的关联唤起时，以启动参数进来；已在运行时由后来的进程转交过来
    val launchedWithLink = deliverIncoming(args.toList(), "启动参数")
    // 带着链接启动时添加链接面板正要弹出，不再叠一个询问框，留到下次普通启动再问；训练进程没人回答
    val askLinkAssociation = !launchedWithLink && !isAotTraining
    val activations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    singleInstance?.listen { forwarded ->
        deliverIncoming(forwarded, "转交")
        activations.tryEmit(Unit)
    }
    val quitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    installMacHandlers(
        onOpenUri = { uri ->
            deliverIncoming(listOf(uri), "openURI")
            activations.tryEmit(Unit)
        },
        // 种子要读文件再算 infohash，回调在界面线程上，挪到后台做
        onOpenFiles = { files ->
            CoroutineScope(Dispatchers.IO).launch {
                deliverIncoming(files.map { it.absolutePath }, "openFiles")
                activations.tryEmit(Unit)
            }
        },
        onQuit = { quitRequests.tryEmit(Unit) },
        onReopen = { activations.tryEmit(Unit) },
    )

    val appearanceFlow = preferences.appearanceFlow(platform.supportsDynamicColor)
    // 偏好在内存里，同步读很快；先读好再开窗，首帧就是用户选的主题
    val initialAppearance = runBlocking { appearanceFlow.first() }

    // 与 application {} 相同，只是给根协程带上动画时长缩放：应用的 Recomposer 取这个上下文，各窗口（主窗口、
    // 播放器、信息流）的 Recomposer 又取自应用组合里的协程作用域，弹层与对话框共用所在窗口的，一路都带着它。
    // Compose Desktop 自己不提供 MotionDurationScale，不注入的话减少动画无从生效
    runBlocking(motionScale) { awaitApplication {
        // 任务栏/标题栏图标：desktopMain/resources/app-icon.png（docs/icon.svg 同源）。
        val appIcon = remember {
            object {}.javaClass.getResourceAsStream("/app-icon.png")
                ?.use { BitmapPainter(loadImageBitmap(it)) }
        }
        val appearance by appearanceFlow.collectAsState(initialAppearance)
        val players = remember { mutableStateListOf<VideoPlayerRequest>() }
        // 信息流从网盘页弹出，只开一个窗口；已开着时再弹一次是把它调到前台。开着的这段时间应用内的侧栏收起，
        // 收回时再出现；关窗是关掉信息流，见 ClipFeedLinks
        var clipFeed by remember { mutableStateOf<ClipFeedLinks?>(null) }
        var clipFeedRaises by remember { mutableIntStateOf(0) }
        var mainWindow by remember { mutableStateOf<java.awt.Frame?>(null) }
        var portableFallback by remember { mutableStateOf(pendingPortableFallback(settings)) }
        // 播放器与信息流窗口等 mpv 运行库准备好再建，理由见 BundledMpvRuntime；启动后立刻打开的先排着
        val mpvReady by produceState(BundledMpvRuntime.isPrepared) {
            withContext(Dispatchers.IO) { BundledMpvRuntime.ensure() }
            value = true
        }
        val videoPlayer = remember {
            VideoPlayerHost.Detached(
                open = { players += it },
                openClipFeed = {
                    clipFeed = it
                    clipFeedRaises++
                },
                isClipFeedOpen = { clipFeed != null },
                closeClipFeed = { clipFeed = null },
            )
        }
        val downloads by services.downloadManager.tasks.collectAsState()
        val uploads by services.uploadManager.tasks.collectAsState()
        val account = services.clientManager.currentClient.collectAsState().value?.account
        val hasActiveTransfers = downloads.values.any { it.status.isActive } || uploads.values.anyActiveFor(account)
        // 关窗时下载或上传还在跑，就藏进托盘传完再退出；传完之前随时可以从托盘叫回来或直接退出
        var isInBackground by remember { mutableStateOf(false) }
        var isMainWindowFocused by remember { mutableStateOf(true) }

        // 主窗口在前台时列表与 Snackbar 已经说明了，不再弹 Toast
        WorkNotifications(services) { isInBackground || !isMainWindowFocused }

        LaunchedEffect(isInBackground, hasActiveTransfers) {
            if (isInBackground && !hasActiveTransfers) exitApplication()
        }
        // 更新脚本等本进程退出后才替换文件。下载会被中断，更新对话框已先征得用户同意
        LaunchedEffect(Unit) {
            platform.updater?.exitRequests?.collect { exitApplication() }
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

        val closeMainWindow = {
            if (hasActiveTransfers) {
                isInBackground = true
                // Toast 同步等系统结果，不能压在界面线程上
                Thread {
                    // macOS 的托盘图标在菜单栏；Linux 未必有托盘
                    val reopenHint = when {
                        isMacOs -> "可从菜单栏图标重新打开"
                        isLinux -> LinuxDesktop.backgroundHint()
                        else -> "可从通知区域图标重新打开"
                    }
                    showSystemNotification("Piko 在后台继续传输", "传输完成后自动退出，$reopenHint。")
                }.start()
            } else {
                exitApplication()
            }
        }
        val currentCloseMainWindow by rememberUpdatedState(closeMainWindow)
        // macOS 的 Cmd+Q：窗口还开着时同关窗；已经藏进后台再退出，是明确要结束传输
        LaunchedEffect(Unit) {
            quitRequests.collect { if (isInBackground) exitApplication() else currentCloseMainWindow() }
        }

        val mainWindowState = rememberRememberedWindowState(settings, "main", DpSize(1120.dp, 760.dp))
        PikoWindow(
            onCloseRequest = closeMainWindow,
            visible = !isInBackground,
            title = "Piko",
            icon = appIcon,
            state = mainWindowState,
        ) {
            // 再窄就放不下 compact 布局的底部导航与列表了；宽度下限等于一台窄手机
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(360, 560)
                mainWindow = window
            }
            val focused = LocalWindowInfo.current.isWindowFocused
            SideEffect { isMainWindowFocused = focused }
            TitleBarThemeEffect(window, appearance.isDark())
            PixelAlignedContentEffect(window)
            TaskbarDownloadProgress(window, services.downloadManager)
            LaunchedEffect(Unit) {
                activations.collect {
                    isInBackground = false
                    bringToFront(window)
                }
            }
            // 标题栏在 PikoApp 之外，主题要自己再套一层；PikoTheme 读平台字体，平台也要先提供
            CompositionLocalProvider(
                LocalPikoPlatform provides platform,
                LocalHorizontalResizeCursor provides HorizontalResizeCursor,
                LocalVerticalResizeCursor provides VerticalResizeCursor,
                LocalDiagonalResizeCursor provides DiagonalResizeCursor,
                LocalAntiDiagonalResizeCursor provides AntiDiagonalResizeCursor,
            ) {
                PikoTheme(appearance = appearance) {
                    // 与侧边栏、网盘页页眉同为外框色：桌面主界面任何宽度都有侧边栏，见 FormFactor
                    val colors = MaterialTheme.colorScheme
                    // 开着也只在有侧边栏的界面上收起标题栏，由界面声明（WindowCaption.Host），窄窗口与登录页仍有标题栏
                    val compactTitleBar = platform.compactTitleBar?.enabled?.collectAsState()?.value == true
                    WindowFrame(
                        title = "Piko",
                        icon = appIcon,
                        colors = TitleBarColors(colors.frame, colors.onSurface),
                        compactCaption = compactTitleBar,
                        // macOS 的全屏空间里系统收起了红绿灯，标题栏也跟着让出来
                        showTitleBar = mainWindowState.placement != WindowPlacement.Fullscreen,
                    ) {
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
                    // 两个对话框不叠在一起：先说数据去了哪里，关掉之后才问关联
                    val fallback = portableFallback
                    if (fallback != null) {
                        PortableFallbackNotice(fallback, settings, onDismiss = { portableFallback = null })
                    } else if (askLinkAssociation) {
                        LinkAssociationPrompt(platform.linkAssociation, settings)
                    }
                }
            }
        }

        if (mpvReady) clipFeed?.let { links ->
            ClipFeedWindow(
                links = ClipFeedLinks(
                    playFull = links.playFull,
                    // 位置在主窗口里，主窗口可能被片段窗口盖着或藏在托盘
                    locate = { file ->
                        isInBackground = false
                        links.locate(file)
                        mainWindow?.let(::bringToFront)
                    },
                    dock = {
                        links.dock()
                        isInBackground = false
                        mainWindow?.let(::bringToFront)
                    },
                    close = links.close,
                ),
                raise = clipFeedRaises,
                services = services,
                platform = platform,
                settings = settings,
                appearance = appearance,
                icon = appIcon,
            )
        }

        // 每个播放请求一个独立窗口，可以边播边浏览网盘
        if (mpvReady) players.forEach { request ->
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
    } }
    // 照 application {} 的默认做法当场结束进程，否则关窗后进程还要挂一到四秒才退
    exitProcess(0)
}

// 侧栏拖宽处的悬停光标。公共代码里的 PointerIcon 没有调整大小这一种，见 LocalHorizontalResizeCursor
private val HorizontalResizeCursor = PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR))

// 浮动面板（目录图）上下两边与四个角的悬停光标，见 FloatingPanel 的 ResizeHandles
private val VerticalResizeCursor = PointerIcon(java.awt.Cursor(java.awt.Cursor.N_RESIZE_CURSOR))
private val DiagonalResizeCursor = PointerIcon(java.awt.Cursor(java.awt.Cursor.NW_RESIZE_CURSOR))
private val AntiDiagonalResizeCursor = PointerIcon(java.awt.Cursor(java.awt.Cursor.NE_RESIZE_CURSOR))

private val DownloadStatus.isActive: Boolean
    get() = this == DownloadStatus.DOWNLOADING || this == DownloadStatus.PENDING

/**
 * 启动参数里的链接：magnet: 经注册的协议唤起时进来；分享链接没法注册成协议（https 归浏览器），
 * 但用命令行或快捷方式带着它启动时也认。交给添加链接面板，由它分辨两者。
 * 双击关联到 Piko 的 .torrent 文件时进来的是文件路径，与拖进窗口一样在本地换算成磁力链接。
 */
private fun magnetIn(args: List<String>): String? {
    args.firstOrNull { it.startsWith("magnet:", ignoreCase = true) || InstantSheetState.findShareLink(it) != null }
        ?.let { return it }
    val magnets = args.filter(::isTorrentPath).mapNotNull { TorrentMagnet.fromFile(File(it)) }
    return magnets.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

private fun isTorrentPath(arg: String): Boolean = arg.endsWith(".torrent", ignoreCase = true) && File(arg).isFile

/** 日志里记的链接类别，与 [magnetIn] 认的顺序一致。 */
private fun linkKindOf(args: List<String>): String = when {
    args.any { it.startsWith("magnet:", ignoreCase = true) } -> "magnet"
    args.any { InstantSheetState.findShareLink(it) != null } -> "share"
    else -> "torrent"
}

/** 相对路径按本进程的工作目录解析，转交给主实例之前先换成绝对路径，主实例的工作目录未必相同。 */
private fun absoluteTorrentPaths(args: List<String>): List<String> =
    args.map { if (isTorrentPath(it)) File(it).absolutePath else it }

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
internal fun bringToFront(window: java.awt.Frame) {
    if (window.extendedState and java.awt.Frame.ICONIFIED != 0) {
        window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
    }
    window.toFront()
    window.requestFocus()
}

private fun createServices(settings: DesktopSettingsStore, preferences: DesktopPikoPreferences): PikoServices {
    // 进程级作用域，与 Android 的 appScope 对应：下载与会话刷新不随某个窗口的组合结束
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clientManager = PikoClientManager(desktopSessionStore(), appScope)
    val mediaRepository = PikoMediaRepository(
        clientManager,
        preferences,
        clipCache = FileClipCache(PikoHome.root.resolve("cache/clip-cache").toFile()),
    )
    return PikoServices(
        platformPreferences = preferences,
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
        cacheStore = FilePikoCacheStore(PikoHome.root.resolve("cache").toString()),
        syncCipher = JvmSyncCipher(),
    )
}

/**
 * 下载、上传、解压、查找重复结束时发系统 Toast，汇总逻辑见 workNotices。[shouldNotify] 在发送那一刻判断：
 * 主窗口在前台时列表与 Snackbar 已经说明了。
 */
@Composable
private fun WorkNotifications(services: PikoServices, shouldNotify: () -> Boolean) {
    val currentShouldNotify by rememberUpdatedState(shouldNotify)
    LaunchedEffect(services) {
        services.workNotices().collect { notice ->
            if (!currentShouldNotify()) return@collect
            // Toast 在 WinRT 专用线程上同步等结果，最长 15 秒，不能压在界面线程上
            withContext(Dispatchers.IO) { showSystemNotification(notice.title, notice.message) }
        }
    }
}

/** 日志在数据根目录的 logs 下（PikoHome）。警告以上同时进标准错误，gradle run 时看得到；安装版的标准错误无人读，代价只是一次写入。 */
private fun installLog() {
    PikoLog.install(PikoHome.root.resolve("logs").toString()) { level, tag, message, error ->
        if (level >= LogLevel.WARN) System.err.println("Piko/$tag: $message" + (error?.let { "\n" + it.stackTraceToString() } ?: ""))
    }
    PikoHome.portableFallback?.let { PikoLog.w("App", "便携目录不可写（${it.problem.javaClass.simpleName}），数据改放用户目录") }
    MpvLogBridge.install()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        PikoLog.e("Crash", "线程 ${thread.name} 未捕获的异常", error)
        runBlocking { withTimeoutOrNull(1_000) { PikoLog.flush() } }
        previous?.uncaughtException(thread, error) ?: error.printStackTrace()
    }
    PikoLog.i(
        "App",
        "启动 ${System.getProperty("jpackage.app-version") ?: "开发版"}，${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
    )
    HardwareReport.logInBackground()
}

private fun showSystemNotification(title: String, message: String): Boolean = when {
    isMacOs -> MacOs.showNotification(title, message)
    isLinux -> LinuxDesktop.showNotification(title, message)
    else -> WinRTSupport.showNotification(title, message)
}
