package dev.piko.ui.screens.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.AddressCompletion
import dev.piko.shared.state.InstantSheetState
import dev.piko.ui.components.ContextMenuArea
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.PaletteItem
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.fileDropTarget
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.components.releasesFocusOnOutsidePress
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 地址栏要的数据与动作，由网盘页拼好传进来。放在一处是因为地址栏同时是路径、历史、链接与搜索的入口，
 * 逐个列成参数有十几个。
 */
internal class AddressBarModel(
    val stack: List<PikoPathBreadcrumb>,
    /** 点路径的某一段：退回到那一级。 */
    val onNavigate: (index: Int) -> Unit,
    /** 换到一条完整的路径：补全、历史、兄弟菜单都走这里，记进浏览历史。 */
    val onOpenStack: (List<PikoPathBreadcrumb>) -> Unit,
    /** 回车时没挑下拉里的项，按输入的路径走；找到了返回 true。 */
    val onSubmitPath: suspend (String) -> Boolean,
    val completions: suspend (String) -> AddressCompletion,
    val subfoldersOf: suspend (folderId: String) -> Result<List<PikoPathBreadcrumb>>,
    val recent: List<List<PikoPathBreadcrumb>>,
    val pinned: List<PikoPathBreadcrumb>,
    /** 快速访问只存 ID 与名字，上级要现查。 */
    val onOpenPinned: (PikoPathBreadcrumb) -> Unit,
    val onRemoveRecent: (folderId: String) -> Unit,
    /** 磁力链接或分享链接：交给「添加链接」，它两种都认。 */
    val onOpenLink: (String) -> Unit,
    val onSearchHere: (String) -> Unit,
    val onSearchAll: (String) -> Unit,
    /** 页面与去处，与命令面板同一份，只取「前往」「页面」两组。 */
    val destinations: List<PaletteItem>,
)

/**
 * 宽窗口顶栏上的地址栏，照资源管理器：一条底框里是「☁ 网盘 › 动画 › Frieren」，每一段都能点，
 * 每个 › 点开是这一级下的全部文件夹，直接跳到同级。点路径后面的空白处、右端的 ˅，或 [editRequests] 加一
 * （快捷键走这条）换成输入框，里面是完整路径并全选，下面接一个下拉：
 * 还没动过输入时是最近去过的与快速访问；输入时是逐层补全，Tab 补上当前一段并接着输下一段，↑↓ 挑、回车前往；
 * 粘进磁力或分享链接时给「添加链接」；输入的不是现有路径时给搜索；输入页面名时给前往那一页。
 * 右键有复制路径与编辑路径。
 * 路径常驻在顶栏上，不随列表滚走；窄屏仍是目录名作标题、上级另成一行面包屑。
 */
@Composable
internal fun DrivePathTitle(model: AddressBarModel, editRequests: Int) {
    var editing by remember { mutableStateOf(false) }
    // 只认进组合之后的请求：计数器由网盘页持有，窗口从窄拉宽、地址栏重新进组合时它已是旧值，不该一进来就是输入态
    val initialRequests = remember { editRequests }
    LaunchedEffect(editRequests) { if (editRequests != initialRequests) editing = true }
    val colors = MaterialTheme.colorScheme
    val platform = LocalPikoPlatform.current
    val pathText = model.stack.joinToString("/") { it.name }
    var barWidth by remember { mutableIntStateOf(0) }
    ContextMenuArea(
        actions = {
            listOf(
                SheetAction(Icons.Outlined.ContentCopy, "复制路径", { platform.copyToClipboard("路径", pathText) }),
                SheetAction(Icons.Outlined.Edit, "编辑路径", { editing = true }),
            )
        },
        enabled = !editing,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .onSizeChanged { barWidth = it.width }
                // 点到列表、命令栏、侧边栏这些不可聚焦的地方也要收起输入：输入框失焦即收起，见 AddressField
                .releasesFocusOnOutsidePress()
                .clip(CircleShape)
                .background(colors.headerFieldColor)
                // 各段自己接住单击；落在段外的（路径后面的空白）进入输入
                .then(if (editing) Modifier else Modifier.clickable(onClickLabel = "输入路径") { editing = true })
                .padding(start = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (editing) {
                AddressField(
                    model = model,
                    initial = pathText,
                    menuWidth = with(LocalDensity.current) { barWidth.toDp() },
                    onClose = { editing = false },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PathCrumbs(model) }
                    // 触屏没有右键与快捷键，历史与输入从这里进
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Outlined.ExpandMore, contentDescription = "最近去过的文件夹", tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 下拉里的一项。[completion] 是 Tab 补成的文本，只有文件夹有；[onRemove] 只有最近去过的有。 */
private class Suggestion(
    val key: String,
    val group: String,
    val title: String,
    val icon: ImageVector,
    val detail: String? = null,
    val completion: String? = null,
    val onRemove: (() -> Unit)? = null,
    /** 为 true 时点了不收起输入框：「显示全部」。 */
    val keepsOpen: Boolean = false,
    val run: () -> Unit,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddressField(model: AddressBarModel, initial: String, menuWidth: Dp, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    var hadFocus by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var completion by remember { mutableStateOf<AddressCompletion?>(null) }
    var highlighted by remember { mutableIntStateOf(-1) }
    var showAllRecent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val text = value.text
    // 进来时是整条路径且全选，没动过就还是「看历史」，动了才补全
    val browsingHistory = text == initial
    val link = remember(text) { text.trim().takeIf { it.startsWith("magnet:", ignoreCase = true) || InstantSheetState.findShareLink(it) != null } }

    // 每敲一个字换一次 key，旧的一次随之取消；停一会儿再问，连着敲字只列最后一次
    LaunchedEffect(text) {
        if (browsingHistory || link != null) {
            completion = null
            return@LaunchedEffect
        }
        delay(COMPLETION_DEBOUNCE_MS)
        completion = model.completions(text)
    }

    fun complete(to: String) {
        value = TextFieldValue(to, selection = TextRange(to.length))
        highlighted = -1
    }

    val suggestions: List<Suggestion> = buildList {
        if (browsingHistory) {
            val here = model.stack.lastOrNull()?.id
            val recent = model.recent.filter { it.last().id != here }
            val shown = if (showAllRecent) recent else recent.take(RECENT_SHOWN)
            shown.forEach { stack ->
                val id = stack.last().id
                add(Suggestion("recent:$id", "最近", stack.last().name, Icons.Outlined.History, detail = stack.displayPath(),
                    completion = stack.pathText(), onRemove = { model.onRemoveRecent(id) }) { model.onOpenStack(stack) })
            }
            if (recent.size > shown.size) {
                add(Suggestion("recent:all", "最近", "显示全部（${recent.size} 项）", Icons.Outlined.UnfoldMore, keepsOpen = true) { showAllRecent = true })
            }
            model.pinned.forEach { folder ->
                add(Suggestion("pinned:${folder.id}", "快速访问", folder.name, Icons.Outlined.PushPin) { model.onOpenPinned(folder) })
            }
            add(Suggestion("copy", "操作", "复制路径", Icons.Outlined.ContentCopy) { platform.copyToClipboard("路径", initial) })
            return@buildList
        }
        if (link != null) {
            val isShare = InstantSheetState.findShareLink(link) != null
            add(Suggestion("link", "链接", if (isShare) "打开分享链接" else "添加磁力链接", if (isShare) Icons.Outlined.Link else Icons.Outlined.Bolt) {
                model.onOpenLink(link)
            })
            return@buildList
        }
        val result = completion
        result?.matches?.forEach { stack ->
            add(Suggestion("folder:${stack.last().id}", "文件夹", stack.last().name, Icons.Outlined.Folder,
                detail = stack.displayPath(), completion = stack.pathText() + "/") { model.onOpenStack(stack) })
        }
        val needle = text.trim()
        if (needle.isNotEmpty() && '/' !in needle && '\\' !in needle) {
            model.destinations.filter { it.title.contains(needle, ignoreCase = true) || it.keywords.contains(needle, ignoreCase = true) }
                .forEach { item -> add(Suggestion("page:${item.title}", "前往", item.title, item.icon, detail = item.detail) { item.run() }) }
        }
        // 输入的已是一个现有文件夹时不给搜索：回车就过去了
        val term = result?.partial.orEmpty()
        val exact = result != null && result.parentFound && result.matches.any { it.last().name.equals(term, ignoreCase = true) }
        if (result != null && term.isNotEmpty() && !exact) {
            add(Suggestion("search:here", "搜索", "在当前文件夹中搜索「$term」", Icons.Outlined.Search) { model.onSearchHere(term) })
            add(Suggestion("search:all", "搜索", "全盘搜索「$term」", Icons.Outlined.TravelExplore) { model.onSearchAll(term) })
        }
    }
    // 下拉的内容换了，高亮回到「没挑」；链接只有一项，直接挑上，回车即处理
    LaunchedEffect(suggestions.map { it.key }) { highlighted = if (link != null) 0 else highlighted.coerceAtMost(suggestions.lastIndex) }

    fun runSuggestion(index: Int) {
        val item = suggestions.getOrNull(index) ?: return
        item.run()
        if (!item.keepsOpen) onClose()
    }

    fun submit() {
        if (resolving) return
        resolving = true
        scope.launch {
            val found = model.onSubmitPath(text)
            resolving = false
            if (found) onClose()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (highlighted >= 0) runSuggestion(highlighted) else submit() }),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) hadFocus = true else if (hadFocus) onClose()
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent event.key in HandledKeys
                    when (event.key) {
                        Key.Escape -> onClose()
                        Key.DirectionDown -> highlighted = (highlighted + 1).coerceAtMost(suggestions.lastIndex)
                        Key.DirectionUp -> highlighted = (highlighted - 1).coerceAtLeast(-1)
                        // Tab 补上挑中的一项，没挑时补第一个文件夹；没有可补的就让 Tab 照常移走焦点
                        Key.Tab -> {
                            val target = suggestions.getOrNull(highlighted)?.completion
                                ?: suggestions.firstOrNull { it.completion != null }?.completion
                                ?: return@onPreviewKeyEvent false
                            complete(target)
                        }
                        Key.Enter, Key.NumPadEnter -> if (highlighted >= 0) runSuggestion(highlighted) else submit()
                        // 挑中的是最近去过的一项时，Delete 删掉它，照资源管理器地址栏的历史
                        Key.Delete -> {
                            val remove = suggestions.getOrNull(highlighted)?.onRemove ?: return@onPreviewKeyEvent false
                            remove()
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        )
        // 逐层列目录要几次请求，慢的时候得看得出在找
        if (resolving) InlineLoadingIndicator()
        // 触屏没有 Esc，点外面也不失焦（见 releasesFocusOnOutsidePress），输入态得有一个看得见的出口
        TooltipIconButton(Icons.Outlined.Close, "取消", onClose, shortcut = "Esc", tint = colors.onSurfaceVariant)
    }

    // 不抢焦点的弹层：键盘留在输入框里，↑↓ 只挪高亮。点弹层外面时输入框失焦、整个收起，不必另外处理
    DropdownMenu(
        expanded = suggestions.isNotEmpty(),
        onDismissRequest = {},
        offset = DpOffset((-8).dp, 8.dp),
        properties = PopupProperties(focusable = false),
        shape = MenuDefaults.shape,
        containerColor = MenuDefaults.containerColor,
        modifier = Modifier.width(menuWidth).heightIn(max = SuggestionsMaxHeight),
    ) {
        suggestions.forEachIndexed { index, item ->
            if (index == 0 || suggestions[index - 1].group != item.group) {
                Text(
                    item.group,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = if (index == 0) 4.dp else 12.dp, bottom = 4.dp),
                )
            }
            SuggestionRow(item, highlighted = index == highlighted, onClick = { runSuggestion(index) })
        }
    }
}

@Composable
private fun SuggestionRow(item: Suggestion, highlighted: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (highlighted) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(start = 12.dp, end = if (item.onRemove != null) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(item.icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (highlighted) colors.onSecondaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item.onRemove?.let { remove ->
            IconButton(onClick = remove) {
                Icon(Icons.Outlined.Close, contentDescription = "从最近去过的文件夹中删除", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * 不在输入时的路径：每一段能点、能接住拖来的条目；段后的 › 点开是这一级下的文件夹，照资源管理器。
 * 最后一段后面也有一个，列当前文件夹里的子文件夹。放不下时横向滚动并停在末尾，鼠标竖滚轮也滚得动。
 */
@Composable
private fun PathCrumbs(model: AddressBarModel) {
    val stack = model.stack
    val scroll = rememberScrollState()
    LaunchedEffect(stack) { scroll.scrollTo(scroll.maxValue) }
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .verticalWheelScrollsRow(scroll)
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stack.forEachIndexed { index, crumb ->
            Row(
                modifier = Modifier
                    // 每一段都接得住拖来的条目：拖到「网盘」就是移回根目录
                    .fileDropTarget("crumb:${crumb.id}", crumb)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = "打开") { model.onNavigate(index) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (index == 0) {
                    Icon(Icons.Outlined.Cloud, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                CrumbName(crumb.name, current = index == stack.lastIndex)
            }
            SiblingChevron(
                level = stack.take(index + 1),
                next = stack.getOrNull(index + 1),
                subfoldersOf = model.subfoldersOf,
                onOpenStack = model.onOpenStack,
            )
        }
    }
}

/**
 * 路径段的名字，过长时截断，悬停看全名。发布组的文件夹名常常带着整串标签，一段就能把地址栏占满，
 * 只靠横向滚动的话上级几段全被挤出视野。当前所在的一段最常被人看，上限放宽一些。
 */
@Composable
private fun CrumbName(name: String, current: Boolean) {
    var truncated by remember(name) { mutableStateOf(false) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { if (truncated) PlainTooltip { Text(name) } },
        state = rememberTooltipState(),
    ) {
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            onTextLayout = { truncated = it.hasVisualOverflow },
            modifier = Modifier.widthIn(max = if (current) CurrentCrumbMaxWidth else CrumbMaxWidth),
        )
    }
}

private val CrumbMaxWidth = 200.dp
private val CurrentCrumbMaxWidth = 360.dp

/**
 * 路径段后面的 ›：点开列出 [level] 末级下的全部文件夹，路径上的下一级标亮。
 * 这里曾换成以这一段为根的目录图，用户找不到也用不上，已撤回；目录图只在右沿的细轨上，见 FolderMap。
 */
@Composable
private fun SiblingChevron(
    level: List<PikoPathBreadcrumb>,
    next: PikoPathBreadcrumb?,
    subfoldersOf: suspend (String) -> Result<List<PikoPathBreadcrumb>>,
    onOpenStack: (List<PikoPathBreadcrumb>) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    // null 是还在列，空表是没有子文件夹
    var folders by remember(level.last().id) { mutableStateOf<Result<List<PikoPathBreadcrumb>>?>(null) }
    LaunchedEffect(expanded, level.last().id) { if (expanded) folders = subfoldersOf(level.last().id) }
    Box {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "「${level.last().name}」里的文件夹",
            tint = colors.onSurfaceVariant,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = true }
                .padding(vertical = 4.dp)
                .size(18.dp),
        )
        PikoDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = SuggestionsMaxHeight)) {
            val loaded = folders
            when {
                loaded == null -> Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) { InlineLoadingIndicator() }
                loaded.isFailure -> MenuNote(loaded.exceptionOrNull()?.message ?: "列不出这个文件夹，请检查网络")
                loaded.getOrThrow().isEmpty() -> MenuNote("没有子文件夹")
                else -> {
                    val list = loaded.getOrThrow()
                    list.forEachIndexed { index, folder ->
                        val onPath = folder.id == next?.id
                        DropdownMenuItem(
                            text = {
                                Text(
                                    folder.name,
                                    fontWeight = if (onPath) FontWeight.SemiBold else null,
                                    color = if (onPath) colors.primary else Color.Unspecified,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            shape = menuItemShape(index, list.size),
                            onClick = {
                                expanded = false
                                onOpenStack(level + folder)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** 输入框里的写法，与 goToPath 认的一致：「网盘/动画/Frieren」。 */
private fun List<PikoPathBreadcrumb>.pathText() = joinToString("/") { it.name }

/** 下拉里的灰字：上级的路径，末级已是标题。 */
private fun List<PikoPathBreadcrumb>.displayPath() = dropLast(1).joinToString(" › ") { it.name }

// 这几个键的抬起也吃掉：按下已由地址栏处理，抬起再落到网盘页上，Esc 会触发返回、方向键会挪列表焦点
private val HandledKeys = setOf(Key.DirectionDown, Key.DirectionUp, Key.Escape)

private const val COMPLETION_DEBOUNCE_MS = 120L
private const val RECENT_SHOWN = 5
private val SuggestionsMaxHeight = 420.dp
