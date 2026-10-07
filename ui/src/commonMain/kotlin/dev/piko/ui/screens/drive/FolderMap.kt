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
import androidx.compose.material.icons.outlined.Home
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
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.isDriveFolderId
import dev.piko.ui.components.typeIcon
import io.github.nihildigit.pikpak.FileStat
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.FolderMapLevel
import dev.piko.shared.state.FolderMapNode
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.fileDropTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.theme.LocalPikoMotion
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.TimeSource

/**
 * 目录图：一棵浮在网盘页上的文件夹树，用来在层层嵌套的目录间快速跳转（issue #18），只在宽窗口里有（[FolderMapPanel]）。
 * 文件夹与文件都列：只列文件夹时要展开到底才看得出内容在哪一层。展开到哪一层才列哪一层。
 * 浮着而不让出位置：让出的话网格随开合与拖宽反复重排；也不进侧边栏或右侧那一栏：侧边栏 240dp 放不下五六层缩进，
 * 右侧那一栏只放信息流。窄窗口不做：手机上面包屑与返回已够用，层层点进去的麻烦主要在桌面的大目录里。
 * 撤回过的入口：地址栏 › 召出以那一段为根的弹出版，藏在分隔符里没人找得到。
 *
 * 状态挂在网盘页上：列过的层与展开的节点都还在，再展开时先显示上次的、后台重列。
 * 过滤、高亮与滚动位置也在这里而不在组合里：自动收起时面板离开组合（隐藏的面板留在组合里的话，焦点遍历与读屏还会进去），
 * 展开回来要原样。展开与收起的时机见 [FolderMapAutoHide]；开着与否、钉着与否两项分别经 [saveOpen]、[savePinned] 存进偏好。
 */
@Stable
internal class FolderMapState(
    private val load: suspend (String) -> FolderMapLevel,
    private val scope: CoroutineScope,
    private val saveOpen: suspend (Boolean) -> Unit,
    private val savePinned: suspend (Boolean) -> Unit,
) {
    val levels = mutableStateMapOf<String, FolderMapLevel>()
    private val expandedIds = mutableStateMapOf<String, Boolean>()

    /** 面板拖到的位置与大小，随网盘页存续。 */
    val panel = FloatingPanelState(PanelWidth)

    var query by mutableStateOf("")

    /** 过滤框显不显示，收在标题行的放大镜后面。 */
    var filterShown by mutableStateOf(false)
        private set

    var filterFocused by mutableStateOf(false)

    /** 方向键挪到的那一行，见 [FolderMapTree]。 */
    var highlighted by mutableStateOf<String?>(null)

    /** 高亮上一次跟到的位置（眼前所在的文件夹 ID）。 */
    var followedId by mutableStateOf<String?>(null)

    val scroll = ScrollState(0)

    var autoHide by mutableStateOf(FolderMapAutoHide(FolderMapPresence.Closed))
        private set

    val presence: FolderMapPresence get() = autoHide.presence

    /** 面板停在哪一侧，null 为停在中间、不自动收起。没排过版时按默认位置（右上角）算右侧。 */
    var dock by mutableStateOf<DockSide?>(DockSide.Right)
        private set

    /** 焦点在面板里、且是键盘带进来的。鼠标点了树里一行留下的焦点不算，否则点完移开也不收。 */
    var keyboardInside by mutableStateOf(false)

    /** 下一次树出现时把焦点给它：快捷键与键盘展开时用。 */
    var pendingTreeFocus by mutableStateOf(false)

    /** 正在拖标题行或改大小。按手势的开始与结束记，不按指针位置猜。 */
    var gesturing by mutableStateOf(false)

    /** 正在拖动或改大小、面板内有键盘焦点、过滤框非空或有焦点：不自动收起。 */
    val busy: Boolean get() = gesturing || keyboardInside || filterFocused || query.isNotEmpty()

    private val clock = TimeSource.Monotonic.markNow()

    fun now(): Long = clock.elapsedNow().inWholeMilliseconds

    /** 把手的上沿：与面板上沿对齐。面板还没出现过时取默认位置。 */
    val stripTop: Dp get() = panel.bounds?.top ?: panel.position?.y ?: PanelTopMargin

    fun zones(): FolderMapZones {
        val side = dock
        val area = panel.area
        val open = if (side != null && area != null) stripZone(side, stripTop.value, area.width.value) else null
        val card = panel.bounds?.let { MapZone(it.left.value, it.top.value, it.right.value, it.bottom.value) }
        val keep = listOfNotNull(card?.outset(FolderMapTiming.KeepMargin.value), open)
        return FolderMapZones(open, keep, docked = side != null)
    }

    fun dispatch(event: FolderMapEvent) {
        val before = autoHide
        autoHide = autoHide.step(event, zones(), now())
        val open = autoHide.presence != FolderMapPresence.Closed
        if (open != (before.presence != FolderMapPresence.Closed)) scope.launch { saveOpen(open) }
        if (autoHide.pinned != before.pinned) scope.launch { savePinned(autoHide.pinned) }
    }

    /** 启动或网盘页重建时按存下的偏好摆好。没钉住的从把手起，不会一启动就是临时展开的。 */
    fun restore(open: Boolean, pinned: Boolean) {
        val presence = when {
            !open -> FolderMapPresence.Closed
            pinned -> FolderMapPresence.Held
            else -> FolderMapPresence.Collapsed
        }
        autoHide = FolderMapAutoHide(presence, pinned = pinned)
    }

    /** 快捷键与命令面板，见 [FolderMapAutoHide]。展开时焦点进树。 */
    fun toggle() {
        val wasExpanded = expanded
        dispatch(FolderMapEvent.Toggle)
        if (!wasExpanded && expanded) pendingTreeFocus = true
    }

    /** 点击或轻点把手，[keyboard] 为 Tab 到把手后按回车、空格。 */
    fun activateStrip(keyboard: Boolean) {
        dispatch(FolderMapEvent.StripActivated)
        if (keyboard) pendingTreeFocus = true
    }

    /** 面板看得见：临时展开或常驻。 */
    val expanded: Boolean get() = presence == FolderMapPresence.Peeking || presence == FolderMapPresence.Held

    val toggleLabel: String
        get() = when {
            presence == FolderMapPresence.Closed -> "打开目录图"
            presence == FolderMapPresence.Collapsed -> "展开目录图"
            presence == FolderMapPresence.Held -> "关闭目录图"
            else -> "收起目录图"
        }

    fun toggleFilter() {
        filterShown = !filterShown
        if (!filterShown) query = ""
    }

    /**
     * 判定停靠：拖动或改大小松手时、列表这一块的大小变了时各判一次，拖动途中不判、不吸附。
     * 停在左沿的面板改为跟着左沿走，窗口拉宽时才不会离开左沿。
     */
    fun judgeDock() {
        val area = panel.area ?: return
        val bounds = panel.bounds ?: return
        val side = dockSideOf(MapZone(bounds.left.value, bounds.top.value, bounds.right.value, bounds.bottom.value), area.width.value)
        if (side != null) panel.followEdge(end = side == DockSide.Right)
        dock = side
        dispatch(FolderMapEvent.Relayout)
    }

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

    /**
     * 网盘里有改动（仓库的 folderChanges）：列过的层里受影响的重列。只看眼前位置变没变不够：
     * 在列表里新建、改名、移走一个子文件夹，眼前的位置不变，树却已不对。压缩包里的层是只读的，不重列。
     */
    fun onChange(change: DriveChange) {
        levels.keys.filter { isDriveFolderId(it) && change.affects(it) }.forEach(::refresh)
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
 * 树本身。顶上一行过滤（[FolderMapState.filterShown] 时），打字即就地过滤已列过的各层；
 * ↑↓ 在行间走，→ 展开或进到第一个子项，← 收起或回到上级，回车跳过去或打开文件，Esc 交给 [onDismiss]。
 */
@Composable
private fun FolderMapTree(
    state: FolderMapState,
    root: List<PikoPathBreadcrumb>,
    current: List<PikoPathBreadcrumb>,
    onOpen: (List<PikoPathBreadcrumb>) -> Unit,
    onOpenFile: (List<PikoPathBreadcrumb>, FileStat) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val query = state.query
    val filterShown = state.filterShown
    val rows = if (query.isBlank()) {
        buildList { flatten(state, root, 0, onOpen, this) }
    } else {
        filtered(state, root, query.trim())
    }
    val selectable = rows.indices.filter { rows[it].node != null || rows[it].onNoteClick != null }
    val currentId = current.lastOrNull()?.id
    val highlighted = state.highlighted
    // 高亮跟着眼前所在的那一级走，不只在召出时定一次：否则点了主页、在列表里换了文件夹，高亮还停在原先那一块。
    // 根目录在树里没有那一行，回到根就不高亮；那一级还没展开出来时等列出来再定。定过一次之后只认人用方向键挪的，
    // 展开、收起改了行数也不把高亮拽回来
    LaunchedEffect(currentId, rows.size, query) {
        if (state.followedId != currentId) {
            val here = rows.firstOrNull { it.node?.crumb?.id == currentId }
            when {
                here != null -> {
                    state.highlighted = here.key
                    state.followedId = currentId
                }
                currentId == null || currentId == root.last().id -> {
                    state.highlighted = null
                    state.followedId = currentId
                }
            }
        }
        if (state.highlighted != null && rows.none { it.key == state.highlighted }) state.highlighted = null
    }
    val treeFocus = remember { FocusRequester() }
    LaunchedEffect(state.pendingTreeFocus) {
        if (state.pendingTreeFocus) {
            runCatching { treeFocus.requestFocus() }
            state.pendingTreeFocus = false
        }
    }

    fun move(step: Int) {
        if (selectable.isEmpty()) return
        val at = selectable.indexOfFirst { rows[it].key == state.highlighted }
        val next = if (at < 0) 0 else (at + step).coerceIn(0, selectable.lastIndex)
        state.highlighted = rows[selectable[next]].key
    }

    fun activate(row: MapRow) = row.activate(onOpen, onOpenFile)

    Column(
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent event.key in TreeKeys
            val row = rows.firstOrNull { it.key == state.highlighted }
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
                        rows.firstOrNull { it.node?.crumb?.id == parentId }?.let { state.highlighted = it.key }
                    }
                }
                Key.Enter, Key.NumPadEnter -> row?.let(::activate)
                Key.Escape -> onDismiss()
                else -> return@onPreviewKeyEvent false
            }
            true
        }
            // 树本身可聚焦，快捷键与键盘展开时焦点落在这里，方向键由上面接住；排在按键处理之后，按键才经过它
            .focusRequester(treeFocus)
            .focusable(),
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
                    onValueChange = { state.query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { state.filterFocused = it.isFocused },
                )
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(state.scroll).padding(bottom = 8.dp)) {
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
                            state.highlighted = row.key
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
 * 宽窗口网盘页的目录图：一块浮在列表上的面板，没钉住、停靠在列表左右边沿时不用就收成贴边的把手。
 * 导航栏搜索旁的树形按钮打开（只在关着时出现）；钉住与否只由标题行的钉住按钮改，× 关掉再开照旧。
 * 指针在把手上停住片刻、点把手、快捷键都是临时展开，离开即收。Esc 收起，× 关掉（导航栏上的按钮重新出现）。
 * 时机与各入口的规则见 [FolderMapAutoHide] 与 [FolderMapTiming]。拖到列表中间的面板不自动收起。
 * 拖动、改大小与层叠见 [FloatingPanel]，[avoid] 等参数照传。由调用方铺满列表这一块，并在列表这一块上挂 [folderMapPointer]。
 * 树的根总是网盘根目录，进来与换了位置时都展开到眼前的文件夹。
 * 树里没有根目录那一行（从根的子项列起），回根目录靠标题行的 [onHome]，与导航栏的主页按钮同一个入口；已在根目录时为 null，不显示。
 *
 * 把手与面板是两个节点：把手只在收起时有，面板在自己的位置上淡入，不从把手形变出来。上一版细轨与树是同一个容器在形变，
 * 碰到的东西当场变形、拖的与停下的不是同一个东西。
 */
@Composable
internal fun FolderMapPanel(
    state: FolderMapState,
    current: List<PikoPathBreadcrumb>,
    onOpen: (List<PikoPathBreadcrumb>) -> Unit,
    onOpenFile: (List<PikoPathBreadcrumb>, FileStat) -> Unit,
    onHome: (() -> Unit)?,
    modifier: Modifier = Modifier,
    avoid: () -> DpRect? = { null },
    onActivate: () -> Unit = {},
) {
    val root = remember { listOf(PikoDriveRepository.ROOT_BREADCRUMB) }
    // 换了位置时展开到眼前的文件夹；网盘里的改动另由 FolderMapState.onChange 重列受影响的层
    LaunchedEffect(current.lastOrNull()?.id) { state.reveal(root, current) }
    val presence = state.presence
    val expanded = presence == FolderMapPresence.Peeking || presence == FolderMapPresence.Held
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val motion = LocalPikoMotion.current

    // 到点叫醒状态机；时长不随减少动画归零，delay 本来就不受它影响
    LaunchedEffect(state) {
        snapshotFlow { state.autoHide.wakeAt }.collectLatest { at ->
            if (at == null) return@collectLatest
            // 计时器早到一两毫秒时状态机不认这次叫醒，wakeAt 又没变、不会再叫，所以等到确实过点再发
            while (state.now() < at) delay(at - state.now())
            state.dispatch(FolderMapEvent.Tick)
        }
    }
    LaunchedEffect(state) { snapshotFlow { state.busy }.collect { state.dispatch(FolderMapEvent.BusyChanged(it)) } }
    // 列表这一块的大小变了（拉窗口）重判一次停靠。等一帧，面板先按新的大小夹好位置
    LaunchedEffect(state.panel.area) {
        withFrameNanos {}
        state.judgeDock()
    }
    // 面板第一次排好版时判一次：默认位置为了让开属性卡片可能不在边沿
    val placed = state.panel.bounds != null
    LaunchedEffect(placed) { if (placed) state.judgeDock() }

    // 焦点是不是键盘带进面板的：按下在面板上之后来的焦点算鼠标的
    var hasFocus by remember { mutableStateOf(false) }
    var pointerFocus by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        // 收起时焦点若在面板里，先还给列表（网盘页的焦点落点接住），不跟着面板一起消失
        if (!expanded && hasFocus) focusManager.clearFocus()
    }

    Box(modifier.fillMaxSize()) {
        FloatingPanel(
            state = state.panel,
            title = "目录图",
            closeLabel = "关闭目录图",
            onClose = { state.dispatch(FolderMapEvent.Close) },
            minWidth = PanelMinWidth,
            maxWidth = PanelMaxWidth,
            autoMaxHeight = FolderMapMaxHeight,
            modifier = Modifier
                .matchParentSize()
                .onFocusChanged { focus ->
                    hasFocus = focus.hasFocus
                    if (!focus.hasFocus) pointerFocus = false
                    state.keyboardInside = focus.hasFocus && !pointerFocus
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && hasFocus) {
                        pointerFocus = false
                        state.keyboardInside = true
                    }
                    false
                },
            avoid = avoid,
            onActivate = {
                pointerFocus = true
                state.keyboardInside = false
                onActivate()
            },
            onSettled = state::judgeDock,
            onGesture = { state.gesturing = it },
            visible = expanded,
            enter = motion.peekEnter(fromEnd = state.dock != DockSide.Left, offsetPx = with(density) { PeekOffset.roundToPx() }),
            exit = motion.peekExit(),
            headerActions = {
                if (onHome != null) PanelHeaderButton(Icons.Outlined.Home, "网盘根目录", onHome)
                PanelHeaderToggle(Icons.Outlined.Search, Icons.Filled.Search, "过滤", checked = state.filterShown, onClick = state::toggleFilter)
                // 停在中间时也给：钉住是记下来的偏好，拖回边沿或关掉再开时生效
                val pinned = state.autoHide.pinned
                PanelHeaderToggle(Icons.Outlined.PushPin, Icons.Filled.PushPin, if (pinned) "取消钉住" else "钉住", checked = pinned) {
                    state.dispatch(FolderMapEvent.PinToggled)
                }
            },
        ) {
            FolderMapTree(
                state = state,
                root = root,
                current = current,
                onOpen = onOpen,
                onOpenFile = onOpenFile,
                onDismiss = {
                    state.dispatch(FolderMapEvent.Escape)
                    focusManager.clearFocus()
                },
                modifier = Modifier.weight(1f, fill = state.panel.height != null),
            )
        }
        val side = state.dock
        if (presence == FolderMapPresence.Collapsed && side != null) FolderMapStrip(state, side)
        if (LocalPikoPlatform.current.debugFolderMapZones) FolderMapDebugOverlay(state)
    }
}

/**
 * 收起时贴在列表边沿的把手：与面板同一种容器色、带一点阴影，中间一个目录树图标（与导航栏的树形按钮同一个），
 * 贴边一侧直角、靠内一侧圆角。整条把手就是一个入口，只放这一个图标，不放多个控件（上一版在细轨上摆按钮，难看又难点）。
 * 原先只画一道 4dp 的半透明竖条，手测几乎看不见。命中区就是把手本身，触屏时向内加宽。
 * 可以 Tab 到，回车或空格展开并把焦点给树。悬停展开不在这里判，由列表这一块上的 [folderMapPointer] 交给状态机。
 */
@Composable
private fun BoxScope.FolderMapStrip(state: FolderMapState, side: DockSide) {
    val colors = MaterialTheme.colorScheme
    val width = if (LocalPointerSource.current.isTouchLike) FolderMapTiming.TouchOpenZoneWidth else FolderMapTiming.OpenZoneWidth
    var focused by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val end = side == DockSide.Right
    val shape = if (end) {
        RoundedCornerShape(topStart = HandleCorner, bottomStart = HandleCorner)
    } else {
        RoundedCornerShape(topEnd = HandleCorner, bottomEnd = HandleCorner)
    }
    // 悬停时叠一层 M3 的悬停状态层（onSurface 8%），容器色深一档
    val container = if (hovered) colors.onSurface.copy(alpha = 0.08f).compositeOver(colors.surfaceContainerHighest) else colors.surfaceContainerHighest
    Surface(
        shape = shape,
        color = container,
        shadowElevation = 2.dp,
        border = if (focused) BorderStroke(2.dp, colors.primary) else null,
        modifier = Modifier
            .align(if (end) Alignment.TopEnd else Alignment.TopStart)
            .padding(top = state.stripTop, end = if (end) FolderMapTiming.ScrollbarClearance else 0.dp)
            .size(width, FolderMapTiming.StripHeight)
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics {
                contentDescription = "展开目录图"
                role = Role.Button
                onClick {
                    state.activateStrip(keyboard = true)
                    true
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val activates = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar
                if (activates && event.type == KeyEventType.KeyDown) state.activateStrip(keyboard = true)
                activates
            }
            .focusable()
            .pointerInput(state) { detectTapGestures { state.activateStrip(keyboard = false) } },
    ) {
        // 触屏加宽时图标仍留在贴边那一截里，与鼠标时看到的位置相同
        Box(Modifier.fillMaxSize(), contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.width(FolderMapTiming.OpenZoneWidth), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AccountTree, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * 挂在列表这一块上（目录图与列表共同的上层），把鼠标的位置交给目录图的状态机。挂在上层而不是目录图自己身上：
 * 目录图铺满列表这一块，自己接指针就把列表整个盖住了；上层在 Initial 阶段只看不吃，列表照常收到。
 * 关着时什么也不做；常驻时也转交，取消钉住那一刻要知道指针在不在面板附近。
 */
internal fun Modifier.folderMapPointer(state: FolderMapState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (state.presence == FolderMapPresence.Closed) continue
            val change = event.changes.firstOrNull() ?: continue
            when (event.type) {
                PointerEventType.Exit -> state.dispatch(FolderMapEvent.PointerLeft)
                PointerEventType.Move, PointerEventType.Enter, PointerEventType.Press, PointerEventType.Release -> state.dispatch(
                    FolderMapEvent.PointerMoved(
                        x = change.position.x.toDp().value,
                        y = change.position.y.toDp().value,
                        mouse = change.type == PointerType.Mouse,
                        pressed = event.buttons.areAnyPressed,
                    ),
                )
                else -> Unit
            }
        }
    }
}

/**
 * 开发构建的调试描边（[PikoPlatform.debugFolderMapZones]）：保持区蓝、打开区橙，半透明；左下角是状态、停靠与两个倒计时。
 * 不接指针，不挡列表。
 */
@Composable
private fun BoxScope.FolderMapDebugOverlay(state: FolderMapState) {
    var now by remember { mutableLongStateOf(state.now()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { now = state.now() } }
    val zones = state.zones()
    val machine = state.autoHide
    Canvas(Modifier.matchParentSize()) {
        fun MapZone.draw(color: Color) {
            val topLeft = Offset(left.dp.toPx(), top.dp.toPx())
            val size = Size((right - left).dp.toPx(), (bottom - top).dp.toPx())
            drawRect(color.copy(alpha = 0.12f), topLeft, size)
            drawRect(color.copy(alpha = 0.7f), topLeft, size, style = Stroke(1.dp.toPx()))
        }
        if (machine.presence == FolderMapPresence.Peeking) zones.keep.forEach { it.draw(Color(0xFF2979FF)) }
        zones.open?.draw(Color(0xFFFF6D00))
    }
    fun remaining(at: Long?) = at?.let { "${(it - now).coerceAtLeast(0)}ms" } ?: "-"
    Text(
        "${machine.presence}  钉住 ${machine.pinned}  停靠 ${state.dock ?: "无"}  武装 ${machine.armed}  " +
            "进过 ${machine.entered}  忙 ${machine.busy}\n" +
            "展开倒计时 ${remaining(machine.openAt)}  收起倒计时 ${remaining(machine.collapseAt)}",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// 这几个键的抬起也吃掉：按下已由树处理，抬起再落到网盘页上，方向键会挪列表焦点、Esc 会触发返回
private val TreeKeys = setOf(Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight, Key.Escape, Key.Enter)

private val TreeIndent = 16.dp

// 每层列出的文件数，多出的收成一行「还有 N 个文件」。三个够看出是什么，又不把上下的文件夹挤走
private const val FilesShown = 3
private val FolderMapMaxHeight = 560.dp

// 展开时从停靠那一侧向内移的距离：只为看出它是从边上出来的，大了像整块飞进来
private val PeekOffset = 8.dp

private val HandleCorner = 10.dp

private val PanelWidth = 360.dp
private val PanelMinWidth = 220.dp
private val PanelMaxWidth = 720.dp
