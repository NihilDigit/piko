package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr
import androidx.compose.material.icons.outlined.Image

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
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TonalToggleButton
import dev.piko.ui.components.connectedToggleShapes
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
import androidx.compose.material.icons.outlined.MoreVert
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
import dev.piko.ui.components.AdaptiveBar
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.platform.windowDragArea

/** 多选顶栏只保留选择控制，条目操作统一在批量面板中。 */
@Composable
internal fun DriveSelectionTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    selectedCount: Int,
    onExit: () -> Unit,
    onSelectAll: (() -> Unit)?,
    onActions: () -> Unit,
) {
    PikoTopBar(
        scrollBehavior = scrollBehavior,
        title = tr("已选择 $selectedCount 项", "${enCount(selectedCount, "item", "items")} selected", "$selectedCount 項目を選択中", "${selectedCount}개 선택됨", "已選取 $selectedCount 項"),
        persistentActions = { dev.piko.ui.workbench.WorkspaceControls() },
        navigationIcon = {
            TooltipIconButton(Icons.Outlined.Close, tr("退出多选", "Exit selection", "選択モードを終了", "선택 모드 종료", "結束多選"), onExit, shortcut = "Esc")
        },
        actions = buildList {
            val shortcutModifier = LocalPikoPlatform.current.shortcutModifier
            if (onSelectAll != null) add(iconBarItem(Icons.Outlined.SelectAll, tr("全选", "Select all", "すべて選択", "모두 선택", "全選"), onSelectAll, priority = 10, shortcut = shortcutModifier.label("A")))
            add(iconBarItem(Icons.Outlined.MoreVert, tr("操作", "Actions", "操作", "작업", "操作"), onActions, priority = 30, enabled = selectedCount > 0))
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
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("关闭搜索", "Close search", "検索を閉じる", "검색 닫기", "關閉搜尋"))
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
                                text = stringResource(Res.string.text_search_current_folder),
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
                        Text(stringResource(Res.string.text_stop))
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
                        Text(stringResource(Res.string.text_entire_drive))
                    }
                }
            }
            // 右端一直有个取消：有字时清掉搜索词，没字时收起搜索。只在有字时才出现的话，刚点开搜索时右边空着，
            // 要找退路得回到左上角的返回，拇指够不着
            IconButton(onClick = { if (query.isNotEmpty()) onQueryChange("") else onClose() }) {
                Icon(Icons.Outlined.Close, contentDescription = if (query.isNotEmpty()) tr("清除搜索词", "Clear search", "検索語を消去", "검색어 지우기", "清除搜尋詞") else tr("关闭搜索", "Close search", "検索を閉じる", "검색 닫기", "關閉搜尋"))
            }
            dev.piko.ui.workbench.WorkspaceControls(compact = true)
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
    moreActions: List<SheetAction> = emptyList(),
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
                moreActions = moreActions,
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
                end = { Row { dev.piko.ui.workbench.WorkspaceControls(); caption.buttons?.invoke() } },
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
            tooltip = { PlainTooltip { Text(if (shown) tr("关闭信息流", "Close feed", "フィードを閉じる", "피드 닫기", "關閉資訊流") else tr("信息流", "Feed", "フィード", "피드", "資訊流")) } },
            state = rememberTooltipState(),
        ) {
            FilledTonalIconToggleButton(checked = shown, onCheckedChange = onShownChange) {
                Icon(
                    imageVector = if (shown) Icons.Filled.SwipeVertical else Icons.Outlined.SwipeVertical,
                    contentDescription = tr("信息流", "Feed", "フィード", "피드", "資訊流"),
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
        Text(stringResource(Res.string.text_feed))
    }
}

/**
 * 窄窗口命令栏右端的连体探索组：左段瀑布流（开启时点开菜单可调尺寸或退出瀑布流），右段信息流（随机片段）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExploreToggleGroup(
    galleryShown: Boolean,
    onGalleryShownChange: ((Boolean) -> Unit)?,
    feedShown: Boolean,
    onFeedShownChange: ((Boolean) -> Unit)?,
    tileSize: TileSize? = null,
    onTileSizeChange: ((TileSize) -> Unit)? = null,
    galleryShowImages: Boolean = true,
    onGalleryShowImagesChange: ((Boolean) -> Unit)? = null,
) {
    val count = (if (onGalleryShownChange != null) 1 else 0) + (if (onFeedShownChange != null) 1 else 0)
    if (count == 0) return
    var index = 0
    Row(
        modifier = Modifier.padding(end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        if (onGalleryShownChange != null) {
            var sizeMenuOpen by remember { mutableStateOf(false) }
            val currentIndex = index++
            Box {
                TonalToggleButton(
                    checked = galleryShown,
                    onCheckedChange = {
                        if (galleryShown && tileSize != null && onTileSizeChange != null) {
                            sizeMenuOpen = true
                        } else {
                            onGalleryShownChange(!galleryShown)
                        }
                    },
                    shapes = connectedToggleShapes(currentIndex, count),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = if (galleryShown && tileSize != null && onTileSizeChange != null) 8.dp else if (count == 1) 16.dp else 12.dp,
                    ),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Icon(
                        imageVector = if (galleryShown) Icons.Filled.PhotoLibrary else Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.text_masonry))
                    if (galleryShown && tileSize != null && onTileSizeChange != null) {
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                if (tileSize != null && onTileSizeChange != null) {
                    PikoDropdownMenu(expanded = sizeMenuOpen, onDismissRequest = { sizeMenuOpen = false }) {
                        val sizes = TileSize.entries
                        sizes.forEachIndexed { sizeIndex, size ->
                            val current = tileSize == size
                            DropdownMenuItem(
                                text = { Text(tr("${size.label}尺寸", "${size.label} size", "${size.label}サイズ", "${size.label} 크기", "${size.label}尺寸"), color = if (current) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified) },
                                trailingIcon = if (current) ({
                                    Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }) else null,
                                shape = menuItemShape(sizeIndex, sizes.size),
                                onClick = {
                                    sizeMenuOpen = false
                                    onTileSizeChange(size)
                                },
                            )
                        }
                        if (onGalleryShowImagesChange != null) {
                            Spacer(Modifier.height(6.dp))
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        tr("显示图片", "Show images", "画像を表示", "이미지 표시", "顯示圖片"),
                                        color = if (galleryShowImages) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        androidx.compose.material.icons.Icons.Outlined.Image,
                                        contentDescription = null,
                                        tint = if (galleryShowImages) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                trailingIcon = if (galleryShowImages) ({
                                    Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }) else null,
                                shape = menuItemShape(0, 1),
                                onClick = {
                                    sizeMenuOpen = false
                                    onGalleryShowImagesChange(!galleryShowImages)
                                },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.text_close_gallery_alt)) },
                            leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            shape = menuItemShape(0, 1),
                            onClick = {
                                sizeMenuOpen = false
                                onGalleryShownChange(false)
                            },
                        )
                    }
                }
            }
        }
        if (onFeedShownChange != null) {
            val currentIndex = index
            TonalToggleButton(
                checked = feedShown,
                onCheckedChange = onFeedShownChange,
                shapes = connectedToggleShapes(currentIndex, count),
                contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Icon(
                    imageVector = if (feedShown) Icons.Filled.SwipeVertical else Icons.Outlined.SwipeVertical,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.text_feed))
            }
        }
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
                .clickable(onClickLabel = tr("跳转到分区", "Jump to section", "区分に移動", "분류로 이동", "跳至分區")) { showMenu = true },
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

