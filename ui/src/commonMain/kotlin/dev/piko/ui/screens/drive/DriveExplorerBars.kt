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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TonalToggleButton
import dev.piko.ui.components.connectedToggleShapes
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
import androidx.compose.material3.VerticalDivider
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
import dev.piko.ui.components.PikoDropdownMenu
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
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
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
) {
    val mac = shortcuts == ShortcutModifier.Command
    val caption = rememberCaptionSlot()
    Row(
        modifier = Modifier.fillMaxWidth().then(caption.modifier).height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 后退与前进走不了时不摆出来，与命令栏的条目操作同一个规矩；地址栏随之左移
        if (canGoBack) TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "后退", onBack, shortcut = if (mac) "⌘[" else "Alt+←")
        if (canGoForward) TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowForward, "前进", onForward, shortcut = if (mac) "⌘]" else "Alt+→")
        TooltipIconButton(Icons.Outlined.ArrowUpward, "上一级", onUp, shortcut = if (mac) "⌘↑" else "Alt+↑", enabled = canGoUp)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) { address() }
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
            .background(colors.surface)
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
        if (query.isNotEmpty()) TooltipIconButton(Icons.Outlined.Close, "清除", { onQueryChange("") }, shortcut = "Esc")
    }
}

/**
 * 宽窗口网盘页的第二行，照资源管理器的命令栏：新建；剪切、复制、粘贴、重命名、分享、删除；排序、筛选、全选、查重；
 * 其余收进「⋯」。右端是刷新、视图与信息流（[viewSwitcher]）、添加链接。每一样显不显示由 [commands] 定，
 * 规则见 DriveCommands.kt；这里只管摆不摆得下，见 [CommandBarLayout]。
 *
 * 条目操作作用于选中的几项，没有选中时作用于焦点所在的一项，与资源管理器相同，所以多选时不再另换一条顶栏：
 * 多选时左端的「新建」换成「已选 N 项」与退出。几组之间的分隔线只画在两边都有东西时，不留孤零零的一道。
 */
@Composable
internal fun ExplorerCommandBar(
    shortcuts: ShortcutModifier,
    commands: DriveCommands,
    newActions: List<SheetAction>,
    targetCount: Int,
    selectedCount: Int,
    /** 选中的文件合计多大，写在「已选 N 项」后面；选中的全是文件夹时为 0，不写。 */
    selectedBytes: Long,
    onExitSelection: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onTrash: () -> Unit,
    /** 回收站的恢复与彻底删除，[DriveCommands.restoreOrDelete] 时取代剪切到删除那一组。 */
    restoreActions: List<SheetAction>,
    sortOrder: FileSortOrder,
    onSortChange: (FileSortOrder) -> Unit,
    typeFilter: FileCategory?,
    availableTypes: List<Pair<FileCategory, Int>>,
    onTypeFilterChange: (FileCategory?) -> Unit,
    onSelectAll: () -> Unit,
    onFindDuplicates: () -> Unit,
    /** 收着的东西，见 [StashTray]；为空时不画。 */
    stash: List<StashItem>,
    sectionJumper: @Composable () -> Unit,
    moreActions: List<SheetAction>,
    onRefresh: () -> Unit,
    onHome: () -> Unit,
    viewSwitcher: @Composable () -> Unit,
    /** 为 null 时不摆添加链接，见 [DriveCommands.addLink]。 */
    onAddLink: (() -> Unit)?,
) {
    val label = shortcuts::label
    val mac = shortcuts == ShortcutModifier.Command
    // 收起的先后见 BarItem.priority。收进「更多」的各带一个组号，菜单里组与组之间一道细线
    val leading = buildList {
        if (commands.home) add(BarItem("home", FixedPriority) { TooltipIconButton(Icons.Outlined.Home, "网盘根目录", onHome) })
        if (selectedCount > 0) {
            add(BarItem("exit", FixedPriority) { TooltipIconButton(Icons.Outlined.Close, "退出多选", onExitSelection, shortcut = "Esc") })
            add(BarItem("selected", FixedPriority) { Text("已选 $selectedCount 项", style = MaterialTheme.typography.labelLarge, maxLines = 1) })
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
            add(BarItem("new", FixedPriority) { MenuTextButton(Icons.Outlined.Add, "新建", newActions) })
        }
        add(BarItem.divider("itemsDivider"))
        if (commands.restoreOrDelete) {
            restoreActions.forEach { action ->
                add(BarItem("restore:${action.label}", 85, listOf(action)) {
                    TooltipIconButton(
                        action.icon,
                        action.label,
                        action.onClick,
                        tint = if (action.destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                })
            }
        }
        if (commands.cutCopy) {
            add(BarItem("cut", 80, listOf(SheetAction(Icons.Outlined.ContentCut, "剪切", onCut, group = 1))) {
                TooltipIconButton(Icons.Outlined.ContentCut, "剪切", onCut, shortcut = label("X"))
            })
            add(BarItem("copy", 80, listOf(SheetAction(Icons.Outlined.ContentCopy, "复制", onCopy, group = 1))) {
                TooltipIconButton(Icons.Outlined.ContentCopy, "复制", onCopy, shortcut = label("C"))
            })
        }
        if (commands.paste) {
            add(BarItem("paste", 80, listOf(SheetAction(Icons.Outlined.ContentPaste, "粘贴", onPaste, group = 1))) {
                TooltipIconButton(Icons.Outlined.ContentPaste, "粘贴", onPaste, shortcut = label("V"))
            })
        }
        if (commands.rename) {
            val renameLabel = if (targetCount > 1) "批量重命名" else "重命名"
            add(BarItem("rename", 70, listOf(SheetAction(Icons.Outlined.DriveFileRenameOutline, renameLabel, onRename, group = 1))) {
                TooltipIconButton(Icons.Outlined.DriveFileRenameOutline, renameLabel, onRename, shortcut = if (mac) "↩" else "F2")
            })
        }
        if (commands.share) {
            add(BarItem("share", 60, listOf(SheetAction(Icons.Outlined.Share, "分享", onShare, group = 1))) {
                TooltipIconButton(Icons.Outlined.Share, "分享", onShare)
            })
        }
        if (commands.moveToTrash) {
            add(BarItem("trash", 85, listOf(SheetAction(Icons.Outlined.Delete, "移入回收站", onTrash, destructive = true, group = 1))) {
                TooltipIconButton(
                    Icons.Outlined.Delete,
                    "移入回收站",
                    onTrash,
                    shortcut = shortcuts.trashLabel,
                    tint = MaterialTheme.colorScheme.error,
                )
            })
        }
        add(BarItem.divider("viewDivider"))
        if (commands.sort) {
            // 收进「更多」时摊成几项，当前的一项打勾并写出方向，再点它是翻转，与排序按钮的菜单相同
            val sortActions = PikoSortField.entries.map { field ->
                val current = field.owns(sortOrder)
                val direction = if (sortOrder.isAscending) "升序" else "降序"
                SheetAction(
                    Icons.AutoMirrored.Outlined.Sort,
                    if (current) "按${field.label}（$direction）" else "按${field.label}",
                    { onSortChange(field.selectFrom(sortOrder)) },
                    group = 2,
                    checked = current,
                )
            }
            add(BarItem("sort", 40, sortActions) { SortButton(sortOrder, onSortChange) })
        }
        if (commands.filter) {
            val filterActions = listOf(SheetAction(Icons.Outlined.FilterList, "全部类型", { onTypeFilterChange(null) }, group = 3, checked = typeFilter == null)) +
                availableTypes.map { (category, count) ->
                    SheetAction(category.icon(), "${category.label}（$count）", { onTypeFilterChange(category) }, group = 3, checked = category == typeFilter)
                }
            add(BarItem("filter", 35, filterActions) { TypeFilterButton(typeFilter, availableTypes, onTypeFilterChange) })
        }
        if (commands.selectAll) {
            add(BarItem("selectAll", 30, listOf(SheetAction(Icons.Outlined.SelectAll, "全选", onSelectAll, group = 4))) {
                TooltipIconButton(Icons.Outlined.SelectAll, "全选", onSelectAll, shortcut = label("A"))
            })
        }
        if (commands.findDuplicates) {
            add(BarItem("findDuplicates", 20, listOf(SheetAction(Icons.Outlined.FileCopy, "查找重复", onFindDuplicates, group = 4))) {
                TooltipIconButton(Icons.Outlined.FileCopy, "查找重复", onFindDuplicates)
            })
        }
    }
    val trailing = buildList {
        // 刷新作用于眼前这份列表，与视图、信息流同属「怎么看这个文件夹」，不放在管位置的导航栏
        add(BarItem("refresh", 50, listOf(SheetAction(Icons.Outlined.Refresh, "刷新", onRefresh, group = 5))) {
            TooltipIconButton(Icons.Outlined.Refresh, "刷新", onRefresh, shortcut = if (mac) "⌘R" else "F5")
        })
        // 详情栏的开关不放在这里：它看的是某一项，入口在条目上悬停出现的详情按钮；关闭在详情栏自己的顶上，
        // 主修饰键+I 照旧开关
        add(BarItem("view", FixedPriority) { viewSwitcher() })
        if (onAddLink != null) {
            // 往网盘里添东西最常用的一件，用主色常驻在右端，不收在「新建」菜单里；窗口窄到连它也放不下时才进「更多」。
            // 收起它在面板自己的顶上
            add(BarItem("addLink", 90, listOf(SheetAction(Icons.Outlined.Bolt, "添加链接", onAddLink, group = 5))) {
                Button(
                    onClick = onAddLink,
                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                    modifier = Modifier.padding(horizontal = 6.dp).heightIn(min = 40.dp),
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加链接")
                }
            })
        }
        if (stash.isNotEmpty()) add(BarItem("stash", FixedPriority) { StashTray(stash) })
    }
    // 没有自己的底色：与导航栏同在页眉那一块外框色里（theme/Frame.kt），下面的列表是卡片。
    // 两行各带底色、或中间再画一条线，底色叠了三层，看着重复
    CommandBarLayout(
        leading = leading,
        trailing = trailing,
        moreMenu = commands.moreMenu,
        moreActions = moreActions,
        sectionJumper = sectionJumper,
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
    )
}

/**
 * 命令栏上的一样东西。[priority] 越大越晚收起，[FixedPriority] 的一直摆着（左端的新建与已选、视图、收着的面板）。
 * 取值的先后：添加链接、移入回收站、剪切复制粘贴、重命名、分享这些条目操作最后收；刷新、排序、筛选、全选、查重
 * 在别处都另有入口（快捷键、列表页眉、命令面板），先收。
 */
private class BarItem(
    val key: String,
    val priority: Int,
    /** 收起后在「更多」里的样子；为空的收起即不显示。 */
    val overflow: List<SheetAction> = emptyList(),
    val isDivider: Boolean = false,
    val content: @Composable () -> Unit,
) {
    companion object {
        fun divider(key: String) = BarItem(key, FixedPriority, isDivider = true) { BarDivider() }
    }
}

private const val FixedPriority = Int.MAX_VALUE

/** 布局时算出的「更多」里的内容。菜单打开时才读，不必经过状态：读它的那次重组总在布局之后。 */
private class OverflowHolder {
    var actions: List<SheetAction> = emptyList()
}

/**
 * 命令栏的排布：[leading] 从左往右，「更多」跟在后面；[trailing] 贴右；中间是分区跳转与拖动窗口的空白。
 *
 * 放不下时照 M3 toolbars 的 Container 与 Adaptive design 两节：容器要整个露在屏幕上，放不下的操作收进末端的
 * overflow 菜单，窗口变宽再放出来。按 [BarItem.priority] 从低往高收，同级的先收靠后的；收起的进「更多」，
 * 排在它原有的几项前面。收哪几项只由宽度决定，与上一次的结果无关，拖动窗口边缘时不会来回跳。
 * 用 SubcomposeLayout 而不是 Row：要先量出各项的宽度，才知道「更多」里放什么、要不要摆出来。
 */
@Composable
private fun CommandBarLayout(
    leading: List<BarItem>,
    trailing: List<BarItem>,
    moreMenu: Boolean,
    moreActions: List<SheetAction>,
    sectionJumper: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overflow = remember { OverflowHolder() }
    SubcomposeLayout(modifier) { constraints ->
        val height = constraints.maxHeight
        val gap = BarItemGap.roundToPx()
        val loose = Constraints(maxHeight = height)
        val items = leading + trailing
        val placeables = items.map { item ->
            subcompose(item.key) { Box(contentAlignment = Alignment.Center) { item.content() } }.first().measure(loose)
        }
        val more = subcompose(MoreSlot) { MoreButton { overflow.actions } }.first().measure(loose)

        val shown = BooleanArray(items.size) { true }
        fun needsMore() = moreMenu || items.indices.any { !shown[it] && items[it].overflow.isNotEmpty() }
        // 分隔线只画在两边都有东西时；左段最后一道的右边是「更多」
        fun dividerShown(index: Int): Boolean {
            if (index >= leading.size || (0 until index).none { !items[it].isDivider && shown[it] }) return false
            var next = index + 1
            while (next < leading.size && !items[next].isDivider) {
                if (shown[next]) return true
                next++
            }
            return next == leading.size && needsMore()
        }
        fun visible(index: Int) = if (items[index].isDivider) dividerShown(index) else shown[index]
        fun totalWidth(): Int {
            var width = 0
            var count = 0
            for (index in items.indices) {
                if (!visible(index)) continue
                width += placeables[index].width
                count++
            }
            if (needsMore()) {
                width += more.width
                count++
            }
            return width + gap * (count - 1).coerceAtLeast(0)
        }
        while (totalWidth() > constraints.maxWidth) {
            val victim = items.indices
                .filter { shown[it] && !items[it].isDivider && items[it].priority != FixedPriority }
                .minWithOrNull(compareBy<Int>({ items[it].priority }, { -it }))
                ?: break
            shown[victim] = false
        }
        val showMore = needsMore()
        overflow.actions = items.indices.filter { !shown[it] }.flatMap { items[it].overflow } +
            if (moreMenu) moreActions else emptyList()

        val leadingIndices = leading.indices.filter(::visible)
        val trailingIndices = (leading.size until items.size).filter(::visible)
        val leadingEnd = leadingIndices.sumOf { placeables[it].width + gap } + if (showMore) more.width + gap else 0
        val trailingWidth = trailingIndices.sumOf { placeables[it].width } + gap * (trailingIndices.size - 1).coerceAtLeast(0)
        val trailingStart = (constraints.maxWidth - trailingWidth).coerceAtLeast(leadingEnd)
        // 分区跳转占剩下的宽度，最多 SectionJumperMaxWidth；窄到放不下一个名字就不摆
        val room = trailingStart - leadingEnd
        val jumper = if (room >= SectionJumperMinWidth.roundToPx()) {
            subcompose(JumperSlot) {
                Box(Modifier.widthIn(max = SectionJumperMaxWidth).padding(horizontal = 8.dp)) { sectionJumper() }
            }.first().measure(Constraints(maxWidth = room, maxHeight = height))
        } else {
            null
        }
        val dragStart = leadingEnd + (jumper?.width ?: 0)
        // 命令栏中间这段空白也能拖动窗口（标题栏并进内容时）
        val dragArea = if (trailingStart > dragStart) {
            subcompose(DragSlot) { Spacer(Modifier.fillMaxSize().windowDragArea()) }
                .first().measure(Constraints.fixed(trailingStart - dragStart, height))
        } else {
            null
        }
        layout(constraints.maxWidth, height) {
            fun Placeable.placeAt(x: Int) = place(x, (height - this.height) / 2)
            var x = 0
            for (index in leadingIndices) {
                placeables[index].placeAt(x)
                x += placeables[index].width + gap
            }
            if (showMore) more.placeAt(x)
            jumper?.placeAt(leadingEnd)
            dragArea?.place(dragStart, 0)
            x = trailingStart
            for (index in trailingIndices) {
                placeables[index].placeAt(x)
                x += placeables[index].width + gap
            }
        }
    }
}

@Composable
private fun MoreButton(actions: () -> List<SheetAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(Icons.Outlined.MoreHoriz, "更多", { expanded = true })
        ActionMenu(expanded, { expanded = false }, if (expanded) actions() else emptyList())
    }
}

private const val MoreSlot = "more"
private const val JumperSlot = "sectionJumper"
private const val DragSlot = "dragArea"
private val BarItemGap = 2.dp
private val SectionJumperMinWidth = 72.dp

/** 收着的一样东西：点它继续，点它后面的 × 丢掉。 */
internal class StashItem(
    val icon: ImageVector,
    val label: String,
    val onResume: () -> Unit,
    /** × 的提示，说清丢掉的是什么，如「放弃添加链接」。 */
    val discardLabel: String,
    val onDiscard: () -> Unit,
)

/**
 * 命令栏最右端「有东西收着」的指示：收起的添加链接、收起的查找重复、挂起的信息流。没有收着的就不画，
 * 有就亮着（实心图标、主色底），点开列出收着的几样，点一项是继续，点它后面的 × 是丢掉。
 * 图标与侧栏顶上的收起按钮、信息流窗口的「收回到主窗口」同一个：收起与找回是一对。
 *
 * 丢掉放在这里而不是面板顶上：面板顶上只有收起，离开时不必先决定还要不要；东西收在哪就在哪清理，
 * 与浏览器的下载列表、移动端底部的把手（展开与关闭）是同一个路数。代价是只收着一样时也要先点开再继续。
 */
@Composable
private fun StashTray(stash: List<StashItem>) {
    if (stash.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text(stash.singleOrNull()?.label ?: "${stash.size} 项收着") } },
            state = rememberTooltipState(),
        ) {
            FilledTonalIconButton(onClick = { expanded = true }) {
                Icon(Icons.AutoMirrored.Filled.ViewSidebar, contentDescription = "收着的面板")
            }
        }
        PikoDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            stash.forEachIndexed { index, item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    leadingIcon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        TooltipIconButton(Icons.Outlined.Close, item.discardLabel, {
                            expanded = false
                            item.onDiscard()
                        })
                    },
                    shape = menuItemShape(index, stash.size),
                    onClick = {
                        expanded = false
                        item.onResume()
                    },
                )
            }
        }
    }
}


/**
 * 命令栏右端「怎么看这个文件夹」的连体按钮：左段是当前视图，点开在列表、海报墙、图库间换；右段是信息流。
 * 视图三选一平时用不着一直摊开，收进下拉；信息流是开关，常驻。[onFeedShownChange] 为 null 时只有左段。
 */
@Composable
internal fun ViewSwitcher(
    viewMode: DriveViewMode,
    onViewModeChange: (DriveViewMode) -> Unit,
    feedShown: Boolean,
    onFeedShownChange: ((Boolean) -> Unit)?,
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
                        shape = menuItemShape(index, modes.size),
                        onClick = {
                            menuOpen = false
                            onViewModeChange(mode)
                        },
                    )
                }
            }
        }
        if (onFeedShownChange != null) {
            TonalToggleButton(
                checked = feedShown,
                onCheckedChange = onFeedShownChange,
                shapes = connectedToggleShapes(1, count),
                contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Icon(
                    imageVector = if (feedShown) Icons.Filled.SwipeVertical else Icons.Outlined.SwipeVertical,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("信息流")
            }
        }
    }
}

@Composable
private fun BarDivider() {
    VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

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
        ActionMenu(expanded, { expanded = false }, actions)
    }
}

/** 组号变了画一道细线，收进「更多」的几组与它原有的几项由此分开；几选一的当前项打勾。 */
@Composable
private fun ActionMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<SheetAction>) {
    PikoDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEachIndexed { index, action ->
            if (index > 0 && actions[index - 1].group != action.group) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            val tint = when {
                action.destructive -> MaterialTheme.colorScheme.error
                action.checked == true -> MaterialTheme.colorScheme.primary
                else -> Color.Unspecified
            }
            DropdownMenuItem(
                text = { Text(action.label, color = tint) },
                leadingIcon = { Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (action.checked == true) {
                    { Icon(Icons.Outlined.Check, contentDescription = "当前", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                } else {
                    null
                },
                shape = menuItemShape(index, actions.size),
                onClick = {
                    onDismiss()
                    action.onClick()
                },
            )
        }
    }
}

private val SearchFieldWidth = 280.dp
private val SectionJumperMaxWidth = 240.dp
