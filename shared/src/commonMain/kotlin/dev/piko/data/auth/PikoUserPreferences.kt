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

    /** 宽窗口网盘页右侧的详情栏：上次是否开着、拖到的宽度。与信息流侧栏占同一个位置，二者只开一个。 */
    val inspectorPanelFlow: Flow<SidePanelPrefs>
    suspend fun setInspectorPanelOpen(open: Boolean)
    suspend fun setInspectorPanelWidth(widthDp: Float)

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

    /** 上传任务表的 JSON，见 PikoUploadCoordinator。含 12 小时有效的 OSS 凭据，与会话同等看待。空串表示从未保存。 */
    suspend fun loadUploadTasks(): String
    suspend fun saveUploadTasks(serialized: String)

    /** 整包离线任务的跟踪记录，JSON，见 OfflinePackTracker。空串表示从未保存。 */
    suspend fun loadOfflinePacks(): String
    suspend fun saveOfflinePacks(serialized: String)

    /** 解压成功过的压缩包密码，JSON，见 ArchivePasswordVault。空串表示从未保存。 */
    val archivePasswordsFlow: Flow<String>
    suspend fun saveArchivePasswords(serialized: String)

    /** 最近移动到过的目录路径，JSON，见 MoveHistory。空串表示从未保存。 */
    val recentMoveTargetsFlow: Flow<String>
    suspend fun saveRecentMoveTargets(serialized: String)

    /** 固定到快速访问的文件夹，JSON，见 PinnedFolders。空串表示从未保存。 */
    val pinnedFoldersFlow: Flow<String>
    suspend fun savePinnedFolders(serialized: String)

    /** 批量重命名上次的选项与最近用过的查找、替换串，JSON，见 BatchRenameMemory。空串表示从未保存。每台设备各自的，不同步。 */
    val batchRenameFlow: Flow<String>
    suspend fun saveBatchRename(serialized: String)

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

    /** 开屏提示里点了「忽略此版本」的版本号。只比相等，更新的版本出来照常提示。 */
    suspend fun getIgnoredUpdateVersion(): String?
    suspend fun setIgnoredUpdateVersion(version: String)
}
