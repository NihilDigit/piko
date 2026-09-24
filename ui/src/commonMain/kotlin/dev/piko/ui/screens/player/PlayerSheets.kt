package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.ORIGINAL_QUALITY
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.shared.media.player.preferredVersion
import dev.piko.shared.media.player.trackDisplayName
import dev.piko.ui.components.ListSpoilerBlur
import dev.piko.ui.components.SpoilerThumbnail
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class PlayerSheet { Episodes, Settings, Tracks }

/**
 * 播放器的面板容器：横屏是贴右侧的浮动 side sheet，竖屏是 bottom sheet。
 *
 * 横屏用 bottom sheet 会盖住大半个画面，而规范在宽窗口下本就建议换成 side sheet；
 * 右侧浮动面板让左侧画面继续可见，选集时还能看着当前这集。Material 3 的 Compose 库
 * 没有 side sheet 组件，这里按规格自己拼：离窗口边 16dp、最宽 400dp、圆角 16dp。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheetHost(
    sheet: PlayerSheet?,
    isLandscape: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(PlayerSheet) -> Unit,
) {
    // 退场动画期间 sheet 已是 null，内容仍要按上一个面板画完
    var lastSheet by remember { mutableStateOf(sheet) }
    if (sheet != null) lastSheet = sheet

    if (isLandscape) {
        PlayerSideSheet(visible = sheet != null, title = lastSheet?.title.orEmpty(), onDismiss = onDismiss) {
            lastSheet?.let { content(it) }
        }
    } else if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            // 选集固定占七成高：按内容定高时几十集会顶满全屏，几集时又只露一条。
            // 其余面板内容少，按内容定高
            val height = if (sheet == PlayerSheet.Episodes) Modifier.fillMaxHeight(EPISODE_SHEET_HEIGHT_FRACTION) else Modifier
            Column(height) {
                Text(
                    text = sheet.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                )
                content(sheet)
            }
        }
    }
}

private val PlayerSheet.title: String
    get() = when (this) {
        PlayerSheet.Episodes -> "选集"
        PlayerSheet.Settings -> "播放设置"
        PlayerSheet.Tracks -> "音轨与字幕"
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerSideSheet(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = SIDE_SHEET_SCRIM_ALPHA))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "关闭面板",
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
            exit = slideOutHorizontally(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                .padding(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(SIDE_SHEET_CORNER),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.width(SIDE_SHEET_WIDTH).fillMaxHeight(),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭")
                        }
                    }
                    content()
                }
            }
        }
    }
}

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

    Column(modifier) {
        if (sections.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { (key, label) ->
                    FilterChip(
                        selected = key == selected,
                        onClick = { selected = key },
                        label = { Text(label, maxLines = 1) },
                    )
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
        items(rows.size, key = { rows[it].key }) { index ->
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
                Icon(Icons.Filled.GraphicEq, contentDescription = "正在播放", modifier = Modifier.size(18.dp))
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
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = "正在播放",
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

/** 网盘没有生成缩略图时画占位，不留空白。 */
@Composable
private fun EpisodeThumbnail(entry: PlaylistEntry, hide: Boolean, modifier: Modifier) {
    if (entry.thumbnailUrl.isBlank()) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    } else {
        SpoilerThumbnail(model = entry.thumbnailUrl, isBlurred = hide, blur = ListSpoilerBlur, modifier = modifier)
    }
}

/**
 * 分段列表的一行：相邻行之间 2dp 间隙，外侧圆角 16dp、内侧 4dp，选中行四角都是 16dp。
 * 主文字是短标签，全名作辅助行，二者相同时只显示一行。
 */
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
                    contentDescription = "正在播放",
                    modifier = Modifier.padding(start = 12.dp).size(20.dp),
                )
            }
        }
    }
}

/**
 * 滑块负责预设之外的值，步进 0.05。不设 stops：0.5 到 3.5 按 0.05 分是 59 个停止点，
 * 规范明确不建议过密。底栏的倍速浮层与播放设置面板共用。
 */
@Composable
internal fun SpeedSlider(playbackSpeed: Float, onSpeedChange: (Float) -> Unit) {
    Slider(
        value = playbackSpeed,
        onValueChange = { raw -> onSpeedChange((raw / SPEED_SLIDER_STEP).roundToInt() * SPEED_SLIDER_STEP) },
        valueRange = MIN_SPEED..MAX_SPEED,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "播放倍速" },
    )
}

/**
 * 完整的播放设置：倍速、清晰度、画面比例。都是单选，用连接式按钮组；倍速另有铺满的滑块，
 * 当前值写在小节标题的右端，不占滑块的宽度。
 */
@Composable
internal fun PlayerSettingsPanel(
    playbackSpeed: Float?,
    onSpeedChange: (Float) -> Unit,
    qualityOptions: List<String>,
    currentQuality: String?,
    onQualityChange: (String) -> Unit,
    aspectRatio: PlayerAspectRatio?,
    onAspectRatioChange: (PlayerAspectRatio) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (playbackSpeed != null) {
            SettingsSection("倍速", trailing = formatSpeed(playbackSpeed)) {
                ConnectedChoiceRow(
                    options = PresetSpeeds,
                    isSelected = { abs(playbackSpeed - it) < SPEED_MATCH_TOLERANCE },
                    optionLabel = ::formatSpeedPreset,
                    onSelect = onSpeedChange,
                )
                Spacer(Modifier.height(8.dp))
                SpeedSlider(playbackSpeed, onSpeedChange)
            }
        }
        if (qualityOptions.isNotEmpty()) {
            SettingsSection("清晰度") {
                ChoiceGroup(
                    options = qualityOptions,
                    isSelected = { it == currentQuality },
                    optionLabel = { it.qualityLabel },
                    onSelect = onQualityChange,
                )
            }
        }
        // 后端不支持画面比例时整节隐藏，避免点了没反应
        if (aspectRatio != null) {
            SettingsSection("画面比例") {
                ConnectedChoiceRow(
                    options = PlayerAspectRatio.entries,
                    isSelected = { it == aspectRatio },
                    optionLabel = { it.label },
                    onSelect = onAspectRatioChange,
                )
            }
        }
    }
}

/**
 * 音轨与字幕。轨道名常带语言、字幕组与「外挂」，长短不一，按钮组放不下，所以一行一条、单选。
 * 音轨只有一条时整节不出现；字幕第一项是关闭。
 */
@Composable
internal fun TracksPanel(
    audioTracks: List<MediaTrack>,
    selectedAudioTrackId: String?,
    onSelectAudio: (MediaTrack) -> Unit,
    subtitleTracks: List<MediaTrack>,
    selectedSubtitleTrackId: String?,
    onSelectSubtitle: (MediaTrack?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (audioTracks.size > 1) {
            TrackSection("音轨") {
                audioTracks.forEachIndexed { index, track ->
                    TrackRow(
                        label = trackDisplayName(track, index),
                        selected = track.id == selectedAudioTrackId,
                        onClick = { onSelectAudio(track) },
                    )
                }
            }
        }
        TrackSection("字幕") {
            TrackRow(label = "关闭", selected = selectedSubtitleTrackId == null, onClick = { onSelectSubtitle(null) })
            subtitleTracks.forEachIndexed { index, track ->
                TrackRow(
                    label = trackDisplayName(track, index),
                    selected = track.id == selectedSubtitleTrackId,
                    onClick = { onSelectSubtitle(track) },
                )
            }
        }
    }
}

@Composable
private fun TrackSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
        )
        content()
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(start = 12.dp).size(20.dp),
            )
        }
    }
}

// 原画的选项值是共用层的常量 ORIGINAL_QUALITY（英文），只在显示时换成中文
private val String.qualityLabel: String
    get() = if (this == ORIGINAL_QUALITY) "原画" else this

@Composable
private fun SettingsSection(
    title: String,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        content()
    }
}

/** 选项少时排成一行连接式按钮组；多到一行放不下时换成可折行的独立按钮，规范不允许连接组折行。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ChoiceGroup(
    options: List<T>,
    isSelected: (T) -> Boolean,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    if (options.size <= CONNECTED_MAX_OPTIONS) {
        ConnectedChoiceRow(options, isSelected, optionLabel, onSelect)
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            ToggleButton(
                checked = isSelected(option),
                onCheckedChange = { onSelect(option) },
            ) {
                Text(optionLabel(option), maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ConnectedChoiceRow(
    options: List<T>,
    isSelected: (T) -> Boolean,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = isSelected(option),
                onCheckedChange = { onSelect(option) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                // 六个倍速预设要在 360dp 宽的竖屏里排成一行，默认的 24dp 水平内边距放不下
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(optionLabel(option), maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

internal val PlayerAspectRatio.label: String
    get() = when (this) {
        PlayerAspectRatio.Fit -> "适应屏幕"
        PlayerAspectRatio.Crop -> "裁剪填充"
        PlayerAspectRatio.Stretch -> "拉伸全屏"
    }

private val PresetSpeeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
private const val SPEED_SLIDER_STEP = 0.05f
private const val SPEED_MATCH_TOLERANCE = 0.005f
private const val CONNECTED_MAX_OPTIONS = 4

private val SIDE_SHEET_WIDTH = 360.dp
private val SIDE_SHEET_CORNER = 16.dp
private const val SIDE_SHEET_SCRIM_ALPHA = 0.32f

private const val GRID_LABEL_MAX_LENGTH = 6
private val EPISODE_CARD_MIN_WIDTH = 140.dp
private val EPISODE_CARD_CORNER = 12.dp
private val EPISODE_ROW_THUMB_WIDTH = 96.dp
private const val LABEL_CONTAINER_ALPHA = 0.85f
private const val EPISODE_SHEET_HEIGHT_FRACTION = 0.7f

private val SEGMENT_GAP = 2.dp

// 版本行缩进，看得出它们属于上面那一集
private val VERSION_INDENT = 24.dp
private val SEGMENT_OUTER_CORNER = 16.dp
private val SEGMENT_INNER_CORNER = 4.dp
