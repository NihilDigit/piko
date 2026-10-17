package dev.piko.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class SourceNode(val id: String, val original: String, val clean: String = original, val index: Int? = null, val children: List<SourceNode> = emptyList(), val excluded: Boolean = false) {
    val fileIds: Set<Int> get() = index?.let(::setOf) ?: children.flatMap { it.fileIds }.toSet()
}
private val sourceTree = listOf(
    SourceNode("season", "Season 01【四季篇】", "四季篇", children = listOf(
        SourceNode("v0", "[山间的一年] S01E01 春・山谷の朝 [2160p].mkv", "春 · 山谷的第一束光", 0),
        SourceNode("v1", "[山间的一年] S01E02 夏・林間の雨 [2160p].mkv", "夏 · 林间的雨", 1),
        SourceNode("v2", "[山间的一年] S01E03 秋・渓流を辿る [2160p].mkv", "秋 · 沿着溪流", 2),
        SourceNode("v3", "[山间的一年] S01E04 冬・雪の小屋 [2160p].mkv", "冬 · 风雪中的小屋", 3),
        SourceNode("subtitles", "字幕／Subtitles", "字幕", children = listOf(
            SourceNode("s0", "S01E01.zh-Hans.ass", index = 6),
            SourceNode("s1", "S01E01.ja.ass", index = 7),
        )),
    )),
    SourceNode("extras", "Extras & 特典", "特典", children = listOf(
        SourceNode("v4", "特典01_早朝の鳥たち.mp4", "清晨的鸟鸣", 4),
        SourceNode("v5", "特典02_ふたたび山へ.mp4", "回到山里", 5),
        SourceNode("ad0", "更多资源请访问.url", index = 8, excluded = true),
        SourceNode("ad1", "扫码加入交流群.jpg", index = 9, excluded = true),
    )),
)
private data class VisibleTreeRow(val node: SourceNode, val depth: Int)
private fun treeRows(state: AddLinkPrototypeState, source: Boolean): List<VisibleTreeRow> = buildList {
    fun matches(node: SourceNode): Boolean = if (node.index != null) {
        if (source) (!state.excludedOnly || node.index !in state.selected) && (state.query.isBlank() || node.original.contains(state.query, true))
        else node.index in state.selected
    } else node.children.any(::matches)
    fun visit(nodes: List<SourceNode>, depth: Int) {
        nodes.filter(::matches).forEach { node ->
            add(VisibleTreeRow(node, depth))
            if (node.index == null && (!source || state.expanded[node.id] == true || state.query.isNotBlank() || state.excludedOnly)) visit(node.children, depth + 1)
        }
    }
    visit(sourceTree, 0)
}

@Composable internal fun TreeImportPrototype(state: AddLinkPrototypeState, wide: Boolean) {
    if (wide) Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        ResultTree(state, Modifier.weight(1f))
        SourcePanel(state, Modifier.width(440.dp).fillMaxHeight(), mobile = false)
    } else BoxWithConstraints(Modifier.fillMaxSize()) {
        ResultTree(state, Modifier.fillMaxSize().padding(horizontal = 16.dp))
        val height = when (state.sheet) {
            ImportSheetLevel.Collapsed -> 88.dp
            ImportSheetLevel.Half -> (maxHeight * .55f).coerceAtLeast(300.dp)
            ImportSheetLevel.Expanded -> maxHeight - 8.dp
        }
        SourcePanel(state, Modifier.fillMaxWidth().height(height).align(Alignment.BottomCenter), mobile = true)
    }
}

@Composable private fun ResultTree(state: AddLinkPrototypeState, modifier: Modifier) {
    Column(modifier.fillMaxHeight()) {
        Row(Modifier.height(56.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("保存结果", style = MaterialTheme.typography.titleMedium)
                Text("旅行影像 / 山间的一年", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${state.selected.size} 个文件", style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestionChip(onClick = {}, label = { Text("自动整理") }, icon = { Icon(Icons.Outlined.AutoFixHigh, null, Modifier.size(16.dp)) })
            if (state.referenceOnly) SuggestionChip(onClick = {}, label = { Text("引用归档") }, icon = { Icon(Icons.Outlined.Inventory2, null, Modifier.size(16.dp)) })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp)) {
            items(treeRows(state, source = false), key = { it.node.id }) { row -> PrototypeTreeRow(row, state, source = false) }
            if (state.selected.isEmpty()) item { Text("在来源文件树中选择要保存的文件", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        state.notice?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (state.mediaPreview) Surface(Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PlayCircleOutline, null)
                Text("预览：" + sourceTree.flatMap { it.children }.firstOrNull { it.index == state.previewIndex }?.clean.orEmpty(), Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TreeButton(Icons.Outlined.Close, "关闭预览") { state.mediaPreview = false }
            }
        }
    }
}

@Composable private fun SourcePanel(state: AddLinkPrototypeState, modifier: Modifier, mobile: Boolean) {
    val collapsed = mobile && state.sheet == ImportSheetLevel.Collapsed
    val full = !mobile || state.sheet == ImportSheetLevel.Expanded
    Surface(modifier, shape = if (mobile) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) else RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = if (mobile) 5.dp else 0.dp) {
        Column {
            if (mobile) Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) { Box(Modifier.width(32.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant)) }
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("来源文件", style = MaterialTheme.typography.titleMedium)
                    Text(if (collapsed) "已选 ${state.selected.size} 项 · 旅行影像" else "山间的一年 · 10 个文件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (mobile) {
                    if (collapsed) TreeButton(Icons.Outlined.KeyboardArrowUp, "展开导入管理") { state.sheet = ImportSheetLevel.Half }
                    else {
                        if (!full) TreeButton(Icons.Outlined.OpenInFull, "完全展开导入管理") { state.sheet = ImportSheetLevel.Expanded }
                        TreeButton(Icons.Outlined.KeyboardArrowDown, "收起导入管理") { state.sheet = ImportSheetLevel.Collapsed }
                    }
                } else TreeButton(Icons.Outlined.MoreHoriz, "链接选项") {}
            }
            if (!collapsed) {
                if (full) {
                    OutlinedTextField(state.query, { state.query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, placeholder = { Text("搜索原始文件名") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(14.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(!state.excludedOnly, { state.excludedOnly = false }, label = { Text("全部 10") })
                        FilterChip(state.excludedOnly, { state.excludedOnly = true }, label = { Text("未选 ${10 - state.selected.size}") })
                        Spacer(Modifier.weight(1f))
                        Text("广告 2 项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    items(treeRows(state, source = true), key = { it.node.id }) { row -> PrototypeTreeRow(row, state, source = true) }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (full) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!state.referenceOnly, { state.referenceOnly = false }, label = { Text("网盘文件") })
                            FilterChip(state.referenceOnly, { state.referenceOnly = true }, label = { Text("以引用保存") })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
                            Text("旅行影像", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = {}) { Text("更改") }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("已选 ${state.selected.size} 项", style = MaterialTheme.typography.labelLarge)
                                Text(if (state.referenceOnly) "不消耗秒传额度" else "按整理后的结构保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { state.notice = "演示：已提交 ${state.selected.size} 个文件" }, enabled = state.selected.isNotEmpty(), modifier = Modifier.height(48.dp)) { Text(if (state.referenceOnly) "保存引用" else "保存到网盘") }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PrototypeTreeRow(row: VisibleTreeRow, state: AddLinkPrototypeState, source: Boolean) {
    val node = row.node
    val folder = node.index == null
    val count = node.fileIds.count { it in state.selected }
    val check = when (count) { 0 -> ToggleableState.Off; node.fileIds.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = (row.depth * 12).dp)
        .background(if (!source && !folder && state.mediaPreview && node.index == state.previewIndex) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 24.dp, height = 48.dp).then(if (folder && source) Modifier.clickable { state.expanded[node.id] = state.expanded[node.id] != true } else Modifier), contentAlignment = Alignment.Center) {
            if (folder) Icon(if (!source || state.expanded[node.id] == true) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight, "展开或收起${node.original}", Modifier.size(18.dp))
            else Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        if (source) TriStateCheckbox(check, {
            state.selected = if (count == node.fileIds.size) state.selected - node.fileIds else state.selected + node.fileIds
        }, modifier = Modifier.size(40.dp).semantics { contentDescription = "选择${node.original}" })
        Icon(if (folder) Icons.Outlined.Folder else if (state.referenceOnly && !source) Icons.Outlined.Inventory2 else if ((node.index ?: 0) < 6) Icons.Outlined.Movie else Icons.Outlined.Description, null, Modifier.size(18.dp), tint = if (node.excluded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp)) {
            val resultName = if (!folder && node.clean != node.original) node.clean + "." + node.original.substringAfterLast('.') else node.clean
            Text(if (source) node.original else resultName, style = MaterialTheme.typography.bodyMedium, maxLines = if (source && !folder) 2 else 1, overflow = TextOverflow.Ellipsis)
            if (folder && source) Text("$count / ${node.fileIds.size} 已选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (node.excluded && source) Text(if (count > 0) "手动保留" else "自动排除 · 广告", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (source && !folder) {
            if ((node.index ?: 99) < 6) TreeButton(Icons.Outlined.PlayCircleOutline, "预览${node.clean}") { state.previewIndex = requireNotNull(node.index); state.mediaPreview = true }
            TreeButton(Icons.Outlined.Download, "下载${node.original}") { state.notice = "演示：已加入下载 · ${node.original}" }
        }
    }
}

@Composable private fun TreeButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(40.dp)) { Icon(icon, label, Modifier.size(18.dp)) }
}
