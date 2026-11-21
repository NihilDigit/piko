@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.piko.ui.platform

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.BitmapImage
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.storage.IosAppDirectories
import dev.piko.shared.upload.importIosUploadDocument
import dev.piko.shared.upload.importIosUploadFolder
import dev.piko.ui.adaptive.FormFactor
import dev.piko.ui.i18n.syncPlatformResourceLocale
import dev.piko.ui.ios.IosHostBridge
import dev.piko.ui.screens.clips.BackdropBlurKernel
import dev.piko.ui.theme.MotionStyle
import dev.piko.ui.theme.PikoMotionScale
import dev.piko.update.AppUpdateService
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryLevelDidChangeNotification
import platform.UIKit.UIDeviceBatteryState
import platform.UIKit.UIDeviceBatteryStateDidChangeNotification
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIFont
import platform.UIKit.UIKeyboardWillHideNotification
import platform.UIKit.UIKeyboardWillShowNotification
import platform.UIKit.UIPasteboard
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * Piko 在 iOS 上的平台能力。只如实声明系统确实能做的事：
 * 没有桌面外壳（标题栏开关、托盘、开机启动），外部播放器使用限时直链而不是后台可能暂停的回环代理，
 * 不能登记磁力链接的打开方式，也没有需要选的外部下载目录（下载固定落在本应用私有目录）。
 *
 * 上传：文件走系统文稿选择器，选中的 URL 用 `importIosUploadDocument` 复制进沙箱后再排进上传队列；
 * 文件夹同理走 `importIosUploadFolder`——iOS 的文稿选择器只能给安全作用域的 URL，共享层的上传源
 * 只认应用私有目录，所以整棵树先复制进 `imports/` 再交回 `piko-app-upload:` 标识。
 */
class IosPikoPlatform(
    private val directories: IosAppDirectories,
    /** 入口传进注入 Recomposer 的那一份，与 Android 一样由系统「减少动态效果」推动。 */
    override val motionScale: PikoMotionScale = PikoMotionScale(),
    /** 单个文件导入上限，超过时选择器那一步直接失败，不进上传队列。 */
    private val uploadImportLimitBytes: Long = 8L * 1024 * 1024 * 1024,
    /** 文件夹导入的总额上限按单文件上限乘这个数；20 个文件 8 GiB 的单文件额度，再大的选择直接失败。 */
    private val folderImportFiles: Int = 20,
) : PikoPlatform {
    override val formFactor: FormFactor = FormFactor.Mobile

    // 桌面用 Standard（原文 "utilitarian products"）。iOS 的界面转场同样短，不跟着 Android 的 expressive 回弹
    override val motionStyle: MotionStyle = MotionStyle.Standard

    override val revealsLastTypedPassword: Boolean = true

    override val appVersion: String = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
        ?.takeIf { it.isNotBlank() } ?: "dev"

    // App Store / 侧载管更新，应用自己既不下载也不替换包
    override val updater: AppUpdateService? = null

    // iOS 没有 Monet 那样的整套取色，外观走内置主题
    override val supportsDynamicColor: Boolean = false

    override val supportsBlur: Boolean = true

    // iPad 外接键盘用 Command，iPhone 上没有快捷键入口
    override val shortcutModifier: ShortcutModifier = ShortcutModifier.Command

    override val fontFamily: FontFamily = FontFamily.Default

    override val deviceSummary: String = UIDevice.currentDevice.let {
        "${it.systemName} ${it.systemVersion}，${it.model}"
    }

    private val previewDecodeSemaphore = Semaphore(2)

    @Volatile
    private var cachedSubtitleFonts: List<String>? = null

    init {
        refreshSystemMotion()
    }

    /** 在初始化及宿主应用回到前台（UIApplicationDidBecomeActiveNotification）时同步系统「减少动态效果」开关。 */
    fun refreshSystemMotion() {
        motionScale.systemScale = if (UIAccessibilityIsReduceMotionEnabled()) 0f else 1f
    }

    @Composable
    override fun dynamicColorScheme(dark: Boolean): ColorScheme =
        error("iOS 不支持系统取色，外观会落到内置主题上")

    /**
     * 日志导出成文件后交给系统分享面板（存到「文件」、发给自己都可以），写出前清理缓存目录下的历史导出文件。
     * 写盘失败或没有可展示的控制器时记录告警并返回 false，由调用方提示导出失败。
     */
    override suspend fun exportLog(fileName: String, content: String): Boolean = withContext(Dispatchers.Main) {
        val target = Path(directories.caches, "exports/$fileName")
        val wrote = try {
            val parent = target.parent ?: return@withContext false
            if (SystemFileSystem.metadataOrNull(parent) == null) {
                SystemFileSystem.createDirectories(parent)
            } else {
                runCatching {
                    SystemFileSystem.list(parent)
                        .filter { it.name.endsWith(".txt") }
                        .forEach { SystemFileSystem.delete(it, mustExist = false) }
                }
            }
            SystemFileSystem.sink(target).buffered().use { it.writeString(content) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PikoLog.w(TAG, "Failed to write exported log file on iOS", e)
            false
        }
        if (!wrote) return@withContext false
        shareItems(listOf(NSURL.fileURLWithPath(target.toString()))).also { shared ->
            if (!shared) PikoLog.w(TAG, "Failed to present log share sheet on iOS: no active UIViewController")
        }
    }

    override fun openUrl(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    @Composable
    override fun isImeVisible(): Boolean {
        var visible by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            val center = NSNotificationCenter.defaultCenter
            val showObserver = center.addObserverForName(
                name = UIKeyboardWillShowNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                visible = true
            }
            val hideObserver = center.addObserverForName(
                name = UIKeyboardWillHideNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                visible = false
            }
            onDispose {
                center.removeObserver(showObserver)
                center.removeObserver(hideObserver)
            }
        }
        return visible
    }

    override fun readClipboardText(): String? = UIPasteboard.generalPasteboard.string

    override fun copyToClipboard(label: String, text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    override val localFiles: LocalFileActions = object : LocalFileActions {
        override fun thumbnailModel(path: String): Any? =
            NSURL.fileURLWithPath(path).takeIf { NSFileManager.defaultManager.fileExistsAtPath(path) }

        override fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

        // The share sheet offers installed document handlers; file URLs are not app URL schemes.
        override fun openExternally(path: String, isMedia: Boolean) {
            if (!exists(path)) return
            shareItems(listOf(NSURL.fileURLWithPath(path)))
        }

        // iOS 没有「在文件夹中显示」：文件都在应用私有容器里，系统文件 App 看不到
        override fun openContainingFolder(path: String) = Unit

        override val canShare: Boolean = true

        override fun share(path: String, isMedia: Boolean) {
            if (!exists(path)) return
            shareItems(listOf(NSURL.fileURLWithPath(path)))
            Unit
        }
    }

    override val downloadLocation: DownloadLocationPicker = object : DownloadLocationPicker {
        override val canChoose: Boolean = false

        // 下载固定在文档目录的 Piko 下，系统不提供「另存到别处」的持久授权
        override val description: String get() = tr(
            "下载的文件保存在本应用的文稿目录里，可在「文件」App 的 Piko 文件夹查看。",
            "Downloads are saved in this app’s Documents directory, available in the Piko folder in Files.",
            "ダウンロードはアプリの書類ディレクトリに保存され、「ファイル」アプリの Piko フォルダで確認できます。",
            "다운로드한 파일은 앱의 문서 디렉터리에 저장되며 파일 앱의 Piko 폴더에서 확인할 수 있습니다.",
            "下載的檔案儲存在本應用程式的文件目錄，可在「檔案」App 的 Piko 資料夾查看。",
        )

        override fun displayName(storedPath: String): String = tr("文件 App / Piko", "Files / Piko", "ファイル / Piko", "파일 / Piko", "檔案 App / Piko")

        override fun diskSpace(storedPath: String): DiskSpace? {
            val attributes = NSFileManager.defaultManager
                .attributesOfFileSystemForPath(directories.downloads, error = null) ?: return null
            val free = (attributes[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: return null
            val total = (attributes[NSFileSystemSize] as? NSNumber)?.longLongValue ?: return null
            return DiskSpace(freeBytes = free, totalBytes = total).takeIf { it.totalBytes > 0 }
        }

        // 位置不可选，界面上这个入口本来就不该出现；真被调用时不做任何事，也不回调
        @Composable
        override fun rememberLauncher(onPicked: (String) -> Unit): () -> Unit = { }
    }

    override val uploadPicker: UploadPicker = object : UploadPicker {
        @Composable
        override fun rememberFilesLauncher(onPicked: (List<String>) -> Unit): () -> Unit {
            val scope = rememberCoroutineScope()
            val delegate = remember(onPicked) { IosDocumentPickerDelegate(onPicked) }
            return presentFiles@{
                val host = IosHostBridge.topViewController() ?: return@presentFiles
                val picker = UIDocumentPickerViewController(
                    documentTypes = listOf("public.item"),
                    inMode = UIDocumentPickerMode.UIDocumentPickerModeOpen,
                )
                picker.allowsMultipleSelection = true
                picker.delegate = delegate
                host.presentViewController(picker, animated = true, completion = null)
                delegate.scope = scope
                delegate.directories = directories
                delegate.limitBytes = uploadImportLimitBytes
            }
        }

        @Composable
        override fun rememberFolderLauncher(onPicked: (String) -> Unit): () -> Unit {
            val scope = rememberCoroutineScope()
            val delegate = remember(onPicked) { IosFolderPickerDelegate(onPicked) }
            return presentFolder@{
                val host = IosHostBridge.topViewController() ?: return@presentFolder
                // 选文件夹只能用 Open 模式（public.folder），Import 模式只对文件有效
                val picker = UIDocumentPickerViewController(
                    documentTypes = listOf("public.folder"),
                    inMode = UIDocumentPickerMode.UIDocumentPickerModeOpen,
                )
                picker.allowsMultipleSelection = false
                picker.delegate = delegate
                host.presentViewController(picker, animated = true, completion = null)
                delegate.scope = scope
                delegate.directories = directories
                delegate.limitBytes = uploadImportLimitBytes
                delegate.totalLimitBytes = uploadImportLimitBytes * folderImportFiles
            }
        }
    }

    override val externalPlayer: ExternalVideoPlayer = IosExternalVideoPlayer()

    override val videoPreview: VideoPreviewSupport = IosVideoPreviewSupport()

    override val htmlViewer: HtmlViewerSupport = IosHtmlViewerSupport()

    override val linkAssociation: LinkAssociation? = null

    @Composable
    override fun FullscreenDialog(
        onDismiss: () -> Unit,
        immersive: Boolean,
        systemBarsVisible: Boolean,
        content: @Composable () -> Unit,
    ) {
        if (immersive) {
            DisposableEffect(systemBarsVisible) {
                val release = IosHostBridge.acquireImmersiveBars(
                    hidden = !systemBarsVisible,
                    lightContent = true,
                )
                onDispose(release)
            }
        }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            content()
        }
    }

    @Composable
    override fun HideSystemBars() {
        DisposableEffect(Unit) {
            val release = IosHostBridge.acquireStatusBarHidden()
            onDispose(release)
        }
    }

    @Composable
    override fun rememberLandscapeLock(): LandscapeLock? {
        if (!IosHostBridge.supportsLandscapeLock) return null
        val lock = remember { IosLandscapeLock() }
        DisposableEffect(lock) { onDispose(lock::release) }
        return lock
    }

    @Composable
    override fun ProvideLocalizedEnvironment(
        language: AppLanguage,
        content: @Composable () -> Unit,
    ) {
        syncPlatformResourceLocale(language.tag)
        key(language) {
            content()
        }
    }

    // 触屏靠甩动翻列表，与 Android 一致不画滚动条
    @Composable
    override fun ListScrollbar(state: LazyListState, modifier: Modifier) = Unit

    @Composable
    override fun ListScrollbar(state: LazyGridState, modifier: Modifier) = Unit

    override suspend fun loadPreblurredBackdrop(
        model: Any,
        containerWidthPx: Int,
        containerHeightPx: Int,
        targetBlurPx: Float,
    ): ImageBitmap? = withContext(Dispatchers.Default) {
        try {
            val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(model)
                .size(BackdropBlurKernel.DECODE_MAX_SIDE_PX)
                .precision(Precision.INEXACT)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
            val skiaBitmap = (SingletonImageLoader.get(PlatformContext.INSTANCE).execute(request).image as? BitmapImage)?.bitmap
                ?: return@withContext null
            val w = skiaBitmap.width
            val h = skiaBitmap.height
            if (w <= 0 || h <= 0) return@withContext null
            val radius = BackdropBlurKernel.equivalentRadiusPx(w, h, containerWidthPx, containerHeightPx, targetBlurPx)
            blurCoilBitmap(skiaBitmap, radius)?.asComposeImageBitmap()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        }
    }

    override fun blurCoilBitmap(input: Bitmap, radiusPx: Int): Bitmap? {
        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0) return null
        val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        val rawBytes = input.readPixels(info, w * 4, 0, 0) ?: return null
        val argb = IntArray(w * h) { idx ->
            val o = idx * 4
            val b = rawBytes[o].toInt() and 0xFF
            val g = rawBytes[o + 1].toInt() and 0xFF
            val r = rawBytes[o + 2].toInt() and 0xFF
            val a = rawBytes[o + 3].toInt() and 0xFF
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val blurred = BackdropBlurKernel.blurArgb(argb, w, h, radiusPx)
        val outBytes = ByteArray(w * h * 4)
        for (idx in blurred.indices) {
            val c = blurred[idx]
            val o = idx * 4
            outBytes[o] = (c and 0xFF).toByte()
            outBytes[o + 1] = ((c ushr 8) and 0xFF).toByte()
            outBytes[o + 2] = ((c ushr 16) and 0xFF).toByte()
            outBytes[o + 3] = ((c ushr 24) and 0xFF).toByte()
        }
        val outBitmap = Bitmap()
        if (!outBitmap.installPixels(info, outBytes, w * 4)) {
            outBitmap.close()
            return null
        }
        outBitmap.setImmutable()
        return outBitmap
    }

    override fun observeSystemStatus(): Flow<PlayerSystemStatusInfo?> = callbackFlow {
        var batteryPercent: Int? = null
        var isCharging = false
        var networkType: PlayerNetworkType? = null
        var previousBatteryMonitoring = false

        fun readBattery(): Pair<Int?, Boolean> {
            val device = UIDevice.currentDevice
            val rawLevel = device.batteryLevel
            val pct = if (rawLevel >= 0f) (rawLevel * 100f).roundToInt().coerceIn(0, 100) else null
            val charging = device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
                device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull
            return pct to charging
        }

        fun emitCurrent() {
            trySend(
                PlayerSystemStatusInfo(
                    batteryPercent = batteryPercent,
                    isCharging = isCharging,
                    networkType = networkType,
                ),
            )
        }

        val center = NSNotificationCenter.defaultCenter
        val levelObserver = center.addObserverForName(
            name = UIDeviceBatteryLevelDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            val (pct, charging) = readBattery()
            batteryPercent = pct
            isCharging = charging
            emitCurrent()
        }
        val stateObserver = center.addObserverForName(
            name = UIDeviceBatteryStateDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            val (pct, charging) = readBattery()
            batteryPercent = pct
            isCharging = charging
            emitCurrent()
        }

        dispatch_async(dispatch_get_main_queue()) {
            val device = UIDevice.currentDevice
            previousBatteryMonitoring = device.batteryMonitoringEnabled
            device.batteryMonitoringEnabled = true
            val (pct, charging) = readBattery()
            batteryPercent = pct
            isCharging = charging
            emitCurrent()
        }

        val monitor = nw_path_monitor_create()
        if (monitor != null) {
            nw_path_monitor_set_update_handler(monitor) { path ->
                networkType = if (path == null || nw_path_get_status(path) != nw_path_status_satisfied) {
                    PlayerNetworkType.Offline
                } else {
                    when {
                        nw_path_uses_interface_type(path, nw_interface_type_wifi) -> PlayerNetworkType.Wifi
                        nw_path_uses_interface_type(path, nw_interface_type_wired) -> PlayerNetworkType.Ethernet
                        nw_path_uses_interface_type(path, nw_interface_type_cellular) -> PlayerNetworkType.Cellular
                        else -> null
                    }
                }
                emitCurrent()
            }
            nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
            nw_path_monitor_start(monitor)
        }

        awaitClose {
            center.removeObserver(levelObserver)
            center.removeObserver(stateObserver)
            if (monitor != null) {
                nw_path_monitor_cancel(monitor)
            }
            dispatch_async(dispatch_get_main_queue()) {
                UIDevice.currentDevice.batteryMonitoringEnabled = previousBatteryMonitoring
            }
        }
    }.distinctUntilChanged()

    override fun decodeEncodedImageBitmap(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val image = SkiaImage.makeFromEncoded(bytes)
            try {
                Bitmap.makeFromImage(image).apply { setImmutable() }.asComposeImageBitmap()
            } finally {
                image.close()
            }
        }.getOrNull()
    }

    override suspend fun decodeLocalVideoPreviewFrame(
        path: String,
        positionMillis: Long,
        maxWidthPx: Int,
    ): ImageBitmap? {
        if (path.isBlank() || !NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        return previewDecodeSemaphore.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val targetMax = maxWidthPx.coerceIn(64, 640)
                    val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(path), options = null)
                    val generator = AVAssetImageGenerator(asset = asset)
                    generator.appliesPreferredTrackTransform = true
                    generator.maximumSize = CGSizeMake(targetMax.toDouble(), targetMax.toDouble())
                    generator.requestedTimeToleranceBefore = CMTimeMakeWithSeconds(1.0, 600)
                    generator.requestedTimeToleranceAfter = CMTimeMakeWithSeconds(1.0, 600)

                    val cgImage = memScoped {
                        val err = alloc<ObjCObjectVar<NSError?>>()
                        err.value = null
                        generator.copyCGImageAtTime(
                            requestedTime = CMTimeMake(positionMillis.coerceAtLeast(0L), 1000),
                            actualTime = null,
                            error = err.ptr,
                        )
                    } ?: return@withContext null

                    try {
                        cgImageToComposeBitmap(cgImage)
                    } finally {
                        CGImageRelease(cgImage)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    override suspend fun listSubtitleFonts(): List<String> {
        cachedSubtitleFonts?.let { return it }
        return withContext(Dispatchers.Main) {
            cachedSubtitleFonts?.let { return@withContext it }
            val installed = UIFont.familyNames.filterIsInstance<String>()
            val seen = LinkedHashMap<String, String>(installed.size)
            for (name in installed) {
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) {
                    val key = trimmed.lowercase()
                    if (key !in seen) seen[key] = trimmed
                }
            }
            val ordered = ArrayList<String>(seen.size)
            for (priority in PRIORITY_SUBTITLE_FONTS) {
                seen.remove(priority.lowercase())?.let(ordered::add)
            }
            ordered.addAll(seen.values.sortedBy { it.lowercase() })
            cachedSubtitleFonts = ordered
            ordered
        }
    }

    private class IosLandscapeLock : LandscapeLock {
        private var locked = false

        override fun lock() {
            if (locked) return
            locked = true
            IosHostBridge.setLandscapeLocked(true)
        }

        override fun release() {
            if (!locked) return
            locked = false
            IosHostBridge.setLandscapeLocked(false)
        }
    }
}

private fun cgImageToComposeBitmap(cgImage: platform.CoreGraphics.CGImageRef): ImageBitmap? {
    val w = CGImageGetWidth(cgImage).toInt()
    val h = CGImageGetHeight(cgImage).toInt()
    if (w <= 0 || h <= 0) return null
    val bytesPerRow = w * 4
    val pixels = ByteArray(bytesPerRow * h)
    val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
    val bitmapInfo = kCGBitmapByteOrder32Little or CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value
    val drawn = try {
        pixels.usePinned { pinned ->
            val context = CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = w.toULong(),
                height = h.toULong(),
                bitsPerComponent = 8uL,
                bytesPerRow = bytesPerRow.toULong(),
                space = colorSpace,
                bitmapInfo = bitmapInfo,
            ) ?: return@usePinned false
            try {
                CGContextDrawImage(context, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), cgImage)
                true
            } finally {
                CGContextRelease(context)
            }
        }
    } finally {
        CGColorSpaceRelease(colorSpace)
    }
    if (!drawn) return null
    val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    val skiaBitmap = Bitmap()
    if (!skiaBitmap.installPixels(info, pixels, bytesPerRow)) {
        skiaBitmap.close()
        return null
    }
    skiaBitmap.setImmutable()
    return skiaBitmap.asComposeImageBitmap()
}

private class ScopedUrlLease(
    val url: NSURL,
    private val accessed: Boolean,
) {
    private val released = kotlin.concurrent.AtomicInt(0)

    fun release() {
        if (accessed && released.compareAndSet(0, 1)) {
            url.stopAccessingSecurityScopedResource()
        }
    }
}

/** 文件选择器的回调对象；安全作用域授权须在主线程代理回调返回前同步获取，导入结束后配对释放。 */
@OptIn(kotlinx.cinterop.BetaInteropApi::class)
private class IosDocumentPickerDelegate(
    private val onPicked: (List<String>) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    var scope: kotlinx.coroutines.CoroutineScope? = null
    var directories: IosAppDirectories? = null
    var limitBytes: Long = 0

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val urls = didPickDocumentsAtURLs.filterIsInstance<NSURL>()
        if (urls.isEmpty()) return
        val scope = scope ?: return
        val directories = directories ?: return
        val leases = urls.map { url -> ScopedUrlLease(url, url.startAccessingSecurityScopedResource()) }
        val job = scope.launch {
            try {
                val imported = mutableListOf<String>()
                var failed = 0
                for (lease in leases) {
                    val uri = try {
                        importIosUploadDocument(directories, lease.url, limitBytes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failed++
                        PikoLog.w("Upload", "Failed to import selected iOS upload document (limitBytes=$limitBytes)", e)
                        null
                    } finally {
                        lease.release()
                    }
                    if (uri != null) imported += uri
                }
                if (imported.isNotEmpty()) {
                    onPicked(imported)
                } else if (failed > 0) {
                    presentPlatformAlert(
                        tr("无法导入所选文件（可能超出大小限制或无法读取）", "Unable to import selected file(s); they may exceed the size limit or be unreadable", "選択したファイルを読み込めません（サイズ上限を超えているか読み取れません）", "선택한 파일을 가져올 수 없습니다(크기 제한 초과 또는 읽기 불가)", "無法匯入所選檔案（可能超出大小限制或無法讀取）"),
                    )
                }
            } finally {
                leases.forEach(ScopedUrlLease::release)
            }
        }
        job.invokeOnCompletion { leases.forEach(ScopedUrlLease::release) }
    }
}

/** 文件夹选择器的回调对象；安全作用域授权须在主线程代理回调返回前同步获取，整棵导入结束后配对释放。 */
@OptIn(kotlinx.cinterop.BetaInteropApi::class)
private class IosFolderPickerDelegate(
    private val onPicked: (String) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    var scope: kotlinx.coroutines.CoroutineScope? = null
    var directories: IosAppDirectories? = null
    var limitBytes: Long = 0
    var totalLimitBytes: Long = 0

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.filterIsInstance<NSURL>().firstOrNull() ?: return
        val scope = scope ?: return
        val directories = directories ?: return
        val lease = ScopedUrlLease(url, url.startAccessingSecurityScopedResource())
        val job = scope.launch {
            try {
                val uri = try {
                    importIosUploadFolder(directories, url, limitBytes, totalLimitBytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    PikoLog.w("Upload", "Failed to import selected iOS upload folder (fileLimit=$limitBytes, totalLimit=$totalLimitBytes)", e)
                    presentPlatformAlert(
                        tr("无法导入所选文件夹（可能超出大小限制或无法读取）", "Unable to import selected folder; it may exceed the size limit or be unreadable", "選択したフォルダーを読み込めません（サイズ上限を超えているか読み取れません）", "선택한 폴더를 가져올 수 없습니다(크기 제한 초과 또는 읽기 불가)", "無法匯入所選資料夾（可能超出大小限制或無法讀取）"),
                    )
                    null
                }
                if (uri != null) onPicked(uri)
            } finally {
                lease.release()
            }
        }
        job.invokeOnCompletion { lease.release() }
    }
}

private fun presentPlatformAlert(message: String) {
    val host = IosHostBridge.topViewController() ?: return
    val dialog = UIAlertController.alertControllerWithTitle(
        title = null,
        message = message,
        preferredStyle = UIAlertControllerStyleAlert,
    )
    dialog.addAction(
        UIAlertAction.actionWithTitle(
            tr("确定", "OK", "OK", "확인", "確定"),
            UIAlertActionStyleDefault,
            handler = null,
        ),
    )
    host.presentViewController(dialog, animated = true, completion = null)
}

private fun shareItems(items: List<Any>): Boolean {
    val host = IosHostBridge.topViewController() ?: return false
    val controller = UIActivityViewController(activityItems = items, applicationActivities = null)
    // iPad 上分享面板是 popover，缺锚点会直接崩；居中展示避免箭头落在左上角 (0, 0)
    controller.popoverPresentationController?.let { popover ->
        popover.sourceView = host.view
        popover.sourceRect = host.view.bounds.useContents {
            CGRectMake(size.width / 2.0, size.height / 2.0, 1.0, 1.0)
        }
        popover.permittedArrowDirections = 0uL
    }
    host.presentViewController(controller, animated = true, completion = null)
    return true
}

private val PRIORITY_SUBTITLE_FONTS = listOf(
    "PingFang SC",
    "PingFang TC",
    "PingFang HK",
    "Hiragino Sans",
    "Apple SD Gothic Neo",
    "Heiti SC",
    "Heiti TC",
    "Helvetica Neue",
    "Avenir Next",
    "Georgia",
    "Menlo",
)

private const val TAG = "Platform"
