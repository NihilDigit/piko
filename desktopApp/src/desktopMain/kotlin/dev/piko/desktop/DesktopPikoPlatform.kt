package dev.piko.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.piko.desktop.winrt.FolderPickResult
import dev.piko.desktop.winrt.FolderPicker
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.ui.platform.DownloadLocationPicker
import dev.piko.ui.platform.LocalFileActions
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.platform.PreviewBackend
import dev.piko.ui.platform.VideoPreviewSupport
import dev.piko.update.AppUpdateService
import java.awt.Desktop
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlinx.coroutines.launch
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer

/** 共享界面在 Windows 上的平台能力。 */
class DesktopPikoPlatform(
    private val settings: DesktopSettingsStore,
) : PikoPlatform {
    // jpackage 启动器写进 -Djpackage.app-version；gradle run 时没有，显示为开发版
    override val appVersion: String = System.getProperty("jpackage.app-version") ?: "开发版"

    // 安装包走 MSI，没有应用内更新
    override val updater: AppUpdateService? = null

    // Windows 没有 Monet 那样的整套取色，强调色只有一个值，撑不起 M3 的色调方案
    override val supportsDynamicColor: Boolean = false

    @Composable
    override fun dynamicColorScheme(dark: Boolean): ColorScheme =
        error("桌面端不支持系统取色，外观会落到内置主题上")

    override val supportsBlur: Boolean = true

    // 中文 Windows 自己的界面字体，西文部分取自 Segoe UI。默认字体族在 Windows 上只有 Segoe UI 与
    // Arial，汉字全靠系统后备，英文系统按英文 locale 挑，常用字落到日文字体、简体字落到雅黑，
    // 一个词里两种字体。Compose 1.12 不把 TextStyle 的 localeList 交给 Skia，标注语言也改不了后备的选择
    @OptIn(ExperimentalTextApi::class)
    override val fontFamily: FontFamily = FontFamily("Microsoft YaHei UI")

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

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

        override fun openExternally(path: String, isMedia: Boolean) = WinRTSupport.openFile(File(path))

        override fun openContainingFolder(path: String) = WinRTSupport.revealInExplorer(File(path))

        // Windows 的共享面板要 WinRT 的 DataTransferManager 挂在窗口句柄上，收益不抵这套接线
        override val canShare: Boolean = false

        override fun share(path: String, isMedia: Boolean) = Unit
    }

    override val downloadLocation: DownloadLocationPicker = object : DownloadLocationPicker {
        override val description = "下载的文件保存到这个文件夹。"

        override fun displayName(storedPath: String): String =
            storedPath.ifBlank { settings.downloadDirectory.absolutePath }

        @Composable
        override fun rememberLauncher(onPicked: (String) -> Unit): () -> Unit {
            val scope = rememberCoroutineScope()
            return {
                // 点击发生在哪个窗口，对话框就模态于哪个窗口
                val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                scope.launch {
                    val initial = settings.downloadDirectory
                    when (val result = FolderPicker.pickFolder(owner, initial, "选择下载位置")) {
                        is FolderPickResult.Picked -> onPicked(result.folder.absolutePath)
                        FolderPickResult.Cancelled -> Unit
                        FolderPickResult.Unavailable -> chooseDirectory(initial)?.let { onPicked(it.absolutePath) }
                    }
                }
            }
        }
    }

    override val videoPreview: VideoPreviewSupport = object : VideoPreviewSupport {
        @Composable
        override fun rememberPreviewBackend(): PreviewBackend {
            val scope = rememberCoroutineScope()
            val player = rememberMediampPlayer()
            val backend = remember(player) { MediampPreviewBackend(MediampPlaybackBackend(player, scope)) }
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

    private class MediampPreviewBackend(val inner: MediampPlaybackBackend) : PreviewBackend, PlaybackBackend by inner {
        // 播放器本身由 rememberPreviewBackend 在离开组合时关闭，这里只停播
        override fun release() = inner.stop()
    }
}

/**
 * 原生目录框弹不出来时的退路。AWT 的 FileDialog 在 Windows 上选不了目录，只能用 Swing 的；
 * 换成系统外观，免得弹出 Metal 风格的窗口。
 */
private fun chooseDirectory(initial: File): File? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser(initial).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "选择下载位置"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
