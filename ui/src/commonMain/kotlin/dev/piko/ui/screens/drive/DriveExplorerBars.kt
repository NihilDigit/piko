package dev.piko.ui.screens.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import dev.piko.ui.platform.ShortcutModifier

/**
 * 宽窗口网盘页的第一行，照资源管理器：后退、前进、上一级、刷新，中间是地址栏，右边常驻搜索框。
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
    onRefresh: () -> Unit,
    address: @Composable () -> Unit,
    search: @Composable () -> Unit,
) {
    val mac = shortcuts == ShortcutModifier.Command
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "后退", onBack, shortcut = if (mac) "⌘[" else "Alt+←", enabled = canGoBack)
        TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowForward, "前进", onForward, shortcut = if (mac) "⌘]" else "Alt+→", enabled = canGoForward)
        TooltipIconButton(Icons.Outlined.ArrowUpward, "上一级", onUp, shortcut = if (mac) "⌘↑" else "Alt+↑", enabled = canGoUp)
        TooltipIconButton(Icons.Outlined.Refresh, "刷新", onRefresh, shortcut = if (mac) "⌘R" else "F5")
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) { address() }
        Box(Modifier.width(SearchFieldWidth)) { search() }
    }
}

/**
 * 常驻的搜索框：边输边筛当前文件夹。有字时出「全盘」，点了递归搜整个网盘，搜的时候出「停止」。
 * Esc 清空；[focusRequests] 加一时取得焦点（主修饰键+F）。
 */
@Composable
internal fun ExplorerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    isGlobalSearching: Boolean,
    isGlobalSearchActive: Boolean,
    onStartGlobalSearch: () -> Unit,
    onCancelGlobalSearch: () -> Unit,
    focusRequests: Int,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequests) { if (focusRequests > 0) focusRequester.requestFocus() }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
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
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Escape || query.isEmpty()) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) onQueryChange("")
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
 * 宽窗口网盘页的第二行，照资源管理器的命令栏：新建菜单；剪切、复制、粘贴、重命名、分享、删除；排序、筛选、查看；
 * 其余收进「⋯」。右端是详情与信息流的开关（[trailing]）。
 *
 * 条目操作作用于选中的几项，没有选中时作用于焦点所在的一项，与资源管理器相同，所以多选时不再另换一条顶栏：
 * [targetCount] 是眼下作用的项数，为 0 时这几个按钮置灰。多选时左端多一个「已选 N 项」与退出。
 */
@Composable
internal fun ExplorerCommandBar(
    shortcuts: ShortcutModifier,
    newActions: List<SheetAction>,
    targetCount: Int,
    selectedCount: Int,
    onExitSelection: () -> Unit,
    canPaste: Boolean,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onTrash: () -> Unit,
    sortOrder: FileSortOrder,
    onSortChange: (FileSortOrder) -> Unit,
    typeFilter: FileCategory?,
    availableTypes: List<Pair<FileCategory, Int>>,
    onTypeFilterChange: (FileCategory?) -> Unit,
    viewMode: DriveViewMode,
    onViewModeChange: (DriveViewMode) -> Unit,
    sectionJumper: @Composable () -> Unit,
    moreActions: List<SheetAction>,
    trailing: @Composable RowScope.() -> Unit,
) {
    val label = shortcuts::label
    val hasTarget = targetCount > 0
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (selectedCount > 0) {
            TooltipIconButton(Icons.Outlined.Close, "退出多选", onExitSelection, shortcut = "Esc")
            Text(
                "已选 $selectedCount 项",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 8.dp),
            )
        } else {
            MenuTextButton(Icons.Outlined.Add, "新建", newActions)
        }
        BarDivider()
        TooltipIconButton(Icons.Outlined.ContentCut, "剪切", onCut, shortcut = label("X"), enabled = hasTarget)
        TooltipIconButton(Icons.Outlined.ContentCopy, "复制", onCopy, shortcut = label("C"), enabled = hasTarget)
        TooltipIconButton(Icons.Outlined.ContentPaste, "粘贴", onPaste, shortcut = label("V"), enabled = canPaste)
        TooltipIconButton(
            Icons.Outlined.DriveFileRenameOutline,
            if (targetCount > 1) "批量重命名" else "重命名",
            onRename,
            shortcut = if (shortcuts == ShortcutModifier.Command) "↩" else "F2",
            enabled = hasTarget,
        )
        TooltipIconButton(Icons.Outlined.Share, "分享", onShare, enabled = hasTarget)
        TooltipIconButton(
            Icons.Outlined.Delete,
            "移入回收站",
            onTrash,
            shortcut = shortcuts.trashLabel,
            enabled = hasTarget,
            tint = if (hasTarget) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        BarDivider()
        SortButton(sortOrder, onSortChange)
        TypeFilterButton(typeFilter, availableTypes, onTypeFilterChange)
        ViewMenuButton(viewMode, onViewModeChange)
        MenuIconButton(Icons.Outlined.MoreHoriz, "更多", moreActions)
        // 只让后面的空白占满剩余：两者都带 weight 的话平分剩余宽度，分区名短时它那一半空着，右端的开关就停在半路
        Box(Modifier.widthIn(max = SectionJumperMaxWidth).padding(horizontal = 8.dp)) { sectionJumper() }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** 两行栏与列表之间的分隔，照资源管理器命令栏下面那条线。 */
@Composable
internal fun ExplorerBarsDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun BarDivider() {
    VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ViewMenuButton(viewMode: DriveViewMode, onViewModeChange: (DriveViewMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(viewMode.icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("查看")
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        PikoDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val modes = DriveViewMode.entries
            modes.forEachIndexed { index, mode ->
                DropdownMenuItem(
                    text = { Text(mode.label, color = if (mode == viewMode) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                    leadingIcon = { Icon(mode.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    shape = menuItemShape(index, modes.size),
                    onClick = {
                        expanded = false
                        onViewModeChange(mode)
                    },
                )
            }
        }
    }
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

@Composable
private fun MenuIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, actions: List<SheetAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(icon, label, { expanded = true }, enabled = actions.isNotEmpty())
        ActionMenu(expanded, { expanded = false }, actions)
    }
}

@Composable
private fun ActionMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<SheetAction>) {
    PikoDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEachIndexed { index, action ->
            val tint = if (action.destructive) MaterialTheme.colorScheme.error else Color.Unspecified
            DropdownMenuItem(
                text = { Text(action.label, color = tint) },
                leadingIcon = { Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
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
