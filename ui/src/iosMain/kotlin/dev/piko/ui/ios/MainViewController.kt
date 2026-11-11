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
import dev.piko.shared.storage.SandboxPaths
import dev.piko.shared.storage.createIosAppDirectories
import dev.piko.ui.PikoApp
import dev.piko.ui.PikoServices
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.UIKit.UIApplicationDidBecomeActiveNotification
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
    IosRoot(services = IosProcess.services, directories = IosProcess.directories)
}

/** UIKit entrypoints run on the main thread; service ownership outlives every controller. */
private object IosProcess {
    init { PikoLocale.bindSystemLocale(NSLocale.currentLocale.localeIdentifier) }
    val directories = createIosAppDirectories()
    val keychainService = NSBundle.mainBundle.bundleIdentifier ?: DEFAULT_KEYCHAIN_SERVICE
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
        val appVersion = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
            ?.takeIf { it.isNotBlank() } ?: "dev"
        val device = UIDevice.currentDevice
        PikoLog.i("App", "App started: Piko $appVersion on ${device.systemName} ${device.systemVersion} (${device.model})")
    }
    val services = createIosServices(directories, appScope, keychainService)
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val videoPlayer = iosVideoPlayerHost(services, playerScope)
}

@Composable
private fun IosRoot(services: PikoServices, directories: dev.piko.shared.storage.IosAppDirectories) {
    val platform = remember { IosPikoPlatform(directories) }
    DisposableEffect(platform) {
        platform.refreshSystemMotion()
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            platform.refreshSystemMotion()
        }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
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
