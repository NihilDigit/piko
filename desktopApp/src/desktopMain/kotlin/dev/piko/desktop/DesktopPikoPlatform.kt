package dev.piko.desktop

import dev.piko.shared.i18n.tr

import dev.piko.desktop.winrt.WindowsLaunchAtStartup
import dev.piko.ui.platform.LaunchAtStartup

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
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import org.jetbrains.skia.FontMgr
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
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.platform.DownloadLocationPicker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import dev.piko.ui.adaptive.FormFactor
import dev.piko.ui.theme.MotionStyle
import dev.piko.ui.theme.PikoMotionScale
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
    /** 入口传进注入 Recomposer 的那一份（见 Main.kt）；截图与测试不注入，用一份不接系统的。 */
    override val motionScale: PikoMotionScale = PikoMotionScale(),
) : PikoPlatform {
    override val formFactor: FormFactor = FormFactor.Desktop
    override val motionStyle: MotionStyle = MotionStyle.Standard

    // jpackage 启动器写进 -Djpackage.app-version；gradle run 时没有，显示为开发版
    override val appVersion: String = System.getProperty("jpackage.app-version") ?: "dev"

    // Flatpak 里为 null：更新归 flatpak 管，自己也写不进 /app，界面据此不给检查更新的入口
    override val updater: DesktopAppUpdater? = DesktopAppUpdater.create()

    // Windows 没有 Monet 那样的整套取色，强调色只有一个值，撑不起 M3 的色调方案
    override val supportsDynamicColor: Boolean = false

    override val launchAtStartup: LaunchAtStartup? =
        if (WinRTSupport.isWindows) System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }
            ?.let { WindowsLaunchAtStartup(it) } else null

    override val closeToTray: PlatformToggle? =
        if (java.awt.SystemTray.isSupported()) StoredToggle(settings, "window.closeToTray", default = false) else null

    // 只在 Windows 上：macOS 的拖动区挪不进内容，见 WindowFrame
    override val compactTitleBar: PlatformToggle? =
        if (WinRTSupport.isWindows) StoredToggle(settings, "window.compactTitleBar", default = true) else null

    @Composable
    override fun dynamicColorScheme(dark: Boolean): ColorScheme =
        error(tr("桌面端不支持系统取色，已回退至内置主题", "System colors are unavailable on desktop; falling back to built-in theme", "デスクトップ版はシステムカラーに非対応のため、内蔵テーマを使用します", "데스크톱에서는 시스템 색상을 지원하지 않아 내장 테마를 사용합니다", "桌面版不支援系統取色，已回退至內建主題"))

    override val supportsBlur: Boolean = true

    // 开发用，经 JAVA_TOOL_OPTIONS 传 -Dpiko.debug.folder-map=true。带版本号打的包（piko.release-build）里不认
    override val debugFolderMapZones: Boolean =
        System.getProperty("piko.release-build") != "true" && System.getProperty("piko.debug.folder-map") == "true"


    /**
     * 按当前有效语言选取单个真正生效的系统主字体（禁止使用多个 FontFamily(name).fonts flatMap 拼接，
     * Compose Desktop 的 FontListFontFamily 按字重只取第一个匹配的 SystemFont，后续字体会被全部丢弃）。
     */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    override val fontFamily: FontFamily
        get() {
            val candidates = desktopPrimaryFontCandidates(
                isMacOs = isMacOs,
                isLinux = isLinux,
                effectiveLanguage = PikoLocale.effectiveLanguage,
                systemLanguage = PikoLocale.systemLanguage,
            )
            val picked = runCatching {
                val fontMgr = FontMgr.default
                candidates.firstOrNull { name -> fontMgr.matchFamily(name).use { it.count() > 0 } }
            }.getOrNull()
            return if (picked == null) FontFamily.Default else FontFamily(picked)
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
            val picked = SaveFilePicker.pickSaveFile(owner, settings.downloadDirectory, fileName, tr("导出日志", "Export logs", "ログをエクスポート", "로그 내보내기", "匯出日誌"), tr("文本文件", "Text files", "テキストファイル", "텍스트 파일", "文字檔案"), "*.txt")
        ) {
            is FolderPickResult.Picked -> picked.folder
            FolderPickResult.Cancelled -> return false
            FolderPickResult.Unavailable -> AwtDialogs.chooseSaveFile(owner, tr("导出日志", "Export logs", "ログをエクスポート", "로그 내보내기", "匯出日誌"), settings.downloadDirectory, fileName) ?: return false
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
        override val description: String
            get() = tr("下载的文件将保存至此文件夹。", "Downloaded files will be saved to this folder.", "ダウンロードしたファイルはこのフォルダーに保存されます。", "다운로드한 파일은 이 폴더에 저장됩니다.", "下載的檔案將儲存至此資料夾。")

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
                    pickFolder(owner, settings.downloadDirectory, tr("选择下载位置", "Choose download location", "ダウンロード先を選択", "다운로드 위치 선택", "選擇下載位置"))?.let { onPicked(it.absolutePath) }
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
                    val files = AwtDialogs.chooseFiles(owner, tr("选择要上传的文件", "Choose files to upload", "アップロードするファイルを選択", "업로드할 파일 선택", "選擇要上傳的檔案"))
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
                    pickFolder(owner, null, tr("选择要上传的文件夹", "Choose folder to upload", "アップロードするフォルダーを選択", "업로드할 폴더 선택", "選擇要上傳的資料夾"))?.let { onPicked(it.absolutePath) }
                }
            }
        }
    }

    override val externalPlayer: ExternalVideoPlayer? = when {
        WinRTSupport.isWindows -> WindowsExternalPlayer
        isMacOs -> MacExternalPlayer
        else -> null
    }

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
            BundledMpvRuntime.ensure()
            val player = rememberMediampPlayer()
            val preferences = LocalPikoServices.current.preferences
            val backend = remember(player) {
                // 偏好是 StateFlow，first() 当场返回
                val hardwareDecoding = runBlocking { preferences.hardwareDecodingFlow.first() }
                MediampPreviewBackend(MediampPlaybackBackend(player, scope, preview = true, keyframeStart = keyframeStart, hardwareDecoding = hardwareDecoding))
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

internal fun desktopPrimaryFontCandidates(
    isMacOs: Boolean,
    isLinux: Boolean,
    effectiveLanguage: AppLanguage,
    systemLanguage: AppLanguage,
): List<String> = when {
    isLinux -> LinuxDesktop.cjkFontCandidatesFor(effectiveLanguage, systemLanguage)
    isMacOs -> when (effectiveLanguage) {
        AppLanguage.ZH_HANT -> listOf("PingFang TC", "PingFang HK", "PingFang SC")
        AppLanguage.JA -> listOf("Hiragino Sans", "PingFang SC")
        AppLanguage.KO -> listOf("Apple SD Gothic Neo", "PingFang SC")
        AppLanguage.EN, AppLanguage.SYSTEM -> when (systemLanguage) {
            AppLanguage.ZH_HANT -> listOf("PingFang TC", "PingFang HK", "PingFang SC")
            AppLanguage.JA -> listOf("Hiragino Sans", "PingFang SC")
            AppLanguage.KO -> listOf("Apple SD Gothic Neo", "PingFang SC")
            else -> listOf("PingFang SC")
        }
        AppLanguage.ZH_CN -> listOf("PingFang SC")
    }
    // Windows：雅黑与正黑的 ASCII 0x5C 本就是标准 '\'，直接作为主字体可保证汉字 100% 统一不混排；
    // 英文下跟随系统原始简繁偏好（非繁中默认雅黑），既保持 Segoe UI 西文度量又避免中文文件名退化成 SimSun 宋体；
    // 日文与韩文用 Segoe UI 防止 Yu Gothic UI / Malgun Gothic 把 0x5C 画成 ¥ / ₩，结合显式 LocaleList 确定性回退。
    else -> when (effectiveLanguage) {
        AppLanguage.ZH_CN -> listOf("Microsoft YaHei UI", "Microsoft YaHei")
        AppLanguage.ZH_HANT -> listOf("Microsoft JhengHei UI", "Microsoft JhengHei", "Microsoft YaHei UI")
        AppLanguage.JA, AppLanguage.KO -> listOf("Segoe UI")
        AppLanguage.EN, AppLanguage.SYSTEM -> if (systemLanguage == AppLanguage.ZH_HANT) {
            listOf("Microsoft JhengHei UI", "Microsoft JhengHei", "Microsoft YaHei UI", "Microsoft YaHei")
        } else {
            listOf("Microsoft YaHei UI", "Microsoft YaHei")
        }
    }
}
