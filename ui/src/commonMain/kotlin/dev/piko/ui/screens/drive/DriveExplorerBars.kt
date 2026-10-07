package dev.piko.ui.screens.drive

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
import io.github.nihildigit.pikpak.FileStat
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material.icons.filled.SwipeVertical
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
import androidx.compose.material.icons.outlined.Home
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
    val caption = rememberCaptionSlot()
    Row(
        modifier = Modifier.fillMaxWidth().then(caption.modifier).height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 后退与前进走不了时不摆出来，与命令栏同一个规矩；地址栏随之左移
        if (canGoBack) TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "后退", onBack, shortcut = if (mac) "⌘[" else "Alt+←")
        if (canGoForward) TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowForward, "前进", onForward, shortcut = if (mac) "⌘]" else "Alt+→")
        TooltipIconButton(Icons.Outlined.ArrowUpward, "上一级", onUp, shortcut = if (mac) "⌘↑" else "Alt+↑", enabled = canGoUp)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) { address() }
        if (showFolderMap != null) {
            TooltipIconButton(Icons.Outlined.AccountTree, "目录图", showFolderMap, shortcut = if (mac) "⌘⇧E" else "Ctrl+Shift+E")
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
        TooltipIconButton(Icons.Outlined.Search, "搜索", { pendingFocus = true }, shortcut = shortcut)
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
                TextButton(onClick = onCancelGlobalSearch, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("停止") }
            }
            query.isNotBlank() && !isGlobalSearchActive ->
                TextButton(onClick = onStartGlobalSearch, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("全盘") }
        }
        // 取消一直在，与 Esc 相同：有字时清空，没字时收起（让出焦点即收回成搜索图标）
        if (query.isNotEmpty()) {
            TooltipIconButton(Icons.Outlined.Close, "清除", { onQueryChange("") }, shortcut = "Esc")
        } else {
            TooltipIconButton(Icons.Outlined.Close, "关闭搜索", { focusManager.clearFocus() }, shortcut = "Esc")
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
    typeFilter: FileCategory?,
    availableTypes: List<Pair<FileCategory, Int>>,
    onTypeFilterChange: (FileCategory?) -> Unit,
    onSelectAll: () -> Unit,
    onFindDuplicates: () -> Unit,
    onCanonicalNaming: () -> Unit,
    sectionJumper: @Composable () -> Unit,
    onRefresh: () -> Unit,
    onHome: () -> Unit,
    viewSwitcher: @Composable () -> Unit,
    /** 这一页的主操作，常驻在右端，见 [PrimaryActionButton]。网盘里是添加链接，库里是清空、查重里是选中建议移走的。 */
    primaryAction: SheetAction?,
) {
    val label = shortcuts::label
    val mac = shortcuts == ShortcutModifier.Command
    // 收起的先后见 BarItem.priority。收进「更多」的各带一个组号，菜单里组与组之间一道细线
    val leading = buildList {
        if (commands.home) add(BarItem("home", PinnedPriority) { TooltipIconButton(Icons.Outlined.Home, "网盘根目录", onHome) })
        if (selectedCount > 0) {
            add(BarItem("exit", PinnedPriority) { TooltipIconButton(Icons.Outlined.Close, "退出多选", onExitSelection, shortcut = "Esc") })
            add(BarItem("selected", PinnedPriority) { Text("已选 $selectedCount 项", style = MaterialTheme.typography.labelLarge, maxLines = 1) })
            // 合计大小只是说明，放不下就不写，不进「更多」
            if (selectedBytes > 0) {
                add(BarItem("selectedBytes", 10) {
                    Text(
                        "，共 ${selectedBytes.toReadableSize()}",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                })
            }
        } else if (commands.create) {
            add(BarItem("new", PinnedPriority) { MenuTextButton(Icons.Outlined.Add, "新建", newActions) })
        }
        // 左段一律是图标加文字的文字按钮：原先图标按钮与文字按钮混排，两种按钮的内边距不同，字形之间的空隙忽大忽小。
        // 同组之间只隔 BarItemGap，组与组之间多一段 GroupGap
        add(barDivider("pasteDivider"))
        if (commands.paste) {
            add(BarItem("paste", 80, listOf(SheetAction(Icons.Outlined.ContentPaste, "粘贴", onPaste, group = ActionGroup.Edit))) {
                BarTextButton(Icons.Outlined.ContentPaste, "粘贴", onPaste, shortcut = label("V"))
            })
        }
        add(barDivider("viewDivider"))
        if (commands.sort) {
            add(BarItem("sort", 40, sortOverflowActions(sortOrder, onSortChange)) { SortButton(sortOrder, onSortChange) })
        }
        if (commands.filter) {
            add(BarItem("filter", 35, typeFilterOverflowActions(typeFilter, availableTypes, onTypeFilterChange)) {
                TypeFilterButton(typeFilter, availableTypes, onTypeFilterChange)
            })
        }
        add(barDivider("selectDivider"))
        if (commands.findDuplicates) {
            // FileCopy 与「复制」的 ContentCopy 都是叠放的两张纸，带上文字才不会被认成复制。图标不换，
            // 查重的标签、库与右下角的卡片都用它
            add(BarItem("findDuplicates", 20, listOf(SheetAction(Icons.Outlined.FileCopy, "查找重复", onFindDuplicates, group = ActionGroup.Select))) {
                BarTextButton(Icons.Outlined.FileCopy, "查找重复", onFindDuplicates)
            })
        }
        // 与查找重复同为作用于整个文件夹的整理，摆在一起；条件与移动端页眉 ⋮ 里的同一项相同（DriveCommands）
        if (commands.canonicalNaming) {
            val action = DriveActions.canonicalName(onCanonicalNaming)
            add(BarItem("canonicalNaming", 15, listOf(action)) {
                BarTextButton(action.icon, action.label, onCanonicalNaming)
            })
        }
        // 只在多选里出现（见 DriveCommands），排在查找重复之后：它是对眼前选择的补充，不是平时要摆着的命令
        if (commands.selectAll) {
            add(BarItem("selectAll", 30, listOf(SheetAction(Icons.Outlined.SelectAll, "全选", onSelectAll, group = ActionGroup.Select))) {
                BarTextButton(Icons.Outlined.SelectAll, "全选", onSelectAll, shortcut = label("A"))
            })
        }
    }
    val trailing = buildList {
        // 刷新作用于眼前这份列表，与视图、信息流同属「怎么看这个文件夹」，不放在管位置的导航栏
        add(BarItem("refresh", 50, listOf(SheetAction(Icons.Outlined.Refresh, "刷新", onRefresh, group = ActionGroup.Refresh))) {
            TooltipIconButton(Icons.Outlined.Refresh, "刷新", onRefresh, shortcut = if (mac) "⌘R" else "F5")
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
private fun BarTextButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit, shortcut: String? = null) {
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
 * 命令栏右端「怎么看这个文件夹」的连体按钮：左段是当前视图，点开在列表、海报墙、图库间换，海报墙与图库另挑卡片大小；右段是信息流。
 * 视图三选一平时用不着一直摊开，收进下拉；信息流是开关，常驻。[onFeedShownChange] 为 null 时只有左段。
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
) {
    var menuOpen by remember { mutableStateOf(false) }
    val count = if (onFeedShownChange == null) 1 else 2
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        Box {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = { PlainTooltip { Text("视图：${viewMode.label}") } },
                state = rememberTooltipState(),
            ) {
                TonalToggleButton(
                    checked = menuOpen,
                    onCheckedChange = { menuOpen = it },
                    shapes = connectedToggleShapes(0, count),
                    contentPadding = PaddingValues(start = 12.dp, end = 8.dp),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Icon(viewMode.icon(selected = true), contentDescription = "视图：${viewMode.label}", modifier = Modifier.size(18.dp))
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            PikoDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val modes = DriveViewMode.entries
                // 海报墙与图库下面另起一组挑卡片大小，主修饰键加滚轮走的也是这几档；触控板与触屏从这里挑
                val sizeCount = if (viewMode.isGrid) TileSize.entries.size else 0
                val total = modes.size + sizeCount
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
                        shape = menuItemShape(index, total),
                        onClick = {
                            menuOpen = false
                            onViewModeChange(mode)
                        },
                    )
                }
                if (sizeCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    TileSizeMenuItems(tileSize, firstIndex = modes.size, total = total) {
                        menuOpen = false
                        onTileSizeChange(it)
                    }
                }
            }
        }
        if (onFeedShownChange != null) {
            val feedButton: @Composable () -> Unit = {
                TonalToggleButton(
                    checked = feedShown,
                    onCheckedChange = onFeedShownChange,
                    enabled = feedBlockedReason == null,
                    shapes = connectedToggleShapes(1, count),
                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    // 挂起的信息流（离开了它的文件夹、队列还在）在图标上点一个小圆点，点开就是接着刷。
                    // 原来另在命令栏右端的「收着的东西」里放一项「继续刷信息流」，与这个按钮是同一件事的两个入口
                    PendingBadge(shown = feedSuspended) {
                        Icon(
                            imageVector = if (feedShown) Icons.Filled.SwipeVertical else Icons.Outlined.SwipeVertical,
                            contentDescription = if (feedSuspended) "信息流已暂停" else null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("信息流")
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
