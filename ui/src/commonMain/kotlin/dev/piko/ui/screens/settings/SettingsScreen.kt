package dev.piko.ui.screens.settings

import dev.piko.data.auth.AppLanguage
import androidx.compose.material.icons.outlined.Language
import dev.piko.shared.i18n.tr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
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
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SwapVerticalCircle
import androidx.compose.material.icons.outlined.SwipeVertical
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
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVerticalCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
import dev.piko.shared.sync.EncryptedSyncStatus
import dev.piko.shared.sync.PikoSettingsSync
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.PassphraseUpdateResult
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.IslandHeaderSpace
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LinkAssociationState
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.archive.SavedArchivePasswordsDialog
import dev.piko.ui.screens.drive.SidebarItem
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalFramed
import dev.piko.ui.theme.SeedTheme
import dev.piko.ui.theme.ThemeMode
import dev.piko.ui.theme.effectiveSeed
import dev.piko.ui.theme.isDark
import dev.piko.ui.theme.isEInk
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
 * 行与组的外观在 SettingsLayout.kt，后来加设置项照 ui/AGENTS.md「设置页」一节。
 */
@Composable
fun SettingsScreen(
    onBackClick: (() -> Unit)?,
    onOpenWebDav: () -> Unit,
    onOpenPlayerCustomization: () -> Unit = {},
    onOpenDriveAppearanceCustomization: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** 账号一类的内容（[dev.piko.ui.workbench.AccountSettings]）。桌面没有「我的」页，账号放在设置的最前；移动端为 null。 */
    account: (@Composable ColumnScope.() -> Unit)? = null,
    /** 关于一类（[AboutSection]）。与账号同理：移动端它在「我的」页，这里不放。 */
    showAbout: Boolean = false,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val sessionManager = services.preferences
    val isSpoilerBlurEnabled by sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(
        initialValue = sessionManager.currentSnapshot.spoilerBlurEnabled,
    )
    val isHeuristicFilterEnabled by sessionManager.heuristicFilterFlow.collectAsStateWithLifecycle(initialValue = true)
    val isNameParsingEnabled by sessionManager.nameParsingFlow.collectAsStateWithLifecycle(initialValue = true)
    val foldersFirst by sessionManager.foldersFirstFlow.collectAsStateWithLifecycle(initialValue = true)
    val isPerFolderSortEnabled by sessionManager.perFolderSortFlow.collectAsStateWithLifecycle(initialValue = false)
    val isBundleSubtitlesEnabled by sessionManager.bundleSubtitlesFlow.collectAsStateWithLifecycle(initialValue = true)
    val isAutoCleanNamesEnabled by sessionManager.autoCleanNamesFlow.collectAsStateWithLifecycle(initialValue = false)
    val isHardwareDecodingEnabled by sessionManager.hardwareDecodingFlow.collectAsStateWithLifecycle(initialValue = true)
    val isClipAutoExtendEnabled by sessionManager.clipAutoExtendFlow.collectAsStateWithLifecycle(initialValue = true)
    val skipFeedExitConfirm by sessionManager.skipFeedExitConfirmFlow.collectAsStateWithLifecycle(
        initialValue = sessionManager.currentSnapshot.skipFeedExitConfirm,
    )
    val isSyncPlayHistoryEnabled by sessionManager.syncPlayHistoryFlow.collectAsStateWithLifecycle(initialValue = true)
    val isSettingsSyncEnabled by sessionManager.settingsSyncFlow.collectAsStateWithLifecycle(initialValue = true)
    val settingsSync = services.settingsSync
    val syncStatus by settingsSync.status.collectAsStateWithLifecycle()
    val lastSynced by settingsSync.lastSynced.collectAsStateWithLifecycle()
    val downloadDirPath by sessionManager.downloadDirPathFlow.collectAsStateWithLifecycle(initialValue = "")
    val isFreeAccount by services.driveRepository.isFreeAccountFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val appLanguage by sessionManager.appLanguageFlow.collectAsStateWithLifecycle(initialValue = AppLanguage.SYSTEM)
    var showLanguage by remember { mutableStateOf(false) }
    val systemLanguageLabel = tr("跟随系统", "System", "システムに従う", "시스템 설정 따르기", "跟隨系統")
    if (showLanguage) SettingsChoiceDialog(
        icon = Icons.Outlined.Language,
        title = tr("语言", "Language", "言語", "언어", "語言"),
        onDismiss = { showLanguage = false },
    ) {
        AppLanguage.entries.forEach { language ->
            SettingsChoiceOption(
                title = if (language == AppLanguage.SYSTEM) systemLanguageLabel else language.nativeLabel,
                selected = appLanguage == language,
                onClick = { scope.launch { sessionManager.setAppLanguage(language) }; showLanguage = false },
            )
        }
    }

    val archivePasswordVault = services.archivePasswords
    val archivePasswords by archivePasswordVault.passwords.collectAsStateWithLifecycle(initialValue = emptyList())
    var showArchivePasswords by remember { mutableStateOf(false) }
    val encryptedSyncStatus by services.encryptedSyncStatus.collectAsStateWithLifecycle()
    val activeClient by services.clientManager.currentClient.collectAsStateWithLifecycle()
    var hasSyncPassphrase by remember(activeClient?.account) { mutableStateOf(false) }
    var showEncryptedSyncDialog by remember { mutableStateOf(false) }
    var encryptedSyncBusy by remember { mutableStateOf(false) }
    var encryptedSyncMismatchError by remember(activeClient?.account) { mutableStateOf<String?>(null) }
    LaunchedEffect(activeClient?.account, showEncryptedSyncDialog) {
        hasSyncPassphrase = services.hasSyncPassphraseForCurrentAccount()
    }
    var showPlaybackQuality by remember { mutableStateOf(false) }
    val playbackMaxHeight by sessionManager.playbackMaxHeightFlow.collectAsStateWithLifecycle(
        initialValue = sessionManager.currentSnapshot.playbackMaxHeight,
    )
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
    val isWindowFocused = LocalWindowInfo.current.isWindowFocused
    if (linkAssociation != null) {
        // 默认应用在系统设置里改，改完切回来窗口重新获得焦点，借此刷新
        LaunchedEffect(linkAssociation, isWindowFocused) {
            if (isWindowFocused) linkAssociationState = linkAssociation.state()
        }
    }
    val backgroundTransferAccess = platform.backgroundTransferAccess
    var isBackgroundUnrestricted by remember(backgroundTransferAccess) {
        mutableStateOf(backgroundTransferAccess?.isUnrestricted() ?: true)
    }
    if (backgroundTransferAccess != null) {
        LaunchedEffect(backgroundTransferAccess, isWindowFocused) {
            if (isWindowFocused) isBackgroundUnrestricted = backgroundTransferAccess.isUnrestricted()
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
    val density = LocalDensity.current
    val headingSlack = with(density) { SectionHeadingSlack.roundToPx() }
    // 不在 SettingsScreen 顶层解构读取 currentSection，而是把 provider 传给 SettingsIndex，
    // 避免滚动切换高亮章节时整页 1,200 行设置项随之重组。
    val currentSectionState = remember(sections, headingSlack) {
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
    var searchQuery by remember { mutableStateOf("") }
    var mobileSearchOpen by remember { mutableStateOf(false) }
    val searching = searchQuery.isNotBlank()
    val desktop = isDesktopLayout()
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    var contentWidthPx by remember(windowWidthPx) { mutableIntStateOf(windowWidthPx) }
    val contentWidthDp = with(density) { contentWidthPx.toDp() }
    // 左侧目录随宽窗口布局出现（>= 600×480dp 且内容区宽度不低于 SettingsIndexMinWidth，大屏平板同样适用）；窄窗保持单列列表
    val framed = LocalFramed.current
    val withIndex = desktop && contentWidthDp >= SettingsIndexMinWidth

    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 有外框时：不分标签（各类连着排、左栏是目录），页名写在岛的页眉里
        island = IslandPage(header = {
            IslandTitle(tr("设置", "Settings", "設定", "설정", "設定"))
            IslandHeaderSpace()
            if (!withIndex) {
                SettingsSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier = Modifier.width(220.dp),
                )
            }
        }),
        topBar = {
            PikoTopBar(
                title = tr("设置", "Settings", "設定", "설정", "設定"),
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回", "Back", "戻る", "뒤로", "返回"))
                        }
                    }
                },
                actions = if (!withIndex) {
                    listOf(
                        BarItem(key = "settingsSearch", priority = PinnedPriority) {
                            TooltipIconButton(
                                icon = if (mobileSearchOpen || searching) Icons.Outlined.Close else Icons.Outlined.Search,
                                label = if (mobileSearchOpen || searching) {
                                    tr("关闭搜索", "Close search", "検索を閉じる", "검색 닫기", "關閉搜尋")
                                } else {
                                    tr("搜索设置", "Search settings", "設定を検索", "설정 검색", "搜尋設定")
                                },
                                onClick = {
                                    if (mobileSearchOpen || searching) {
                                        searchQuery = ""
                                        mobileSearchOpen = false
                                    } else {
                                        mobileSearchOpen = true
                                    }
                                },
                            )
                        },
                    )
                } else {
                    emptyList()
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .onSizeChanged { if (it.width > 0) contentWidthPx = it.width },
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (withIndex) {
                    SettingsIndex(
                        sections = sections,
                        currentSectionProvider = { currentSectionState.value },
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        onSelect = { section ->
                            if (searching) searchQuery = ""
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
                    if (!framed && !withIndex && (mobileSearchOpen || searching)) {
                        val mobileSearchFocus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { runCatching { mobileSearchFocus.requestFocus() } }
                        SettingsSearchField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 4.dp)
                                .focusRequester(mobileSearchFocus),
                        )
                    }

                    fun match(vararg terms: String): Boolean = matchesSettingsQuery(searchQuery, *terms)

                    var renderedSections = 0

                    @Composable
                    fun section(section: SettingsSection, content: @Composable ColumnScope.() -> Unit) {
                        val isFirst = renderedSections == 0
                        renderedSections += 1
                        SettingsSectionBlock(
                            section = section,
                            first = isFirst,
                            onPositioned = { if (!searching) sectionOffsets[section] = it },
                            content = content,
                        )
                    }

                    if (account != null && match(SettingsSection.Account.title, "account", "账号", "退出", "切换", "会员", "sign out", "storage")) {
                        section(SettingsSection.Account) { account() }
                    }

                    val appearance = LocalAppearance.current
                    val motionScale = platform.motionScale
                    val matchLang = match("语言", "Language", "言語", "언어", "語言", systemLanguageLabel, appLanguage.nativeLabel)
                    val matchThemeMode = match("深色模式", "Theme mode", "dark", "light", "system", "テーマモード", "테마 모드")
                    val matchPureBlack = !appearance.isEInk && appearance.mode != ThemeMode.LIGHT && match("纯黑深色模式", "Pure black", "OLED", "amoled", "true black", "ピュアブラック", "순수 블랙", "純黑")
                    val matchThemeColor = match("主题色", "Theme color", "dynamic", "accent", "color", "水墨屏", "e-ink", "eink", "テーマカラー", "테마 색상", "主題色", appearance.effectiveSeed?.label.orEmpty())
                    val matchStartup = platform.launchAtStartup != null && match("开机自动启动", "Launch at startup", "autostart", "Windows", "ログイン時に起動", "시작 시 실행", "開機自動啟動")
                    val matchTray = platform.closeToTray != null && match("关闭时最小化到托盘", "Minimize to tray on close", "tray", "トレイ", "트레이", "系統匣")
                    val matchCompactBar = platform.compactTitleBar != null && match("紧凑标题栏", "Compact title bar", "titlebar", "タイトルバー", "제목 표시줄", "精簡標題列")
                    val matchMotion = match("减少动画", "Reduce motion", "animation", "アニメーション", "애니메이션", "減少動畫")
                    if (matchLang || matchThemeMode || matchPureBlack || matchThemeColor || matchStartup || matchTray || matchCompactBar || matchMotion) {
                        section(SettingsSection.Appearance) {
                            SettingsGroup(null) {
                                if (matchLang) {
                                    SettingsNavigationRow(
                                        icon = Icons.Outlined.Language,
                                        title = tr("语言", "Language", "言語", "언어", "語言"),
                                        supporting = if (appLanguage == AppLanguage.SYSTEM) systemLanguageLabel else appLanguage.nativeLabel,
                                        onClick = { showLanguage = true },
                                    )
                                }
                                if (matchThemeMode) {
                                    ThemeModeRow(
                                        mode = appearance.mode,
                                        onModeChange = { scope.launch { sessionManager.setThemeMode(it.name) } },
                                    )
                                }
                                if (matchPureBlack) {
                                    DependentRow(visible = !appearance.isEInk && appearance.mode != ThemeMode.LIGHT) {
                                        SettingsSwitchRow(
                                            icon = Icons.Outlined.Contrast,
                                            title = tr("纯黑深色模式", "Pure black (OLED)", "ピュアブラック（OLED）", "순수 블랙 (OLED)", "純黑深色模式"),
                                            supporting = tr("深色模式下使用纯黑背景以节省 OLED 屏幕功耗", "Use true black surfaces in dark theme for OLED displays", "ダークテーマで完全な黒背景を使用します（OLED向け）", "다크 테마에서 OLED 화면용 순수 블랙 배경 사용", "深色模式下使用純黑背景以節省 OLED 螢幕功耗"),
                                            checked = appearance.pureBlack,
                                            onCheckedChange = { scope.launch { sessionManager.setPureBlack(it) } },
                                        )
                                    }
                                }
                                if (matchThemeColor) {
                                    ThemeColorRow(
                                        appearance = appearance,
                                        onSeedChange = { scope.launch { sessionManager.setThemeSeed(it?.name) } },
                                    )
                                }
                                if (matchStartup) {
                                    platform.launchAtStartup?.let { startup ->
                                        var enabled by remember { mutableStateOf(false) }
                                        var busy by remember { mutableStateOf(true) }
                                        val focused = LocalWindowInfo.current.isWindowFocused
                                        LaunchedEffect(startup, focused) {
                                            if (focused) {
                                                enabled = startup.isEnabled()
                                                busy = false
                                            }
                                        }
                                        SettingsSwitchRow(
                                            icon = Icons.Outlined.PowerSettingsNew,
                                            title = tr("开机自动启动", "Launch at startup", "ログイン時に起動", "시작 시 실행", "開機自動啟動"),
                                            supporting = tr("登录 Windows 后打开 Piko", "Open Piko after signing in to Windows", "Windowsへのログイン時にPikoを開く", "Windows 로그인 후 Piko 실행", "登入 Windows 後開啟 Piko"),
                                            checked = enabled,
                                            onCheckedChange = { value ->
                                                if (!busy) scope.launch {
                                                    busy = true
                                                    if (!startup.setEnabled(value)) snackbarHostState.showSnackbar(tr("无法更改开机启动设置", "Unable to change startup setting", "起動設定を変更できません", "시작 설정을 변경할 수 없습니다", "無法變更開機啟動設定"))
                                                    enabled = startup.isEnabled()
                                                    busy = false
                                                }
                                            },
                                        )
                                    }
                                }
                                if (matchTray) {
                                    platform.closeToTray?.let { toggle ->
                                        val enabled by toggle.enabled.collectAsState()
                                        SettingsSwitchRow(
                                            icon = Icons.Outlined.Minimize,
                                            title = tr("关闭时最小化到托盘", "Minimize to tray on close", "閉じるときにトレイへ最小化", "닫을 때 트레이로 최소화", "關閉時最小化至系統匣"),
                                            supporting = tr("点击关闭按钮时最小化至系统托盘，不退出应用", "Minimize to the system tray instead of quitting when clicking Close", "閉じるボタンをクリックした際、終了せずにシステムトレイへ格納します", "닫기 버튼을 누를 때 종료하지 않고 시스템 트레이로 최소화합니다", "點擊關閉按鈕時最小化至系統匣，不結束應用程式"),
                                            checked = enabled,
                                            onCheckedChange = toggle::set,
                                        )
                                    }
                                }
                                if (matchCompactBar) {
                                    platform.compactTitleBar?.let { compactTitleBar ->
                                        val compact by compactTitleBar.enabled.collectAsState()
                                        SettingsSwitchRow(
                                            icon = Icons.Outlined.WebAsset,
                                            title = tr("紧凑标题栏", "Compact title bar", "コンパクトなタイトルバー", "간결한 제목 표시줄", "精簡標題列"),
                                            supporting = tr("窗口按钮并入界面右上角", "Integrate window controls into the top right corner", "ウィンドウボタンを画面右上に統合", "창 버튼을 화면 오른쪽 위에 통합", "視窗按鈕整合至介面右上角"),
                                            checked = compact,
                                            onCheckedChange = compactTitleBar::set,
                                        )
                                    }
                                }
                                if (matchMotion) {
                                    // 与系统设置及水墨屏模式取或，见 PikoMotionScale 与 PikoTheme。
                                    SettingsSwitchRow(
                                        icon = Icons.Outlined.Animation,
                                        title = tr("减少动画", "Reduce motion", "アニメーションを減らす", "애니메이션 줄이기", "減少動畫"),
                                        supporting = when {
                                            appearance.isEInk -> tr("水墨屏主题已自动关闭过渡动画", "Transitions are disabled automatically in E-Ink theme", "E-Inkテーマではアニメーションが自動的に無効になります", "E-Ink 테마에서는 전환 애니메이션이 자동으로 꺼집니다", "水墨螢幕主題已自動關閉轉場動畫")
                                            motionScale.systemScale == 0f -> tr("系统已关闭动画", "Animations disabled by system", "システムでアニメーションが無効です", "시스템에서 애니메이션을 껐습니다", "系統已關閉動畫")
                                            else -> tr("界面切换不播放过渡", "Disable transition animations", "画面切り替えのアニメーションを無効にする", "화면 전환 애니메이션 끄기", "切換介面時不播放轉場動畫")
                                        },
                                        checked = motionScale.appReduced || appearance.isEInk,
                                        enabled = !appearance.isEInk,
                                        onCheckedChange = { scope.launch { sessionManager.setReduceMotion(it) } },
                                    )
                                }
                            }
                        }
                    }

                    val matchDriveAppearance = match("自定义", "Customize", "カスタマイズ", "사용자 지정", "自訂", "文件外观", "File appearance", "card", "grid", "list", "gallery", "列表", "网格", "瀑布流", "角标", "文件夹样式", "ファイル外観", "파일 모양", "檔案外觀")
                    val matchPlayerCustom = match("自定义", "Customize", "カスタマイズ", "사용자 지정", "自訂", "播放器外观", "播放器自定义", "Player appearance", "Player customization", "layout", "gesture", "手势", "听音频", "プレーヤー", "플레이어", "播放器外觀", "播放器自訂")
                    if (matchDriveAppearance || matchPlayerCustom) {
                        section(SettingsSection.Customize) {
                            SettingsGroup(null) {
                                if (matchDriveAppearance) {
                                    SettingsNavigationRow(
                                        icon = Icons.Outlined.GridView,
                                        title = tr("文件外观", "File appearance", "ファイル外観", "파일 모양", "檔案外觀"),
                                        supporting = tr("列表、网格与瀑布流的文件夹样式、角标与详情字段", "Folder style, corner badges, and detail fields across views", "リスト、グリッド、ギャラリーのフォルダー外観とバッジ", "목록, 격자, 갤러리의 폴더 스타일, 배지 및 상세 필드", "清單、網格與瀑布流的資料夾樣式、角標與詳情欄位"),
                                        onClick = onOpenDriveAppearanceCustomization,
                                    )
                                }
                                if (matchPlayerCustom) {
                                    SettingsNavigationRow(
                                        icon = Icons.Outlined.Tune,
                                        title = tr("播放器外观", "Player appearance", "プレーヤー外観", "플레이어 모양", "播放器外觀"),
                                        supporting = tr("横竖屏视频、信息流、听音频与手势", "Video, clips, audio listen mode, and gestures", "動画、フィード、音声再生とジェスチャー", "동영상, 피드, 오디오 듣기 및 제스처", "橫直螢幕影片、資訊流、聽音訊與手勢"),
                                        onClick = onOpenPlayerCustomization,
                                    )
                                }
                            }
                        }
                    }

                    val matchParse = match("文件名解析", "Parse file names", "ファイル名を解析", "파일 이름 분석", "檔案名稱解析")
                    val matchHeuristic = isNameParsingEnabled && match("启发式折叠", "Smart grouping", "不要な項目を折りたたむ", "스마트 접기", "智慧摺疊")
                    val matchBlur = match("缩略图防窥", "Blur thumbnails", "spoiler", "サムネイルをぼかす", "미리 보기 흐리게", "縮圖防窺")
                    val matchFoldersFirst = match("文件夹置顶", "Folders on top", "sort", "フォルダーを上部に固定", "폴더 상단 고정", "資料夾置頂")
                    val matchPerFolderSort = match("各文件夹独立排序", "Sort each folder separately", "フォルダーごとに並べ替え", "폴더별 정렬", "各資料夾獨立排序")
                    val matchFilesGroup = matchParse || matchHeuristic || matchBlur || matchFoldersFirst || matchPerFolderSort
                    val matchPlayQuality = match("播放画质", "Playback quality", "resolution", "再生画質", "재생 화질", "播放畫質")
                    val matchHwDec = match("硬件解码", "Hardware decoding", "mpv", "hwdec", "ハードウェアデコード", "하드웨어 디코딩", "硬體解碼")
                    val matchClipAuto = match("信息流自动续播", "Auto-extend feed clips", "clips", "feed", "フィード", "피드 클립", "資訊流自動續播")
                    val matchExitConfirm = match("退出瀑布流与信息流时确认", "Confirm before exiting", "gallery", "clips", "feed", "瀑布流", "信息流", "退出确认", "終了時に確認", "종료 시 확인", "結束時確認")
                    val matchPlayGroup = matchPlayQuality || matchHwDec || matchClipAuto || matchExitConfirm
                    if (matchFilesGroup || matchPlayGroup) {
                        section(SettingsSection.Browse) {
                            if (matchFilesGroup) {
                                SettingsGroup(tr("文件", "Files", "ファイル", "파일", "檔案")) {
                                    if (matchParse) {
                                        SettingsSwitchRow(
                                            icon = Icons.Outlined.TextFields,
                                            title = tr("文件名解析", "Parse file names", "ファイル名を解析", "파일 이름 분석", "檔案名稱解析"),
                                            supporting = tr("按作品、分区与集数整理，标出发布组与清晰度", "Organize by title, section and episode; show release groups and quality", "作品・区分・話数で整理し、リリースグループと画質を表示", "작품, 분류, 회차별로 정리하고 배포 그룹 및 화질 표시", "依作品、分區與集數整理，標示發布群組與畫質"),
                                            checked = isNameParsingEnabled,
                                            onCheckedChange = { scope.launch { sessionManager.setNameParsingEnabled(it) } },
                                        )
                                    }
                                    if (matchHeuristic) {
                                        // 只在解析开着时有意义，关掉解析就收起，不留一行灰掉的开关
                                        DependentRow(visible = isNameParsingEnabled) {
                                            SettingsSwitchRow(
                                                icon = Icons.Outlined.AutoFixHigh,
                                                title = tr("启发式折叠", "Smart grouping", "不要な項目を折りたたむ", "스마트 접기", "智慧摺疊"),
                                                supporting = tr("收起广告、样片、说明文件等次要项", "Collapse ads, samples, readme files and other extras", "広告・サンプル・説明ファイルなどを折りたたむ", "광고, 샘플, 설명 파일 등 부가 항목 접기", "收合廣告、樣片、說明檔案等次要項目"),
                                                checked = isHeuristicFilterEnabled,
                                                onCheckedChange = { scope.launch { sessionManager.setHeuristicFilterEnabled(it) } },
                                            )
                                        }
                                    }
                                    if (matchBlur) {
                                        SettingsSwitchRow(
                                            icon = Icons.Outlined.VisibilityOff,
                                            title = tr("缩略图防窥", "Blur thumbnails", "サムネイルをぼかす", "미리 보기 흐리게", "縮圖防窺"),
                                            supporting = tr("模糊显示缩略图与封面", "Blur thumbnails and covers", "サムネイルとカバーをぼかして表示", "미리 보기 및 표지 흐리게 표시", "模糊顯示縮圖與封面"),
                                            checked = isSpoilerBlurEnabled,
                                            onCheckedChange = { scope.launch { sessionManager.setSpoilerBlurEnabled(it) } },
                                        )
                                    }
                                    if (matchFoldersFirst) {
                                        SettingsSwitchRow(
                                            icon = Icons.AutoMirrored.Outlined.Sort,
                                            title = tr("文件夹置顶", "Folders on top", "フォルダーを上部に固定", "폴더 상단 고정", "資料夾置頂"),
                                            supporting = tr("关闭后文件夹与文件按所选顺序混合排列", "Turn off to mix folders and files together in sort order", "オフにするとフォルダーとファイルを混在して並べ替えます", "끄면 폴더와 파일을 선택한 정렬 순서대로 함께 배열합니다", "關閉後資料夾與檔案依所選順序混合排列"),
                                            checked = foldersFirst,
                                            onCheckedChange = { scope.launch { sessionManager.setFoldersFirst(it) } },
                                        )
                                    }
                                    if (matchPerFolderSort) {
                                        SettingsSwitchRow(
                                            icon = Icons.AutoMirrored.Outlined.Sort,
                                            title = tr("各文件夹独立排序", "Sort each folder separately", "フォルダーごとに並べ替え", "폴더별 정렬", "各資料夾獨立排序"),
                                            supporting = tr("为每个文件夹分别保存排序方式", "Save sort order independently for each folder", "フォルダーごとに並び順を個別に保存します", "폴더마다 정렬 방식을 개별적으로 저장합니다", "為每個資料夾分別儲存排序方式"),
                                            checked = isPerFolderSortEnabled,
                                            onCheckedChange = { scope.launch { sessionManager.setPerFolderSort(it) } },
                                        )
                                    }
                                }
                            }
                            if (matchPlayGroup) {
                                SettingsGroup(tr("播放", "Play", "再生", "재생", "播放")) {
                                    if (matchPlayQuality) {
                                        SettingsNavigationRow(
                                            icon = Icons.Outlined.HighQuality,
                                            title = tr("播放画质", "Playback quality", "再生画質", "재생 화질", "播放畫質"),
                                            supporting = playbackQualitySummary(playbackMaxHeight),
                                            onClick = { showPlaybackQuality = true },
                                            trailingIcon = null,
                                        )
                                    }
                                    if (matchHwDec) {
                                        SettingsSwitchRow(
                                            icon = Icons.Outlined.Memory,
                                            title = tr("硬件解码", "Hardware decoding", "ハードウェアデコード", "하드웨어 디코딩", "硬體解碼"),
                                            supporting = tr("画面异常或卡顿时可关闭，改用软件解码", "Disable to use software decoding if video artifacts or freezes occur", "映像の乱れや停止が発生する場合は無効にしてソフトウェアデコードを使用します", "화면 깨짐이나 멈춤이 발생하면 끄고 소프트웨어 디코딩을 사용하세요", "畫面異常或停頓時可關閉，改用軟體解碼"),
                                            checked = isHardwareDecodingEnabled,
                                            onCheckedChange = { scope.launch { sessionManager.setHardwareDecoding(it) } },
                                        )
                                    }
                                    if (matchClipAuto) {
                                        SettingsSwitchRow(
                                            icon = Icons.Outlined.SwipeVertical,
                                            title = tr("信息流自动续播", "Auto-extend feed clips", "フィードの自動延長再生", "피드 클립 자동 연장", "資訊流自動續播"),
                                            supporting = tr("30 秒片段播完后自动顺延播放当前视频后续内容；关闭后循环播放当前片段", "Continue playing subsequent segments of the video after 30 seconds; loop the current clip when disabled", "30秒の再生後に同じ動画の続きを自動再生します。無効時は現在のクリップをループ再生します", "30초 재생 후 현재 동영상의 다음 구간을 이어서 재생하며, 끄면 현재 클립을 반복 재생합니다", "30 秒片段播完後自動順延播放目前影片後續內容；關閉後循環播放目前片段"),
                                            checked = isClipAutoExtendEnabled,
                                            onCheckedChange = { scope.launch { sessionManager.setClipAutoExtend(it) } },
                                        )
                                    }
                                    if (matchExitConfirm) {
                                        SettingsSwitchRow(
                                            icon = Icons.Outlined.Close,
                                            title = tr("退出瀑布流与信息流时确认", "Confirm before exiting Gallery & Clips", "ギャラリーやフィード終了時に確認", "갤러리 및 피드 종료 시 확인", "結束瀑布流與資訊流時確認"),
                                            supporting = tr("关闭后退出瀑布流或信息流不再弹窗提醒", "Exit Gallery or Clips directly without a confirmation dialog when off", "オフにすると確認ダイアログなしで直接終了します", "끄면 확인 창 없이 바로 종료합니다", "關閉後結束瀑布流或資訊流不再跳出確認視窗"),
                                            checked = !skipFeedExitConfirm,
                                            onCheckedChange = { confirm -> scope.launch { sessionManager.setSkipFeedExitConfirm(!confirm) } },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 存进网盘时的处理：链接由谁打开、带不带字幕、存成什么名字。原来分在「网盘」与「下载」两处，
                    // 而配套字幕只在添加链接时起作用，与本机下载无关
                    val matchClipboard = match("自动检测剪贴板链接", "Detect clipboard links", "magnet", "clipboard", "クリップボード", "클립보드", "剪貼簿")
                    val matchLinkAssoc = linkAssociation != null && match("磁力链接与种子文件", "Magnet links and torrent files", "torrent", "magnet", "マグネットリンク", "마그넷 링크", "磁力連結")
                    val matchAppLinks = platform.openAppLinkSettings != null && match("默认打开分享与深链", "Open shares and deep links by default", "deep link", "ディープリンク", "딥 링크", "深層連結")
                    val matchSubtitles = match("保存配套字幕", "Save matching subtitles", "subtitle", "字幕", "자막")
                    val matchCleanNames = match("自动修正名称", "Clean names automatically", "rename", "名前を自動修正", "이름 자동 수정", "自動修正名稱")
                    val matchMetaTube = metaTube != null && match("MetaTube", "番号", "catalog", "刮削", "作品番号", "품번")
                    if (matchClipboard || matchLinkAssoc || matchAppLinks || matchSubtitles || matchCleanNames || matchMetaTube) {
                        section(SettingsSection.Saving) {
                            SettingsGroup(null) {
                                if (matchClipboard) {
                                    val detectClipboard by sessionManager.autoDetectClipboardLinksFlow.collectAsStateWithLifecycle(initialValue = false)
                                    SettingsSwitchRow(
                                        icon = Icons.Outlined.ContentPaste,
                                        title = tr("自动检测剪贴板链接", "Detect clipboard links", "クリップボードのリンクを検出", "클립보드 링크 자동 감지", "自動偵測剪貼簿連結"),
                                        supporting = tr("回到前台时提示磁力或 PikPak 分享链接，确认后解析", "Offer to open magnet or PikPak share links when returning to Piko", "復帰時にマグネットやPikPak共有リンクを検出し、確認後に解析", "앱으로 돌아오면 마그넷 또는 PikPak 공유 링크를 감지하고 확인 후 분석", "回到前景時提示磁力或 PikPak 分享連結，確認後解析"),
                                        checked = detectClipboard,
                                        onCheckedChange = { scope.launch { sessionManager.setAutoDetectClipboardLinks(it) } },
                                    )
                                }
                                if (matchLinkAssoc && linkAssociation != null) {
                                    LinkAssociationRow(
                                        association = linkAssociation,
                                        state = linkAssociationState,
                                        onStateChange = { linkAssociationState = it },
                                        onFailure = { message -> scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true) } },
                                    )
                                }
                                if (matchAppLinks && platform.openAppLinkSettings != null) {
                                    SettingsNavigationRow(
                                        icon = Icons.Outlined.Link,
                                        title = tr("默认打开分享与深链", "Open shares and deep links by default", "共有とディープリンクを既定で開く", "공유 및 딥 링크 기본 열기", "預設開啟分享與深層連結"),
                                        supporting = tr("在系统设置中添加支持的链接域名，点击 PikPak 分享或跳转链接时直接用 Piko 打开", "Enable supported link domains in system settings to open PikPak shares and deep links in Piko", "システム設定で対応ドメインを有効にすると、PikPakの共有やディープリンクをPikoで開けます", "시스템 설정에서 지원 도메인을 추가하면 PikPak 공유 및 딥 링크를 Piko에서 바로 엽니다", "在系統設定中新增支援的連結網域，點選 PikPak 分享或跳轉連結時直接用 Piko 開啟"),
                                        onClick = platform.openAppLinkSettings!!,
                                    )
                                }
                                if (matchSubtitles) {
                                    // 这一项靠文件名解析认出字幕。解析在另一类，关掉时留着并写明原因，收起的话人找不到它去了哪
                                    SettingsSwitchRow(
                                        icon = Icons.Outlined.Subtitles,
                                        title = tr("保存配套字幕", "Save matching subtitles", "対応する字幕も保存", "관련 자막 함께 저장", "儲存對應字幕"),
                                        supporting = if (isNameParsingEnabled) tr("保存视频时一并保存外挂字幕", "Save external subtitles with videos", "動画の保存時に外部字幕も保存", "동영상 저장 시 외부 자막 함께 저장", "儲存影片時一併儲存外掛字幕") else tr("需先开启文件名解析", "Enable file name parsing first", "先にファイル名の解析を有効にしてください", "먼저 파일 이름 분석을 켜 주세요", "需先開啟檔案名稱解析"),
                                        checked = isBundleSubtitlesEnabled,
                                        onCheckedChange = { scope.launch { sessionManager.setBundleSubtitlesEnabled(it) } },
                                        enabled = isNameParsingEnabled,
                                    )
                                }
                                if (matchCleanNames) {
                                    SettingsSwitchRow(
                                        icon = Icons.Outlined.CleaningServices,
                                        title = tr("自动修正名称", "Clean names automatically", "名前を自動修正", "이름 자동 수정", "自動修正名稱"),
                                        supporting = tr("新建或重命名时自动移除不受支持的字符", "Automatically remove unsupported characters when creating or renaming", "新規作成や名前変更時に使用できない文字を自動除去します", "새로 만들거나 이름을 변경할 때 지원하지 않는 문자를 자동으로 제거합니다", "新增或重新命名時自動移除不支援的字元"),
                                        checked = isAutoCleanNamesEnabled,
                                        onCheckedChange = { scope.launch { sessionManager.setAutoCleanNamesEnabled(it) } },
                                    )
                                }
                                if (matchMetaTube && metaTube != null) {
                                    SettingsNavigationRow(
                                        icon = Icons.Outlined.TravelExplore,
                                        title = "MetaTube",
                                        supporting = metaTubeUrl.ifBlank { tr("未配置，按番号命名时不包含片名", "Not configured; titles omitted when naming by catalog code", "未設定（作品番号での命名時にタイトルを含めません）", "미설정 (품번으로 이름 정리 시 제목 제외)", "尚未設定，依番號命名時不包含片名") },
                                        onClick = { showMetaTubeDialog = true },
                                        trailingIcon = null,
                                    )
                                }
                            }
                        }
                    }

                    val matchDlDir = match("下载位置", "Download location", "folder", "path", "ダウンロード先", "다운로드 위치", "下載位置", resolvedDownloadPath)
                    val matchDlQuality = match("默认下载画质", "Default download quality", "ダウンロード画質", "기본 다운로드 화질", "預設下載畫質")
                    val matchBgTransfer = backgroundTransferAccess != null && match("后台持续传输", "Unrestricted background transfers", "battery", "バックグラウンド", "백그라운드", "背景持續傳輸")
                    val matchDlGroup = matchDlDir || matchDlQuality || matchBgTransfer
                    val matchSnail = match("蜗牛模式", "Snail mode", "speed limit", "限速", "低速モード", "저속 모드", "蝸牛模式")
                    val matchSnailLimit = snailMode.enabled && match("速度上限", "Speed limit", "速度上限", "속도 한도")
                    val matchSnailGroup = matchSnail || matchSnailLimit
                    if (matchDlGroup || matchSnailGroup) {
                        section(SettingsSection.Transfer) {
                            if (matchDlGroup) {
                                SettingsGroup(tr("下载", "Downloads", "ダウンロード", "다운로드", "下載")) {
                                    if (matchDlDir) {
                                        SettingsNavigationRow(
                                            icon = Icons.Outlined.FolderOpen,
                                            title = tr("下载位置", "Download location", "ダウンロード先", "다운로드 위치", "下載位置"),
                                            supporting = resolvedDownloadPath,
                                            onClick = { showDownloadDirDialog = true },
                                            trailingIcon = null,
                                        )
                                    }
                                    if (matchDlQuality) {
                                        SettingsNavigationRow(
                                            icon = Icons.Outlined.HighQuality,
                                            title = tr("默认下载画质", "Default download quality", "既定のダウンロード画質", "기본 다운로드 화질", "預設下載畫質"),
                                            supporting = downloadQualitySummary(downloadMaxHeight),
                                            onClick = { showDownloadQuality = true },
                                            trailingIcon = null,
                                        )
                                    }
                                    if (matchBgTransfer && backgroundTransferAccess != null) {
                                        SettingsNavigationRow(
                                            icon = Icons.Outlined.PowerSettingsNew,
                                            title = tr("后台持续传输", "Unrestricted background transfers", "バックグラウンド継続転送", "백그라운드 지속 전송", "背景持續傳輸"),
                                            supporting = if (isBackgroundUnrestricted) {
                                                tr(
                                                    "已将系统电池优化设为「无限制」",
                                                    "System battery optimization set to Unrestricted",
                                                    "システムのバッテリー最適化が「制限なし」に設定されています",
                                                    "시스템 배터리 최적화가 '제한 없음'으로 설정됨",
                                                    "已將系統電池最佳化設為「無限制」",
                                                )
                                            } else {
                                                tr("受系统电池优化限制，切到后台可能被暂停，点击前往应用设置将电池设为「无限制」", "Restricted by system battery optimization; tap to set battery usage to Unrestricted in app settings", "システムのバッテリー最適化により制限されています。タップしてアプリ設定からバッテリーを「制限なし」に変更", "시스템 배터리 최적화로 제한됨. 탭하여 앱 설정에서 배터리를 '제한 없음'으로 변경", "受系統電池最佳化限制，切至背景可能被暫停，點擊前往應用程式設定將電池設為「無限制」")
                                            },
                                            onClick = { backgroundTransferAccess.requestUnrestricted() },
                                            trailingIcon = if (isBackgroundUnrestricted) Icons.Outlined.Check else null,
                                        )
                                    }
                                }
                            }
                            if (matchSnailGroup) {
                                SettingsGroup(tr("限速", "Speed limits", "速度制限", "속도 제한", "限速")) {
                                    if (matchSnail) {
                                        SettingsSwitchRow(
                                            icon = Icons.Outlined.SlowMotionVideo,
                                            title = tr("蜗牛模式", "Snail mode", "低速モード", "저속 모드", "蝸牛模式"),
                                            supporting = tr("限制下载与上传速度，不影响在线播放", "Limit transfer speeds without affecting streaming", "ダウンロードとアップロードの速度を制限。ストリーミングには影響しません", "다운로드 및 업로드 속도를 제한하며 스트리밍에는 영향 없음", "限制下載與上傳速度，不影響線上播放"),
                                            checked = snailMode.enabled,
                                            onCheckedChange = { scope.launch { sessionManager.setSnailMode(snailMode.copy(enabled = it)) } },
                                        )
                                    }
                                    if (matchSnailLimit) {
                                        DependentRow(visible = snailMode.enabled) {
                                            SettingsNavigationRow(
                                                icon = Icons.Outlined.Tune,
                                                title = tr("速度上限", "Speed limit", "速度上限", "속도 한도", "速度上限"),
                                                supporting = snailMode.limitSummary(),
                                                onClick = { showSnailDialog = true },
                                                trailingIcon = null,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val matchStorage = match(
                        SettingsSection.Storage.title,
                        "存储", "缓存", "清理", "缩略图", "播放缓存", "进度条预览图", "预览图", "信息流", "索引",
                        "storage", "cache", "clear", "cleanup", "thumbnail", "playback", "seekbar", "preview", "ttl", "limit",
                        "ストレージ", "キャッシュ", "消去", "シークバープレビュー", "저장공간", "캐시", "정리", "탐색 미리보기", "儲存", "快取", "進度條預覽圖",
                    )
                    if (matchStorage) {
                        section(SettingsSection.Storage) {
                            CacheSectionContent(
                                cacheManager = services.cacheManager,
                                preferences = sessionManager,
                                snackbarHostState = snackbarHostState,
                            )
                        }
                    }

                    val matchProxy = match("网络代理", "Network proxy", "socks", "http", "proxy", "プロキシ", "프록시", "網路代理")
                    val matchDomain = match("服务器域名", "Server domain", "dns", "host", "ドメイン", "도메인", "伺服器網域")
                    if (matchProxy || matchDomain) {
                        section(SettingsSection.Network) {
                            SettingsGroup(null) {
                                if (matchProxy) {
                                    SettingsNavigationRow(
                                        icon = Icons.Outlined.Public,
                                        title = tr("网络代理", "Network proxy", "ネットワークプロキシ", "네트워크 프록시", "網路代理"),
                                        supporting = proxySetting.summary(),
                                        onClick = { showProxyDialog = true },
                                        trailingIcon = null,
                                    )
                                }
                                if (matchDomain) {
                                    SettingsNavigationRow(
                                        icon = Icons.Outlined.Dns,
                                        title = tr("服务器域名", "Server domain", "サーバードメイン", "서버 도메인", "伺服器網域"),
                                        supporting = domainSummary(domainChoice, activeDomain, domainProbes),
                                        onClick = { showDomainDialog = true },
                                        trailingIcon = null,
                                    )
                                }
                            }
                        }
                    }

                    // 这一类都挂在账号上、随账号切换，不放进「网络」：那里是这台设备自己的连接设置
                    val matchSyncSettings = match("同步设置", "Sync settings", "sync", "設定を同期", "설정 동기화", "同步設定")
                    val matchSyncHistory = match("同步播放记录", "Sync playback history", "history", "再生履歴", "재생 기록", "播放記錄")
                    val matchSyncGroup = matchSyncSettings || matchSyncHistory
                    val matchArchivePw = match("解压密码", "Archive password", "zip", "rar", "解凍パスワード", "압축 해제 비밀번호", "解壓縮密碼")
                    val matchEncSync = services.supportsEncryptedSync && match("加密同步口令", "Encrypted sync passphrase", "passphrase", "encrypt", "暗号化同期", "암호화 동기화")
                    val matchWebDav = isFreeAccount == false && match("WebDAV", "dav", "挂载", "播放器")
                    val matchCredGroup = matchArchivePw || matchEncSync || matchWebDav
                    if (matchSyncGroup || matchCredGroup) {
                        section(SettingsSection.Data) {
                            if (matchSyncGroup) {
                                SettingsGroup(tr("同步", "Sync", "同期", "동기화", "同步")) {
                                    if (matchSyncSettings) {
                                        SettingsSyncRow(
                                            enabled = isSettingsSyncEnabled,
                                            status = syncStatusText(syncStatus, lastSynced),
                                            onEnabledChange = { scope.launch { sessionManager.setSettingsSyncEnabled(it) } },
                                            onSyncNow = { scope.launch { services.syncAllNow() } },
                                        )
                                    }
                                    if (matchSyncHistory) {
                                        SettingsSwitchRow(
                                            icon = Icons.Outlined.History,
                                            title = tr("同步播放记录", "Sync playback history", "再生履歴を同期", "재생 기록 동기화", "同步播放記錄"),
                                            supporting = tr("与 PikPak 官方客户端共用播放历史与续播进度", "Share playback history and resume positions with official PikPak apps", "PikPak公式アプリと再生履歴・再開位置を共有", "PikPak 공식 앱과 재생 기록 및 이어보기 위치 공유", "與 PikPak 官方用戶端共用播放歷史與續播進度"),
                                            checked = isSyncPlayHistoryEnabled,
                                            onCheckedChange = { scope.launch { sessionManager.setSyncPlayHistoryEnabled(it) } },
                                        )
                                    }
                                }
                            }
                            if (matchCredGroup) {
                                SettingsGroup(tr("密码与凭据", "Passwords and credentials", "パスワードと認証情報", "비밀번호 및 인증 정보", "密碼與憑證")) {
                                    if (matchArchivePw) {
                                        SettingsNavigationRow(
                                            icon = Icons.Outlined.Key,
                                            title = tr("解压密码", "Archive password", "解凍パスワード", "압축 해제 비밀번호", "解壓縮密碼"),
                                            supporting = if (archivePasswords.isEmpty()) tr("尚无保存的密码", "No saved passwords", "保存済みパスワードはありません", "저장된 비밀번호가 없습니다", "尚無儲存的密碼") else tr("已保存 ${archivePasswords.size} 个", "${archivePasswords.size} saved", "${archivePasswords.size} 件保存済み", "${archivePasswords.size}개 저장됨", "已儲存 ${archivePasswords.size} 個"),
                                            onClick = { showArchivePasswords = true },
                                            trailingIcon = null,
                                        )
                                    }
                                    if (matchEncSync && services.supportsEncryptedSync) {
                                        val syncPassphraseSummary = when (encryptedSyncStatus) {
                                            EncryptedSyncStatus.LockedKeyMismatch -> tr(
                                                "云端密文与当前密钥不匹配，已暂停覆盖，点击输入口令或重置",
                                                "Cloud data encrypted with another key; paused to protect data. Tap to unlock or reset.",
                                                "クラウドの暗号化データとキーが一致しません。タップして解除またはリセット",
                                                "클라우드 암호문과 키가 일치하지 않아 보호 중. 탭하여 잠금 해제 또는 초기화",
                                                "雲端密文與目前金鑰不符，已暫停覆寫，點選輸入口令或重設",
                                            )
                                            EncryptedSyncStatus.LockedNoKey -> tr(
                                                "未设置（Google 登录账号设置后可跨设备加密同步密码与配置）",
                                                "Not set (set a passphrase to encrypt and sync passwords & MetaTube on Google accounts)",
                                                "未設定（設定すると解凍パスワードやMetaTubeを端末間で暗号化同期できます）",
                                                "미설정 (설정 시 압축 해제 비밀번호 및 MetaTube를 기기 간 암호화 동기화)",
                                                "尚未設定（Google 登入帳號設定後可跨裝置加密同步密碼與設定）",
                                            )
                                            EncryptedSyncStatus.CorruptOrUnsupported -> tr(
                                                "云端同步文件需要更新版本或已损坏，已跳过同步以保护数据",
                                                "Cloud sync file requires a newer version or is damaged; skipped to preserve data",
                                                "クラウドの同期ファイルが新しいバージョン用か破損しているためスキップしました",
                                                "클라우드 동기화 파일이 최신 버전용이거나 손상되어 건너뛰었습니다",
                                                "雲端同步檔案需要較新版本或已損壞，已略過同步以保護資料",
                                            )
                                            EncryptedSyncStatus.Idle, EncryptedSyncStatus.Unlocked -> if (hasSyncPassphrase) {
                                                tr(
                                                    "已启用独立同步口令",
                                                    "Custom sync passphrase enabled",
                                                    "独自の同期パスフレーズを使用中",
                                                    "별도 동기화 암호문구 사용 중",
                                                    "已啟用獨立同步口令",
                                                )
                                            } else {
                                                tr(
                                                    "默认使用登录密码加密，点击可设置独立同步口令",
                                                    "Encrypted with account password by default; tap to set a custom passphrase",
                                                    "既定ではログインパスワードを使用。タップして独自パスフレーズを設定",
                                                    "기본적으로 로그인 비밀번호 사용. 탭하여 별도 암호문구 설정",
                                                    "預設使用登入密碼加密，點選可設定獨立同步口令",
                                                )
                                            }
                                        }
                                        SettingsRow(
                                            icon = Icons.Outlined.Lock,
                                            title = tr("加密同步口令", "Encrypted sync passphrase", "暗号化同期パスフレーズ", "암호화 동기화 암호문구", "加密同步口令"),
                                            supporting = syncPassphraseSummary,
                                            supportingColor = if (encryptedSyncStatus == EncryptedSyncStatus.LockedKeyMismatch) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                Color.Unspecified
                                            },
                                            onClick = {
                                                encryptedSyncMismatchError = null
                                                showEncryptedSyncDialog = true
                                            },
                                        )
                                    }
                                    // WebDAV 仅限会员，免费账号与等级未取到时不出现
                                    if (matchWebDav && isFreeAccount == false) {
                                        SettingsNavigationRow(
                                            icon = Icons.Outlined.Lan,
                                            title = "WebDAV",
                                            supporting = tr("供播放器、文件管理器等应用读取网盘", "Access the drive from media players and file managers", "プレーヤーやファイルマネージャーからドライブにアクセス", "플레이어 및 파일 관리자에서 드라이브 접근", "供播放器、檔案管理員等應用程式讀取雲端硬碟"),
                                            onClick = onOpenWebDav,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (showAbout && match(SettingsSection.About.title, "about", "update", "version", "更新", "版本", "日志", "telegram", "关于")) {
                        section(SettingsSection.About) {
                            AboutSection(snackbarHostState)
                        }
                    }

                    if (searching && renderedSections == 0) {
                        PikoEmptyState(
                            icon = Icons.Outlined.SearchOff,
                            title = tr("未找到匹配的设置项", "No matching settings", "一致する設定が見つかりません", "일치하는 설정이 없습니다", "找不到相符的設定項目"),
                            actionText = tr("清除搜索", "Clear search", "検索をクリア", "검색 지우기", "清除搜尋"),
                            onActionClick = { searchQuery = "" },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        )
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
            showAutoOption = true,
        )
    }

    if (showDownloadQuality) {
        PlaybackQualityDialog(
            maxHeight = downloadMaxHeight ?: 0,
            onSelect = { scope.launch { sessionManager.setDownloadMaxHeight(it) } },
            onDismiss = { showDownloadQuality = false },
            title = tr("默认下载画质", "Default download quality", "既定のダウンロード画質", "기본 다운로드 화질", "預設下載畫質"),
            description = tr("普通下载默认采用此画质，也可通过「下载指定画质」单次选择。优先选用不高于所选上限的最高画质，若均超出上限则选用最低画质；转码视频保存为 MP4 格式。", "Standard downloads use this quality by default; use Download at chosen quality to override per download. Selects the highest quality within the limit, or the lowest available if all exceed it. Transcoded videos are saved as MP4.", "通常のダウンロードではこの画質を使用し、「画質を指定してダウンロード」で個別に変更できます。上限以下で最も高い画質を選択し、すべて上限を超える場合は最低画質を選択します。変換動画はMP4形式で保存されます。", "일반 다운로드 시 기본적으로 이 화질을 사용하며 '화질을 선택하여 다운로드'로 개별 선택할 수 있습니다. 선택한 한도 이하의 최고 화질을 사용하며 모두 초과할 경우 최저 화질을 사용합니다. 변환 동영상은 MP4 형식으로 저장됩니다.", "一般下載預設採用此畫質，亦可透過「下載指定畫質」單次選擇。優先選用不高於所選上限的最高畫質，若均超出上限則選用最低畫質；轉碼影片儲存為 MP4 格式。"),
        )
    }

    if (showArchivePasswords) {
        SavedArchivePasswordsDialog(
            passwords = archivePasswords,
            onDelete = { scope.launch { archivePasswordVault.forget(it) } },
            onDismiss = { showArchivePasswords = false },
        )
    }

    if (showEncryptedSyncDialog) {
        EncryptedSyncSettingsDialog(
            status = encryptedSyncStatus,
            hasPassphrase = hasSyncPassphrase,
            busy = encryptedSyncBusy,
            mismatchError = encryptedSyncMismatchError,
            onClearMismatchError = { encryptedSyncMismatchError = null },
            onSavePassphrase = { passphrase ->
                encryptedSyncBusy = true
                encryptedSyncMismatchError = null
                scope.launch {
                    try {
                        val outcome = services.updateSyncPassphrase(passphrase)
                        hasSyncPassphrase = services.hasSyncPassphraseForCurrentAccount()
                        when (outcome) {
                            PassphraseUpdateResult.SavedAndSynced -> {
                                showEncryptedSyncDialog = false
                                snackbarHostState.showSnackbar(
                                    tr(
                                        "已保存同步口令并完成加密同步",
                                        "Sync passphrase saved and synced",
                                        "同期パスフレーズを保存して同期しました",
                                        "동기화 암호문구를 저장하고 동기화했습니다",
                                        "已儲存同步口令並完成加密同步",
                                    ),
                                    withDismissAction = true,
                                )
                            }
                            PassphraseUpdateResult.SavedOffline -> {
                                showEncryptedSyncDialog = false
                                snackbarHostState.showSnackbar(
                                    tr(
                                        "已保存同步口令，待网络恢复后自动同步",
                                        "Sync passphrase saved; will sync when online",
                                        "同期パスフレーズを保存しました（オンライン時に同期）",
                                        "동기화 암호문구 저장됨 (온라인 상태 시 동기화)",
                                        "已儲存同步口令，待網路恢復後自動同步",
                                    ),
                                    withDismissAction = true,
                                )
                            }
                            PassphraseUpdateResult.KeyMismatch -> {
                                encryptedSyncMismatchError = tr(
                                    "口令无法解开云端密文，请核对原口令或在上方备份重置",
                                    "Passphrase does not match cloud ciphertext; check it or reset above",
                                    "パスフレーズがクラウドの暗号化データと一致しません",
                                    "암호문구가 클라우드 암호문과 일치하지 않습니다",
                                    "口令無法解開雲端密文，請核對原口令或在上方備份重設",
                                )
                            }
                            PassphraseUpdateResult.Failed -> {
                                showEncryptedSyncDialog = false
                                snackbarHostState.showSnackbar(
                                    tr(
                                        "同步口令保存失败",
                                        "Failed to save sync passphrase",
                                        "同期パスフレーズの保存に失敗しました",
                                        "동기화 암호문구 저장 실패",
                                        "同步口令儲存失敗",
                                    ),
                                    withDismissAction = true,
                                )
                            }
                        }
                    } finally {
                        encryptedSyncBusy = false
                    }
                }
            },
            onClearPassphrase = {
                encryptedSyncBusy = true
                encryptedSyncMismatchError = null
                scope.launch {
                    try {
                        services.updateSyncPassphrase("")
                        hasSyncPassphrase = services.hasSyncPassphraseForCurrentAccount()
                        showEncryptedSyncDialog = false
                    } finally {
                        encryptedSyncBusy = false
                    }
                }
            },
            onResetCloud = {
                encryptedSyncBusy = true
                encryptedSyncMismatchError = null
                scope.launch {
                    try {
                        val ok = services.resetEncryptedSyncRemote()
                        showEncryptedSyncDialog = false
                        val msg = if (ok) {
                            tr("已备份旧云端密文并用本机数据重置", "Backed up old ciphertext and reset cloud copy", "旧暗号化データをバックアップしてリセットしました", "기존 암호문을 백업하고 클라우드 데이터를 초기화했습니다", "已備份舊雲端密文並以本機資料重設")
                        } else {
                            tr("云端重置未完成，请检查网络或先设置口令", "Cloud reset failed. Check connection or set a passphrase first.", "リセットできませんでした。通信環境またはパスフレーズを確認してください", "초기화하지 못했습니다. 네트워크 또는 암호문구를 확인해 주세요", "雲端重設未完成，請檢查網路或先設定口令")
                        }
                        snackbarHostState.showSnackbar(msg, withDismissAction = true)
                    } finally {
                        encryptedSyncBusy = false
                    }
                }
            },
            onDismiss = {
                if (!encryptedSyncBusy) {
                    encryptedSyncMismatchError = null
                    showEncryptedSyncDialog = false
                }
            },
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
        title = tr("下载位置", "Download location", "ダウンロード先", "다운로드 위치", "下載位置"),
        description = description,
        onDismiss = onDismiss,
    ) {
        SettingsChoiceOption(
            title = tr("默认位置", "Default location", "既定の保存先", "기본 위치", "預設位置"),
            supporting = defaultPath,
            selected = customPath == null,
            onClick = onUseDefault,
        )
        SettingsChoiceOption(
            title = tr("自定义位置", "Custom location", "指定した保存先", "사용자 지정 위치", "自訂位置"),
            supporting = customPath ?: tr("选择一个文件夹", "Choose a folder", "フォルダーを選択", "폴더 선택", "選擇資料夾"),
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
internal enum class SettingsSection(private val titleText: () -> String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Account({ tr("账号", "Account", "アカウント", "계정", "帳號") }, Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
    Appearance({ tr("外观", "Appearance", "外観", "모양", "外觀") }, Icons.Outlined.Palette, Icons.Filled.Palette),
    Customize({ tr("自定义", "Customize", "カスタマイズ", "사용자 지정", "自訂") }, Icons.Outlined.Tune, Icons.Filled.Tune),
    Browse({ tr("浏览与播放", "Browsing & playback", "閲覧と再生", "탐색 및 재생", "瀏覽與播放") }, Icons.Outlined.PlayCircle, Icons.Filled.PlayCircle),
    Saving({ tr("保存与命名", "Saving & naming", "保存と命名", "저장 및 이름 지정", "儲存與命名") }, Icons.Outlined.DriveFileRenameOutline, Icons.Filled.DriveFileRenameOutline),
    // 与导航的「传输」同一款图标
    Transfer({ tr("下载与传输", "Downloads & transfers", "ダウンロードと転送", "다운로드 및 전송", "下載與傳輸") }, Icons.Outlined.SwapVerticalCircle, Icons.Filled.SwapVerticalCircle),
    Storage({ tr("存储与缓存", "Storage & cache", "ストレージとキャッシュ", "저장공간 및 캐시", "儲存與快取") }, Icons.Outlined.Storage, Icons.Filled.Storage),
    Network({ tr("网络", "Network", "ネットワーク", "네트워크", "網路") }, Icons.Outlined.Router, Icons.Filled.Router),
    Data({ tr("同步与数据", "Sync & data", "同期とデータ", "동기화 및 데이터", "同步與資料") }, Icons.Outlined.CloudSync, Icons.Filled.CloudSync),
    About({ tr("关于", "About", "このアプリについて", "정보", "關於") }, Icons.Outlined.Info, Icons.Filled.Info),
;

    val title: String get() = titleText()
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
    PikoSettingsSync.Status.SYNCING -> tr("同步中…", "Syncing…", "同期中…", "동기화 중…", "同步中…")
    PikoSettingsSync.Status.FAILED -> tr("同步失败，将在更改设置或下次启动时重试", "Sync failed; will retry when settings change or on next launch", "同期に失敗しました。設定変更時または次回起動時に再試行します", "동기화 실패. 설정 변경 시 또는 다음 실행 시 다시 시도합니다", "同步失敗，將於變更設定或下次啟動時重試")
    else -> lastSynced?.let {
        val time = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault())
        tr("上次同步于 ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}", "Last synced at ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}", "最終同期 ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}", "마지막 동기화 ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}", "上次同步於 ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}")
    } ?: tr("登录后自动同步", "Sync automatically after sign-in", "ログイン後に自動同期", "로그인 후 자동 동기화", "登入後自動同步")
}

// 设置页自身宽到这个程度才放目录：目录 240dp，右边至少还要放得下一列 600dp 左右的卡片
private val SettingsIndexMinWidth = 900.dp

/** 目录的宽度，比应用侧边栏略宽以容纳多语言分类名。 */
private val SettingsIndexWidth = 264.dp

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

/** 桌面左侧的分类目录，行的样式与应用侧边栏相同；顶置实时搜索框。 */
@Composable
private fun SettingsIndex(
    sections: List<SettingsSection>,
    currentSectionProvider: () -> SettingsSection,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSelect: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = currentSectionProvider()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsSearchField(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        )
        sections.forEach { section ->
            SidebarItem(
                icon = section.icon,
                selectedIcon = section.selectedIcon,
                label = section.title,
                selected = searchQuery.isBlank() && section == current,
                onClick = { onSelect(section) },
            )
        }
    }
}

@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.height(38.dp),
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        text = tr("搜索设置", "Search settings", "設定を検索", "설정 검색", "搜尋設定"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = tr("清除", "Clear", "クリア", "지우기", "清除"),
                            onClick = { onQueryChange("") },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

private fun matchesSettingsQuery(query: String, vararg fields: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    val tokens = trimmed.lowercase().split(' ').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return true
    return tokens.all { token -> fields.any { it.contains(token, ignoreCase = true) } }
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
    val confirmHint = if (association.needsSystemConfirmation) tr("，设为默认需到系统设置确认", "; confirm the default app in system settings", "。既定にするにはシステム設定で確認が必要です", ". 기본 앱 설정은 시스템 설정에서 확인 필요", "，設為預設需至系統設定確認") else ""
    val act: (Boolean) -> Unit = { register ->
        busy = true
        scope.launch {
            val done = if (register) association.register() else association.unregister()
            if (!done) onFailure(if (register) tr("无法设为默认打开方式", "Unable to set as default app", "既定のアプリに設定できません", "기본 앱으로 설정할 수 없습니다", "無法設為預設開啟方式") else tr("无法取消关联", "Unable to remove association", "関連付けを解除できません", "연결을 해제할 수 없습니다", "無法取消關聯"))
            // 不经系统设置的平台当场就改好了，窗口不会失焦再回来，这里重读一次
            onStateChange(association.state())
            busy = false
        }
    }
    val enabled = state != LinkAssociationState.Unavailable && !busy
    SettingsRow(
        title = tr("磁力链接与种子文件", "Magnet links and torrent files", "マグネットリンクとトレントファイル", "마그넷 링크 및 토렌트 파일", "磁力連結與種子檔案"),
        icon = Icons.Outlined.Link,
        supporting = when {
            state == LinkAssociationState.Unavailable -> tr("仅安装版与便携版可设为默认打开方式", "Only installed and portable builds can be the default app", "インストール版とポータブル版のみ既定のアプリに設定できます", "설치 버전과 포터블 버전만 기본 앱으로 설정 가능", "僅安裝版與可攜版可設為預設開啟方式")
            isDefault && !association.canUnregister -> tr("当前由 Piko 打开；如需更改，请将其他应用设为默认", "Opened by Piko. Set another app as default to change", "Pikoで開きます。変更するには他のアプリを既定に設定してください", "현재 Piko에서 엽니다. 변경하려면 다른 앱을 기본 앱으로 설정하세요", "目前由 Piko 開啟；如需變更，請將其他應用程式設為預設")
            isDefault -> tr("由 Piko 打开", "Opened by Piko", "Pikoで開く", "Piko에서 열기", "由 Piko 開啟")
            isRegistered -> tr("已登记，未设为默认$confirmHint", "Registered; not the default$confirmHint", "登録済み、既定は未設定$confirmHint", "등록됨, 기본 앱 아님$confirmHint", "已登錄，尚未設為預設$confirmHint")
            else -> tr("由其他应用打开$confirmHint", "Opened by another app$confirmHint", "ほかのアプリで開く$confirmHint", "다른 앱에서 열기$confirmHint", "由其他應用程式開啟$confirmHint")
        },
        trailing = {
            if (isRegistered && association.canUnregister) {
                SettingsRowButton(tr("取消关联", "Remove association", "関連付けを解除", "연결 해제", "取消關聯"), onClick = { act(false) }, enabled = enabled, primary = false)
            }
            if (!isDefault) SettingsRowButton(tr("设为默认", "Set as default", "既定に設定", "기본으로 설정", "設為預設"), onClick = { act(true) }, enabled = enabled)
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
        title = tr("深色模式", "Theme mode", "テーマモード", "테마 모드", "深色模式"),
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
        title = tr("主题色", "Theme color", "テーマカラー", "테마 색상", "主題色"),
        icon = Icons.Outlined.Palette,
        supporting = appearance.effectiveSeed?.label ?: tr("系统取色", "System color", "システムの色", "시스템 색상", "系統取色"),
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
                        scheme = scheme,
                        label = tr("系统取色", "System color", "システムの色", "시스템 색상", "系統取色"),
                        selected = appearance.seed == null,
                        idleIcon = Icons.Outlined.Wallpaper,
                        onClick = { onSeedChange(null) },
                    )
                }
                SeedTheme.entries.forEach { theme ->
                    val scheme = if (dark) theme.dark else theme.light
                    ColorSwatch(
                        scheme = scheme,
                        label = theme.label,
                        selected = appearance.effectiveSeed == theme,
                        onClick = { onSeedChange(theme) },
                        isEInkSwatch = theme == SeedTheme.EINK,
                    )
                }
            }
        },
    )
}

/**
 * Material 3 Expressive 三段色板预览球：上半圆展示 `primary`，左下四分之一展示 `primaryContainer`，
 * 右下四分之一展示 `tertiary`，选中时配外环与对勾徽章。
 */
@Composable
private fun ColorSwatch(
    scheme: ColorScheme,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    idleIcon: ImageVector? = null,
    isEInkSwatch: Boolean = false,
) {
    val style = settingsStyle()
    val ringColor = MaterialTheme.colorScheme.primary
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
                .then(
                    when {
                        selected -> Modifier
                            .border(2.dp, ringColor, CircleShape)
                            .padding(3.dp)
                        isEInkSwatch -> Modifier.border(1.dp, Color.Black, CircleShape)
                        else -> Modifier
                    },
                )
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val diameter = size.minDimension
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                if (isEInkSwatch) {
                    drawArc(
                        color = Color.Black,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = true,
                        topLeft = topLeft,
                        size = arcSize,
                    )
                    drawArc(
                        color = Color.White,
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = true,
                        topLeft = topLeft,
                        size = arcSize,
                    )
                } else {
                    drawArc(
                        color = scheme.primary,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = true,
                        topLeft = topLeft,
                        size = arcSize,
                    )
                    drawArc(
                        color = scheme.primaryContainer,
                        startAngle = 90f,
                        sweepAngle = 90f,
                        useCenter = true,
                        topLeft = topLeft,
                        size = arcSize,
                    )
                    drawArc(
                        color = scheme.tertiary,
                        startAngle = 0f,
                        sweepAngle = 90f,
                        useCenter = true,
                        topLeft = topLeft,
                        size = arcSize,
                    )
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            } else if (idleIcon != null) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(scheme.surface.copy(alpha = 0.82f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = idleIcon,
                        contentDescription = null,
                        tint = scheme.onSurface,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
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
        title = tr("同步设置", "Sync settings", "設定を同期", "설정 동기화", "同步設定"),
        supporting = if (enabled) status else tr("将设置保存在网盘 .piko 文件夹中，在其他设备登录后自动同步", "Save settings to the .piko folder in your drive and sync across devices", "設定をドライブの .piko フォルダーに保存し、他の端末でログインした際に自動同期します", "설정을 드라이브의 .piko 폴더에 저장하여 다른 기기에서 로그인 시 자동으로 동기화합니다", "將設定儲存於雲端硬碟 .piko 資料夾中，於其他裝置登入後自動同步"),
        checked = enabled,
        onCheckedChange = onEnabledChange,
        extraTrailing = if (enabled) ({ TooltipIconButton(Icons.Outlined.Sync, tr("立即同步", "Sync now", "今すぐ同期", "지금 동기화", "立即同步"), onSyncNow) }) else null,
    )
}
