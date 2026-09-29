package dev.piko.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.piko.desktop.ui.player.MediampPlaybackBackend
import dev.piko.desktop.update.DesktopAppUpdater
import dev.piko.desktop.winrt.FolderPickResult
import dev.piko.desktop.winrt.FolderPicker
import dev.piko.desktop.winrt.SaveFilePicker
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.desktop.winrt.WindowsExternalPlayer
import dev.piko.desktop.winrt.WindowsLinkAssociation
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.ui.platform.DownloadLocationPicker
import dev.piko.ui.platform.ExternalVideoPlayer
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LocalFileActions
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.platform.PlatformToggle
import dev.piko.ui.platform.DiskSpace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.piko.ui.platform.PreviewBackend
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.platform.UploadPicker
import dev.piko.ui.platform.VideoPreviewSupport
import java.awt.Desktop
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer

/** 存在 settings.properties 里的一个开关，每台设备各自的。 */
private class StoredToggle(private val settings: DesktopSettingsStore, private val key: String, default: Boolean) : PlatformToggle {
    private val state = MutableStateFlow(settings.get(key, default.toString()).toBoolean())
    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    override fun set(enabled: Boolean) {
        settings.set(key, enabled.toString())
        state.value = enabled
    }
}

/** 共享界面在 Windows 上的平台能力。 */
class DesktopPikoPlatform(
    private val settings: DesktopSettingsStore,
) : PikoPlatform {
    // jpackage 启动器写进 -Djpackage.app-version；gradle run 时没有，显示为开发版
    override val appVersion: String = System.getProperty("jpackage.app-version") ?: "开发版"

    // Flatpak 里为 null：更新归 flatpak 管，自己也写不进 /app，界面据此不给检查更新的入口
    override val updater: DesktopAppUpdater? = DesktopAppUpdater.create()

    // Windows 没有 Monet 那样的整套取色，强调色只有一个值，撑不起 M3 的色调方案
    override val supportsDynamicColor: Boolean = false

    // 只在 Windows 上：macOS 的拖动区挪不进内容，见 WindowFrame
    override val compactTitleBar: PlatformToggle? =
        if (WinRTSupport.isWindows) StoredToggle(settings, "window.compactTitleBar", default = true) else null

    @Composable
    override fun dynamicColorScheme(dark: Boolean): ColorScheme =
        error("桌面端不支持系统取色，外观会落到内置主题上")

    override val supportsBlur: Boolean = true


    // 中文 Windows 自己的界面字体，西文部分取自 Segoe UI。默认字体族在 Windows 上只有 Segoe UI 与
    // Arial，汉字全靠系统后备，英文系统按英文 locale 挑，常用字落到日文字体、简体字落到雅黑，
    // 一个词里两种字体。Compose 1.12 不把 TextStyle 的 localeList 交给 Skia，标注语言也改不了后备的选择。
    // macOS 的后备同样按系统语言挑，指定系统自带的苹方；Linux 挑一个装了的简体中文字体，见 LinuxDesktop.cjkFontFamily
    @OptIn(ExperimentalTextApi::class)
    override val fontFamily: FontFamily = when {
        isMacOs -> FontFamily("PingFang SC")
        isLinux -> LinuxDesktop.cjkFontFamily?.let { FontFamily(it) } ?: FontFamily.Default
        else -> FontFamily("Microsoft YaHei UI")
    }

    override val shortcutModifier: ShortcutModifier = if (isMacOs) ShortcutModifier.Command else ShortcutModifier.Ctrl

    override val deviceSummary: String =
        "${System.getProperty("os.name")} ${System.getProperty("os.version")}，${System.getProperty("os.arch")}，Java ${System.getProperty("java.version")}"

    /**
     * 系统的保存框，默认放在下载目录。Windows 上用原生保存框，放在单独的线程上：AWT 的 FileDialog
     * 会让界面在对话框开着时停止重绘，见 SaveFilePicker。原生框弹不出来与 macOS 上才用 FileDialog。
     */
    override suspend fun exportLog(fileName: String, content: String): Boolean {
        val owner = activeWindow()
        val target = when (
            val picked = SaveFilePicker.pickSaveFile(owner, settings.downloadDirectory, fileName, "导出日志", "文本文件", "*.txt")
        ) {
            is FolderPickResult.Picked -> picked.folder
            FolderPickResult.Cancelled -> return false
            FolderPickResult.Unavailable -> AwtDialogs.chooseSaveFile(owner, "导出日志", settings.downloadDirectory, fileName) ?: return false
        }
        return withContext(Dispatchers.IO) { runCatching { target.writeText(content) }.isSuccess }
    }

    override fun openUrl(url: String) = if (isLinux) LinuxDesktop.open(url) else WinRTSupport.openUrl(url)

    @Composable
    override fun isImeVisible(): Boolean = false

    override fun readClipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun copyToClipboard(label: String, text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override val localFiles: LocalFileActions = object : LocalFileActions {
        override fun thumbnailModel(path: String): Any? = File(path).takeIf { it.exists() }

        override fun exists(path: String): Boolean = File(path).exists()

        override fun openExternally(path: String, isMedia: Boolean) {
            val file = File(path)
            if (isLinux) {
                if (file.exists()) LinuxDesktop.open(file.absolutePath)
            } else {
                WinRTSupport.openFile(file)
            }
        }

        override fun openContainingFolder(path: String) {
            val file = File(path)
            when {
                isLinux -> LinuxDesktop.reveal(file)
                isMacOs && file.exists() -> MacOs.revealInFinder(file)
                else -> WinRTSupport.revealInExplorer(file)
            }
        }

        // Windows 的共享面板要 WinRT 的 DataTransferManager 挂在窗口句柄上，收益不抵这套接线
        override val canShare: Boolean = false

        override fun share(path: String, isMedia: Boolean) = Unit
    }

    override val downloadLocation: DownloadLocationPicker = object : DownloadLocationPicker {
        override val description = "下载的文件保存到这个文件夹。"

        override fun displayName(storedPath: String): String =
            storedPath.ifBlank { settings.downloadDirectory.absolutePath }

        // 下载目录可能还没建出来：往上找到第一个存在的目录，它与将来的下载目录在同一块盘上
        override fun diskSpace(storedPath: String): DiskSpace? {
            val dir = generateSequence(File(displayName(storedPath))) { it.parentFile }.firstOrNull { it.exists() } ?: return null
            return DiskSpace(freeBytes = dir.usableSpace, totalBytes = dir.totalSpace).takeIf { it.totalBytes > 0 }
        }

        @Composable
        override fun rememberLauncher(onPicked: (String) -> Unit): () -> Unit {
            val scope = rememberCoroutineScope()
            return {
                val owner = activeWindow()
                scope.launch {
                    pickFolder(owner, settings.downloadDirectory, "选择下载位置")?.let { onPicked(it.absolutePath) }
                }
            }
        }
    }

    override val uploadPicker: UploadPicker = object : UploadPicker {
        @Composable
        override fun rememberFilesLauncher(onPicked: (List<String>) -> Unit): () -> Unit {
            val scope = rememberCoroutineScope()
            return {
                val owner = activeWindow()
                scope.launch {
                    val files = AwtDialogs.chooseFiles(owner, "选择要上传的文件")
                    if (files.isNotEmpty()) onPicked(files.map { it.absolutePath })
                }
            }
        }

        @Composable
        override fun rememberFolderLauncher(onPicked: (String) -> Unit): () -> Unit {
            val scope = rememberCoroutineScope()
            return {
                val owner = activeWindow()
                scope.launch {
                    pickFolder(owner, null, "选择要上传的文件夹")?.let { onPicked(it.absolutePath) }
                }
            }
        }
    }

    // macOS 上 open 一个 http 地址同样进浏览器；按类型查到默认播放器后用 open -a 交给它，
    // 播放器是否接受网址各不相同，没有 Mac 实测，先不给入口
    override val externalPlayer: ExternalVideoPlayer? = if (WinRTSupport.isWindows) WindowsExternalPlayer else null

    // macOS 按 Info.plist 的 CFBundleURLTypes 自动列为候选，改默认要调已弃用的 LaunchServices 接口，
    // 包又没有签名，不给入口。开发版与便携版由 state 报 Unavailable，设置页同样不显示
    override val linkAssociation: LinkAssociation? = when {
        WinRTSupport.isWindows -> WindowsLinkAssociation
        isMacOs -> MacLinkAssociation
        isLinux -> LinuxLinkAssociation
        else -> null
    }

    init {
        // AppImage 挪过位置时把登记过的 .desktop 指到新路径，读写几个小文件，放后台
        if (isLinux) Thread(LinuxLinkAssociation::refreshIfRegistered, "Piko-Linux-Setup").apply { isDaemon = true; start() }
    }

    override val videoPreview: VideoPreviewSupport = object : VideoPreviewSupport {
        @Composable
        override fun rememberPreviewBackend(keyframeStart: Boolean): PreviewBackend {
            val scope = rememberCoroutineScope()
            val player = rememberMediampPlayer()
            val backend = remember(player) {
                MediampPreviewBackend(MediampPlaybackBackend(player, scope, preview = true, keyframeStart = keyframeStart))
            }
            DisposableEffect(player) { onDispose { player.close() } }
            return backend
        }

        @Composable
        override fun Surface(backend: PreviewBackend, modifier: Modifier) {
            MediampPlayerSurface((backend as MediampPreviewBackend).inner.player, modifier)
        }
    }

    @Composable
    override fun FullscreenDialog(
        onDismiss: () -> Unit,
        immersive: Boolean,
        systemBarsVisible: Boolean,
        content: @Composable () -> Unit,
    ) {
        // 窗口内的一层，Esc 由 dismissOnBackPress 关闭
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }

    @Composable
    override fun ListScrollbar(state: LazyListState, modifier: Modifier) = dev.piko.desktop.ListScrollbar(state, modifier)

    @Composable
    override fun ListScrollbar(state: LazyGridState, modifier: Modifier) =
        dev.piko.desktop.ListScrollbar(state, modifier)

    private class MediampPreviewBackend(val inner: MediampPlaybackBackend) : PreviewBackend, PlaybackBackend by inner {
        // 播放器本身由 rememberPreviewBackend 在离开组合时关闭，这里只停播
        override fun release() = inner.stop()

        override fun setBufferAhead(seconds: Int) = inner.setBufferAhead(seconds)

        override fun bufferReport(): String? = inner.bufferReport()
    }
}

/** 点击发生在哪个窗口，对话框就模态于哪个窗口。要在点击的当下取，launch 之后焦点可能已经变了。 */
private fun activeWindow(): Window? = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow

/** 系统目录框。Windows 用原生框，弹不出来与 macOS 上走 [AwtDialogs]。取消时为 null。 */
private suspend fun pickFolder(owner: Window?, initial: File?, title: String): File? {
    if (isMacOs) return AwtDialogs.chooseDirectory(owner, initial, title)
    return when (val result = FolderPicker.pickFolder(owner, initial, title)) {
        is FolderPickResult.Picked -> result.folder
        FolderPickResult.Cancelled -> null
        FolderPickResult.Unavailable -> AwtDialogs.chooseDirectory(owner, initial, title)
    }
}
