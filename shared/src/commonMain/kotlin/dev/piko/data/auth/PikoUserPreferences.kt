package dev.piko.data.auth

import dev.piko.shared.net.ProxySetting
import kotlinx.coroutines.flow.Flow

/** 网盘空间用量。取到之前先用账号列表里记着的上一次的值，免得卡片整块缺席。 */
data class QuotaSnapshot(val usageBytes: Long, val limitBytes: Long)

/** 侧栏的开关与宽度。[widthDp] 为 null 表示从未拖过，取调用方的默认宽度。 */
data class SidePanelPrefs(val open: Boolean, val widthDp: Float?)

/**
 * 蜗牛模式，照 FDM：开着时下载与上传各自不超过设定的带宽，让出网络给别的用途。播放不受限。
 * 上限以 KiB/s 计；开关与上限分开存，关掉再开回到原来的上限。
 */
data class SnailMode(
    val enabled: Boolean = false,
    val downloadKiBps: Int = DEFAULT_DOWNLOAD_KIBPS,
    val uploadKiBps: Int = DEFAULT_UPLOAD_KIBPS,
) {
    companion object {
        const val DEFAULT_DOWNLOAD_KIBPS = 1024
        const val DEFAULT_UPLOAD_KIBPS = 512
    }
}

/** 播放器进退步长与长按倍速的默认值与可选档位。存储两端与播放设置面板共用这一份。 */
object PlayerGestureDefaults {
    const val SEEK_STEP_SECONDS = 10
    val SeekStepChoices = listOf(1, 2, 5, 10, 15, 30)
    const val BOOST_SPEED = 2f
    val BoostSpeedChoices = listOf(1.5f, 2f, 2.5f, 3f)

    /** 播放画质上限的档位，画面高度，0 是原画。PikPak 的转码一般是这三档 */
    val MaxHeightChoices = listOf(0, 1080, 720, 480)
}

interface PikoUserPreferences {
    suspend fun savePlaybackPosition(fileId: String, positionMs: Long)
    suspend fun getPlaybackPosition(fileId: String): Long
    suspend fun saveLastFolder(folderId: String, folderName: String, stackSerialized: String)
    suspend fun getLastFolder(): Triple<String, String, String>

    val spoilerBlurFlow: Flow<Boolean>
    suspend fun setSpoilerBlurEnabled(enabled: Boolean)
    val heuristicFilterFlow: Flow<Boolean>
    suspend fun setHeuristicFilterEnabled(enabled: Boolean)

    /**
     * 文件名解析总开关，默认开。关闭后网盘列表、磁力面板与选集都照原样列出文件名：不分区、不改标题、
     * 不挂标签；启发式折叠与配套字幕依赖解析，一并失效。
     */
    val nameParsingFlow: Flow<Boolean>
    suspend fun setNameParsingEnabled(enabled: Boolean)

    /** 添加链接时一并保存视频的外挂字幕。字幕在面板里挂在视频行下，不单独勾选，关闭后保存时跳过它们。 */
    val bundleSubtitlesFlow: Flow<Boolean>
    suspend fun setBundleSubtitlesEnabled(enabled: Boolean)

    /**
     * 添加链接与转存分享时按番号规范命名，默认关。面板里的同名开关以它为初值，单次改动不写回。
     * 规则见 docs/development/av-naming.md。
     */
    val autoCanonicalNamesFlow: Flow<Boolean>
    suspend fun setAutoCanonicalNames(enabled: Boolean)

    /**
     * 新建文件夹与重命名时，名称含 PikPak 不支持的内容就直接去掉，不再询问，默认关。
     * 规则见 [dev.piko.shared.data.DriveNames]。
     */
    val autoCleanNamesFlow: Flow<Boolean>
    suspend fun setAutoCleanNamesEnabled(enabled: Boolean)

    /** 把部分设置同步到网盘根目录的 .piko 文件夹，换设备登录时带过去，默认开。见 PikoSettingsSync。 */
    val settingsSyncFlow: Flow<Boolean>
    suspend fun setSettingsSyncEnabled(enabled: Boolean)

    /** 把播放进度上报到 PikPak 的播放历史，与官方客户端共用；没有本机记录时也从那里续播。 */
    val syncPlayHistoryFlow: Flow<Boolean>
    suspend fun setSyncPlayHistoryEnabled(enabled: Boolean)

    /** 深浅模式，存 ThemeMode 的名字。为 null 表示跟随系统。 */
    val themeModeFlow: Flow<String?>
    suspend fun setThemeMode(mode: String)

    /** 内置主题色，存 SeedTheme 的名字。为 null 表示系统取色。 */
    val themeSeedFlow: Flow<String?>
    suspend fun setThemeSeed(seed: String?)

    /**
     * 网盘列表的视图，存 ui 里 DriveViewMode 的名字：列表、海报墙或图库。全局记住，不随进出目录或重启复位。
     * 旧版只存是否海报墙，没有新键时由它换算，已有的选择不丢。
     */
    val driveViewModeFlow: Flow<String>
    suspend fun setDriveViewMode(mode: String)

    /**
     * 海报墙与图库各自的卡片大小，[view] 是 DriveViewMode 的名字，值是 ui 里 TileSize 的名字，没存过时为空串。
     * 每台设备各自的，不同步：同一档在手机与宽屏上排出的栏数相差很远。
     */
    fun driveTileSizeFlow(view: String): Flow<String>
    suspend fun setDriveTileSize(view: String, size: String)

    /** 大窗口左侧边栏收起成了窄轨。每台设备各自的，不同步：屏幕宽窄因机而异。 */
    val sidebarCollapsedFlow: Flow<Boolean>
    suspend fun setSidebarCollapsed(collapsed: Boolean)

    /**
     * 文件名带不带扩展名显示。默认值两端不同：桌面照资源管理器与 Finder 的习惯显示，手机上宽度金贵，
     * 类型已在副标题里单列，不显示。因此每台设备各自的，不同步。
     */
    val showExtensionsFlow: Flow<Boolean>
    suspend fun setShowExtensions(show: Boolean)

    /**
     * 网盘页的信息流：上次是否开着、宽窗口里侧栏拖到的宽度。开关不改 [driveViewModeFlow]，
     * 关掉信息流即回到原来的列表视图。
     */
    val clipPanelFlow: Flow<SidePanelPrefs>
    suspend fun setClipPanelOpen(open: Boolean)
    suspend fun setClipPanelWidth(widthDp: Float)

    /** PikPak API 用哪个根域名（如 mypikpak.net），空串是自动测速挑选，见 PikPakDomainSelector。每台设备各自的网络，不同步。 */
    val pikpakDomainFlow: Flow<String>
    suspend fun setPikpakDomain(root: String)

    /** 蜗牛模式的开关与上下行上限。每台设备各自的网络，不同步。 */
    val snailModeFlow: Flow<SnailMode>
    suspend fun setSnailMode(mode: SnailMode)

    val concurrentAccelerationFlow: Flow<Boolean>
    val concurrentConnectionsFlow: Flow<Int>
    val downloadDirPathFlow: Flow<String>
    suspend fun setDownloadDirPath(path: String)
    suspend fun getDownloadDirPath(): String
    suspend fun setConcurrentAccelerationEnabled(enabled: Boolean)

    /** 本地下载任务表的 JSON。空串表示从未保存。 */
    suspend fun loadDownloadTasks(): String
    suspend fun saveDownloadTasks(serialized: String)

    /** 上传任务表的 JSON，见 PikoUploadCoordinator。会话里的 OSS 凭据已抹去，另存在 [loadUploadCredentials]。空串表示从未保存。 */
    suspend fun loadUploadTasks(): String
    suspend fun saveUploadTasks(serialized: String)

    /**
     * 一个上传任务的 OSS 凭据（12 小时有效），JSON，按任务 ID 存，见 PikoUploadCoordinator。与登录会话同等看待，
     * 由平台加密存放。没有或解不开时为 null，调用方当作凭据已过期。
     */
    suspend fun loadUploadCredentials(taskId: String): String?
    suspend fun saveUploadCredentials(taskId: String, serialized: String)
    suspend fun clearUploadCredentials(taskId: String)

    /** 整包离线任务的跟踪记录，JSON，见 OfflinePackTracker。空串表示从未保存。 */
    suspend fun loadOfflinePacks(): String
    suspend fun saveOfflinePacks(serialized: String)

    /**
     * 1.1.0 全机一份、明文存着的压缩包密码（最近用过的在前的 JSON 列表），没有时为空串。之后按账号存在机密里
     * （ArchivePasswordStore），这一份由 ArchivePasswordVault 并入头一个运行的账号后 [clearLegacyArchivePasswords]。
     */
    suspend fun loadLegacyArchivePasswords(): String
    suspend fun clearLegacyArchivePasswords()

    /** 最近移动到过的目录路径，JSON，见 MoveHistory。空串表示从未保存。 */
    val recentMoveTargetsFlow: Flow<String>
    suspend fun saveRecentMoveTargets(serialized: String)

    /** 固定到快速访问的文件夹，JSON，见 PinnedFolders。空串表示从未保存。 */
    val pinnedFoldersFlow: Flow<String>
    suspend fun savePinnedFolders(serialized: String)

    /** 批量重命名上次的选项与最近用过的查找、替换串，JSON，见 BatchRenameMemory。空串表示从未保存。每台设备各自的，不同步。 */
    val batchRenameFlow: Flow<String>
    suspend fun saveBatchRename(serialized: String)

    /** 归档对话框上次的三个勾选，JSON，见 VaultArchiveOptions。空串表示从未保存。每台设备各自的，不同步。 */
    val vaultArchiveOptionsFlow: Flow<String>
    suspend fun saveVaultArchiveOptions(serialized: String)

    /**
     * 批量重命名的查找替换写成正则文本，而不是拼积木。默认关（积木）。记的是用户手动切换的结果，
     * 因正则无法图形化而停在文本模式的那一次不算。跨设备同步：它反映的是这个人的水平。
     */
    val renameRegexTextModeFlow: Flow<Boolean>
    suspend fun setRenameRegexTextMode(enabled: Boolean)

    /** 应用内网络请求用的代理，见 PikoProxySelector。 */
    val proxySettingFlow: Flow<ProxySetting>
    suspend fun saveProxySetting(setting: ProxySetting)

    /** 开屏自动检查更新，默认开。关掉后只在设置页手动检查。 */
    val autoCheckUpdatesFlow: Flow<Boolean>
    suspend fun setAutoCheckUpdates(enabled: Boolean)

    /** 设置里的「减少动画」，默认关，与系统的减少动画取或。每台设备各自的，不同步：系统那一半本来就按设备。 */
    val reduceMotionFlow: Flow<Boolean>
    suspend fun setReduceMotion(enabled: Boolean)

    /**
     * 播放时用显卡解码视频，默认开。关掉改用处理器解码，给显卡驱动出问题的机器一条退路
     * （有用户在 Windows 上播 4K 时整机卡死）。每台设备各自的，不同步：好不好用看的是这台的显卡。
     * 播放器打开时读一次，开着的播放器不跟着换。
     */
    val hardwareDecodingFlow: Flow<Boolean>
    suspend fun setHardwareDecoding(enabled: Boolean)

    /**
     * 播放画质上限：画面高度，0 是原画（默认）。播放时挑不高于它的最大一档转码，没有就放原画，见 transcodeNameAtMost。
     * 每台设备各自的，不同步：手机走流量想要 720P，电脑上要原画。
     */
    val playbackMaxHeightFlow: Flow<Int>
    suspend fun setPlaybackMaxHeight(height: Int)

    /**
     * 默认下载画质：画面高度的上限，0 是原画；null 是未设置（默认），下载视频时每次弹画质框。设了就不再问，
     * 按它直接挑，规则见 dev.piko.shared.media.downloadQualityOrder，转码档转封装成 MP4 存下。
     * 「问不问」与「按哪一档」合成这一项：原先分成两项，可能出现关了询问、画质却是没人选过的原画。
     * 每台设备各自的，不同步，理由同 [playbackMaxHeightFlow]。
     */
    val downloadMaxHeightFlow: Flow<Int?>
    suspend fun setDownloadMaxHeight(height: Int?)

    /** 播放器双击、方向键进退一步的秒数，默认 10。在播放设置里改，跟着设置同步：这是看片的习惯，不看设备。 */
    val playerSeekStepSecondsFlow: Flow<Int>
    suspend fun setPlayerSeekStepSeconds(seconds: Int)

    /** 长按画面临时加速到的倍速，默认 2。同上，在播放设置里改、跟着设置同步。 */
    val playerBoostSpeedFlow: Flow<Float>
    suspend fun setPlayerBoostSpeed(speed: Float)

    /**
     * 网盘页的目录图（FolderMap）开着与钉着，默认都否。两项各自独立：× 关掉不改钉住，再打开照旧钉着；
     * 没钉住的目录图停在边沿时不用就收成把手。每台设备各自的，不同步：目录图只在宽窗口里有，
     * 桌面上钉着它的人未必在另一台机器上也想钉着。
     */
    val folderMapOpenFlow: Flow<Boolean>
    suspend fun setFolderMapOpen(open: Boolean)
    val folderMapPinnedFlow: Flow<Boolean>
    suspend fun setFolderMapPinned(pinned: Boolean)

    /** 开屏提示里点了「忽略此版本」的版本号。只比相等，更新的版本出来照常提示。 */
    suspend fun getIgnoredUpdateVersion(): String?
    suspend fun setIgnoredUpdateVersion(version: String)

    /**
     * 用户自己部署的 MetaTube 服务地址，如「http://192.168.1.2:8080」。空串表示不用刮削，界面上不出现任何刮削入口。
     * 每台设备各自的，不进设置同步：局域网地址换一台设备未必连得上。
     */
    val metaTubeUrlFlow: Flow<String>
    suspend fun setMetaTubeUrl(url: String)

    /** MetaTube 的访问令牌（服务端的 TOKEN）。机密，由平台加密存放，不进设置同步。空串表示没填。 */
    val metaTubeTokenFlow: Flow<String>
    suspend fun setMetaTubeToken(token: String)
}
