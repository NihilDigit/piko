package dev.piko.ui.platform

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.ui.adaptive.FormFactor
import dev.piko.ui.theme.MotionStyle
import dev.piko.ui.theme.PikoMotionScale
import dev.piko.update.AppUpdateService
import kotlinx.coroutines.flow.StateFlow

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

    /** 标题栏并进内容、窗口按钮画在界面右上角（见 WindowCaption）。为 null 表示平台没有这一项，设置页不给开关。 */
    val compactTitleBar: PlatformToggle? get() = null

    /**
     * 界面字体。汉字不在其中时由系统按系统语言挑后备字体，英文系统上会逐字混用日文与中文字体，
     * 需要的平台在这里给出一款带简体中文的字体。
     */
    val fontFamily: FontFamily get() = FontFamily.Default

    val shortcutModifier: ShortcutModifier get() = ShortcutModifier.Ctrl

    /**
     * 密码框里刚打的那个字先明文显示一下。这是手机的习惯（软键盘容易按错）；Windows 与 macOS 的密码框直接显示圆点，
     * 桌面端照做。桌面端开着它时密码框每打一个字的出帧耗时是普通输入框的两倍（实测帧间隔 21–32ms 对 10ms）。
     */
    val revealsLastTypedPassword: Boolean get() = false

    /** 交互模型，见 [dev.piko.ui.adaptive.FormFactor]。 */
    val formFactor: FormFactor

    /** 动效的风格与转场时长，见 [MotionStyle]。 */
    val motionStyle: MotionStyle

    /** 入口注入到 Recomposer 的那一份动画时长缩放；主题从它读出「减少动画」。 */
    val motionScale: PikoMotionScale

    /** 导出日志时写在开头的运行环境：系统版本、机型或架构。 */
    val deviceSummary: String

    /**
     * 把导出的日志交给用户：Android 调起系统分享，直接发进聊天或邮件；桌面端弹保存对话框。
     * 返回 false 表示没有交出去（取消或失败）。
     */
    suspend fun exportLog(fileName: String, content: String): Boolean

    fun openUrl(url: String)

    /** 软键盘是否弹出。桌面端没有软键盘，恒为 false。 */
    @Composable
    fun isImeVisible(): Boolean

    fun readClipboardText(): String?

    fun copyToClipboard(label: String, text: String)

    val localFiles: LocalFileActions

    val downloadLocation: DownloadLocationPicker

    val uploadPicker: UploadPicker

    /** 为 null 表示该平台没法把视频交给其他播放器，「用外部播放器打开」随之隐藏。 */
    val externalPlayer: ExternalVideoPlayer?

    /** 为 null 表示该平台不提供片段下载的画面预览，入口随之隐藏。 */
    val videoPreview: VideoPreviewSupport?

    /** 为 null 表示该平台不能由应用自己登记为磁力链接与种子文件的打开方式，设置页不给入口。 */
    val linkAssociation: LinkAssociation?

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

    /**
     * 在组合期间收起主窗口的系统栏，从边缘轻扫临时调出，离开组合时放回。横握手机全屏刷信息流用：
     * 状态栏与导航条在三百多 dp 的高度里各占一截。没有系统栏的平台什么也不做。
     */
    @Composable
    fun HideSystemBars() = Unit

    /**
     * 把屏幕转成横屏的开关，信息流的横屏按钮用；转不了方向的平台为 null。离开组合时自动 release。
     * 系统锁定了自动旋转时，横握手机靠这个才转得过来。
     */
    @Composable
    fun rememberLandscapeLock(): LandscapeLock? = null

    /**
     * 列表右侧可拖动的滚动条，放在列表所在的 Box 里靠右对齐。鼠标没有甩动，几百项的目录只靠滚轮
     * 走不到底，也看不出当前位置。Android 不画：触屏靠甩动，系统也没有这个惯例。
     */
    @Composable
    fun ListScrollbar(state: LazyListState, modifier: Modifier)

    @Composable
    fun ListScrollbar(state: LazyGridState, modifier: Modifier)
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
     * 下载位置所在磁盘的空间，传输页据此看排队的下载放不放得下。查不出时为 null
     * （Android 上授权的 SD 卡文件夹没有可查的路径），界面不画这一项。
     */
    fun diskSpace(storedPath: String): DiskSpace?

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

/**
 * 把网盘视频交给系统里的其他播放器。[url] 是本机回环代理的地址，不是会过期的直链；
 * [fileName] 是网盘上的原名，用作标题与判断类型。返回 false 表示没能交出去。
 */
/** 一块磁盘还能写多少、一共多少，字节。 */
data class DiskSpace(val freeBytes: Long, val totalBytes: Long)

/**
 * 平台自有的一个开关，值存在该平台自己的设置里，不跨设备同步。只有一个平台有的项走这里，
 * 不进 PikoUserPreferences：那边每加一项，没有这项的平台也得跟着实现一份存储。
 */
interface PlatformToggle {
    val enabled: StateFlow<Boolean>
    fun set(enabled: Boolean)
}

/**
 * 横屏请求。命令式而不是「在组合期间生效」：要能在离开组合之前当场放开，
 * 理由见 ClipFeedScreen 里看完整之前的 release。
 */
interface LandscapeLock {
    /** 转成横屏，随传感器在两个横向间转。头一次调用时记下原来的方向，重复调用不改记下的值。 */
    fun lock()

    /** 恢复 [lock] 之前的方向；没锁着时什么也不做。 */
    fun release()
}

fun interface ExternalVideoPlayer {
    suspend fun open(url: String, fileName: String): Boolean
}

/** 片段下载面板与随机片段里的画面预览：一个缓存小、不自动播放的播放后端，加上它的画面表面。 */
interface VideoPreviewSupport {
    /**
     * 返回的后端由调用方在离开组合时 release。
     *
     * [keyframeStart] 为 true 时从起点之前最近的关键帧起播，不精确定位：精确定位要从关键帧一路解码到起点，
     * 多读多解几秒。随机片段不在乎从哪一帧起；片段下载要按所选时刻预览，保持精确。
     */
    @Composable
    fun rememberPreviewBackend(keyframeStart: Boolean = false): PreviewBackend

    @Composable
    fun Surface(backend: PreviewBackend, modifier: Modifier)
}

/**
 * 把 magnet: 链接与 .torrent 文件交给 Piko 打开。Windows 不许应用自己改默认打开方式，
 * 分两步：应用登记成可选项，再由用户在系统设置里选定；macOS 由 LaunchServices 直接改。
 */
interface LinkAssociation {
    /** 读系统眼下的选择。读注册表或问 LaunchServices，调用方放在后台协程里。 */
    suspend fun state(): LinkAssociationState

    /**
     * 设为默认打开方式。[needsSystemConfirmation] 时只是登记成可选项并打开系统的默认应用设置，
     * 由用户在那里选定。返回 false 表示没办成（登记失败、设置页也没打开）。
     */
    suspend fun register(): Boolean

    /**
     * 取消关联：删掉 Piko 写的登记，系统回头让用户另选。只在 [canUnregister] 时有意义。
     * 返回 false 表示没办成。
     */
    suspend fun unregister(): Boolean

    /**
     * 能不能取消关联。macOS 没有「不设默认」这一说，只能把默认改给另一个应用，那是替用户挑应用，
     * 不做；换回去在那个应用里设为默认即可，界面据此不给取消的按钮。
     */
    val canUnregister: Boolean

    /** 还要用户在系统设置里确认一次（Windows）。界面据此决定说明怎么写。 */
    val needsSystemConfirmation: Boolean
}

enum class LinkAssociationState {
    /** 这份构建不能登记，例如开发版：登记的是开发工具的进程，不是 Piko。 */
    Unavailable,
    NotDefault,

    /**
     * 登记过、但系统眼下不由它打开（Windows：用户在系统设置里没选 Piko，或选了又换回去），可以取消关联。
     * 不能取消的平台不报这一态。
     */
    Registered,
    /** 磁力链接与种子文件都已由 Piko 打开。 */
    Default,
}

interface PreviewBackend : PlaybackBackend {
    fun release()

    /**
     * 播放器自己往后缓冲多少秒。它的缓冲读到代理那里都是「播放器卡在这一块上」的最高档，
     * 压过一切预取，所以还没真看起来的段要压低，见信息流的升档。播放中途改，下一次读即生效。
     */
    fun setBufferAhead(seconds: Int)

    /** 缓冲的设定与眼下缓存着的时长，只供日志核对 [setBufferAhead] 生效没有；取不到为 null。 */
    fun bufferReport(): String?
}

val LocalPikoPlatform = staticCompositionLocalOf<PikoPlatform> {
    error("PikoPlatform 未提供，入口要用 PikoApp 包一层")
}
