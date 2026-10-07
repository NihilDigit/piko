package dev.piko.ui.screens.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.DpRect
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.ui.components.typeIcon
import io.github.nihildigit.pikpak.FileStat
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.FolderMapLevel
import dev.piko.shared.state.FolderMapNode
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.fileDropTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 目录图：一棵浮在网盘页上的文件夹树，用来在层层嵌套的目录间快速跳转（issue #18），只在宽窗口里有（[FolderMapPanel]）。
 * 文件夹与文件都列：只列文件夹时要展开到底才看得出内容在哪一层。展开到哪一层才列哪一层。
 * 浮着而不让出位置：让出的话网格随开合与拖宽反复重排；也不进侧边栏或右侧那一栏：侧边栏 240dp 放不下五六层缩进，
 * 右侧那一栏只放信息流。窄窗口不做：手机上面包屑与返回已够用，层层点进去的麻烦主要在桌面的大目录里。
 * 撤回过的入口：地址栏 › 召出以那一段为根的弹出版，藏在分隔符里没人找得到。
 *
 * 状态挂在网盘页上：列过的层与展开的节点都还在，再展开时先显示上次的、后台重列。
 */
@Stable
internal class FolderMapState(
    private val load: suspend (String) -> FolderMapLevel,
    private val scope: CoroutineScope,
) {
    val levels = mutableStateMapOf<String, FolderMapLevel>()
    private val expandedIds = mutableStateMapOf<String, Boolean>()

    /** 面板拖到的位置与大小，随网盘页存续。 */
    val panel = FloatingPanelState(PanelWidth)

    // 点过「还有 N 个文件」的文件夹，文件全部列出
    private val allFilesIds = mutableStateMapOf<String, Boolean>()

    fun showsAllFiles(id: String): Boolean = allFilesIds[id] == true

    fun showAllFiles(id: String) {
        allFilesIds[id] = true
    }

    fun foldFiles(id: String) {
        allFilesIds.remove(id)
    }

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

/**
 * 树里的一行。[node] 为 null 的是提示行：在列、列不出、要密码、还有几个文件没列。
 * [guides] 是各级上级的竖线要不要穿过这一行（那一级后面还有兄弟），[last] 是它是不是这一层的最后一项，画引导线用。
 */
private class MapRow(
    val key: String,
    val stack: List<PikoPathBreadcrumb>,
    val depth: Int,
    val node: FolderMapNode? = null,
    val note: String? = null,
    val loading: Boolean = false,
    /** 提示行点了做什么：要密码的压缩包是进到包里输，省略的文件是全部列出。 */
    val onNoteClick: (() -> Unit)? = null,
    val noteIcon: ImageVector? = null,
    val guides: List<Boolean> = emptyList(),
    val last: Boolean = true,
)

/**
 * 树按眼下的展开情形摊成一行一行。最内层的文件夹（没有子文件夹）文件超过 [FilesShown] 个时只列前几个，
 * 第四行是「还有 N 个文件」，点了才全列：一集一个文件的番剧文件夹全列出来，
 * 几十行把上下的文件夹挤得看不到，树就不成其为导航了。有子文件夹的一层照全列，规则只此一条，好猜。
 */
private fun flatten(
    state: FolderMapState,
    parent: List<PikoPathBreadcrumb>,
    depth: Int,
    onOpen: (List<PikoPathBreadcrumb>) -> Unit,
    out: MutableList<MapRow>,
    guides: List<Boolean> = emptyList(),
) {
    val parentId = parent.last().id
    when (val level = state.levels[parentId]) {
        null -> out += MapRow("loading:$parentId", parent, depth, loading = true, guides = guides)
        is FolderMapLevel.Failed -> out += MapRow("failed:$parentId", parent, depth, note = level.message, guides = guides)
        FolderMapLevel.NeedsPassword -> out += MapRow(
            "password:$parentId", parent, depth, note = "需要密码，点此进入后输入", onNoteClick = { onOpen(parent) },
            noteIcon = Icons.Outlined.Key, guides = guides,
        )
        is FolderMapLevel.Loaded -> {
            if (level.nodes.isEmpty()) {
                out += MapRow("empty:$parentId", parent, depth, note = "空文件夹", guides = guides)
                return
            }
            val (expandable, files) = level.nodes.partition { it.expandable }
            val foldable = expandable.isEmpty() && files.size > FilesShown
            val hidden = if (foldable && !state.showsAllFiles(parentId)) files.size - FilesShown else 0
            val shown = expandable + files.dropLast(hidden)
            // 能省略的一层末尾总有一行开合：收着时是「还有 N 个文件」，全列出来后是「收起」，不然展开了就收不回去
            shown.forEachIndexed { index, node ->
                val last = index == shown.lastIndex && !foldable
                val stack = parent + node.crumb
                out += MapRow("node:${node.crumb.id}", stack, depth, node = node, guides = guides, last = last)
                if (node.expandable && state.isExpanded(node.crumb.id)) flatten(state, stack, depth + 1, onOpen, out, guides + !last)
            }
            if (hidden > 0) {
                out += MapRow(
                    "more:$parentId", parent, depth, note = "还有 $hidden 个文件", onNoteClick = { state.showAllFiles(parentId) },
                    noteIcon = Icons.Outlined.UnfoldMore, guides = guides,
                )
            } else if (foldable) {
                out += MapRow(
                    "less:$parentId", parent, depth, note = "收起", onNoteClick = { state.foldFiles(parentId) },
                    noteIcon = Icons.Outlined.UnfoldLess, guides = guides,
                )
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

/** 点了树里的一行：能展开的跳过去，文件在它所在的文件夹（[MapRow.stack] 去掉末级）里打开。 */
private fun MapRow.activate(onOpen: (List<PikoPathBreadcrumb>) -> Unit, onOpenFile: (List<PikoPathBreadcrumb>, FileStat) -> Unit) {
    onNoteClick?.invoke()
    val node = node ?: return
    val file = node.file
    if (file != null) onOpenFile(stack.dropLast(1), file) else onOpen(stack)
}

/**
 * 树本身，细轨旁的浮层与底部面板共用。顶上一行过滤，打字即就地过滤已列过的各层；
 * ↑↓ 在行间走，→ 展开或进到第一个子项，← 收起或回到上级，回车跳过去或打开文件，Esc 交给 [onDismiss]。
 */
@Composable
internal fun FolderMapTree(
    state: FolderMapState,
    root: List<PikoPathBreadcrumb>,
    current: List<PikoPathBreadcrumb>,
    onOpen: (List<PikoPathBreadcrumb>) -> Unit,
    onOpenFile: (List<PikoPathBreadcrumb>, FileStat) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    focusFilter: Boolean = true,
    /** 过滤框显不显示。细轨旁的树收在标题栏的放大镜后面，点了才出来。 */
    filterShown: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    LaunchedEffect(filterShown) { if (!filterShown) query = "" }
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
    LaunchedEffect(filterShown) { if (focusFilter && filterShown) runCatching { focus.requestFocus() } }

    fun move(step: Int) {
        if (selectable.isEmpty()) return
        val at = selectable.indexOfFirst { rows[it].key == highlighted }
        val next = if (at < 0) 0 else (at + step).coerceIn(0, selectable.lastIndex)
        highlighted = rows[selectable[next]].key
    }

    fun activate(row: MapRow) = row.activate(onOpen, onOpenFile)

    Column(
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent event.key in TreeKeys
            val row = rows.firstOrNull { it.key == highlighted }
            when (event.key) {
                Key.DirectionDown -> move(1)
                Key.DirectionUp -> move(-1)
                Key.DirectionRight -> {
                    val id = row?.node?.takeIf { it.expandable }?.crumb?.id ?: return@onPreviewKeyEvent false
                    if (!state.isExpanded(id)) state.expand(id) else move(1)
                }
                Key.DirectionLeft -> {
                    val id = row?.node?.crumb?.id ?: return@onPreviewKeyEvent false
                    if (row.node.expandable && state.isExpanded(id)) {
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
        if (filterShown) Row(
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
                if (query.isEmpty()) Text("过滤已展开的内容", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
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
            if (rows.isEmpty() && query.isNotBlank()) MapNote("已展开的内容里没有「${query.trim()}」", row = null)
            rows.forEach { row ->
                when {
                    row.loading -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .treeGuides(row, colors.outlineVariant, childrenBelow = false)
                            .padding(start = indent(row.depth) + 28.dp, top = 8.dp, bottom = 8.dp),
                    ) { InlineLoadingIndicator() }
                    row.node == null -> MapNote(row.note.orEmpty(), row, highlighted = row.key == highlighted, onClick = row.onNoteClick, icon = row.noteIcon)
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
    // 列出来是空的、又收着的才算叶子，不画展开钮。展开着的照画：点开时还不知道是空的，展开之后要能收回去
    val leaf = !node.expandable || (loaded != null && loaded.nodes.isEmpty() && !state.isExpanded(id))
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted) { if (highlighted) bring.bringIntoView() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .padding(horizontal = 4.dp)
            // 拖到树上的文件夹里就是移进去，与地址栏、侧边栏相同；压缩包与库的位置接不住，由 fileDropTarget 自己判断
            .then(if (node.expandable) Modifier.fileDropTarget("map:$id", node.crumb) else Modifier)
            .clip(MaterialTheme.shapes.medium)
            .background(if (highlighted) colors.secondaryContainer else Color.Transparent)
            .treeGuides(row, colors.outlineVariant, childrenBelow = node.expandable && !leaf && state.isExpanded(id))
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
            node.file?.typeIcon() ?: if (node.isArchive) Icons.Outlined.FolderZip else Icons.Outlined.Folder,
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
private fun MapNote(text: String, row: MapRow?, highlighted: Boolean = false, onClick: (() -> Unit)? = null, icon: ImageVector? = null) {
    val colors = MaterialTheme.colorScheme
    val depth = row?.depth ?: 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (highlighted) colors.secondaryContainer else Color.Transparent)
            .then(if (row != null) Modifier.treeGuides(row, colors.outlineVariant, childrenBelow = false) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 36.dp)
            .padding(start = indent(depth) + 28.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

private fun indent(depth: Int): Dp = TreeIndent * depth

/**
 * 树的引导线，照文件管理器：每一层的子项挂在上级展开钮正下方的竖线上，中间的是 ├，最后一个是 └，
 * 上级后面还有兄弟时，那一级的竖线穿过整段子树。[childrenBelow] 是这一行自己展开着、下面接着它的子项，
 * 从展开钮下方起补一段竖线接上。坐标以展开钮（宽 28dp）的中线为准。
 */
private fun Modifier.treeGuides(row: MapRow, color: Color, childrenBelow: Boolean): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    fun x(level: Int) = (TreeIndent * level + 14.dp).toPx()
    val depth = row.depth
    // guides[k] 是第 k 级的那位上级后面还有没有兄弟，它挂在第 k-1 级的竖线上
    for (k in 1 until depth) {
        if (row.guides.getOrElse(k) { false }) drawLine(color, Offset(x(k - 1), 0f), Offset(x(k - 1), size.height), stroke)
    }
    val centerY = size.height / 2
    if (depth > 0) {
        val lineX = x(depth - 1)
        drawLine(color, Offset(lineX, 0f), Offset(lineX, if (row.last) centerY else size.height), stroke)
        drawLine(color, Offset(lineX, centerY), Offset((TreeIndent * depth + 8.dp).toPx(), centerY), stroke)
    }
    if (childrenBelow) drawLine(color, Offset(x(depth), centerY + 9.dp.toPx()), Offset(x(depth), size.height), stroke)
}

/**
 * 宽窗口网盘页的目录图：一块浮在列表上的面板。导航栏搜索旁的树形按钮打开，打开后一直开着、跳转也不收，
 * 面板上的 × 关掉（[onClose]），关着时导航栏上才有那个按钮。拖动、改大小与层叠见 [FloatingPanel]，[avoid] 等参数照传。
 * 由调用方铺满列表这一块。树的根总是网盘根目录，进来与换了位置时都展开到眼前的文件夹。
 *
 * 否决过的形态：贴在右沿的缩略图细轨（悬停展开、与树同一个容器形变、拖到任意位置），碰到的东西当场变形、
 * 拖的与停下的不是同一个东西，补了延时、方向冻结、吸附鼠标仍旧别扭；缩略图上放按钮又难看。也试过钉住开关，
 * 面板能随便拖、打开就一直开着之后用不着了。
 */
@Composable
internal fun FolderMapPanel(
    state: FolderMapState,
    current: List<PikoPathBreadcrumb>,
    onClose: () -> Unit,
    onOpen: (List<PikoPathBreadcrumb>) -> Unit,
    onOpenFile: (List<PikoPathBreadcrumb>, FileStat) -> Unit,
    modifier: Modifier = Modifier,
    avoid: () -> DpRect? = { null },
    onActivate: () -> Unit = {},
) {
    val root = remember { listOf(PikoDriveRepository.ROOT_BREADCRUMB) }
    LaunchedEffect(current.lastOrNull()?.id) { state.reveal(root, current) }
    var filterShown by remember { mutableStateOf(false) }
    FloatingPanel(
        state = state.panel,
        title = "目录图",
        closeLabel = "关闭目录图",
        onClose = onClose,
        minWidth = PanelMinWidth,
        maxWidth = PanelMaxWidth,
        autoMaxHeight = FolderMapMaxHeight,
        modifier = modifier,
        avoid = avoid,
        onActivate = onActivate,
        headerActions = {
            PanelHeaderToggle(Icons.Outlined.Search, Icons.Filled.Search, "过滤", checked = filterShown) { filterShown = !filterShown }
        },
    ) {
        FolderMapTree(
            state = state,
            root = root,
            current = current,
            onOpen = onOpen,
            onOpenFile = onOpenFile,
            onDismiss = onClose,
            filterShown = filterShown,
            focusFilter = false,
            modifier = Modifier.weight(1f, fill = state.panel.height != null),
        )
    }
}

// 这几个键的抬起也吃掉：按下已由树处理，抬起再落到网盘页上，方向键会挪列表焦点、Esc 会触发返回
private val TreeKeys = setOf(Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight, Key.Escape, Key.Enter)

private val TreeIndent = 16.dp

// 每层列出的文件数，多出的收成一行「还有 N 个文件」。三个够看出是什么，又不把上下的文件夹挤走
private const val FilesShown = 3
private val FolderMapMaxHeight = 560.dp

private val PanelWidth = 360.dp
private val PanelMinWidth = 220.dp
private val PanelMaxWidth = 720.dp
