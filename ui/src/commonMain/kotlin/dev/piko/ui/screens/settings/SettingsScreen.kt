package dev.piko.ui.screens.settings

import kotlin.time.Instant
import dev.piko.shared.sync.PikoSettingsSync
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.SwapVerticalCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.SwapVerticalCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import dev.piko.ui.components.PikoDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.ProvideTextStyle
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.IslandHeaderSpace
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.outlined.WebAsset
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.net.ProxySetting
import dev.piko.data.auth.SnailMode
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Tune
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.platform.LinkAssociationState
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.archive.SavedArchivePasswordsDialog
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.SeedTheme
import dev.piko.ui.theme.ThemeMode
import dev.piko.ui.theme.effectiveSeed
import dev.piko.ui.theme.isDark
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 设置页：外观、文件名解析、浏览与下载，从「我的」进入。[onBackClick] 为 null 时不显示返回按钮
 * （expanded 窗口里它是「我的」旁边的默认详情栏，没有可返回的地方）。
 *
 * 设置项用 M3 Expressive 的分段列表（SegmentedListItem，组内 2dp 间隙、首尾圆角），
 * 取代原先每组一张 18dp 内边距的卡片加手工拼的行。行高回到列表规范的 56/72dp，
 * 开关行整行可点并由组件报告开关状态，读屏不必再单独聚焦到 Switch 上。
 *
 * Documentation references:
 * - m3-material-mirror/pages/components/lists.md（Gaps & dividers：容器化列表用间隙分组）
 * - m3-material-mirror/pages/components/switch.md
 */
@Composable
fun SettingsScreen(
    onBackClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * 账号一类的内容（账号卡片与退出登录）。有整条侧边栏的宽窗口里没有「我的」页，账号放在设置里，排在最前；
     * 手机上它们在「我的」页，这里为 null。
     */
    account: (@Composable ColumnScope.() -> Unit)? = null,
    /** 关于一节（[AboutSection]）。与账号同理：有「我的」页时它在那里，这里不放。 */
    showAbout: Boolean = false,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val sessionManager = services.preferences
    val isSpoilerBlurEnabled by sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)
    val isHeuristicFilterEnabled by sessionManager.heuristicFilterFlow.collectAsStateWithLifecycle(initialValue = true)
    val isNameParsingEnabled by sessionManager.nameParsingFlow.collectAsStateWithLifecycle(initialValue = true)
    val isBundleSubtitlesEnabled by sessionManager.bundleSubtitlesFlow.collectAsStateWithLifecycle(initialValue = true)
    val isAutoCleanNamesEnabled by sessionManager.autoCleanNamesFlow.collectAsStateWithLifecycle(initialValue = false)
    val isSyncPlayHistoryEnabled by sessionManager.syncPlayHistoryFlow.collectAsStateWithLifecycle(initialValue = true)
    val isSettingsSyncEnabled by sessionManager.settingsSyncFlow.collectAsStateWithLifecycle(initialValue = true)
    val settingsSync = LocalPikoServices.current.settingsSync
    val syncStatus by settingsSync.status.collectAsStateWithLifecycle()
    val lastSynced by settingsSync.lastSynced.collectAsStateWithLifecycle()
    val isConcurrentAccelerationEnabled by sessionManager.concurrentAccelerationFlow.collectAsStateWithLifecycle(initialValue = true)
    val downloadDirPath by sessionManager.downloadDirPathFlow.collectAsStateWithLifecycle(initialValue = "")
    val scope = rememberCoroutineScope()

    val archivePasswordVault = remember(sessionManager) { ArchivePasswordVault(sessionManager) }
    val archivePasswords by archivePasswordVault.passwords.collectAsStateWithLifecycle(initialValue = emptyList())
    var showArchivePasswords by remember { mutableStateOf(false) }

    var showDownloadDirDialog by remember { mutableStateOf(false) }
    val proxySetting by sessionManager.proxySettingFlow.collectAsStateWithLifecycle(initialValue = ProxySetting())
    var showProxyDialog by remember { mutableStateOf(false) }
    val domainSelector = LocalPikoServices.current.domainSelector
    val domainChoice by sessionManager.pikpakDomainFlow.collectAsStateWithLifecycle(initialValue = "")
    val activeDomain by domainSelector.active.collectAsStateWithLifecycle()
    val domainProbes by domainSelector.probes.collectAsStateWithLifecycle()
    val domainProbing by domainSelector.probing.collectAsStateWithLifecycle()
    var showDomainDialog by remember { mutableStateOf(false) }
    val snailMode by sessionManager.snailModeFlow.collectAsStateWithLifecycle(initialValue = SnailMode())
    var showSnailDialog by remember { mutableStateOf(false) }
    val downloadLocation = platform.downloadLocation
    val resolvedDownloadPath = remember(downloadDirPath) { downloadLocation.displayName(downloadDirPath) }
    // 选完不关对话框，让用户在卡片上看到新位置再点「完成」
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
    val sections = if (showAbout) SettingsSection.entries else SettingsSection.entries - SettingsSection.About
    // 各类在滚动内容里的起点，左栏据此跳转与高亮
    val sectionOffsets = remember { mutableStateMapOf<SettingsSection, Int>() }
    // 左栏点过的一类。末尾几类矮，滚到底也到不了顶端，这时按起点算会亮成更靠前的一类，点了却亮别的
    var requestedSection by remember { mutableStateOf<SettingsSection?>(null) }
    val headingSlack = with(LocalDensity.current) { SectionHeadingSlack.roundToPx() }
    // 以 sections 为键：窗口拉宽出现「关于」时换一份，否则一直按首次组合时的目录算，新出现的一类永远亮不了
    val currentSection by remember(sections, headingSlack) {
        derivedStateOf {
            val y = scrollState.value
            val atEnd = y >= scrollState.maxValue
            requestedSection?.takeIf { atEnd && (sectionOffsets[it] ?: 0) > y }
                ?: sections.lastOrNull { (sectionOffsets[it] ?: Int.MAX_VALUE) <= y + headingSlack }
                ?: sections.first()
        }
    }

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
            // 放得下左栏时左栏是目录，右边各类照旧从上到下连着排，每类带标题、组内分栏。不做成选一类显示一类：
            // 几类都只有几组，分页后每页大半是空的，找一项还得先猜它归哪一类。放不下时是一整列往下滚
            val paged = maxWidth >= SettingsIndexMinWidth
            Row(modifier = Modifier.fillMaxSize()) {
                if (paged) {
                    SettingsIndex(
                        sections = sections,
                        current = currentSection,
                        onSelect = { section ->
                            requestedSection = section
                            scope.launch { scrollState.animateScrollTo(sectionOffsets[section] ?: 0) }
                        },
                        modifier = Modifier.width(SettingsIndexWidth).padding(start = 12.dp, top = 16.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        // 有目录时照 M3 的窗格边距留 24dp。内容收在阅读宽度里居中：各类的组数不一，
                        // 按组分栏时一组的类只占最左一栏、两组的并排，一路下来参差不齐
                        .padding(horizontal = if (paged) 24.dp else 16.dp)
                        .padding(bottom = 24.dp)
                        .readableWidth(),
                ) {
                    // 有目录时每类自带标题，只有一组的一类不再给组标题；一整列往下滚时没有类标题，要靠组标题分开各类
                    fun soleTitle(section: SettingsSection) = if (paged) null else section.title
                    fun onPositioned(section: SettingsSection): (Int) -> Unit = { sectionOffsets[section] = it }

                    SettingsSectionBlock(SettingsSection.Account, paged, onPositioned(SettingsSection.Account)) {
                        if (account != null) SettingsGroup(if (paged) null else "账号") { account() }
                        SettingsGroup(if (account != null || !paged) "同步" else null) {
                            SettingsSyncRow(
                                index = 0, count = 2,
                                enabled = isSettingsSyncEnabled,
                                status = syncStatusText(syncStatus, lastSynced),
                                onEnabledChange = { scope.launch { sessionManager.setSettingsSyncEnabled(it) } },
                                onSyncNow = { scope.launch { settingsSync.syncNow() } },
                            )
                            SettingsSwitchRow(
                                index = 1, count = 2,
                                icon = Icons.Outlined.History,
                                title = "同步播放记录",
                                supporting = "与 PikPak 官方客户端共用播放历史与续播进度",
                                checked = isSyncPlayHistoryEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setSyncPlayHistoryEnabled(it) } },
                            )
                        }
                    }

                    SettingsSectionBlock(SettingsSection.Appearance, paged, onPositioned(SettingsSection.Appearance)) {
                    SettingsGroup(soleTitle(SettingsSection.Appearance)) {
                        val appearance = LocalAppearance.current
                        val compactTitleBar = platform.compactTitleBar
                        val appearanceCount = if (compactTitleBar != null) 4 else 3
                        ThemeModeRow(
                            mode = appearance.mode,
                            onModeChange = { scope.launch { sessionManager.setThemeMode(it.name) } },
                            count = appearanceCount,
                        )
                        ThemeColorRow(
                            appearance = appearance,
                            onSeedChange = { scope.launch { sessionManager.setThemeSeed(it?.name) } },
                            count = appearanceCount,
                        )
                        if (compactTitleBar != null) {
                            val compact by compactTitleBar.enabled.collectAsState()
                            SettingsSwitchRow(
                                index = 2, count = appearanceCount,
                                icon = Icons.Outlined.WebAsset,
                                title = "紧凑标题栏",
                                supporting = "窗口按钮并入界面右上角，省去单独的标题栏",
                                checked = compact,
                                onCheckedChange = compactTitleBar::set,
                            )
                        }
                        // 与系统设置取或，见 PikoMotionScale。系统已关掉动画时这里开不开都一样，提示一句免得以为开关失灵
                        val motionScale = platform.motionScale
                        SettingsSwitchRow(
                            index = appearanceCount - 1, count = appearanceCount,
                            icon = Icons.Outlined.Animation,
                            title = "减少动画",
                            supporting = if (motionScale.systemScale == 0f) "系统已关闭动画" else "界面切换不播放过渡",
                            checked = motionScale.appReduced,
                            onCheckedChange = { scope.launch { sessionManager.setReduceMotion(it) } },
                        )
                    }
                    }

                    SettingsSectionBlock(SettingsSection.Drive, paged, onPositioned(SettingsSection.Drive)) {
                    SettingsGroup(soleTitle(SettingsSection.Drive)) {
                        // 启发式折叠只在解析开着时有意义，关掉解析就收起这一项，不留一行灰掉的开关；收起与出现要看得见。
                        // 不缩进表示从属：行背景是整条分段，只缩内容读起来像错位
                        val driveCount = if (isNameParsingEnabled) 5 else 4
                        SettingsSwitchRow(
                            index = 0, count = driveCount,
                            icon = Icons.Outlined.TextFields,
                            title = "文件名解析",
                            supporting = "按作品、分区与集数整理，标出发布组与清晰度",
                            checked = isNameParsingEnabled,
                            onCheckedChange = { scope.launch { sessionManager.setNameParsingEnabled(it) } },
                        )
                        DependentRow(visible = isNameParsingEnabled) {
                            SettingsSwitchRow(
                                index = 1, count = driveCount,
                                icon = Icons.Outlined.AutoFixHigh,
                                title = "启发式折叠",
                                supporting = "收起广告、样片、说明文件等次要项",
                                checked = isHeuristicFilterEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setHeuristicFilterEnabled(it) } },
                            )
                        }
                        SettingsSwitchRow(
                            index = driveCount - 3, count = driveCount,
                            icon = Icons.Outlined.VisibilityOff,
                            title = "缩略图防窥",
                            supporting = "模糊显示缩略图与封面",
                            checked = isSpoilerBlurEnabled,
                            onCheckedChange = { scope.launch { sessionManager.setSpoilerBlurEnabled(it) } },
                        )
                        SettingsSwitchRow(
                            index = driveCount - 2, count = driveCount,
                            icon = Icons.Outlined.CleaningServices,
                            title = "自动修正名称",
                            supporting = "新建与重命名时直接去掉 PikPak 不支持的字符",
                            checked = isAutoCleanNamesEnabled,
                            onCheckedChange = { scope.launch { sessionManager.setAutoCleanNamesEnabled(it) } },
                        )
                        SettingsNavigationRow(
                            index = driveCount - 1, count = driveCount,
                            icon = Icons.Outlined.Key,
                            title = "解压密码",
                            supporting = if (archivePasswords.isEmpty()) "尚无保存的密码" else "已保存 ${archivePasswords.size} 个",
                            onClick = { showArchivePasswords = true },
                            trailingIcon = null,
                        )
                    }
                    }

                    SettingsSectionBlock(SettingsSection.Transfer, paged, onPositioned(SettingsSection.Transfer)) {
                        SettingsGroup("下载") {
                            SettingsNavigationRow(
                                index = 0, count = 3,
                                icon = Icons.Outlined.FolderOpen,
                                title = "下载位置",
                                supporting = resolvedDownloadPath,
                                onClick = { showDownloadDirDialog = true },
                                trailingIcon = null,
                            )
                            // 字幕靠解析配到视频上。解析在另一页，关掉解析时写明原因，而不是只把开关灰掉
                            SettingsSwitchRow(
                                index = 1, count = 3,
                                icon = Icons.Outlined.Subtitles,
                                title = "保存配套字幕",
                                supporting = if (isNameParsingEnabled) "保存视频时一并保存外挂字幕" else "需先在「网盘」里开启文件名解析",
                                checked = isBundleSubtitlesEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setBundleSubtitlesEnabled(it) } },
                                enabled = isNameParsingEnabled,
                            )
                            SettingsSwitchRow(
                                index = 2, count = 3,
                                icon = Icons.Outlined.Speed,
                                title = "并发加速",
                                supporting = "多连接下载，提升速度",
                                checked = isConcurrentAccelerationEnabled,
                                onCheckedChange = { scope.launch { sessionManager.setConcurrentAccelerationEnabled(it) } },
                            )
                        }
                        SettingsGroup("限速") {
                            val snailCount = if (snailMode.enabled) 2 else 1
                            SettingsSwitchRow(
                                index = 0, count = snailCount,
                                icon = Icons.Outlined.SlowMotionVideo,
                                title = "蜗牛模式",
                                supporting = "限制下载与上传的速度，在线播放不受限",
                                checked = snailMode.enabled,
                                onCheckedChange = { scope.launch { sessionManager.setSnailMode(snailMode.copy(enabled = it)) } },
                            )
                            // 上限只在开着时起作用，关着时收起
                            DependentRow(visible = snailMode.enabled) {
                                SettingsNavigationRow(
                                    index = 1, count = snailCount,
                                    icon = Icons.Outlined.Tune,
                                    title = "速度上限",
                                    supporting = snailMode.limitSummary(),
                                    onClick = { showSnailDialog = true },
                                    trailingIcon = null,
                                )
                            }
                        }
                        if (linkAssociation != null) SettingsGroup("链接") {
                            LinkAssociationRow(
                                association = linkAssociation,
                                state = linkAssociationState,
                                onStateChange = { linkAssociationState = it },
                                onFailure = { message -> scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true) } },
                            )
                        }
                        SettingsGroup("连接") {
                            SettingsNavigationRow(
                                index = 0, count = 2,
                                icon = Icons.Outlined.Public,
                                title = "网络代理",
                                supporting = proxySetting.summary(),
                                onClick = { showProxyDialog = true },
                                trailingIcon = null,
                            )
                            SettingsNavigationRow(
                                index = 1, count = 2,
                                icon = Icons.Outlined.Dns,
                                title = "服务器域名",
                                supporting = domainSummary(domainChoice, activeDomain, domainProbes),
                                onClick = { showDomainDialog = true },
                                trailingIcon = null,
                            )
                        }
                    }

                    if (showAbout) {
                        SettingsSectionBlock(SettingsSection.About, paged, onPositioned(SettingsSection.About)) {
                            SettingsGroup(soleTitle(SettingsSection.About)) {
                                AboutSection(snackbarHostState)
                            }
                        }
                    }
                }
            }
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
 * 下载位置：两个单选项，默认位置与自定义位置，各带路径。照 M3 的基本对话框排：头部图标、标题、一句说明，
 * 可选的内容放在正文里，动作只有「完成」一个。
 *
 * 原来正文里一个「更改文件夹」、一个「恢复默认」，底部再一个「完成」，三个动作并排，而 M3 dialogs 规定对话框
 * 至多两个动作（一个确认、一个取消）。换成单选列表后「恢复默认」就是选中默认那一项，「更改」是点自定义那一项，
 * 两样都成了正文里的选项，读起来也是「现在用的是哪一个」，不必再挂一个「默认」标签。
 *
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
    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
        title = { Text("下载位置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DownloadLocationOption(
                        title = "默认位置",
                        path = defaultPath,
                        selected = customPath == null,
                        onClick = onUseDefault,
                    )
                    DownloadLocationOption(
                        title = "自定义位置",
                        path = customPath ?: "选择一个文件夹",
                        selected = customPath != null,
                        onClick = onPickFolder,
                        // 已选中时这一行点下去是换文件夹，不是切换，给一个编辑的提示
                        trailingIcon = if (customPath != null) Icons.Outlined.Edit else null,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/**
 * 单选列表的一行：整行可点、至少 56dp 高，单选钮不单独接点击（onClick 为 null），免得点钮和点行各触发一次。
 * 路径可能很长，最多两行，从中间省掉的话头尾都看不全，末尾省略保住开头的盘符与上级目录。
 */
@Composable
private fun DownloadLocationOption(
    title: String,
    path: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingIcon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
            )
        }
    }
}

/** 设置的分组，也是宽窗口左侧目录的条目。 */
/**
 * 设置的分类，按「想做什么」分，不按功能模块分：原来九类里有四类只有一两行，点进去几乎是空页。
 * 只有一两项的并进相近的一类，一类里再用组标题分开。
 */
private enum class SettingsSection(val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Account("账号与同步", Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
    Appearance("外观", Icons.Outlined.Palette, Icons.Filled.Palette),
    // 与侧边栏快速访问的「网盘」、导航的「传输」同一款图标
    Drive("网盘", Icons.Outlined.Cloud, Icons.Filled.Cloud),
    Transfer("传输与网络", Icons.Outlined.SwapVerticalCircle, Icons.Filled.SwapVerticalCircle),
    About("关于", Icons.Outlined.Info, Icons.Filled.Info),
}

private fun syncStatusText(status: PikoSettingsSync.Status, lastSynced: Long?): String = when (status) {
    PikoSettingsSync.Status.SYNCING -> "同步中…"
    PikoSettingsSync.Status.FAILED -> "上次同步失败，改动设置或重新打开时会再试"
    else -> lastSynced?.let {
        val time = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault())
        "上次同步于 ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    } ?: "登录后自动同步"
}

// 设置页自身宽到这个程度才放左侧目录。expanded 窗口里设置页只是「我的」旁边的详情栏，多数时候放不下
private val SettingsIndexMinWidth = 900.dp

/**
 * 左侧分类栏的宽度。M3 list-detail 固定窗格的默认值是 360dp（large 起 412dp），这里取与应用侧边栏一样的 240dp：
 * 五个短分类名用不着那么宽，省下的宽度让右边多排一栏。
 */
private val SettingsIndexWidth = 240.dp


// 滚动位置离一类的标题还差这么多时就算进了这一类：标题行本身不必完全滚出顶端
private val SectionHeadingSlack = 48.dp

/**
 * 一类设置。有目录时带类标题，并报出自己在滚动内容里的起点；没有目录时只是把几组原样排进外层 Column。
 */
@Composable
private fun SettingsSectionBlock(
    section: SettingsSection,
    withIndex: Boolean,
    onPositioned: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!withIndex) {
        content()
        return
    }
    Column(modifier = Modifier.onGloballyPositioned { onPositioned(it.positionInParent().y.roundToInt()) }) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, top = if (section.ordinal == 0) 16.dp else 40.dp),
        )
        content()
    }
}

@Composable
private fun SettingsGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
            )
        } else {
            Spacer(Modifier.height(16.dp))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            content = content,
        )
    }
}

/** 宽窗口左侧的分组目录，点击滚到对应分组，滚动时高亮当前所在的组。 */
@Composable
private fun SettingsIndex(sections: List<SettingsSection>, current: SettingsSection, onSelect: (SettingsSection) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sections.forEach { section ->
            NavigationDrawerItem(
                icon = { Icon(if (section == current) section.selectedIcon else section.icon, contentDescription = null) },
                label = { Text(section.title) },
                selected = section == current,
                onClick = { onSelect(section) },
            )
        }
    }
}

// 选中色与底色取同一值：开关行用 checked 重载拿开关语义，而它把 checked 当作选中，
// 开着的行会换成选中底色与选中形状，一组设置里亮一块暗一块。开关状态由 Switch 表达。
@Composable
internal fun settingsRowColors() =
    ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        selectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )

/**
 * 不可点的分段行：外观与可点的 SegmentedListItem 一致，只是没有点击态。
 *
 * 不直接用 SegmentedListItem 的无点击重载：桌面端的 material3 停在 1.12.0-alpha03（原因见
 * libs.versions.toml），那一版的 SegmentedListItem 只有带 onClick 与带 checked 的两种。
 * 也不套经典 ListItem：它把图标放在整行垂直居中，这两行下面挂着按钮组与色块，图标会悬在
 * 标题与控件之间；它的图标与文字间距也比分段列表宽，标题和相邻开关行对不齐。
 * 所以自己排：图标与标题首行顶端对齐，间距取分段列表的数值。
 */
@Composable
private fun StaticSegmentedRow(
    shapes: ListItemShapes,
    leadingContent: @Composable () -> Unit,
    supportingContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = shapes.shape, color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                leadingContent()
            }
            Spacer(modifier = Modifier.width(StaticRowLeadingGap))
            // 颜色走 LocalContentColor，不写进 TextStyle：副标题里嵌着按钮组，按钮靠 LocalContentColor
            // 给选中项换浅色字，TextStyle 里的颜色优先级更高，会把它盖掉
            Column(modifier = Modifier.weight(1f)) {
                CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
                    ProvideTextStyle(MaterialTheme.typography.bodyLarge) { content() }
                }
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { supportingContent() }
                }
            }
        }
    }
}

// SegmentedListItem 图标与标题之间的距离，经典 ListItem 是 16dp
private val StaticRowLeadingGap = 12.dp

/**
 * 磁力链接与种子文件的默认打开方式：说明眼下是谁在打开，行尾一个按钮，不是 Piko 时「设为默认」，
 * 是 Piko 时「取消关联」，随时能撤销、重做。整行不可点：两个方向的动作都有后果，放在明写着的按钮上。
 * 系统不能取消的（macOS，见 LinkAssociation.canUnregister）已是默认时不给按钮，说明怎么换回去。
 *
 * 开发版也列出来，按钮不可点并写明原因：藏起来的话，在开发版里找这一项的人会以为功能不存在。
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
    StaticSegmentedRow(
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        leadingContent = { Icon(Icons.Outlined.Link, contentDescription = null) },
        supportingContent = {
            Text(
                when {
                    state == LinkAssociationState.Unavailable -> "开发版不能设为默认打开方式，需用安装版或便携版"
                    isDefault && !association.canUnregister -> "由 Piko 打开。要换回其他应用，在该应用中设为默认"
                    isDefault -> "由 Piko 打开"
                    association.needsSystemConfirmation -> "由其他应用打开。设为默认需在系统设置中确认"
                    else -> "由其他应用打开"
                },
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("磁力链接与种子文件", modifier = Modifier.weight(1f))
            if (!isDefault || association.canUnregister) TextButton(
                enabled = state != LinkAssociationState.Unavailable && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val done = if (isDefault) association.unregister() else association.register()
                        if (!done) onFailure(if (isDefault) "无法取消关联" else "无法设为默认打开方式")
                        // 不经系统设置的平台当场就改好了，窗口不会失焦再回来，这里重读一次
                        onStateChange(association.state())
                        busy = false
                    }
                },
            ) { Text(if (isDefault) "取消关联" else "设为默认") }
        }
    }
}

/** 深色模式三选一，用 M3 Expressive 的连体按钮组，与播放器倍速选择的写法一致。 */
@Composable
private fun ThemeModeRow(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit, count: Int) {
    StaticSegmentedRow(
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = count),
        leadingContent = { Icon(Icons.Outlined.DarkMode, contentDescription = null) },
        supportingContent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                ThemeMode.entries.forEachIndexed { index, option ->
                    ToggleButton(
                        checked = option == mode,
                        onCheckedChange = { onModeChange(option) },
                        shapes = connectedToggleShapes(index, ThemeMode.entries.size),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(option.label, maxLines = 1)
                    }
                }
            }
        },
        content = { Text("深色模式") },
    )
}

/**
 * 主题色：系统取色加内置主题，一排圆形色块，选中的打勾，名字写在标题下方。
 *
 * 色块显示的是该主题在当前深浅下的 primary，即选中后按钮与强调色的实际颜色，而非种子色：
 * 种子色经 TonalSpot 调和后会变淡，按种子色画会与结果对不上。
 * 触控区按 48dp 下限给，七个在窄屏上放不下一行，改为横向滚动。
 */
@Composable
private fun ThemeColorRow(appearance: Appearance, onSeedChange: (SeedTheme?) -> Unit, count: Int) {
    val dark = appearance.isDark()
    val platform = LocalPikoPlatform.current
    val selectedLabel = appearance.effectiveSeed?.label ?: "系统取色"
    StaticSegmentedRow(
        shapes = ListItemDefaults.segmentedShapes(index = 1, count = count),
        leadingContent = { Icon(Icons.Outlined.Palette, contentDescription = null) },
        supportingContent = {
            Column {
                Text(selectedLabel)
                val swatchScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
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
            }
        },
        content = { Text("主题色") },
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
    Box(
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            val icon = if (selected) Icons.Outlined.Check else idleIcon
            if (icon != null) Icon(icon, contentDescription = null, tint = onColor, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun SettingsSwitchRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        shapes = stableSegmentedShapes(index, count),
        colors = settingsRowColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        // 开关只作指示，整行的 checked 语义已由列表项提供
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        supportingContent = { Text(supporting) },
        content = { Text(title) },
    )
}

/**
 * 设置同步：开关与「立即同步」在同一行。立即同步只在开着时有意义，单独占一行是把一件事画成两件。
 * 开着时副文本是同步的状态，关着时说明它同步什么、存在哪。
 */
@Composable
private fun SettingsSyncRow(
    index: Int,
    count: Int,
    enabled: Boolean,
    status: String,
    onEnabledChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
) {
    SegmentedListItem(
        checked = enabled,
        onCheckedChange = onEnabledChange,
        shapes = stableSegmentedShapes(index, count),
        colors = settingsRowColors(),
        leadingContent = { Icon(Icons.Outlined.CloudSync, contentDescription = null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (enabled) TooltipIconButton(Icons.Outlined.Sync, "立即同步", onSyncNow)
                Switch(checked = enabled, onCheckedChange = null)
            }
        },
        supportingContent = {
            Text(if (enabled) status else "外观、网盘与播放的设置和解压成功过的密码存在网盘的 .piko 文件夹，换设备登录时带过去")
        },
        content = { Text("同步设置") },
    )
}

/**
 * 依赖上面某个开关的行：开关关着时收起，而不是灰着留在那里。一行灰掉的设置读起来像「坏了」或「没权限」，
 * 收起则明说「这时它不起作用」；收起与展开有动画，看得出是上面那个开关带出来的。
 */
@Composable
private fun ColumnScope.DependentRow(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
    ) { content() }
}

/**
 * 分段行在各状态下都用同一个形状。库的默认按下、悬停时换形状并做形变动画，内侧的小圆角冲过头会算出负值，
 * 与连体按钮是同一个崩溃（见 connectedToggleShapes）；选中态换形状也会让开着的开关行与关着的长得不一样。
 */
@Composable
private fun stableSegmentedShapes(index: Int, count: Int) =
    ListItemDefaults.segmentedShapes(index = index, count = count).let {
        it.copy(selectedShape = it.shape, pressedShape = it.shape, focusedShape = it.shape, hoveredShape = it.shape, draggedShape = it.shape)
    }

@Composable
internal fun SettingsNavigationRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    supporting: String,
    onClick: () -> Unit,
    /**
     * 行尾图标说明点了去哪：去下一页是箭头（默认），离开应用是外链图标，弹对话框或当场执行的为 null，什么也不画。
     * 箭头在 M3 的列表里只说「去下一页」这一件事，给每个可点的行都画上，它就什么也不说明了。
     */
    trailingIcon: ImageVector? = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
    /** 两栏布局里这一行对应的页正显示在右侧。 */
    selected: Boolean = false,
    /** 亮起时换成的实心图标，与侧边栏一样。 */
    selectedIcon: ImageVector = icon,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = stableSegmentedShapes(index, count),
        colors = if (selected) {
            ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            settingsRowColors()
        },
        leadingContent = { Icon(if (selected) selectedIcon else icon, contentDescription = null) },
        trailingContent = trailingIcon?.let { trailing -> { Icon(trailing, contentDescription = null) } },
        supportingContent = {
            Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        content = { Text(title) },
    )
}
