@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.ui.platform

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.piko.shared.storage.IosAppDirectories
import dev.piko.update.AppUpdateService
import dev.piko.shared.upload.importIosUploadDocument
import dev.piko.shared.upload.importIosUploadFolder
import dev.piko.ui.adaptive.FormFactor
import dev.piko.ui.theme.MotionStyle
import dev.piko.ui.theme.PikoMotionScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject

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

    override val appVersion: String = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
        ?.takeIf { it.isNotBlank() } ?: "开发版"

    // App Store 管更新，应用自己既不下载也不替换包
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

    init {
        refreshSystemMotion()
    }

    /** 系统「减少动态效果」改动后由宿主的 scenePhase 回调再调一次（系统设置在外，应用内监听不到）。 */
    fun refreshSystemMotion() {
        motionScale.systemScale = if (UIAccessibilityIsReduceMotionEnabled()) 0f else 1f
    }

    @Composable
    override fun dynamicColorScheme(dark: Boolean): ColorScheme =
        error("iOS 不支持系统取色，外观会落到内置主题上")

    /**
     * 日志导出成文件后交给系统分享面板（存到「文件」、发给自己都可以）。没有可展示的控制器时返回 false，
     * 调用方据此提示导出失败，而不是写到一个用户找不到的路径。
     */
    override suspend fun exportLog(fileName: String, content: String): Boolean = withContext(Dispatchers.Main) {
        val target = Path(directories.caches, "exports/$fileName")
        val wrote = runCatching {
            val parent = target.parent ?: return@runCatching false
            if (SystemFileSystem.metadataOrNull(parent) == null) SystemFileSystem.createDirectories(parent)
            SystemFileSystem.sink(target).buffered().use { it.writeString(content) }
            true
        }.getOrDefault(false)
        if (!wrote) return@withContext false
        shareItems(listOf(NSURL.fileURLWithPath(target.toString())))
    }

    override fun openUrl(url: String) {
        val target = NSURL(string = url)
        UIApplication.sharedApplication.openURL(target, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    // 软键盘的显隐要靠 UIKeyboardWillShow 通知，宿主接上键盘通知前恒为 false
    @Composable
    override fun isImeVisible(): Boolean = false

    override fun readClipboardText(): String? = UIPasteboard.generalPasteboard.string

    override fun copyToClipboard(label: String, text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    override val localFiles: LocalFileActions = object : LocalFileActions {
        override fun thumbnailModel(path: String): Any? =
            NSURL.fileURLWithPath(path).takeIf { NSFileManager.defaultManager.fileExistsAtPath(path) }

        override fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

        // 交给系统的「快速查看」：能预览就预览，不能就落到文件 App
        override fun openExternally(path: String, isMedia: Boolean) {
            if (!exists(path)) return
            UIApplication.sharedApplication.openURL(
                NSURL.fileURLWithPath(path),
                options = emptyMap<Any?, Any>(),
                completionHandler = null,
            )
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
        // 下载固定在文档目录的 Piko 下，系统不提供「另存到别处」的持久授权
        override val description = "下载的文件保存在本应用的文稿目录里，可在「文件」App 的 Piko 文件夹查看。"

        override fun displayName(storedPath: String): String = "文件 App / Piko"

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
                val host = topViewController() ?: return@presentFiles
                val picker = UIDocumentPickerViewController(
                    documentTypes = listOf("public.item"),
                    inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
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
                val host = topViewController() ?: return@presentFolder
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

    override val videoPreview: VideoPreviewSupport? = null

    override val linkAssociation: LinkAssociation? = null

    @Composable
    override fun FullscreenDialog(
        onDismiss: () -> Unit,
        immersive: Boolean,
        systemBarsVisible: Boolean,
        content: @Composable () -> Unit,
    ) {
        // 铺满整屏由 usePlatformDefaultWidth = false 给出；沉浸式下状态栏的浅色与收放属于宿主
        // UIViewController 的 preferredStatusBarStyle，这里不越权改
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            content()
        }
    }

    // 触屏靠甩动翻列表，与 Android 一致不画滚动条
    @Composable
    override fun ListScrollbar(state: LazyListState, modifier: Modifier) = Unit

    @Composable
    override fun ListScrollbar(state: LazyGridState, modifier: Modifier) = Unit
}

/** 文件选择器的回调对象；选择器给的 URL 只在回调期间有效，导入在回调里立刻开始。 */
@OptIn(kotlinx.cinterop.BetaInteropApi::class)
private class IosDocumentPickerDelegate(
    private val onPicked: (List<String>) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    var scope: kotlinx.coroutines.CoroutineScope? = null
    var directories: IosAppDirectories? = null
    var limitBytes: Long = 0

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val urls = didPickDocumentsAtURLs.filterIsInstance<NSURL>()
        val scope = scope ?: return
        val directories = directories ?: return
        scope.launch {
            val imported = mutableListOf<String>()
            for (url in urls) {
                val uri = runCatching { importIosUploadDocument(directories, url, limitBytes) }.getOrNull()
                if (uri != null) imported += uri
            }
            if (imported.isNotEmpty()) onPicked(imported)
        }
    }
}

/** 文件夹选择器的回调对象；选中的 URL 只在回调期间有效，整棵导入在回调里立刻开始。 */
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
        scope.launch {
            val uri = runCatching { importIosUploadFolder(directories, url, limitBytes, totalLimitBytes) }.getOrNull()
            if (uri != null) onPicked(uri)
        }
    }
}

/** 最上层的展示控制器，分享面板与文稿选择器都要挂在它上面。 */
private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) controller = controller.presentedViewController
    return controller
}

private fun shareItems(items: List<Any>): Boolean {
    val host = topViewController() ?: return false
    val controller = UIActivityViewController(activityItems = items, applicationActivities = null)
    // iPad 上分享面板是 popover，缺锚点会直接崩
    controller.popoverPresentationController?.sourceView = host.view
    host.presentViewController(controller, animated = true, completion = null)
    return true
}
