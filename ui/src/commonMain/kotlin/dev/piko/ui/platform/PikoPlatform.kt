package dev.piko.ui.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.update.AppUpdateService

/**
 * 共享界面向所在平台要的能力。Android 与 Desktop 各实现一份，按 CLAUDE.md 的约定用接口而不用
 * expect/actual：实现要拿 Context、窗口与各自的播放后端，这些都是运行时对象，不是编译期的平台差异。
 */
interface PikoPlatform {
    /** 关于页显示的版本号。 */
    val appVersion: String

    /** 为 null 表示该平台没有应用内更新。 */
    val updater: AppUpdateService?

    /** 系统取色（Android 12 起的 Monet）。不支持时设置页不给这一项，外观落到第一个内置主题。 */
    val supportsDynamicColor: Boolean

    /** 只在 [supportsDynamicColor] 为 true 时调用。 */
    @Composable
    fun dynamicColorScheme(dark: Boolean): ColorScheme

    /**
     * 防窥缩略图能否真的模糊。Android 12 以下没有 RenderEffect，Modifier.blur 什么都不做，
     * 这时界面只画不透明占位，而不是露出原图。
     */
    val supportsBlur: Boolean


    /**
     * 界面字体。汉字不在其中时由系统按系统语言挑后备字体，英文系统上会逐字混用日文与中文字体，
     * 需要的平台在这里给出一款带简体中文的字体。
     */
    val fontFamily: FontFamily get() = FontFamily.Default

    fun openUrl(url: String)

    /** 软键盘是否弹出。桌面端没有软键盘，恒为 false。 */
    @Composable
    fun isImeVisible(): Boolean

    fun readClipboardText(): String?

    fun copyToClipboard(label: String, text: String)

    val localFiles: LocalFileActions

    val downloadLocation: DownloadLocationPicker

    val uploadPicker: UploadPicker

    /** 为 null 表示该平台不提供片段下载的画面预览，入口随之隐藏。 */
    val videoPreview: VideoPreviewSupport?

    /**
     * 铺满窗口的对话框。Android 要关掉 decorFitsSystemWindows，内容自己按 safeDrawing 避让；
     * [immersive] 为真时（看图）系统栏图标固定浅色，并按 [systemBarsVisible] 收起系统栏，
     * 这些都是 Dialog 自己窗口上的操作。桌面端就是窗口内的一层浮层。
     */
    @Composable
    fun FullscreenDialog(
        onDismiss: () -> Unit,
        immersive: Boolean,
        systemBarsVisible: Boolean,
        content: @Composable () -> Unit,
    )
}

/** 已下载到本机的文件的外部动作。路径可能是普通路径，也可能是 Android SAF 的 content: URI。 */
interface LocalFileActions {
    /** Coil 能加载的本地缩略图来源；文件不存在时为 null。 */
    fun thumbnailModel(path: String): Any?

    fun exists(path: String): Boolean

    fun openExternally(path: String, isMedia: Boolean)

    fun openContainingFolder(path: String)

    /** 系统分享面板。桌面端没有对应物时为 false，界面不显示「分享」。 */
    val canShare: Boolean

    fun share(path: String, isMedia: Boolean)
}

/** 下载位置的展示与选择。Android 选的是 SAF 目录树，桌面端是系统的目录选择框。 */
interface DownloadLocationPicker {
    /** 选择对话框里的说明文字。 */
    val description: String

    /** 把偏好里存的值（路径或 content: URI）换成给人看的名字，空值时给出默认位置。 */
    fun displayName(storedPath: String): String

    /**
     * 返回一个启动选择器的函数。选中后以要写入偏好的值回调 [onPicked]。
     * 做成 Composable 是因为 Android 要在组合里注册 ActivityResult 启动器。
     */
    @Composable
    fun rememberLauncher(onPicked: (String) -> Unit): () -> Unit
}

/**
 * 选要上传的本机文件与文件夹。回调给的是 PikoUploadSources 认得的 uri，取消时不回调。
 * 做成 Composable 的理由同 [DownloadLocationPicker.rememberLauncher]。
 */
interface UploadPicker {
    /** 可多选。 */
    @Composable
    fun rememberFilesLauncher(onPicked: (List<String>) -> Unit): () -> Unit

    @Composable
    fun rememberFolderLauncher(onPicked: (String) -> Unit): () -> Unit
}

/** 片段下载面板里的画面预览：一个不出声、不自动播放的播放后端，加上它的画面表面。 */
interface VideoPreviewSupport {
    /** 返回的后端由调用方在离开组合时 release。 */
    @Composable
    fun rememberPreviewBackend(): PreviewBackend

    @Composable
    fun Surface(backend: PreviewBackend, modifier: Modifier)
}

interface PreviewBackend : PlaybackBackend {
    fun release()
}

val LocalPikoPlatform = staticCompositionLocalOf<PikoPlatform> {
    error("PikoPlatform 未提供，入口要用 PikoApp 包一层")
}
