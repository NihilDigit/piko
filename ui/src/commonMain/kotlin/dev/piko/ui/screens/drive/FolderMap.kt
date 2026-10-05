package dev.piko.ui.screens.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.FolderMapLevel
import dev.piko.shared.state.FolderMapNode
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.fileDropTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 目录图：照 VS Code 的 minimap，按需召出、浮着的一棵文件夹树，用来在层层嵌套的目录间快速跳转（issue #18）。
 * 不常驻占一栏：侧边栏旁边再并排一栏导航是 ui/CLAUDE.md 否决过的，侧边栏的 240dp 也放不下五六层缩进。
 *
 * 宽窗口从地址栏每一段后面的 › 召出，以那一段为根；钉住后成为浮在列表左上方的卡片，跳转后不关。
 * 窄窗口从顶栏召出，是底部面板。只列文件夹与能当文件夹打开的压缩包，展开到哪一层才列哪一层。
 *
 * 状态挂在网盘页上，弹出版与钉住的卡片共用：列过的层与展开的节点在两者之间、多次召出之间都还在，
 * 再展开时先显示上次的、后台重列。
 */
@Stable
internal class FolderMapState(
    private val load: suspend (String) -> FolderMapLevel,
    private val scope: CoroutineScope,
) {
    val levels = mutableStateMapOf<String, FolderMapLevel>()
    private val expandedIds = mutableStateMapOf<String, Boolean>()

    /** 钉住的卡片以哪一级为根，没钉住时为 null。 */
    var pinnedRoot by mutableStateOf<List<PikoPathBreadcrumb>?>(null)

    fun isExpanded(id: String): Boolean = expandedIds[id] == true

    fun expand(id: String) {
        expandedIds[id] = true
        refresh(id)
    }

    fun collapse(id: String) {
        expandedIds.remove(id)
    }

    fun refresh(id: String) {
        scope.launch { levels[id] = load(id) }
    }

    /** 召出时展开到当前位置：[root] 以下、[current] 末级以上的各级都展开，眼前的文件夹露在树里。 */
    fun reveal(root: List<PikoPathBreadcrumb>, current: List<PikoPathBreadcrumb>) {
        refresh(root.last().id)
        val rootIndex = current.indexOfFirst { it.id == root.last().id }
        if (rootIndex < 0) return
        current.subList(rootIndex + 1, (current.size - 1).coerceAtLeast(rootIndex + 1)).forEach { expand(it.id) }
    }
}

/** 树里的一行。[node] 为 null 的是提示行：在列、列不出、要密码。 */
private class MapRow(
    val key: String,
    val stack: List<PikoPathBreadcrumb>,
    val depth: Int,
    val node: FolderMapNode? = null,
    val note: String? = null,
    val loading: Boolean = false,
    /** 提示行点了做什么：要密码的压缩包是进到包里输。 */
    val onNoteClick: (() -> Unit)? = null,
)

private fun flatten(
    state: FolderMapState,
    parent: List<PikoPathBreadcrumb>,
    depth: Int,
    onOpen: (List<PikoPathBreadcrumb>) -> Unit,
    out: MutableList<MapRow>,
) {
    val parentId = parent.last().id
    when (val level = state.levels[parentId]) {
        null -> out += MapRow("loading:$parentId", parent, depth, loading = true)
        is FolderMapLevel.Failed -> out += MapRow("failed:$parentId", parent, depth, note = level.message)
        FolderMapLevel.NeedsPassword -> out += MapRow("password:$parentId", parent, depth, note = "需要密码，点此进入后输入", onNoteClick = { onOpen(parent) })
        is FolderMapLevel.Loaded -> {
            if (level.nodes.isEmpty() && depth == 0) out += MapRow("empty:$parentId", parent, depth, note = "没有子文件夹")
            level.nodes.forEach { node ->
                val stack = parent + node.crumb
                out += MapRow("node:${node.crumb.id}", stack, depth, node = node)
                if (state.isExpanded(node.crumb.id)) flatten(state, stack, depth + 1, onOpen, out)
            }
        }
    }
}

// 过滤时不分层级，列出已经列过的各层里名字含 [query] 的；没展开过的层不去列，那要遍历整个网盘
private fun filtered(state: FolderMapState, root: List<PikoPathBreadcrumb>, query: String): List<MapRow> {
    val out = mutableListOf<MapRow>()
    fun walk(parent: List<PikoPathBreadcrumb>) {
        val level = state.levels[parent.last().id] as? FolderMapLevel.Loaded ?: return
        level.nodes.forEach { node ->
            val stack = parent + node.crumb
            if (node.crumb.name.contains(query, ignoreCase = true)) out += MapRow("node:${node.crumb.id}", stack, 0, node = node)
            walk(stack)
        }
    }
    walk(root)
    return out
}

/**
 * 树本身，弹出版、钉住的卡片与底部面板共用。顶上一行过滤，打字即就地过滤已列过的各层；
 * ↑↓ 在行间走，→ 展开或进到第一个子项，← 收起或回到上级，回车跳过去，Esc 交给 [onDismiss]。
 */
@Composable
internal fun FolderMapTree(
    state: FolderMapState,
    root: List<PikoPathBreadcrumb>,
    current: List<PikoPathBreadcrumb>,
    onOpen: (List<PikoPathBreadcrumb>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    focusFilter: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val rows = if (query.isBlank()) {
        buildList { flatten(state, root, 0, onOpen, this) }
    } else {
        filtered(state, root, query.trim())
    }
    val selectable = rows.indices.filter { rows[it].node != null || rows[it].onNoteClick != null }
    val currentId = current.lastOrNull()?.id
    var highlighted by remember(root.last().id) { mutableStateOf<String?>(null) }
    // 召出时高亮眼前所在的那一级，没有就是第一项
    LaunchedEffect(rows.size, query) {
        if (highlighted == null || rows.none { it.key == highlighted }) {
            highlighted = (rows.firstOrNull { it.node?.crumb?.id == currentId } ?: selectable.firstOrNull()?.let(rows::get))?.key
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusFilter) runCatching { focus.requestFocus() } }

    fun move(step: Int) {
        if (selectable.isEmpty()) return
        val at = selectable.indexOfFirst { rows[it].key == highlighted }
        val next = if (at < 0) 0 else (at + step).coerceIn(0, selectable.lastIndex)
        highlighted = rows[selectable[next]].key
    }

    fun activate(row: MapRow) {
        row.onNoteClick?.invoke()
        if (row.node != null) onOpen(row.stack)
    }

    Column(
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent event.key in TreeKeys
            val row = rows.firstOrNull { it.key == highlighted }
            when (event.key) {
                Key.DirectionDown -> move(1)
                Key.DirectionUp -> move(-1)
                Key.DirectionRight -> {
                    val id = row?.node?.crumb?.id ?: return@onPreviewKeyEvent false
                    if (!state.isExpanded(id)) state.expand(id) else move(1)
                }
                Key.DirectionLeft -> {
                    val id = row?.node?.crumb?.id ?: return@onPreviewKeyEvent false
                    if (state.isExpanded(id)) {
                        state.collapse(id)
                    } else {
                        val parentId = row.stack.getOrNull(row.stack.size - 2)?.id
                        rows.firstOrNull { it.node?.crumb?.id == parentId }?.let { highlighted = it.key }
                    }
                }
                Key.Enter, Key.NumPadEnter -> row?.let(::activate)
                Key.Escape -> onDismiss()
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(CircleShape)
                .background(colors.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("过滤已展开的文件夹", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            if (rows.isEmpty() && query.isNotBlank()) MapNote("已展开的文件夹里没有「${query.trim()}」", depth = 0)
            rows.forEach { row ->
                when {
                    row.loading -> Box(Modifier.padding(start = indent(row.depth) + 16.dp, top = 8.dp, bottom = 8.dp)) { InlineLoadingIndicator() }
                    row.node == null -> MapNote(row.note.orEmpty(), row.depth, highlighted = row.key == highlighted, onClick = row.onNoteClick)
                    else -> MapNodeRow(
                        row = row,
                        state = state,
                        current = current,
                        highlighted = row.key == highlighted,
                        showPath = query.isNotBlank(),
                        onClick = {
                            highlighted = row.key
                            activate(row)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MapNodeRow(
    row: MapRow,
    state: FolderMapState,
    current: List<PikoPathBreadcrumb>,
    highlighted: Boolean,
    showPath: Boolean,
    onClick: () -> Unit,
) {
    val node = row.node ?: return
    val colors = MaterialTheme.colorScheme
    val id = node.crumb.id
    val here = current.lastOrNull()?.id == id
    val onPath = !here && current.any { it.id == id }
    val loaded = state.levels[id] as? FolderMapLevel.Loaded
    val leaf = loaded != null && loaded.nodes.isEmpty()
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted) { if (highlighted) bring.bringIntoView() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .padding(horizontal = 4.dp)
            // 拖到树上的文件夹里就是移进去，与地址栏、侧边栏相同；压缩包与库的位置接不住，由 fileDropTarget 自己判断
            .fileDropTarget("map:$id", node.crumb)
            .clip(MaterialTheme.shapes.medium)
            .background(if (highlighted) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(start = indent(row.depth), end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 展开钮单独接点击：点名字是跳过去，点 › 只是展开
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .then(if (leaf) Modifier else Modifier.clickable { if (state.isExpanded(id)) state.collapse(id) else state.expand(id) }),
            contentAlignment = Alignment.Center,
        ) {
            if (!leaf) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (state.isExpanded(id)) "收起" else "展开",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp).rotate(if (state.isExpanded(id)) 90f else 0f),
                )
            }
        }
        Icon(
            if (node.isArchive) Icons.Outlined.FolderZip else Icons.Outlined.Folder,
            contentDescription = null,
            tint = if (here) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.crumb.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (here || onPath) FontWeight.SemiBold else null,
                color = if (here) colors.primary else if (highlighted) colors.onSecondaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showPath && row.stack.size > 2) {
                Text(
                    row.stack.drop(1).dropLast(1).joinToString(" › ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MapNote(text: String, depth: Int, highlighted: Boolean = false, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (highlighted) colors.secondaryContainer else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 36.dp)
            .padding(start = indent(depth) + 28.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onClick != null) Icon(Icons.Outlined.Key, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

private fun indent(depth: Int): Dp = TreeIndent * depth

/**
 * 地址栏 › 召出的目录图：以那一段为根。点名字跳过去并收起；右上角的图钉钉成卡片（[onPin]），之后跳转不收。
 */
@Composable
internal fun FolderMapMenu(
    expanded: Boolean,
    state: FolderMapState,
    root: List<PikoPathBreadcrumb>,
    current: List<PikoPathBreadcrumb>,
    onOpen: (List<PikoPathBreadcrumb>) -> Unit,
    onPin: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(expanded, root.last().id) { if (expanded) state.reveal(root, current) }
    dev.piko.ui.components.PikoDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(Modifier.width(FolderMapWidth).heightIn(max = FolderMapMaxHeight)) {
            MapHeader(title = root.last().name, pinned = false, onPin = onPin, onClose = null)
            FolderMapTree(
                state = state,
                root = root,
                current = current,
                onOpen = { stack ->
                    onDismiss()
                    onOpen(stack)
                },
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * 钉住的目录图：浮在列表左上方的卡片，按住标题行拖动，拖右下角改大小。跳转后不收，取消钉住或关掉才收。
 * 不放进右侧那一栏：那一栏只放详情或信息流，见 ui/CLAUDE.md。
 */
@Composable
internal fun PinnedFolderMap(
    state: FolderMapState,
    current: List<PikoPathBreadcrumb>,
    onOpen: (List<PikoPathBreadcrumb>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val root = state.pinnedRoot ?: return
    LaunchedEffect(root.last().id) { state.reveal(root, current) }
    // 眼前的位置变了，树跟着展开到那里，才看得到自己在哪
    LaunchedEffect(current.lastOrNull()?.id) { state.reveal(root, current) }
    val density = LocalDensity.current
    var offset by remember { mutableStateOf(DpOffset(12.dp, 12.dp)) }
    var size by remember { mutableStateOf(DpSize(FolderMapWidth, 420.dp)) }
    Surface(
        modifier = modifier
            .offset { with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) } }
            .size(size),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Box {
            Column(Modifier.fillMaxSize()) {
                MapHeader(
                    title = root.last().name,
                    pinned = true,
                    onPin = { state.pinnedRoot = null },
                    onClose = { state.pinnedRoot = null },
                    modifier = Modifier.pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            offset = with(density) {
                                DpOffset((offset.x + drag.x.toDp()).coerceAtLeast(0.dp), (offset.y + drag.y.toDp()).coerceAtLeast(0.dp))
                            }
                        }
                    },
                )
                FolderMapTree(
                    state = state,
                    root = root,
                    current = current,
                    onOpen = onOpen,
                    onDismiss = { state.pinnedRoot = null },
                    focusFilter = false,
                    modifier = Modifier.weight(1f),
                )
            }
            Icon(
                Icons.Outlined.OpenInFull,
                contentDescription = "调整大小",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .rotate(90f)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            size = with(density) {
                                DpSize(
                                    (size.width + drag.x.toDp()).coerceIn(FolderMapMinSize, 720.dp),
                                    (size.height + drag.y.toDp()).coerceIn(FolderMapMinSize, 1200.dp),
                                )
                            }
                        }
                    },
            )
        }
    }
}

@Composable
private fun MapHeader(title: String, pinned: Boolean, onPin: (() -> Unit)?, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onPin != null) {
            TooltipIconButton(
                icon = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                label = if (pinned) "取消钉住" else "钉在列表上",
                onClick = onPin,
            )
        }
        if (onClose != null) TooltipIconButton(Icons.Outlined.Close, "关闭", onClose)
    }
}

// 这几个键的抬起也吃掉：按下已由树处理，抬起再落到网盘页上，方向键会挪列表焦点、Esc 会触发返回
private val TreeKeys = setOf(Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight, Key.Escape, Key.Enter)

private val TreeIndent = 16.dp
internal val FolderMapWidth = 360.dp
private val FolderMapMaxHeight = 480.dp
private val FolderMapMinSize = 200.dp
