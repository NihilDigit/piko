package dev.piko.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapVerticalCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
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
import dev.piko.ui.components.releasesFocusOnOutsidePress
import dev.piko.ui.platform.captionExclusion
import dev.piko.ui.screens.drive.SidebarItem
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalFramed
import dev.piko.ui.theme.isEInk
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 设置页。桌面从侧边栏左下角进入，账号与关于并在这里；移动端从「我的」进入，那两类留在「我的」。
 *
 * 各类从上到下连着排，每类一个类标题，类里不止一组时各组再有组标题；两端、各宽度都是这一份结构。
 * 桌面放得下时左边是分类目录，点了滚到那一类，滚动时亮当前的一类。不做成选一类显示一类：
 * 几类都只有几组，分页后每页大半是空的，找一项还得先猜它归哪一类。
 *
 * 行与组的外观在 SettingsLayout.kt，各类业务实现拆在同目录 `*SettingsSection.kt`，新增设置项须遵循 ui/AGENTS.md。
 */
@Composable
fun SettingsScreen(
    onBackClick: (() -> Unit)?,
    onOpenWebDav: () -> Unit,
    onOpenPlayerCustomization: () -> Unit = {},
    onOpenDriveAppearanceCustomization: () -> Unit = {},
    onOpenCachedVideo: ((fileId: String, fileName: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** 账号一类的内容（[dev.piko.ui.workbench.AccountSettings]）。桌面没有「我的」页，账号放在设置的最前；移动端为 null。 */
    account: (@Composable ColumnScope.() -> Unit)? = null,
    /** 关于一类（[AboutSection]）。与账号同理：移动端它在「我的」页，这里不放。 */
    showAbout: Boolean = false,
) {
    val scope = rememberCoroutineScope()
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
    // 避免滚动切换高亮章节时整页设置项随之重组。
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

    val compactSearchActive = !framed && !withIndex && (mobileSearchOpen || searching)
    BackHandler(enabled = compactSearchActive) {
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
        } else {
            mobileSearchOpen = false
        }
    }

    val onSectionPositioned = remember(searching) {
        { section: SettingsSection, y: Int ->
            if (!searching) sectionOffsets[section] = y
        }
    }

    val firstVisibleSection: SettingsSection? = if (!searching) {
        if (account != null) SettingsSection.Account else SettingsSection.Appearance
    } else {
        firstMatchedSettingsSection(
            searchQuery = searchQuery,
            hasAccount = account != null,
            showAbout = showAbout,
        )
    }

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
                    modifier = Modifier.width(220.dp).captionExclusion(),
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
                actions = if (!withIndex && !mobileSearchOpen && !searching) {
                    listOf(
                        BarItem(key = "settingsSearch", priority = PinnedPriority) {
                            TooltipIconButton(
                                icon = Icons.Outlined.Search,
                                label = tr("搜索设置", "Search settings", "設定を検索", "설정 검색", "搜尋設定"),
                                onClick = { mobileSearchOpen = true },
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
                    if (compactSearchActive) {
                        SettingsSearchField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onDismiss = { mobileSearchOpen = false },
                            autoFocus = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 4.dp),
                        )
                    }

                    AccountSettingsSection(
                        searchQuery = searchQuery,
                        firstSection = firstVisibleSection,
                        onSectionPositioned = onSectionPositioned,
                        account = account,
                    )
                    AppearanceSettingsSection(
                        searchQuery = searchQuery,
                        firstSection = firstVisibleSection,
                        onSectionPositioned = onSectionPositioned,
                        snackbarHostState = snackbarHostState,
                    )
                    CustomizeSettingsSection(
                        searchQuery = searchQuery,
                        firstSection = firstVisibleSection,
                        onSectionPositioned = onSectionPositioned,
                        onOpenDriveAppearanceCustomization = onOpenDriveAppearanceCustomization,
                        onOpenPlayerCustomization = onOpenPlayerCustomization,
                    )
                    BrowseSettingsSection(
                        searchQuery = searchQuery,
                        firstSection = firstVisibleSection,
                        onSectionPositioned = onSectionPositioned,
                    )
                    SavingSettingsSection(
                        searchQuery = searchQuery,
                        firstSection = firstVisibleSection,
                        onSectionPositioned = onSectionPositioned,
                        snackbarHostState = snackbarHostState,
                    )
                    TransferSettingsSection(
                        searchQuery = searchQuery,
                        firstSection = firstVisibleSection,
                        onSectionPositioned = onSectionPositioned,
                    )
                    StorageSettingsSection(
                        searchQuery = searchQuery,
                        firstSection = firstVisibleSection,
                        onSectionPositioned = onSectionPositioned,
                        snackbarHostState = snackbarHostState,
                        onOpenCachedVideo = onOpenCachedVideo,
                    )
                    NetworkSettingsSection(
                        searchQuery = searchQuery,
                        firstSection = firstVisibleSection,
                        onSectionPositioned = onSectionPositioned,
                    )
                    SyncAndDataSettingsSection(
                        searchQuery = searchQuery,
                        firstSection = firstVisibleSection,
                        onSectionPositioned = onSectionPositioned,
                        onOpenWebDav = onOpenWebDav,
                        snackbarHostState = snackbarHostState,
                    )
                    AboutSettingsSection(
                        searchQuery = searchQuery,
                        firstSection = firstVisibleSection,
                        onSectionPositioned = onSectionPositioned,
                        showAbout = showAbout,
                        snackbarHostState = snackbarHostState,
                    )

                    if (searching && firstVisibleSection == null) {
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
}

@Composable
private fun firstMatchedSettingsSection(
    searchQuery: String,
    hasAccount: Boolean,
    showAbout: Boolean,
): SettingsSection? {
    if (hasAccount && hasAccountSectionMatches(searchQuery)) return SettingsSection.Account
    if (hasAppearanceSectionMatches(searchQuery)) return SettingsSection.Appearance
    if (hasCustomizeSectionMatches(searchQuery)) return SettingsSection.Customize
    if (hasBrowseSectionMatches(searchQuery)) return SettingsSection.Browse
    if (hasSavingSectionMatches(searchQuery)) return SettingsSection.Saving
    if (hasTransferSectionMatches(searchQuery)) return SettingsSection.Transfer
    if (hasStorageSectionMatches(searchQuery)) return SettingsSection.Storage
    if (hasNetworkSectionMatches(searchQuery)) return SettingsSection.Network
    if (hasDataSectionMatches(searchQuery)) return SettingsSection.Data
    if (showAbout && hasAboutSectionMatches(searchQuery)) return SettingsSection.About
    return null
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

// 设置页自身宽到这个程度才放目录：目录 240dp，右边至少还要放得下一列 600dp 左右的卡片
private val SettingsIndexMinWidth = 900.dp

/** 目录的宽度，比应用侧边栏略宽以容纳多语言分类名。 */
private val SettingsIndexWidth = 264.dp

// 滚动位置离一类的标题还差这么多时就算进了这一类：标题行本身不必完全滚出顶端
private val SectionHeadingSlack = 48.dp

/** 一类设置：类标题加内容，并报出自己在滚动内容里的起点。 */
@Composable
internal fun SettingsSectionBlock(
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

/**
 * 设置页内联搜索框：
 * - 遵循 ui/AGENTS.md §36，激活态右端常驻取消按钮：框内有字时清空文本，无字时退出搜索或让出焦点；
 * - 紧凑窗口展开态仅保留框内单一取消按钮，不在顶栏重复绘制第二枚关闭图标；
 * - 水墨屏模式下采用纯白底面与 1dp 纯黑实线边框，常规主题聚焦时平滑显现主色描边。
 */
@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    autoFocus: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val desktop = isDesktopLayout()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }

    val focusBorderColor by animateColorAsState(
        targetValue = when {
            isEInk -> Color.Black
            focused -> colors.primary
            else -> Color.Transparent
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "settings_search_border",
    )
    val borderWidth = when {
        focused -> if (isEInk) 2.dp else 1.5.dp
        isEInk -> 1.dp
        else -> 1.5.dp
    }
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface)
    val placeholder = tr("搜索设置", "Search settings", "設定を検索", "설정 검색", "搜尋設定")

    Row(
        modifier = modifier
            .height(if (desktop) 40.dp else 48.dp)
            .releasesFocusOnOutsidePress()
            .border(borderWidth, focusBorderColor, CircleShape)
            .clip(CircleShape)
            .background(if (isEInk) Color.White else colors.surfaceContainerHigh)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Escape) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) {
                        if (query.isNotEmpty()) {
                            onQueryChange("")
                        } else {
                            onDismiss?.invoke()
                            focusManager.clearFocus()
                        }
                    }
                    true
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            TooltipIconButton(
                icon = Icons.Outlined.Close,
                label = tr("清除", "Clear", "クリア", "지우기", "清除"),
                onClick = { onQueryChange("") },
                shortcut = "Esc",
            )
        } else if (focused || onDismiss != null) {
            TooltipIconButton(
                icon = Icons.Outlined.Close,
                label = tr("关闭搜索", "Close search", "検索を閉じる", "검색 닫기", "關閉搜尋"),
                onClick = {
                    onDismiss?.invoke()
                    focusManager.clearFocus()
                },
                shortcut = "Esc",
            )
        }
    }
}

internal fun matchesSettingsQuery(query: String, vararg fields: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    val tokens = trimmed.lowercase().split(' ').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return true
    return tokens.all { token -> fields.any { it.contains(token, ignoreCase = true) } }
}
