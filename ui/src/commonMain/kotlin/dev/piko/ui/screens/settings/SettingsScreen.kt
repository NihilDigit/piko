package dev.piko.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SwapVerticalCircle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SwapVerticalCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.auth.SnailMode
import dev.piko.shared.net.ProxySetting
import dev.piko.shared.sync.PikoSettingsSync
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.IslandHeaderSpace
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LinkAssociationState
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.archive.SavedArchivePasswordsDialog
import dev.piko.ui.screens.drive.SidebarItem
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.SeedTheme
import dev.piko.ui.theme.ThemeMode
import dev.piko.ui.theme.effectiveSeed
import dev.piko.ui.theme.isDark
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 设置页。桌面从侧边栏左下角进入，账号与关于并在这里；移动端从「我的」进入，那两类留在「我的」。
 *
 * 各类从上到下连着排，每类一个类标题，类里不止一组时各组再有组标题；两端、各宽度都是这一份结构。
 * 桌面放得下时左边是分类目录，点了滚到那一类，滚动时亮当前的一类。不做成选一类显示一类：
 * 几类都只有几组，分页后每页大半是空的，找一项还得先猜它归哪一类。
 *
 * 行与组的外观在 SettingsLayout.kt，后来加设置项照 ui/CLAUDE.md「设置页」一节。
 */
@Composable
fun SettingsScreen(
    onBackClick: (() -> Unit)?,
    onOpenWebDav: () -> Unit,
    modifier: Modifier = Modifier,
    /** 账号一类的内容（[dev.piko.ui.workbench.AccountSettings]）。桌面没有「我的」页，账号放在设置的最前；移动端为 null。 */
    account: (@Composable ColumnScope.() -> Unit)? = null,
    /** 关于一类（[AboutSection]）。与账号同理：移动端它在「我的」页，这里不放。 */
    showAbout: Boolean = false,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val sessionManager = services.preferences
    val isSpoilerBlurEnabled by sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)
    val isHeuristicFilterEnabled by sessionManager.heuristicFilterFlow.collectAsStateWithLifecycle(initialValue = true)
    val isNameParsingEnabled by sessionManager.nameParsingFlow.collectAsStateWithLifecycle(initialValue = true)
    val isBundleSubtitlesEnabled by sessionManager.bundleSubtitlesFlow.collectAsStateWithLifecycle(initialValue = true)
    val isAutoCanonicalNamesEnabled by sessionManager.autoCanonicalNamesFlow.collectAsStateWithLifecycle(initialValue = false)
    val isAutoCleanNamesEnabled by sessionManager.autoCleanNamesFlow.collectAsStateWithLifecycle(initialValue = false)
    val isHardwareDecodingEnabled by sessionManager.hardwareDecodingFlow.collectAsStateWithLifecycle(initialValue = true)
    val isSyncPlayHistoryEnabled by sessionManager.syncPlayHistoryFlow.collectAsStateWithLifecycle(initialValue = true)
    val isSettingsSyncEnabled by sessionManager.settingsSyncFlow.collectAsStateWithLifecycle(initialValue = true)
    val settingsSync = services.settingsSync
    val syncStatus by settingsSync.status.collectAsStateWithLifecycle()
    val lastSynced by settingsSync.lastSynced.collectAsStateWithLifecycle()
    val isConcurrentAccelerationEnabled by sessionManager.concurrentAccelerationFlow.collectAsStateWithLifecycle(initialValue = true)
    val downloadDirPath by sessionManager.downloadDirPathFlow.collectAsStateWithLifecycle(initialValue = "")
    val isFreeAccount by services.driveRepository.isFreeAccountFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val archivePasswordVault = services.archivePasswords
    val archivePasswords by archivePasswordVault.passwords.collectAsStateWithLifecycle(initialValue = emptyList())
    var showArchivePasswords by remember { mutableStateOf(false) }
    var showPlaybackQuality by remember { mutableStateOf(false) }
    val playbackMaxHeight by sessionManager.playbackMaxHeightFlow.collectAsStateWithLifecycle(initialValue = 0)
    var showDownloadQuality by remember { mutableStateOf(false) }
    val downloadMaxHeight by sessionManager.downloadMaxHeightFlow.collectAsStateWithLifecycle(initialValue = null)

    val metaTube = services.metaTube
    val metaTubeUrl by sessionManager.metaTubeUrlFlow.collectAsStateWithLifecycle(initialValue = "")
    var showMetaTubeDialog by remember { mutableStateOf(false) }

    var showDownloadDirDialog by remember { mutableStateOf(false) }
    val proxySetting by sessionManager.proxySettingFlow.collectAsStateWithLifecycle(initialValue = ProxySetting())
    var showProxyDialog by remember { mutableStateOf(false) }
    val domainSelector = services.domainSelector
    val domainChoice by sessionManager.pikpakDomainFlow.collectAsStateWithLifecycle(initialValue = "")
    val activeDomain by domainSelector.active.collectAsStateWithLifecycle()
    val domainProbes by domainSelector.probes.collectAsStateWithLifecycle()
    val domainProbing by domainSelector.probing.collectAsStateWithLifecycle()
    var showDomainDialog by remember { mutableStateOf(false) }
    val snailMode by sessionManager.snailModeFlow.collectAsStateWithLifecycle(initialValue = SnailMode())
    var showSnailDialog by remember { mutableStateOf(false) }
    val downloadLocation = platform.downloadLocation
    val resolvedDownloadPath = remember(downloadDirPath) { downloadLocation.displayName(downloadDirPath) }
    // 选完不关对话框，让用户在对话框里看到新位置
    val pickDownloadDir = downloadLocation.rememberLauncher { picked ->
        scope.launch { sessionManager.setDownloadDirPath(picked) }
    }

    val linkAssociation = platform.linkAssociation
    var linkAssociationState by remember { mutableStateOf(LinkAssociationState.Unavailable) }
    if (linkAssociation != null) {
        // 默认应用在系统设置里改，改完切回来窗口重新获得焦点，借此刷新
        val isWindowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(linkAssociation, isWindowFocused) {
            if (isWindowFocused) linkAssociationState = linkAssociation.state()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    val sections = remember(account != null, showAbout) {
        SettingsSection.entries.filter {
            when (it) {
                SettingsSection.Account -> account != null
                SettingsSection.About -> showAbout
                else -> true
            }
        }
    }
    // 各类在滚动内容里的起点，目录据此跳转与高亮
    val sectionOffsets = remember { mutableStateMapOf<SettingsSection, Int>() }
    var pinned by remember { mutableStateOf<PinnedSection?>(null) }
    val headingSlack = with(LocalDensity.current) { SectionHeadingSlack.roundToPx() }
    // 以 sections 为键：目录变了（账号或关于出现）时换一份，否则一直按首次组合时的目录算
    val currentSection by remember(sections, headingSlack) {
        derivedStateOf {
            activeSettingsSection(
                sections = sections,
                offsets = sectionOffsets,
                scroll = scrollState.value,
                maxScroll = scrollState.maxValue,
                slack = headingSlack,
                pinned = pinned?.let { it.section to it.target.takeIf { _ -> it.settled } },
            )
        }
    }
    val desktop = isDesktopLayout()

    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 有外框时：不分标签（各类连着排、左栏是目录），页名写在岛的页眉里
        island = IslandPage(header = {
            IslandTitle("设置")
            IslandHeaderSpace()
        }),
        topBar = {
            PikoTopBar(
                title = "设置",
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 目录只在桌面：移动端照 M3 的单列列表，平板与手机一致，宽时只把内容限宽居中
            val withIndex = desktop && maxWidth >= SettingsIndexMinWidth
            Row(modifier = Modifier.fillMaxSize()) {
                if (withIndex) {
                    SettingsIndex(
                        sections = sections,
                        current = currentSection,
                        onSelect = { section ->
                            val target = (sectionOffsets[section] ?: 0).coerceAtMost(scrollState.maxValue)
                            val pin = PinnedSection(section, target)
                            pinned = pin
                            scope.launch {
                                try {
                                    scrollState.animateScrollTo(target)
                                    pin.settled = true
                                } catch (e: CancellationException) {
                                    // 滚动途中被滚轮或另一次点击打断：前者回到按位置算，后者已换上新的一项
                                    if (pinned === pin) pinned = null
                                    throw e
                                }
                            }
                        },
                        modifier = Modifier.width(SettingsIndexWidth).padding(start = 12.dp, end = 12.dp, top = 16.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        // 有目录时照 M3 的窗格边距留 24dp；内容收在阅读宽度里居中
                        .padding(horizontal = if (withIndex) 24.dp else 16.dp)
                        .padding(bottom = 24.dp)
                        .readableWidth(),
                ) {
                    @Composable
                    fun section(section: SettingsSection, content: @Composable ColumnScope.() -> Unit) {
                        SettingsSectionBlock(
                            section = section,
                            first = section == sections.first(),
                            onPositioned = { sectionOffsets[section] = it },
                            content = content,
                        )
                    }

                    if (account != null) section(SettingsSection.Account) { account() }

                    section(SettingsSection.Appearance) {
                        SettingsGroup(null) {
                            val appearance = LocalAppearance.current
                            ThemeModeRow(
                                mode = appearance.mode,
                                onModeChange = { scope.launch { sessionManager.setThemeMode(it.name) } },
                            )
                            ThemeColorRow(
                                appearance = appearance,
                                onSeedChange = { scope.launch { sessionManager.setThemeSeed(it?.name) } },
                            )
                            platform.compactTitleBar?.let { compactTitleBar ->
                                val compact by compactTitleBar.enabled.collectAsState()
                                SettingsSwitchRow(
                                    icon = Icons.Outlined.WebAsset,
                                    title = "紧凑标题栏",
                                    supporting = "窗口按钮并入界面右上角",
                                    checked = compact,
                                    onCheckedChange = compactTitleBar::set,
                                )
                            }
                            // 与系统设置取或，见 PikoMotionScale。系统已关掉动画时这里开不开都一样，写明免得以为开关失灵
                            val motionScale = platform.motionScale
                            SettingsSwitchRow(
                                icon = Icons.Outlined.Animation,
                                title = "减少动画",
                                supporting = if (motionScale.systemScale == 0f) "系统已关闭动画" else "界面切换不播放过渡",
                                checked = motionScale.appReduced,
                                onCheckedChange = { scope.launch { sessionManager.setReduceMotion(it) } },
                            )
                        }
                    }

                    section(SettingsSection.Browse) {
                        SettingsGroup("文件") {
                            SettingsSwitchRow(
                                icon = Icons.Outlined.TextFields,
                                title = "文件名解析",
                                supporting = "按作品、分区与集数整理，标出发布组与清晰度",
                                checked = isNameParsingEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setNameParsingEnabled(it) } },
                            )
                            // 只在解析开着时有意义，关掉解析就收起，不留一行灰掉的开关
                            DependentRow(visible = isNameParsingEnabled) {
                                SettingsSwitchRow(
                                    icon = Icons.Outlined.AutoFixHigh,
                                    title = "启发式折叠",
                                    supporting = "收起广告、样片、说明文件等次要项",
                                    checked = isHeuristicFilterEnabled,
                                    onCheckedChange = { scope.launch { sessionManager.setHeuristicFilterEnabled(it) } },
                                )
                            }
                            SettingsSwitchRow(
                                icon = Icons.Outlined.VisibilityOff,
                                title = "缩略图防窥",
                                supporting = "模糊显示缩略图与封面",
                                checked = isSpoilerBlurEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setSpoilerBlurEnabled(it) } },
                            )
                        }
                        SettingsGroup("播放") {
                            SettingsNavigationRow(
                                icon = Icons.Outlined.HighQuality,
                                title = "播放画质",
                                supporting = playbackQualitySummary(playbackMaxHeight),
                                onClick = { showPlaybackQuality = true },
                                trailingIcon = null,
                            )
                            SettingsSwitchRow(
                                icon = Icons.Outlined.Memory,
                                title = "硬件解码",
                                supporting = "花屏或卡死时关闭，改用处理器解码",
                                checked = isHardwareDecodingEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setHardwareDecoding(it) } },
                            )
                        }
                    }

                    // 存进网盘时的处理：链接由谁打开、带不带字幕、存成什么名字。原来分在「网盘」与「下载」两处，
                    // 而配套字幕只在添加链接时起作用，与本机下载无关
                    section(SettingsSection.Saving) {
                        SettingsGroup(null) {
                            if (linkAssociation != null) {
                                LinkAssociationRow(
                                    association = linkAssociation,
                                    state = linkAssociationState,
                                    onStateChange = { linkAssociationState = it },
                                    onFailure = { message -> scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true) } },
                                )
                            }
                            // 这两项靠文件名解析认出字幕与番号。解析在另一类，关掉时留着并写明原因，收起的话人找不到它去了哪
                            SettingsSwitchRow(
                                icon = Icons.Outlined.Subtitles,
                                title = "保存配套字幕",
                                supporting = if (isNameParsingEnabled) "保存视频时一并保存外挂字幕" else "需先开启文件名解析",
                                checked = isBundleSubtitlesEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setBundleSubtitlesEnabled(it) } },
                                enabled = isNameParsingEnabled,
                            )
                            SettingsSwitchRow(
                                icon = Icons.Outlined.DriveFileRenameOutline,
                                title = "保存时按番号规范命名",
                                supporting = if (isNameParsingEnabled) "添加链接与转存时，带番号的项目存为规范名" else "需先开启文件名解析",
                                checked = isAutoCanonicalNamesEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setAutoCanonicalNames(it) } },
                                enabled = isNameParsingEnabled,
                            )
                            SettingsSwitchRow(
                                icon = Icons.Outlined.CleaningServices,
                                title = "自动修正名称",
                                supporting = "新建与重命名时去掉 PikPak 不支持的字符",
                                checked = isAutoCleanNamesEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setAutoCleanNamesEnabled(it) } },
                            )
                            if (metaTube != null) {
                                SettingsNavigationRow(
                                    icon = Icons.Outlined.TravelExplore,
                                    title = "MetaTube",
                                    supporting = metaTubeUrl.ifBlank { "未设置，按番号命名时不取片名" },
                                    onClick = { showMetaTubeDialog = true },
                                    trailingIcon = null,
                                )
                            }
                        }
                    }

                    section(SettingsSection.Transfer) {
                        SettingsGroup("下载") {
                            SettingsNavigationRow(
                                icon = Icons.Outlined.FolderOpen,
                                title = "下载位置",
                                supporting = resolvedDownloadPath,
                                onClick = { showDownloadDirDialog = true },
                                trailingIcon = null,
                            )
                            SettingsNavigationRow(
                                icon = Icons.Outlined.HighQuality,
                                title = "默认下载画质",
                                supporting = downloadQualitySummary(downloadMaxHeight),
                                onClick = { showDownloadQuality = true },
                                trailingIcon = null,
                            )
                            SettingsSwitchRow(
                                icon = Icons.Outlined.Speed,
                                title = "并发加速",
                                supporting = "多连接下载，提升速度",
                                checked = isConcurrentAccelerationEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setConcurrentAccelerationEnabled(it) } },
                            )
                        }
                        SettingsGroup("限速") {
                            SettingsSwitchRow(
                                icon = Icons.Outlined.SlowMotionVideo,
                                title = "蜗牛模式",
                                supporting = "限制下载与上传速度，不影响在线播放",
                                checked = snailMode.enabled,
                                onCheckedChange = { scope.launch { sessionManager.setSnailMode(snailMode.copy(enabled = it)) } },
                            )
                            DependentRow(visible = snailMode.enabled) {
                                SettingsNavigationRow(
                                    icon = Icons.Outlined.Tune,
                                    title = "速度上限",
                                    supporting = snailMode.limitSummary(),
                                    onClick = { showSnailDialog = true },
                                    trailingIcon = null,
                                )
                            }
                        }
                    }

                    section(SettingsSection.Network) {
                        SettingsGroup(null) {
                            SettingsNavigationRow(
                                icon = Icons.Outlined.Public,
                                title = "网络代理",
                                supporting = proxySetting.summary(),
                                onClick = { showProxyDialog = true },
                                trailingIcon = null,
                            )
                            SettingsNavigationRow(
                                icon = Icons.Outlined.Dns,
                                title = "服务器域名",
                                supporting = domainSummary(domainChoice, activeDomain, domainProbes),
                                onClick = { showDomainDialog = true },
                                trailingIcon = null,
                            )
                        }
                    }

                    // 这一类都挂在账号上、随账号切换，不放进「网络」：那里是这台设备自己的连接设置
                    section(SettingsSection.Data) {
                        SettingsGroup("同步") {
                            SettingsSyncRow(
                                enabled = isSettingsSyncEnabled,
                                status = syncStatusText(syncStatus, lastSynced),
                                onEnabledChange = { scope.launch { sessionManager.setSettingsSyncEnabled(it) } },
                                onSyncNow = { scope.launch { settingsSync.syncNow() } },
                            )
                            SettingsSwitchRow(
                                icon = Icons.Outlined.History,
                                title = "同步播放记录",
                                supporting = "与 PikPak 官方客户端共用播放历史与续播进度",
                                checked = isSyncPlayHistoryEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setSyncPlayHistoryEnabled(it) } },
                            )
                        }
                        SettingsGroup("密码与凭据") {
                            SettingsNavigationRow(
                                icon = Icons.Outlined.Key,
                                title = "解压密码",
                                supporting = if (archivePasswords.isEmpty()) "尚无保存的密码" else "已保存 ${archivePasswords.size} 个",
                                onClick = { showArchivePasswords = true },
                                trailingIcon = null,
                            )
                            // WebDAV 仅限会员，免费账号与等级未取到时不出现
                            if (isFreeAccount == false) {
                                SettingsNavigationRow(
                                    icon = Icons.Outlined.Lan,
                                    title = "WebDAV",
                                    supporting = "供播放器、文件管理器等应用读取网盘",
                                    onClick = onOpenWebDav,
                                )
                            }
                        }
                    }

                    if (showAbout) {
                        section(SettingsSection.About) {
                            AboutSection(snackbarHostState)
                        }
                    }
                }
            }
        }
    }

    if (showMetaTubeDialog && metaTube != null) {
        // 令牌在打开对话框时才读：桌面端从系统保管处取，不必在设置页一打开就碰钥匙串
        val token by sessionManager.metaTubeTokenFlow.collectAsStateWithLifecycle(initialValue = null)
        token?.let { current ->
            MetaTubeSettingsDialog(
                service = metaTube,
                currentUrl = metaTubeUrl,
                currentToken = current,
                onSave = { url, newToken ->
                    showMetaTubeDialog = false
                    scope.launch {
                        sessionManager.setMetaTubeUrl(url)
                        sessionManager.setMetaTubeToken(newToken)
                    }
                },
                onDismiss = { showMetaTubeDialog = false },
            )
        }
    }

    if (showProxyDialog) {
        ProxySettingsDialog(
            current = proxySetting,
            onSave = { setting ->
                showProxyDialog = false
                scope.launch { sessionManager.saveProxySetting(setting) }
            },
            onDismiss = { showProxyDialog = false },
        )
    }

    if (showDomainDialog) {
        PikPakDomainDialog(
            choice = domainChoice,
            active = activeDomain,
            probes = domainProbes,
            probing = domainProbing,
            onChoose = { root -> scope.launch { sessionManager.setPikpakDomain(root) } },
            onProbeAgain = domainSelector::probeAgain,
            onDismiss = { showDomainDialog = false },
        )
    }

    if (showSnailDialog) {
        SnailModeDialog(
            current = snailMode,
            onSave = { mode ->
                showSnailDialog = false
                scope.launch { sessionManager.setSnailMode(mode) }
            },
            onDismiss = { showSnailDialog = false },
        )
    }

    if (showPlaybackQuality) {
        PlaybackQualityDialog(
            maxHeight = playbackMaxHeight,
            onSelect = { height -> if (height != null) scope.launch { sessionManager.setPlaybackMaxHeight(height) } },
            onDismiss = { showPlaybackQuality = false },
        )
    }

    if (showDownloadQuality) {
        PlaybackQualityDialog(
            maxHeight = downloadMaxHeight,
            onSelect = { scope.launch { sessionManager.setDownloadMaxHeight(it) } },
            onDismiss = { showDownloadQuality = false },
            title = "默认下载画质",
            description = "设定后下载视频不再询问。每个视频取不高于所选的最高一档，都高于所选时取最低一档；转码档存为 MP4。",
            unsetLabel = DOWNLOAD_QUALITY_UNSET,
        )
    }

    if (showArchivePasswords) {
        SavedArchivePasswordsDialog(
            passwords = archivePasswords,
            onDelete = { scope.launch { archivePasswordVault.forget(it) } },
            onDismiss = { showArchivePasswords = false },
        )
    }

    if (showDownloadDirDialog) {
        DownloadLocationDialog(
            description = downloadLocation.description,
            defaultPath = remember { downloadLocation.displayName("") },
            customPath = resolvedDownloadPath.takeIf { downloadDirPath.isNotEmpty() },
            onUseDefault = { scope.launch { sessionManager.setDownloadDirPath("") } },
            onPickFolder = pickDownloadDir,
            onDismiss = { showDownloadDirDialog = false },
        )
    }
}

/**
 * 下载位置：两个单选项，默认位置与自定义位置，各带路径。「恢复默认」就是选中默认那一项，「更改」是点自定义那一项。
 * [customPath] 为 null 表示正用默认位置。已选自定义时再点它，照旧弹目录选择框，换一个文件夹。
 */
@Composable
private fun DownloadLocationDialog(
    description: String,
    defaultPath: String,
    customPath: String?,
    onUseDefault: () -> Unit,
    onPickFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsChoiceDialog(
        icon = Icons.Outlined.FolderOpen,
        title = "下载位置",
        description = description,
        onDismiss = onDismiss,
    ) {
        SettingsChoiceOption(
            title = "默认位置",
            supporting = defaultPath,
            selected = customPath == null,
            onClick = onUseDefault,
        )
        SettingsChoiceOption(
            title = "自定义位置",
            supporting = customPath ?: "选择一个文件夹",
            selected = customPath != null,
            onClick = onPickFolder,
            // 已选中时这一行点下去是换文件夹，不是切换，给一个编辑的提示
            trailingIcon = if (customPath != null) Icons.Outlined.Edit else null,
        )
    }
}

/**
 * 设置的分类，也是桌面目录的条目。按用户要做的事分，不按功能模块分：
 * 原来「网盘」一类混着浏览、命名与刮削，「传输与网络」一类混着下载、默认打开方式与连接。
 */
internal enum class SettingsSection(val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Account("账号", Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
    Appearance("外观", Icons.Outlined.Palette, Icons.Filled.Palette),
    Browse("浏览与播放", Icons.Outlined.PlayCircle, Icons.Filled.PlayCircle),
    Saving("保存与命名", Icons.Outlined.DriveFileRenameOutline, Icons.Filled.DriveFileRenameOutline),
    // 与导航的「传输」同一款图标
    Transfer("下载与传输", Icons.Outlined.SwapVerticalCircle, Icons.Filled.SwapVerticalCircle),
    Network("网络", Icons.Outlined.Router, Icons.Filled.Router),
    Data("同步与数据", Icons.Outlined.CloudSync, Icons.Filled.CloudSync),
    About("关于", Icons.Outlined.Info, Icons.Filled.Info),
}

/**
 * 点目录跳去的一类。[settled] 之前（滚动动画途中）一直亮它，不让途经的各类轮流亮一下；
 * 到位之后只要位置没动就仍亮它：末尾几类矮，滚到底也到不了顶端，按位置算会亮成更靠前的一类。
 */
private class PinnedSection(val section: SettingsSection, val target: Int) {
    var settled by mutableStateOf(false)
}

/**
 * 目录该亮哪一类（ux-review M13）。依次：
 * 1. 点目录跳去的一类，滚动途中（[pinned] 的位置为 null）或停在跳到的位置上时亮它；
 * 2. 滚到底时亮最后一类：最后一类矮，起点到不了视口顶端，按起点算永远亮不到它；
 * 3. 其余按起点：起点已滚到视口顶端以下 [slack] 以内的最后一类。
 */
internal fun <T> activeSettingsSection(
    sections: List<T>,
    offsets: Map<T, Int>,
    scroll: Int,
    maxScroll: Int,
    slack: Int,
    pinned: Pair<T, Int?>?,
): T {
    if (pinned != null && pinned.first in sections) {
        val target = pinned.second
        if (target == null || target == scroll) return pinned.first
    }
    if (maxScroll > 0 && scroll >= maxScroll) return sections.last()
    return sections.lastOrNull { (offsets[it] ?: Int.MAX_VALUE) <= scroll + slack } ?: sections.first()
}

private fun syncStatusText(status: PikoSettingsSync.Status, lastSynced: Long?): String = when (status) {
    PikoSettingsSync.Status.SYNCING -> "同步中…"
    PikoSettingsSync.Status.FAILED -> "同步失败，改动设置或重新打开时重试"
    else -> lastSynced?.let {
        val time = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault())
        "上次同步于 ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    } ?: "登录后自动同步"
}

// 设置页自身宽到这个程度才放目录：目录 240dp，右边至少还要放得下一列 600dp 左右的卡片
private val SettingsIndexMinWidth = 900.dp

/** 目录的宽度，与应用侧边栏一样。八个短分类名用不着 M3 list-detail 默认的 360dp。 */
private val SettingsIndexWidth = 240.dp

// 滚动位置离一类的标题还差这么多时就算进了这一类：标题行本身不必完全滚出顶端
private val SectionHeadingSlack = 48.dp

/** 一类设置：类标题加内容，并报出自己在滚动内容里的起点。 */
@Composable
private fun SettingsSectionBlock(
    section: SettingsSection,
    first: Boolean,
    onPositioned: (Int) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.onGloballyPositioned { onPositioned(it.positionInParent().y.roundToInt()) }) {
        SettingsSectionTitle(section.title, first = first)
        content()
    }
}

/** 桌面左侧的分类目录，行的样式与应用侧边栏相同。 */
@Composable
private fun SettingsIndex(sections: List<SettingsSection>, current: SettingsSection, onSelect: (SettingsSection) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        sections.forEach { section ->
            SidebarItem(
                icon = section.icon,
                selectedIcon = section.selectedIcon,
                label = section.title,
                selected = section == current,
                onClick = { onSelect(section) },
            )
        }
    }
}

/**
 * 磁力链接与种子文件的默认打开方式：说明眼下是谁在打开，行尾是对应的按钮。不是默认时「设为默认」，
 * 登记过（不论是不是默认）时「取消关联」，随时能撤销、重做。只登记、没设成默认是常见的中间态：
 * Windows 上用户在系统设置里没选 Piko，或选了又换回别的应用，登记仍留着，要能从这里撤掉。
 * 整行不可点：两个方向的动作都有后果，放在明写着的按钮上。
 * 系统不能取消的（macOS，见 LinkAssociation.canUnregister）不给取消的按钮，已是默认时说明怎么换回去。
 *
 * 开发版也列出来，按钮置灰并写明原因：藏起来的话，在开发版里找这一项的人会以为功能不存在。
 */
@Composable
private fun LinkAssociationRow(
    association: LinkAssociation,
    state: LinkAssociationState,
    onStateChange: (LinkAssociationState) -> Unit,
    onFailure: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val isDefault = state == LinkAssociationState.Default
    val isRegistered = isDefault || state == LinkAssociationState.Registered
    val confirmHint = if (association.needsSystemConfirmation) "，设为默认需到系统设置确认" else ""
    val act: (Boolean) -> Unit = { register ->
        busy = true
        scope.launch {
            val done = if (register) association.register() else association.unregister()
            if (!done) onFailure(if (register) "无法设为默认打开方式" else "无法取消关联")
            // 不经系统设置的平台当场就改好了，窗口不会失焦再回来，这里重读一次
            onStateChange(association.state())
            busy = false
        }
    }
    val enabled = state != LinkAssociationState.Unavailable && !busy
    SettingsRow(
        title = "磁力链接与种子文件",
        icon = Icons.Outlined.Link,
        supporting = when {
            state == LinkAssociationState.Unavailable -> "仅安装版与便携版可设为默认打开方式"
            isDefault && !association.canUnregister -> "由 Piko 打开。换回需在其他应用中设为默认"
            isDefault -> "由 Piko 打开"
            isRegistered -> "已登记，未设为默认$confirmHint"
            else -> "由其他应用打开$confirmHint"
        },
        trailing = {
            if (isRegistered && association.canUnregister) {
                SettingsRowButton("取消关联", onClick = { act(false) }, enabled = enabled, primary = false)
            }
            if (!isDefault) SettingsRowButton("设为默认", onClick = { act(true) }, enabled = enabled)
        },
    )
}

/** 深色模式三选一。放在行尾（照 Windows 设置的下拉位置）；手机竖握时行尾放不下，挪到标题下方占满一行。 */
@Composable
private fun ThemeModeRow(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    val style = settingsStyle()
    val choice: @Composable (Boolean) -> Unit = { fill ->
        SettingsSegmentedChoice(
            options = ThemeMode.entries,
            selected = mode,
            label = { it.label },
            onSelect = onModeChange,
            fill = fill,
        )
    }
    SettingsRow(
        title = "深色模式",
        icon = Icons.Outlined.DarkMode,
        trailing = if (style.trailingChoices) ({ choice(false) }) else null,
        below = if (style.trailingChoices) null else ({ choice(true) }),
    )
}

/**
 * 主题色：系统取色加内置主题，一排圆形色块，选中的打勾，名字写在标题下方。
 *
 * 色块显示的是该主题在当前深浅下的 primary，即选中后按钮与强调色的实际颜色，而非种子色：
 * 种子色经 TonalSpot 调和后会变淡，按种子色画会与结果对不上。
 * 触控区按平台的下限给（移动端 48dp），放不下一行时横向滚动。
 */
@Composable
private fun ThemeColorRow(appearance: Appearance, onSeedChange: (SeedTheme?) -> Unit) {
    val dark = appearance.isDark()
    val platform = LocalPikoPlatform.current
    val style = settingsStyle()
    // 色块画在触控区正中，触控区比色块宽出的一半是看不见的边。整排往前挪这一截，第一个色块的左缘才与标题文字齐
    val swatchInset = (style.swatchTarget - style.swatchSize) / 2
    SettingsRow(
        title = "主题色",
        icon = Icons.Outlined.Palette,
        supporting = appearance.effectiveSeed?.label ?: "系统取色",
        below = {
            val swatchScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .offset(x = -swatchInset)
                    .selectableGroup()
                    .verticalWheelScrollsRow(swatchScroll)
                    .horizontalScroll(swatchScroll),
            ) {
                if (platform.supportsDynamicColor) {
                    val scheme = platform.dynamicColorScheme(dark)
                    ColorSwatch(
                        color = scheme.primary,
                        onColor = scheme.onPrimary,
                        label = "系统取色",
                        selected = appearance.seed == null,
                        idleIcon = Icons.Outlined.Wallpaper,
                        onClick = { onSeedChange(null) },
                    )
                }
                SeedTheme.entries.forEach { theme ->
                    val scheme = if (dark) theme.dark else theme.light
                    ColorSwatch(
                        color = scheme.primary,
                        onColor = scheme.onPrimary,
                        label = theme.label,
                        selected = appearance.effectiveSeed == theme,
                        onClick = { onSeedChange(theme) },
                    )
                }
            }
        },
    )
}

@Composable
private fun ColorSwatch(
    color: Color,
    onColor: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    idleIcon: ImageVector? = null,
) {
    val style = settingsStyle()
    Box(
        modifier = Modifier
            .size(style.swatchTarget)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(style.swatchSize)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            val icon = if (selected) Icons.Outlined.Check else idleIcon
            if (icon != null) Icon(icon, contentDescription = null, tint = onColor, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * 设置同步：开关与「立即同步」在同一行。立即同步只在开着时有意义，单独占一行是把一件事画成两件。
 * 开着时说明是同步的状态，关着时说明它同步什么、存在哪。
 */
@Composable
private fun SettingsSyncRow(
    enabled: Boolean,
    status: String,
    onEnabledChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
) {
    SettingsSwitchRow(
        icon = Icons.Outlined.CloudSync,
        title = "同步设置",
        supporting = if (enabled) status else "设置存于网盘的 .piko 文件夹，换设备登录后自动恢复",
        checked = enabled,
        onCheckedChange = onEnabledChange,
        extraTrailing = if (enabled) ({ TooltipIconButton(Icons.Outlined.Sync, "立即同步", onSyncNow) }) else null,
    )
}
