@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.piko.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.log.LogLevel
import dev.piko.shared.log.PikoLog
import dev.piko.shared.memory.MemoryTrim
import dev.piko.shared.network.createIosAuxiliaryHttpClient
import dev.piko.shared.storage.SandboxPaths
import dev.piko.shared.storage.createIosAppDirectories
import dev.piko.ui.PikoApp
import dev.piko.ui.PikoServices
import dev.piko.ui.image.installIosImageLoader
import dev.piko.ui.platform.IosPikoPlatform
import dev.piko.ui.theme.appearanceFlow
import kotlin.native.Platform
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

/**
 * Xcode 工程的入口：Swift 侧 `MainViewControllerKt.MainViewController()` 拿到这个 UIViewController，
 * 交给 SwiftUI 的 `UIViewControllerRepresentable` 或直接设成 window 的 root。
 *
 * 服务是进程级的：Compose 视图重建（旋转、切前台）不会重建 [PikoServices]，与 Android 的 Application
 * 作用域一致。日志装一次，写进应用私有 support 目录的 logs，`导出日志` 也读这一份。
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    IosRoot(
        services = IosProcess.services,
        platform = IosProcess.platform,
    )
}

/** UIKit entrypoints run on the main thread; service ownership outlives every controller. */
private object IosProcess {
    init { PikoLocale.bindSystemLocale(NSLocale.currentLocale.localeIdentifier) }
    val directories = createIosAppDirectories()
    val keychainService = NSBundle.mainBundle.bundleIdentifier ?: DEFAULT_KEYCHAIN_SERVICE
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val imageHttpClient = createIosAuxiliaryHttpClient()

    init {
        val isDebug = Platform.isDebugBinary
        PikoLog.install(SandboxPaths.resolveDirectory(directories.support, "logs")) { level, tag, message, error ->
            if (isDebug || level >= LogLevel.WARN) {
                println("Piko/$tag: $message" + (error?.let { "\n" + PikoLog.redact(it.stackTraceToString()) } ?: ""))
            }
        }
        var previousHook: ReportUnhandledExceptionHook? = null
        previousHook = setUnhandledExceptionHook { error ->
            PikoLog.e("Crash", "Uncaught exception in iOS process", error)
            runBlocking { withTimeoutOrNull(1_000) { PikoLog.flush() } }
            val prev = previousHook
            if (prev != null) prev(error) else terminateWithUnhandledException(error)
        }
        // 在首个图片请求前显式安装 Coil Ktor3 Darwin 网络层与定向磁盘缓存
        installIosImageLoader(
            httpClient = imageHttpClient,
            imageCacheDir = SandboxPaths.resolveDirectory(directories.caches, "image_cache"),
        )
        val appVersion = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
            ?.takeIf { it.isNotBlank() } ?: "dev"
        val device = UIDevice.currentDevice
        PikoLog.i("App", "App started: Piko $appVersion on ${device.systemName} ${device.systemVersion} (${device.model})")
    }

    val services = createIosServices(directories, appScope, keychainService)
    val platform = IosPikoPlatform(directories)
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val videoPlayer = iosVideoPlayerHost(services, playerScope, platform = platform)
}

@Composable
private fun IosRoot(
    services: PikoServices,
    platform: IosPikoPlatform,
) {
    DisposableEffect(platform, services) {
        platform.refreshSystemMotion()
        val center = NSNotificationCenter.defaultCenter
        val activeObserver = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            platform.refreshSystemMotion()
            services.memoryGovernor.onVisibilityChanged(true)
            services.onAppForegrounded()
        }
        val backgroundObserver = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            services.memoryGovernor.onVisibilityChanged(false)
            IosProcess.appScope.launch {
                services.mediaRepository.flushPlayHistory(2_000L)
            }
        }
        val memoryObserver = center.addObserverForName(
            name = UIApplicationDidReceiveMemoryWarningNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            services.memoryGovernor.onPlatformTrim(MemoryTrim.Critical)
        }
        onDispose {
            center.removeObserver(activeObserver)
            center.removeObserver(backgroundObserver)
            center.removeObserver(memoryObserver)
        }
    }
    val videoPlayer = IosProcess.videoPlayer
    // 首帧之前同步读出外观，否则先按默认配色画一帧再跳成用户选的主题，与另外两端一样
    val initialAppearance = remember { runBlocking { services.preferences.appearanceFlow(dynamicColorAvailable = false).first() } }
    val appearance by services.preferences.appearanceFlow(dynamicColorAvailable = false)
        .collectAsStateWithLifecycle(initialValue = initialAppearance)
    PikoApp(
        services = services,
        platform = platform,
        appearance = appearance,
        videoPlayer = videoPlayer,
    )
}

private const val DEFAULT_KEYCHAIN_SERVICE = "dev.piko.pikpak.ios"
