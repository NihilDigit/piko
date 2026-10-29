package dev.piko.ui.screens.drive

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.pluralStringResource

import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TonalToggleButton
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.AdaptiveBar
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.OverflowMenu
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.CloudCapacityRow
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.FrameBottomRowHeight
import dev.piko.ui.theme.IslandGap
import io.github.nihildigit.pikpak.FileStat
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.FileSortOrder
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.MenuMotion
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.PendingBadge
import dev.piko.ui.components.PrimaryActionButton
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.components.releasesFocusOnOutsidePress
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.platform.windowDragArea
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.key
import androidx.compose.ui.unit.Constraints
import dev.piko.data.repository.label
import dev.piko.shared.data.PikoSortField
import dev.piko.shared.data.isAscending
import dev.piko.ui.components.icon

/**
 * 宽窗口网盘页的第一行，照资源管理器：后退、前进、上一级，中间是地址栏，右边是搜索。
 * 窄屏仍是 M3 顶栏（目录名作标题，搜索是图标按钮点开）。
 */
@Composable
internal fun ExplorerNavBar(
    shortcuts: ShortcutModifier,
    canGoBack: Boolean,
    canGoForward: Boolean,
    canGoUp: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onUp: () -> Unit,
    address: @Composable () -> Unit,
    search: @Composable () -> Unit,
    /** 目录图关着时打开它的按钮，放在搜索旁；开着时为 null，关掉它的 × 在面板上。 */
    showFolderMap: (() -> Unit)? = null,
) {
    val mac = shortcuts == ShortcutModifier.Command
    val caption = rememberCaptionSlot(topInset = IslandGap)
    Row(
        modifier = Modifier.fillMaxWidth().then(caption.modifier).height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 后退与前进走不了时不摆出来，与命令栏同一个规矩；地址栏随之左移
        if (canGoBack) TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, tr("后退", "Back", "戻る", "뒤로"), onBack, shortcut = if (mac) "⌘[" else "Alt+←")
        if (canGoForward) TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowForward, tr("前进", "Forward", "進む", "앞으로"), onForward, shortcut = if (mac) "⌘]" else "Alt+→")
        TooltipIconButton(Icons.Outlined.ArrowUpward, tr("上一级", "Parent folder", "上の階層", "상위 폴더"), onUp, shortcut = if (mac) "⌘↑" else "Alt+↑", enabled = canGoUp)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) { address() }
        if (showFolderMap != null) {
            TooltipIconButton(Icons.Outlined.AccountTree, tr("目录图", "Folder map", "フォルダーマップ", "폴더 맵"), showFolderMap, shortcut = if (mac) "⌘⇧E" else "Ctrl+Shift+E")
        }
        // 搜索收起时只是一个图标，地址栏占走让出的宽度
        Box(Modifier.widthIn(max = SearchFieldWidth).animateContentSize()) { search() }
        caption.buttons?.invoke()
    }
}

/**
 * 搜索：平时是一个图标，点开或 [focusRequests] 加一（主修饰键+F）时展开成输入框并取得焦点。
 * 边输边筛当前文件夹；有字时出「全盘」，点了递归搜整个网盘，搜的时候出「停止」。
 * 有字时 Esc 清空，没字时 Esc 或焦点离开即收起；有字时一直展开，眼前的列表是筛过的，要看得出来。
 */
@Composable
internal fun ExplorerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    shortcut: String,
    isGlobalSearching: Boolean,
    isGlobalSearchActive: Boolean,
    onStartGlobalSearch: () -> Unit,
    onCancelGlobalSearch: () -> Unit,
    focusRequests: Int,
) {
    // 展不展开不单独记，由这三者推出：收起只需让焦点离开，清空搜索的各条路径（换目录等）不必另外通知这里
    var focused by remember { mutableStateOf(false) }
    // 点开后要等输入框进了组合才能取焦点，所以先记下「要焦点」，由输入框那边取
    var pendingFocus by remember { mutableStateOf(false) }
    // 只认进组合之后的请求，理由同 DrivePathTitle
    val initialRequests = remember { focusRequests }
    LaunchedEffect(focusRequests) { if (focusRequests != initialRequests) pendingFocus = true }
    if (query.isEmpty() && !focused && !pendingFocus) {
        TooltipIconButton(Icons.Outlined.Search, tr("搜索", "Search", "検索", "검색"), { pendingFocus = true }, shortcut = shortcut)
        return
    }

    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(pendingFocus) {
        if (pendingFocus) {
            focusRequester.requestFocus()
            pendingFocus = false
        }
    }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            // 点到列表或别处即失焦；没有字时随之收起
            .releasesFocusOnOutsidePress()
            .clip(CircleShape)
            .background(colors.headerFieldColor)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.primary),
            // 当前目录是边输边滤，回车等于点「全盘」
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank() && !isGlobalSearchActive) onStartGlobalSearch() }),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Escape) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) {
                        if (query.isNotEmpty()) onQueryChange("") else focusManager.clearFocus()
                    }
                    true
                },
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(placeholder, style = textStyle, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            },
        )
        when {
            isGlobalSearching -> {
                InlineLoadingIndicator()
                TextButton(onClick = onCancelGlobalSearch, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(Res.string.text_stop)) }
            }
            query.isNotBlank() && !isGlobalSearchActive ->
                TextButton(onClick = onStartGlobalSearch, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(Res.string.text_entire_drive)) }
        }
        // 取消一直在，与 Esc 相同：有字时清空，没字时收起（让出焦点即收回成搜索图标）
        if (query.isNotEmpty()) {
            TooltipIconButton(Icons.Outlined.Close, tr("清除", "Clear", "消去", "지우기"), { onQueryChange("") }, shortcut = "Esc")
        } else {
            TooltipIconButton(Icons.Outlined.Close, tr("关闭搜索", "Close search", "検索を閉じる", "검색 닫기"), { focusManager.clearFocus() }, shortcut = "Esc")
        }
    }
}

/**
 * 宽窗口网盘页的第二行：新建；粘贴；排序、筛选；查找重复、按番号规范命名，多选时再加全选。右端是刷新、视图与信息流（[viewSwitcher]）、
 * 这一页的主操作。只放作用于当前位置的命令，条目操作在右键菜单里，理由见 DriveCommands.kt。
 * 每一样显不显示由 [commands] 定；这里只管摆不摆得下：放不下时按 [BarItem.priority] 从低往高收进「⋯」。
 *
 * 多选时左端的「新建」换成退出与「已选 N 项」。几组之间的分隔线只画在两边都有东西时，不留孤零零的一道。
 */
@Composable
internal fun ExplorerCommandBar(
    shortcuts: ShortcutModifier,
    commands: DriveCommands,
    newActions: List<SheetAction>,
    selectedCount: Int,
    /** 选中的文件合计多大，写在「已选 N 项」后面；选中的全是文件夹时为 0，不写。 */
    selectedBytes: Long,
    onExitSelection: () -> Unit,
    onPaste: () -> Unit,
    sortOrder: FileSortOrder,
    onSortChange: (FileSortOrder) -> Unit,
    perFolderSort: Boolean = false,
    onPerFolderSortChange: ((Boolean) -> Unit)? = null,
    typeFilter: FileCategory?,
    availableTypes: List<Pair<FileCategory, Int>>,
    onTypeFilterChange: (FileCategory?) -> Unit,
    onSelectAll: () -> Unit,
    onFindDuplicates: () -> Unit,
    onCanonicalNaming: () -> Unit,
    sectionJumper: @Composable () -> Unit,
    onRefresh: () -> Unit,
    viewSwitcher: @Composable () -> Unit,
    /** 这一页的主操作，常驻在右端，见 [PrimaryActionButton]。网盘里是添加链接，库里是清空、查重里是选中建议移走的。 */
    primaryAction: SheetAction?,
    customLeading: List<BarItem> = emptyList(),
) {
    val label = shortcuts::label
    val mac = shortcuts == ShortcutModifier.Command
    // 收起的先后见 BarItem.priority。收进「更多」的各带一个组号，菜单里组与组之间一道细线
    val leading = buildList {
        if (selectedCount > 0) {
            add(BarItem("exit", PinnedPriority) { TooltipIconButton(Icons.Outlined.Close, tr("退出多选", "Exit selection", "選択モードを終了", "선택 모드 종료"), onExitSelection, shortcut = "Esc") })
            add(BarItem("selected", PinnedPriority) { Text(pluralStringResource(Res.plurals.selected_count, selectedCount, selectedCount), style = MaterialTheme.typography.labelLarge, maxLines = 1) })
            // 合计大小只是说明，放不下就不写，不进「更多」
            if (selectedBytes > 0) {
                add(BarItem("selectedBytes", 10) {
                    Text(
                        tr("，共 ${selectedBytes.toReadableSize()}", ", ${selectedBytes.toReadableSize()} total", "、合計 ${selectedBytes.toReadableSize()}", ", 총 ${selectedBytes.toReadableSize()}"),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                })
            }
        }
        if (customLeading.isNotEmpty()) {
            addAll(customLeading)
        } else if (selectedCount == 0 && commands.create) {
            add(BarItem("new", PinnedPriority) { MenuTextButton(Icons.Outlined.Add, tr("新建", "New", "新規", "새로 만들기"), newActions) })
        }
        // 左段一律是图标加文字的文字按钮：原先图标按钮与文字按钮混排，两种按钮的内边距不同，字形之间的空隙忽大忽小。
        // 同组之间只隔 BarItemGap，组与组之间多一段 GroupGap
        add(barDivider("pasteDivider"))
        if (commands.paste) {
            add(BarItem("paste", 80, listOf(SheetAction(Icons.Outlined.ContentPaste, tr("粘贴", "Paste", "貼り付け", "붙여넣기"), onPaste, group = ActionGroup.Edit))) {
                BarTextButton(Icons.Outlined.ContentPaste, tr("粘贴", "Paste", "貼り付け", "붙여넣기"), onPaste, shortcut = label("V"))
            })
        }
        add(barDivider("viewDivider"))
        if (commands.sort) {
            add(BarItem("sort", 40, sortOverflowActions(sortOrder, onSortChange, perFolderSort, onPerFolderSortChange)) {
                SortButton(sortOrder, onSortChange, perFolderSort, onPerFolderSortChange)
            })
        }
        if (commands.filter) {
            add(BarItem("filter", 35, typeFilterOverflowActions(typeFilter, availableTypes, onTypeFilterChange)) {
                TypeFilterButton(typeFilter, availableTypes, onTypeFilterChange)
            })
        }
        add(barDivider("selectDivider"))
        if (commands.findDuplicates) {
            // FileCopy 与「复制」的 ContentCopy 都是叠放的两张纸，带上文字才不会被认成复制。图标不换，
            // 查重报告与库使用同一会话摘要。
            add(BarItem("findDuplicates", 60, listOf(SheetAction(Icons.Outlined.FileCopy, tr("查重", "Duplicates", "重複検索", "중복 검색"), onFindDuplicates, group = ActionGroup.Select))) {
                BarTextButton(Icons.Outlined.FileCopy, tr("查重", "Duplicates", "重複検索", "중복 검색"), onFindDuplicates)
            })
        }
        // 与查找重复同为作用于整个文件夹的整理，摆在一起；条件与窄窗口浏览顶栏「更多」里的同一项相同（DriveCommands）
        if (commands.canonicalNaming) {
            val action = DriveActions.canonicalName(onCanonicalNaming)
            add(BarItem("canonicalNaming", 55, listOf(action)) {
                BarTextButton(action.icon, action.label, onCanonicalNaming)
            })
        }
        // 只在多选里出现（见 DriveCommands），排在查找重复之后：它是对眼前选择的补充，不是平时要摆着的命令
        if (commands.selectAll) {
            add(BarItem("selectAll", 30, listOf(SheetAction(Icons.Outlined.SelectAll, tr("全选", "Select all", "すべて選択", "모두 선택"), onSelectAll, group = ActionGroup.Select))) {
                BarTextButton(Icons.Outlined.SelectAll, tr("全选", "Select all", "すべて選択", "모두 선택"), onSelectAll, shortcut = label("A"))
            })
        }
    }
    val trailing = buildList {
        // 刷新作用于眼前这份列表，与视图、信息流同属「怎么看这个文件夹」，不放在管位置的导航栏
        add(BarItem("refresh", 50, listOf(SheetAction(Icons.Outlined.Refresh, tr("刷新", "Refresh", "更新", "새로고침"), onRefresh, group = ActionGroup.Refresh))) {
            TooltipIconButton(Icons.Outlined.Refresh, tr("刷新", "Refresh", "更新", "새로고침"), onRefresh, shortcut = if (mac) "⌘R" else "F5")
        })
        // 属性不放在这里：它看的是某一项，入口在右键菜单与操作面板末尾，以及主修饰键+I、Alt+Enter
        add(BarItem("view", PinnedPriority) { viewSwitcher() })
        if (primaryAction != null) {
            // 常驻在右端，不收在菜单里；窗口窄到连它也放不下时才进「更多」
            add(BarItem("primary", 90, listOf(primaryAction)) {
                PrimaryActionButton(primaryAction, Modifier.padding(horizontal = 6.dp))
            })
        }
    }
    // 没有自己的底色：与导航栏同在页眉那一块外框色里（theme/Frame.kt），下面的列表是卡片。
    // 两行各带底色、或中间再画一条线，底色叠了三层，看着重复
    // 优先级的取值：主操作与粘贴最后收；刷新、排序、筛选、全选、查找重复在别处都另有入口（快捷键、列表页眉、
    // 命令面板、空白处右键），先收。左端的新建与已选、视图一直摆着
    AdaptiveBar(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
        leading = leading,
        trailing = trailing,
        middle = { Box(Modifier.padding(horizontal = 8.dp)) { sectionJumper() } },
        middleMinWidth = SectionJumperMinWidth,
        middleMaxWidth = SectionJumperMaxWidth,
        moreAfterLeading = true,
        moreIcon = Icons.Outlined.MoreHoriz,
        gap = BarItemGap,
        dragWindow = true,
    )
}

private fun barDivider(key: String) = BarItem(key, PinnedPriority, isDivider = true) { BarDivider() }

/** 命令栏左段的一项：图标加文字，与新建、排序、筛选同一种按钮，快捷键写在悬停提示里。 */
@Composable
internal fun BarTextButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit, shortcut: String? = null) {
    val button = @Composable {
        TextButton(onClick = onClick) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text)
        }
    }
    if (shortcut == null) {
        button()
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text("$text ($shortcut)") } },
            state = rememberTooltipState(),
        ) { button() }
    }
}

private val BarItemGap = 4.dp
private val SectionJumperMinWidth = 72.dp



/**
 * 命令栏右端「怎么看这个文件夹」的连体按钮：左段是当前视图，点开在列表与海报墙间换并挑卡片大小；
 * 中段与右段是两种跨子目录探索方式——瀑布流（封面画布）与信息流（随机片段）。
 */
@Composable
internal fun ViewSwitcher(
    viewMode: DriveViewMode,
    onViewModeChange: (DriveViewMode) -> Unit,
    tileSize: TileSize,
    onTileSizeChange: (TileSize) -> Unit,
    feedShown: Boolean,
    onFeedShownChange: ((Boolean) -> Unit)?,
    feedSuspended: Boolean = false,
    /** 非 null 时信息流一段置灰，悬停提示写这个原因：右栏被添加链接占着，信息流挤不走它。 */
    feedBlockedReason: String? = null,
    galleryShown: Boolean = false,
    onGalleryShownChange: ((Boolean) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val count = 1 + (if (onGalleryShownChange != null) 1 else 0) + (if (onFeedShownChange != null) 1 else 0)
    var buttonIndex = 0
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        val viewIndex = buttonIndex++
        Box {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = { PlainTooltip { Text(if (galleryShown) tr("瀑布流尺寸：${tileSize.label}", "Gallery size: ${tileSize.label}", "ギャラリーのサイズ：${tileSize.label}", "갤러리 크기: ${tileSize.label}") else tr("视图：${viewMode.label}", "View: ${viewMode.label}", "表示：${viewMode.label}", "보기: ${viewMode.label}")) } },
                state = rememberTooltipState(),
            ) {
                TonalToggleButton(
                    checked = menuOpen,
                    onCheckedChange = { menuOpen = it },
                    shapes = connectedToggleShapes(viewIndex, count),
                    contentPadding = PaddingValues(start = 12.dp, end = 8.dp),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Icon(
                        if (galleryShown) Icons.Filled.PhotoLibrary else viewMode.icon(selected = true),
                        contentDescription = if (galleryShown) tr("瀑布流尺寸：${tileSize.label}", "Gallery size: ${tileSize.label}", "ギャラリーのサイズ：${tileSize.label}", "갤러리 크기: ${tileSize.label}") else tr("视图：${viewMode.label}", "View: ${viewMode.label}", "表示：${viewMode.label}", "보기: ${viewMode.label}"),
                        modifier = Modifier.size(18.dp),
                    )
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            PikoDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!galleryShown) {
                    val modes = DriveViewMode.entries
                    modes.forEachIndexed { index, mode ->
                        val current = mode == viewMode
                        DropdownMenuItem(
                            text = { Text(mode.label, color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                            leadingIcon = {
                                Icon(
                                    mode.icon(selected = current),
                                    contentDescription = null,
                                    tint = if (current) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            trailingIcon = if (current) ({
                                Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }) else null,
                            shape = menuItemShape(index, modes.size),
                            onClick = {
                                menuOpen = false
                                onViewModeChange(mode)
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                val sizes = TileSize.entries
                sizes.forEachIndexed { index, size ->
                    val current = tileSize == size
                    DropdownMenuItem(
                        text = { Text(tr("${size.label}尺寸", "${size.label} size", "${size.label}サイズ", "${size.label} 크기"), color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                        trailingIcon = if (current) ({
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }) else null,
                        shape = menuItemShape(index, sizes.size),
                        onClick = {
                            menuOpen = false
                            onTileSizeChange(size)
                        },
                    )
                }
            }
        }
        if (onGalleryShownChange != null) {
            val galleryIndex = buttonIndex++
            TonalToggleButton(
                checked = galleryShown,
                onCheckedChange = onGalleryShownChange,
                shapes = connectedToggleShapes(galleryIndex, count),
                contentPadding = PaddingValues(start = 12.dp, end = if (count == galleryIndex + 1) 16.dp else 12.dp),
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Icon(
                    imageVector = if (galleryShown) Icons.Filled.PhotoLibrary else Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.text_masonry))
            }
        }
        if (onFeedShownChange != null) {
            val feedIndex = buttonIndex
            val feedButton: @Composable () -> Unit = {
                TonalToggleButton(
                    checked = feedShown,
                    onCheckedChange = onFeedShownChange,
                    enabled = feedBlockedReason == null,
                    shapes = connectedToggleShapes(feedIndex, count),
                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    // 挂起的信息流（离开了它的文件夹、队列还在）在图标上点一个小圆点，点开就是接着刷。
                    // 原来另在命令栏右端的「收着的东西」里放一项「继续刷信息流」，与这个按钮是同一件事的两个入口
                    PendingBadge(shown = feedSuspended) {
                        Icon(
                            imageVector = if (feedShown) Icons.Filled.SwipeVertical else Icons.Outlined.SwipeVertical,
                            contentDescription = if (feedSuspended) tr("信息流已暂停", "Feed paused", "フィードを一時停止しました", "피드 일시 중지됨") else null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.text_feed))
                }
            }
            // 置灰而不藏起来：藏起来时人找不到它，也不知道为什么没了
            if (feedBlockedReason != null) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                    tooltip = { PlainTooltip { Text(feedBlockedReason) } },
                    state = rememberTooltipState(),
                ) { feedButton() }
            } else {
                feedButton()
            }
        }
    }
}

// 组与组之间只留一段空白，不画竖线：竖线在岛的页眉色上显得碎，分组靠间距已经读得出
@Composable
private fun BarDivider() {
    Spacer(Modifier.width(GroupGap))
}

private val GroupGap = 12.dp

@Composable
private fun MenuTextButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, actions: List<SheetAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text)
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        OverflowMenu(expanded, { expanded = false }, actions)
    }
}

private val SearchFieldWidth = 280.dp
private val SectionJumperMaxWidth = 240.dp
