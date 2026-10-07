package dev.piko.ui.screens.drive

import dev.piko.ui.components.fileDropTarget
import dev.piko.ui.components.releasesFocusOnOutsidePress
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TonalToggleButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.asBarItem
import dev.piko.ui.components.AdaptiveBar
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.platform.windowDragArea

/**
 * 多选态顶栏。动作都作用于整批选中项，没有可以下放到别处的。
 * 各项的名字与图标取自 [DriveActions]，与右键菜单、操作面板一致。
 */
@Composable
internal fun DriveSelectionTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    selectedCount: Int,
    onExit: () -> Unit,
    // 以下为 null 的不画：眼下做不了的不摆，规则见 DriveCommands.kt
    onSelectAll: (() -> Unit)?,
    download: SheetAction?,
    moveTo: SheetAction?,
    copyTo: SheetAction?,
    trash: SheetAction?,
    extract: SheetAction?,
    share: SheetAction?,
    batchRename: SheetAction?,
) {
    PikoTopBar(
        scrollBehavior = scrollBehavior,
        title = "已选择 $selectedCount 项",
        navigationIcon = {
            TooltipIconButton(Icons.Outlined.Close, "退出多选", onExit, shortcut = "Esc")
        },
        // 放不下时先收全选（另有快捷键）、批量重命名与解压，移动、复制与移入回收站最后收
        actions = buildList {
            val shortcutModifier = LocalPikoPlatform.current.shortcutModifier
            if (onSelectAll != null) add(iconBarItem(Icons.Outlined.SelectAll, "全选", onSelectAll, priority = 10, shortcut = shortcutModifier.label("A")))
            extract?.let { add(it.asBarItem(priority = 30)) }
            download?.let { add(it.asBarItem(priority = 35)) }
            share?.let { add(it.asBarItem(priority = 40)) }
            // 只选一项时没有共同前后缀可言，单项改名走条目菜单
            if (batchRename != null && selectedCount >= 2) add(batchRename.asBarItem(priority = 20, shortcut = "F2"))
            moveTo?.let { add(it.asBarItem(priority = 60)) }
            copyTo?.let { add(it.asBarItem(priority = 50)) }
            trash?.let { add(it.asBarItem(priority = 70, shortcut = shortcutModifier.trashLabel)) }
        },
    )
}

/**
 * 搜索态顶栏：输入框取代标题，占用的仍是顶栏那一行。
 *
 * 搜索入口原先是列表上方一条常驻的 56dp 输入框，加上下边距约 68dp，任何时候都挤占
 * 列表。M3 对「搜索是次要动作」的页面给出的入口是顶栏里的搜索图标按钮，点开后才出现
 * 输入框；这里就是点开后的样子。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    isGlobalSearching: Boolean,
    isGlobalSearchActive: Boolean,
    onStartGlobalSearch: () -> Unit,
    onCancelGlobalSearch: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val caption = rememberCaptionSlot()

    TopAppBar(
        // 窄窗口的桌面端：点到列表即让出焦点，方向键回到列表上；搜索栏与搜索词留着
        modifier = Modifier.releasesFocusOnOutsidePress().then(caption.modifier),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "关闭搜索")
            }
        },
        title = {
            val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // 当前目录是边输边滤，回车只需收起键盘
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索当前文件夹",
                                style = textStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
        },
        actions = {
            when {
                isGlobalSearching -> {
                    InlineLoadingIndicator()
                    // 只取消遍历，已找到的结果留在列表里
                    TextButton(onClick = onCancelGlobalSearch, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text("停止")
                    }
                }
                query.isNotBlank() && !isGlobalSearchActive -> {
                    TextButton(
                        onClick = {
                            keyboard?.hide()
                            onStartGlobalSearch()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("全盘")
                    }
                }
            }
            // 右端一直有个取消：有字时清掉搜索词，没字时收起搜索。只在有字时才出现的话，刚点开搜索时右边空着，
            // 要找退路得回到左上角的返回，拇指够不着
            IconButton(onClick = { if (query.isNotEmpty()) onQueryChange("") else onClose() }) {
                Icon(Icons.Outlined.Close, contentDescription = if (query.isNotEmpty()) "清除搜索词" else "关闭搜索")
            }
            caption.buttons?.invoke()
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

/**
 * 浏览态顶栏：目录名，以及结构化列表下当前滚动到的分区。点副标题弹出分区菜单，选哪个跳到哪个。
 *
 * 分区标题随网格滚走（海报墙没有吸顶标题），所以「身在哪一区」由这里常驻给出。
 * PikoTopBar 只有单行标题，这里直接用 TopAppBar，配色与它一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveBrowseTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    currentSection: String?,
    sections: List<String>,
    onSectionSelected: (Int) -> Unit,
    navigationIcon: (@Composable () -> Unit)?,
    /** 放不下时按优先级收进「更多」，见 AdaptiveBar。 */
    actions: List<BarItem>,
) {
    val caption = rememberCaptionSlot()
    TopAppBar(
        modifier = caption.modifier,
        title = {
            // 标题、动作与窗口按钮一起交给 AdaptiveBar 排，不用 TopAppBar 的 actions：它先量动作、标题拿剩下的，
            // 窄窗口加上窗口按钮后目录名被挤没。标题后面的空白是拖动区（AdaptiveBar 自带）；
            // 不把标题这一格登记上去：副标题是能点的分区菜单，落在拖动区里就点不到了
            AdaptiveBar(
                modifier = Modifier.height(TopAppBarDefaults.TopAppBarExpandedHeight),
                trailing = actions,
                middle = {
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                        SectionJumper(currentSection, sections, onSectionSelected)
                    }
                },
                middleMinWidth = BrowseTitleMinWidth,
                reserveMiddle = true,
                end = caption.buttons,
                dragWindow = caption.atTop,
            )
        },
        navigationIcon = { navigationIcon?.invoke() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/** 目录名至少留这么宽，动作先收进「更多」。 */
private val BrowseTitleMinWidth = 96.dp

/**
 * 信息流的开关，放在网盘页顶栏上、搜索之前，图标带字。原先是视图切换里第四个只有图标的按钮，
 * 与列表、海报墙、图库挤在一排，看上去只是又一种排列方式，窄屏上还被挤出这一行。
 * 它打开的是另一种浏览方式：随机刷这个文件夹里的视频片段，所以单独一个带名字的按钮，开着时是选中态。
 * 已弹出到独立窗口时仍是开着的，再点一下连同窗口一起关掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedToggle(
    shown: Boolean,
    onShownChange: (Boolean) -> Unit,
    /**
     * 只留图标，名字在提示里。窄窗口的桌面端把三个窗口按钮也画在这一行，带字的这一格一占，
     * 目录名只剩一两个字（实测）。
     */
    iconOnly: Boolean = false,
) {
    if (iconOnly) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text(if (shown) "关闭信息流" else "信息流") } },
            state = rememberTooltipState(),
        ) {
            FilledTonalIconToggleButton(checked = shown, onCheckedChange = onShownChange) {
                Icon(
                    imageVector = if (shown) Icons.Filled.SwipeVertical else Icons.Outlined.SwipeVertical,
                    contentDescription = "信息流",
                )
            }
        }
        return
    }
    TonalToggleButton(
        checked = shown,
        onCheckedChange = onShownChange,
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
        modifier = Modifier.padding(end = 4.dp).heightIn(min = 40.dp),
    ) {
        Icon(
            imageVector = if (shown) Icons.Filled.SwipeVertical else Icons.Outlined.SwipeVertical,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("信息流")
    }
}


/**
 * 分区跳转：眼前所在的分区（「Yuru Camp Season 2」），点开列出这个目录的全部分区，选一个滚过去。
 * 目录没有分区时不出现。窄屏挂在顶栏标题下，宽窗口在命令栏里。
 */
@Composable
internal fun SectionJumper(currentSection: String?, sections: List<String>, onSectionSelected: (Int) -> Unit) {
    if (currentSection == null || sections.isEmpty()) return
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = "跳转到分区") { showMenu = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentSection,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        PikoDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            sections.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    shape = menuItemShape(index, sections.size),
                    onClick = {
                        showMenu = false
                        onSectionSelected(index)
                    },
                )
            }
        }
    }
}

