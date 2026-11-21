package dev.piko.data.auth

import dev.piko.shared.data.DriveAppearancePrefs
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.media.player.SubtitleStylePrefs
import dev.piko.shared.net.ProxySetting
import dev.piko.shared.sync.PortableDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 将账号标识映射为固定长度的十六进制存储槽位键，避免特殊字符并隐去手机号或邮箱明文。 */
fun accountPreferenceKey(account: String): String =
    PortableDigest.sha256(account.encodeToByteArray()).toHexString()

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

    /** 播放画质自动档：按网络速度与当前倍速自动升降画质（默认），仅用于在线播放。 */
    const val PLAYBACK_AUTO_HEIGHT = -1

    /** 固定画质上限的档位，画面高度，0 是原画。PikPak 的转码一般是这三档 */
    val MaxHeightChoices = listOf(0, 1080, 720, 480)
}

/** 本地缓存上限与过期清理天数的默认值与可选档位（设备本地设置，不参与跨设备同步）。 */
object CachePolicyDefaults {
    const val DEFAULT_MAX_BYTES: Long = 2L * 1024L * 1024L * 1024L
    const val DEFAULT_MAX_AGE_DAYS: Int = 14
    val MaxBytesChoices: List<Long> = listOf(
        512L * 1024L * 1024L,
        1L * 1024L * 1024L * 1024L,
        2L * 1024L * 1024L * 1024L,
        5L * 1024L * 1024L * 1024L,
        10L * 1024L * 1024L * 1024L,
        0L,
    )
    val MaxAgeDaysChoices: List<Int> = listOf(3, 7, 14, 30, 0)
}

enum class AppLanguage(val tag: String, val nativeLabel: String) {
    SYSTEM("system", ""),
    ZH_CN("zh-CN", "简体中文"),
    ZH_HANT("zh-TW", "繁體中文"),
    KO("ko-KR", "한국어"),
    JA("ja-JP", "日本語"),
    EN("en", "English");

    companion object {
        fun fromStorage(raw: String?): AppLanguage {
            val normalized = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return SYSTEM
            entries.firstOrNull {
                it.name.equals(normalized, ignoreCase = true) || it.tag.equals(normalized, ignoreCase = true)
            }?.let { return it }
            val parts = normalized.lowercase().split('-', '_').filter { it.isNotEmpty() }
            return when (parts.firstOrNull()) {
                "system" -> SYSTEM
                "zh" -> when {
                    "hant" in parts -> ZH_HANT
                    "hans" in parts -> ZH_CN
                    parts.any { it in TRADITIONAL_CHINESE_REGIONS } -> ZH_HANT
                    parts.size == 1 || parts.any { it in SIMPLIFIED_CHINESE_REGIONS } -> ZH_CN
                    else -> SYSTEM
                }
                "ko" -> KO
                "ja" -> JA
                "en" -> EN
                else -> SYSTEM
            }
        }

        private val TRADITIONAL_CHINESE_REGIONS = setOf("tw", "hk", "mo")
        private val SIMPLIFIED_CHINESE_REGIONS = setOf("cn", "sg", "my")
    }
}

/**
 * 启动与高频界面路径的无锁偏好快照，避免在组合或 ViewModel 初始化期间调用 `runBlocking`。
 * 带内存缓存的实现（`StoredPikoPreferences`、已预加载的 `SessionManager`）直接返回最新值；测试替身回退至默认值。
 */
data class PikoPreferencesSnapshot(
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val themeMode: String? = null,
    val themeSeed: String? = null,
    val pureBlack: Boolean = false,
    val einkMode: Boolean = false,
    val spoilerBlurEnabled: Boolean = false,
    val reduceMotion: Boolean = false,
    val hardwareDecoding: Boolean = true,
    val playbackMaxHeight: Int = PlayerGestureDefaults.PLAYBACK_AUTO_HEIGHT,
    val driveViewMode: String = "POSTER",
    val galleryShowImages: Boolean = true,
    val sidebarCollapsed: Boolean = false,
    val showExtensions: Boolean = false,
    val proxySetting: ProxySetting = ProxySetting(),
    val tileSizes: Map<String, String> = emptyMap(),
    val playerLayout: PlayerLayoutPrefs = PlayerLayoutPrefs.Default,
    val subtitleStyle: SubtitleStylePrefs = SubtitleStylePrefs.DEFAULT,
    val driveAppearance: DriveAppearancePrefs = DriveAppearancePrefs.DEFAULT,
    val skipFeedExitConfirm: Boolean = false,
    val cacheMaxBytes: Long = CachePolicyDefaults.DEFAULT_MAX_BYTES,
    val cacheMaxAgeDays: Int = CachePolicyDefaults.DEFAULT_MAX_AGE_DAYS,
) {
    fun tileSize(view: String): String = tileSizes[view].orEmpty()
}

interface PikoUserPreferences {
    /** 无锁读取当前偏好快照，供启动初始化与界面初值使用。 */
    val currentSnapshot: PikoPreferencesSnapshot get() = PikoPreferencesSnapshot()

    /** 无锁读取指定视图的卡片大小偏好，未设置时返回空串。 */
    fun currentTileSize(view: String): String = currentSnapshot.tileSize(view)

    /** 当前设备记住的引用保存选择，不参与跨设备设置同步。 */
    val saveAsReferenceFlow: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(false)
    suspend fun setSaveAsReference(enabled: Boolean) {}

    /** 本设备的只读文件夹挂载表，内含账号，不参与云端同步。 */
    val localMountsFlow: Flow<String> get() = flowOf("")
    suspend fun saveLocalMounts(serialized: String) {}

    /** 瀑布流是否一并展示图片，默认开启。 */
    val galleryShowImagesFlow: Flow<Boolean> get() = flowOf(true)
    suspend fun setGalleryShowImages(show: Boolean) {}

    val appLanguageFlow: Flow<AppLanguage> get() = flowOf(AppLanguage.SYSTEM)
    suspend fun setAppLanguage(language: AppLanguage) {}

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

    /** 深浅模式，存 ThemeMode 的名字（`SYSTEM`、`LIGHT`、`DARK`）；首次未设置时流中初值为 null，按跟随系统处理。 */
    val themeModeFlow: Flow<String?>
    suspend fun setThemeMode(mode: String)

    /** 内置主题色，存 SeedTheme 的名字。为 null 表示系统取色。 */
    val themeSeedFlow: Flow<String?>
    suspend fun setThemeSeed(seed: String?)

    /** 纯黑深色模式（OLED），仅在深色外观下生效，默认关闭。随设置跨设备同步。 */
    val pureBlackFlow: Flow<Boolean> get() = flowOf(false)
    suspend fun setPureBlack(enabled: Boolean) {}

    /** 水墨屏模式（纯黑白高对比度、清晰描边与自动关闭动画），默认关闭。每台设备各自独立，不随账号跨设备同步。 */
    val einkModeFlow: Flow<Boolean> get() = flowOf(false)
    suspend fun setEinkMode(enabled: Boolean) {}

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

    val downloadDirPathFlow: Flow<String>
    suspend fun setDownloadDirPath(path: String)
    suspend fun getDownloadDirPath(): String

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
    fun recentMoveTargetsFlowFor(account: String): Flow<String> =
        if (account.isBlank()) flowOf("") else recentMoveTargetsFlow
    suspend fun saveRecentMoveTargetsFor(account: String, serialized: String) {
        if (account.isNotBlank()) saveRecentMoveTargets(serialized)
    }

    /** 固定到快速访问的文件夹，JSON，见 PinnedFolders。空串表示从未保存。 */
    val pinnedFoldersFlow: Flow<String>
    suspend fun savePinnedFolders(serialized: String)
    fun pinnedFoldersFlowFor(account: String): Flow<String> =
        if (account.isBlank()) flowOf("") else pinnedFoldersFlow
    suspend fun savePinnedFoldersFor(account: String, serialized: String) {
        if (account.isNotBlank()) savePinnedFolders(serialized)
    }

    /** 文件夹优先显示，关闭后与文件按同一排序混排，默认开。 */
    val foldersFirstFlow: Flow<Boolean> get() = flowOf(true)
    suspend fun setFoldersFirst(enabled: Boolean) {}

    /** 每个文件夹独立记住自己的排序方式，默认关（所有文件夹共用同一排序）。 */
    val perFolderSortFlow: Flow<Boolean> get() = flowOf(false)
    suspend fun setPerFolderSort(enabled: Boolean) {}

    /** 按文件夹记住的排序方式，JSON（folderId -> PikoFileSortOrder.name），按账号隔离。 */
    val folderSortOrdersFlow: Flow<String> get() = flowOf("")
    suspend fun saveFolderSortOrders(serialized: String) {}
    fun folderSortOrdersFlowFor(account: String): Flow<String> =
        if (account.isBlank()) flowOf("") else folderSortOrdersFlow
    suspend fun saveFolderSortOrdersFor(account: String, serialized: String) {
        if (account.isNotBlank()) saveFolderSortOrders(serialized)
    }

    /** 批量重命名上次的选项与最近用过的查找、替换串，JSON，见 BatchRenameMemory。空串表示从未保存。每台设备各自的，不同步。 */
    val batchRenameFlow: Flow<String>
    suspend fun saveBatchRename(serialized: String)

    /** 归档对话框上次的三个勾选，JSON，见 VaultArchiveOptions。空串表示从未保存。每台设备各自的，不同步。 */
    val vaultArchiveOptionsFlow: Flow<String>
    suspend fun saveVaultArchiveOptions(serialized: String)

    /**
     * 批量重命名的查找替换写成正则文本，而不是拼积木。默认关（积木）。记的是用户手动切换的结果，
     * 因正则无法图形化而停在文本模式的那一次不算。属于用户的操作习惯，随设置跨设备同步。
     */
    val renameRegexTextModeFlow: Flow<Boolean>
    suspend fun setRenameRegexTextMode(enabled: Boolean)

    /** 应用内网络请求用的代理，见 PikoProxySelector。 */
    val proxySettingFlow: Flow<ProxySetting>
    suspend fun saveProxySetting(setting: ProxySetting)

    /** 开屏自动检查更新，默认开。关掉后只在设置页手动检查。 */
    val autoCheckUpdatesFlow: Flow<Boolean>
    suspend fun setAutoCheckUpdates(enabled: Boolean)

    /** 回到前台时检测剪贴板中的磁力或分享链接，默认关，仅保存在当前设备。 */
    val autoDetectClipboardLinksFlow: Flow<Boolean>
    suspend fun setAutoDetectClipboardLinks(enabled: Boolean)

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
     * 播放画质设置：-1（[PlayerGestureDefaults.PLAYBACK_AUTO_HEIGHT]）是自动（默认，按网速与倍速调节），
     * 0 是原画，正整数是画面高度上限（播放时挑不高于它的最大一档转码，没有就放原画，见 transcodeNameAtMost）。
     * 每台设备各自的，不同步：手机走流量想要 720P，电脑上要自动或原画。
     */
    val playbackMaxHeightFlow: Flow<Int>
    suspend fun setPlaybackMaxHeight(height: Int)

    /**
     * 默认下载画质：画面高度的上限，0 是原画；null 是未设置，普通下载同样使用原画。指定画质另由显式动作选择，
     * 按它直接挑，规则见 dev.piko.shared.media.downloadQualityOrder，转码档转封装成 MP4 存下。
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

    /** 信息流 30 秒片段播完后自动顺延续播当前视频后续内容，默认开启。跟着设置同步。 */
    val clipAutoExtendFlow: Flow<Boolean>
    suspend fun setClipAutoExtend(enabled: Boolean)

    /** 播放器四态自定义布局、听音频与手势配置。跟着设置同步。 */
    val playerLayoutPrefsFlow: Flow<PlayerLayoutPrefs> get() = flowOf(PlayerLayoutPrefs.Default)
    suspend fun setPlayerLayoutPrefs(prefs: PlayerLayoutPrefs) {}

    /** 播放器字幕外观偏好（底色、字体、字号、纵向位置与 ASS 覆盖）。跟着设置同步。 */
    val subtitleStylePrefsFlow: Flow<SubtitleStylePrefs> get() = flowOf(SubtitleStylePrefs.DEFAULT)
    suspend fun setSubtitleStylePrefs(prefs: SubtitleStylePrefs) {}

    /** 网盘三视图（列表 / 网格 / 瀑布流）文件外观自定义配置。跟着设置同步。 */
    val driveAppearancePrefsFlow: Flow<DriveAppearancePrefs> get() = flowOf(DriveAppearancePrefs.DEFAULT)
    suspend fun setDriveAppearancePrefs(prefs: DriveAppearancePrefs) {}

    /** 退出瀑布流与信息流时跳过确认弹窗直接退出，默认关闭。跨设备同步。 */
    val skipFeedExitConfirmFlow: Flow<Boolean> get() = flowOf(false)
    suspend fun setSkipFeedExitConfirm(skip: Boolean) {}

    /** 本地缓存容量上限（字节；0 表示无限制）。仅保存在当前设备，不同步。 */
    val cacheMaxBytesFlow: Flow<Long> get() = flowOf(CachePolicyDefaults.DEFAULT_MAX_BYTES)
    suspend fun setCacheMaxBytes(bytes: Long) {}

    /** 本地缓存过期自动清理天数（0 表示永不自动按时间清理）。仅保存在当前设备，不同步。 */
    val cacheMaxAgeDaysFlow: Flow<Int> get() = flowOf(CachePolicyDefaults.DEFAULT_MAX_AGE_DAYS)
    suspend fun setCacheMaxAgeDays(days: Int) {}

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
     * 按账号隔离存储，跨设备经 MetaTubeSync 加密同步，不进入普通设置同步文件。
     */
    val metaTubeUrlFlow: Flow<String>
    suspend fun setMetaTubeUrl(url: String)
    fun metaTubeUrlFlowFor(account: String): Flow<String> =
        if (account.isBlank()) flowOf("") else metaTubeUrlFlow
    suspend fun setMetaTubeUrlFor(account: String, url: String) {
        if (account.isNotBlank()) setMetaTubeUrl(url)
    }

    /** MetaTube 的访问令牌（服务端的 TOKEN）。机密，按账号由平台加密存放，跨设备仅经 MetaTubeSync 加密同步。空串表示没填。 */
    val metaTubeTokenFlow: Flow<String>
    suspend fun setMetaTubeToken(token: String)
    fun metaTubeTokenFlowFor(account: String): Flow<String> =
        if (account.isBlank()) flowOf("") else metaTubeTokenFlow
    suspend fun setMetaTubeTokenFor(account: String, token: String) {
        if (account.isNotBlank()) setMetaTubeToken(token)
    }
}
