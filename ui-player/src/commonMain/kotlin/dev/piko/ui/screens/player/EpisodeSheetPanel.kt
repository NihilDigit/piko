package dev.piko.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.shared.media.player.preferredVersion
import dev.piko.ui.components.FileArtwork
import dev.piko.ui.components.FileArtworkFormat
import dev.piko.ui.components.FileVisual
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular

/**
 * 选集。标签都短（剥掉公共前后缀后只剩集数）时排成缩略图网格，一屏能看到大半季；
 * 有长标签时退回带缩略图的列表，否则网格格子里只能放下半截名字。
 *
 * 打开时直接定位到当前这集：几十集的目录很常见，从头翻是每次都要付的代价。
 * 缩略图跟随网盘的防窥开关：开着时一律模糊，这里没有逐项揭示的入口。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EpisodePanel(
    entries: List<PlaylistEntry>,
    currentFileId: String,
    hideThumbnails: Boolean,
    onSelect: (PlaylistEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 分区按播放列表里出现的先后排列：正片在前，其后是 SP、外传、剧场版、PV 等
    val sections = remember(entries) { entries.map { it.sectionKey to it.sectionLabel }.distinct() }
    val currentSection = entries.find { it.fileId == currentFileId }?.sectionKey ?: sections.firstOrNull()?.first
    var selected by remember(currentSection) { mutableStateOf(currentSection) }
    val shown = remember(entries, selected) { entries.filter { it.sectionKey == selected } }
    val isEInk = LocalAppearance.current.isEInk

    Column(modifier) {
        if (sections.size > 1) {
            val sectionScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalWheelScrollsRow(sectionScroll)
                    .horizontalScroll(sectionScroll)
                    .selectableGroup()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                sections.forEachIndexed { index, (sectionKey, label) ->
                    val active = sectionKey == selected
                    OutlinedToggleButton(
                        checked = active,
                        onCheckedChange = { if (it) selected = sectionKey },
                        shapes = connectedToggleShapes(index, sections.size),
                        border = if (isEInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .semantics { role = Role.RadioButton }
                            .handCursor(),
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
        // 换分区时列表重建，才能按新分区里当前这集的位置重新定位
        key(selected) {
            EpisodeEntries(shown, currentFileId, hideThumbnails, onSelect, Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EpisodeEntries(
    entries: List<PlaylistEntry>,
    currentFileId: String,
    hideThumbnails: Boolean,
    onSelect: (PlaylistEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(entries) { entries.groupBy { it.groupKey }.values.toList() }
    // 版本得缩进在所属那集下面，网格排不出层级，有版本就用列表
    val hasVersions = groups.any { it.size > 1 }
    val currentIndex = entries.indexOfFirst { it.fileId == currentFileId }.coerceAtLeast(0)
    val useGrid = !hasVersions && entries.all { it.label.length <= GRID_LABEL_MAX_LENGTH }

    if (hasVersions) {
        EpisodeVersionList(groups, currentFileId, hideThumbnails, onSelect, modifier)
    } else if (useGrid) {
        val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = currentIndex)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = EPISODE_CARD_MIN_WIDTH),
            state = gridState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = modifier.fillMaxWidth(),
        ) {
            itemsIndexed(entries, key = { _, item -> item.fileId }) { _, entry ->
                EpisodeCard(
                    entry = entry,
                    isCurrent = entry.fileId == currentFileId,
                    hideThumbnail = hideThumbnails,
                    onClick = { onSelect(entry) },
                )
            }
        }
    } else {
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
            modifier = modifier.fillMaxWidth(),
        ) {
            itemsIndexed(entries, key = { _, item -> item.fileId }) { index, entry ->
                EpisodeRow(
                    entry = entry,
                    isCurrent = entry.fileId == currentFileId,
                    hideThumbnail = hideThumbnails,
                    index = index,
                    count = entries.size,
                    onClick = { onSelect(entry) },
                )
            }
        }
    }
}

/** 选集列表里的一行：一集，或缩进在它下面的一个版本。 */
private sealed interface EpisodeListRow {
    val key: String

    class Episode(val group: List<PlaylistEntry>, val index: Int, val count: Int) : EpisodeListRow {
        override val key: String get() = "g:" + group.first().groupKey
    }

    class Version(val entry: PlaylistEntry) : EpisodeListRow {
        override val key: String get() = "v:" + entry.fileId
    }
}

/**
 * 有版本的选集：每集一行，其下缩进列出各个版本（「SDR」「HDR10」「DoVi」）。点集那一行按版本偏好挑
 * （沿用正在放的版本，没有就放体积最大的），点版本行就放那个版本。整个分区只有一集时直接列版本，
 * 顶上那一行只是把同一个名字再写一遍。
 */
@Composable
private fun EpisodeVersionList(
    groups: List<List<PlaylistEntry>>,
    currentFileId: String,
    hideThumbnails: Boolean,
    onSelect: (PlaylistEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentVersion = groups.flatten().find { it.fileId == currentFileId }?.versionLabel
    val rows = remember(groups) {
        if (groups.size == 1) {
            groups.single().map { EpisodeListRow.Version(it) }
        } else {
            groups.flatMapIndexed { index, group ->
                listOf(EpisodeListRow.Episode(group, index, groups.size)) +
                    if (group.size > 1) group.map { EpisodeListRow.Version(it) } else emptyList()
            }
        }
    }
    val rowKeys = remember(rows) {
        val occurrences = mutableMapOf<String, Int>()
        rows.map { row ->
            val occurrence = occurrences[row.key] ?: 0
            occurrences[row.key] = occurrence + 1
            // 所有键都带次序，原始 ID 自带 # 后缀时也不会与生成键碰撞。
            "${row.key}#$occurrence"
        }
    }
    val currentRow = rows.indexOfFirst { row ->
        when (row) {
            is EpisodeListRow.Episode -> row.group.any { it.fileId == currentFileId }
            is EpisodeListRow.Version -> row.entry.fileId == currentFileId
        }
    }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentRow)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(rows.size, key = { rowKeys[it] }) { index ->
            when (val row = rows[index]) {
                is EpisodeListRow.Episode -> {
                    val shown = preferredVersion(row.group, currentVersion)
                    EpisodeRow(
                        entry = shown,
                        isCurrent = row.group.any { it.fileId == currentFileId },
                        hideThumbnail = hideThumbnails,
                        index = row.index,
                        count = row.count,
                        onClick = { onSelect(shown) },
                    )
                }
                is EpisodeListRow.Version -> VersionRow(
                    entry = row.entry,
                    isCurrent = row.entry.fileId == currentFileId,
                    indented = groups.size > 1,
                    onClick = { onSelect(row.entry) },
                )
            }
        }
    }
}

/** 一集下面的一个版本：缩进、比集那一行矮，只写能区分它的那段标签。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VersionRow(entry: PlaylistEntry, isCurrent: Boolean, indented: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isCurrent) colors.primaryContainer else colors.surfaceContainerLow,
        contentColor = if (isCurrent) colors.onPrimaryContainer else colors.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indented) VERSION_INDENT else 0.dp)
            .heightIn(min = 44.dp)
            .handCursor()
            .semantics {
                contentDescription = entry.name
                selected = isCurrent
            },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.versionLabel.ifEmpty { entry.label },
                style = if (isCurrent) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isCurrent) {
                Icon(Icons.Filled.GraphicEq, contentDescription = tr("正在播放", "Now playing", "再生中", "재생 중", "正在播放"), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * 网格里的一集：16:9 缩略图，集数压在左下角。当前这集用 primary 描边并在右上角标出，
 * 缩略图本身不变色，免得看不清画面。
 */
@Composable
private fun EpisodeCard(
    entry: PlaylistEntry,
    isCurrent: Boolean,
    hideThumbnail: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(EPISODE_CARD_CORNER),
        color = colors.surfaceContainerHighest,
        border = if (isCurrent) BorderStroke(3.dp, colors.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .handCursor()
            .semantics {
                contentDescription = entry.name
                selected = isCurrent
            },
    ) {
        Box {
            EpisodeThumbnail(entry, hideThumbnail, Modifier.fillMaxSize())
            Surface(
                shape = RoundedCornerShape(topEnd = 8.dp),
                color = if (isCurrent) colors.primary else colors.surfaceContainerHighest.copy(alpha = LABEL_CONTAINER_ALPHA),
                contentColor = if (isCurrent) colors.onPrimary else colors.onSurface,
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelLarge.tabular,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = tr("正在播放", "Now playing", "再生中", "재생 중", "正在播放"),
                    tint = colors.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(colors.primary, CircleShape)
                        .padding(4.dp)
                        .size(16.dp),
                )
            }
        }
    }
}

/** 选集封面缩略图：委托单权威 [FileArtwork]，统一遵循缩略图缩放、清晰度与无封面类型占位。 */
@Composable
private fun EpisodeThumbnail(entry: PlaylistEntry, hide: Boolean, modifier: Modifier) {
    val visual = remember(entry.fileId, entry.name, entry.thumbnailUrl) {
        FileVisual(
            name = entry.name,
            thumbnail = entry.thumbnailUrl.ifBlank { null },
            stableId = entry.fileId,
        )
    }
    FileArtwork(
        file = visual,
        format = FileArtworkFormat.Poster,
        isSpoilerBlurred = hide,
        showVideoPlayMark = false,
        modifier = modifier,
    )
}

/**
 * 分段列表的一行：相邻行之间 2dp 间隙，外侧圆角 16dp、内侧 4dp，选中行四角都是 16dp。
 * 主文字是短标签，全名作辅助行，二者相同时只显示一行。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EpisodeRow(
    entry: PlaylistEntry,
    isCurrent: Boolean,
    hideThumbnail: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val outer = SEGMENT_OUTER_CORNER
    val inner = SEGMENT_INNER_CORNER
    val shape = if (isCurrent) {
        RoundedCornerShape(outer)
    } else {
        RoundedCornerShape(
            topStart = if (index == 0) outer else inner,
            topEnd = if (index == 0) outer else inner,
            bottomStart = if (index == count - 1) outer else inner,
            bottomEnd = if (index == count - 1) outer else inner,
        )
    }
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .handCursor()
            .semantics { selected = isCurrent },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EpisodeThumbnail(
                entry = entry,
                hide = hideThumbnail,
                modifier = Modifier
                    .width(EPISODE_ROW_THUMB_WIDTH)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    style = if (isCurrent) {
                        MaterialTheme.typography.titleMediumEmphasized
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                if (entry.label != entry.name.substringBeforeLast('.')) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = tr("正在播放", "Now playing", "再生中", "재생 중", "正在播放"),
                    modifier = Modifier.padding(start = 12.dp).size(20.dp),
                )
            }
        }
    }
}

private const val GRID_LABEL_MAX_LENGTH = 6
private val EPISODE_CARD_MIN_WIDTH = 140.dp
private val EPISODE_CARD_CORNER = 12.dp
private val EPISODE_ROW_THUMB_WIDTH = 96.dp
private const val LABEL_CONTAINER_ALPHA = 0.85f

// 版本行缩进，看得出它们属于上面那一集
private val VERSION_INDENT = 24.dp
