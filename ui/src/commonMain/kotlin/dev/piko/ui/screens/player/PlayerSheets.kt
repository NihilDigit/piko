package dev.piko.ui.screens.player

import dev.piko.shared.i18n.tr

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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.AUTO_QUALITY
import dev.piko.shared.media.ORIGINAL_QUALITY
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.OriginalSubtitleTrackInfo
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.shared.media.player.SubtitleBackgroundPreset
import dev.piko.shared.media.player.SubtitleFontPreset
import dev.piko.shared.media.player.SubtitleStylePrefs
import dev.piko.shared.media.player.imageSubtitleFormatLabel
import dev.piko.shared.media.player.preferredVersion
import dev.piko.shared.media.player.trackDisplayNames
import dev.piko.ui.components.ListSpoilerBlur
import dev.piko.ui.components.MenuMotion
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.components.wheelStaysInSheet
import dev.piko.ui.screens.drive.withoutSavedState
import kotlin.math.abs
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.ActionIconRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import dev.piko.shared.media.player.PlaybackStatsSection
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_copy_diagnostics
import dev.piko.ui.resources.player_diagnostics_copied
import dev.piko.ui.resources.player_hw_decode
import dev.piko.ui.resources.player_quality
import dev.piko.ui.resources.player_speed_decrease
import dev.piko.ui.resources.player_speed_default
import dev.piko.ui.resources.player_speed_increase
import dev.piko.ui.resources.player_speed_reset
import dev.piko.ui.resources.player_sw_decode
import dev.piko.ui.resources.player_tab_episodes
import dev.piko.ui.resources.player_tab_settings
import dev.piko.ui.resources.player_tab_stats
import dev.piko.ui.resources.player_tab_tracks
import dev.piko.ui.resources.subtitle_style_background
import dev.piko.ui.resources.subtitle_style_background_color
import dev.piko.ui.resources.subtitle_style_background_desc
import dev.piko.ui.resources.subtitle_style_background_opacity
import dev.piko.ui.resources.subtitle_style_background_opacity_decrease
import dev.piko.ui.resources.subtitle_style_background_opacity_increase
import dev.piko.ui.resources.subtitle_style_bg_black
import dev.piko.ui.resources.subtitle_style_bg_charcoal
import dev.piko.ui.resources.subtitle_style_bg_espresso
import dev.piko.ui.resources.subtitle_style_bg_forest
import dev.piko.ui.resources.subtitle_style_bg_midnight
import dev.piko.ui.resources.subtitle_style_bg_plum
import dev.piko.ui.resources.subtitle_style_font
import dev.piko.ui.resources.subtitle_style_font_choose
import dev.piko.ui.resources.subtitle_style_font_default
import dev.piko.ui.resources.subtitle_style_font_missing_fallback
import dev.piko.ui.resources.subtitle_style_font_monospace
import dev.piko.ui.resources.subtitle_style_font_serif
import dev.piko.ui.resources.subtitle_style_font_system
import dev.piko.ui.resources.subtitle_style_image_notice
import dev.piko.ui.resources.subtitle_style_image_notice_generic
import dev.piko.ui.resources.subtitle_style_override_ass
import dev.piko.ui.resources.subtitle_style_override_ass_desc
import dev.piko.ui.resources.subtitle_style_position
import dev.piko.ui.resources.subtitle_style_position_bottom
import dev.piko.ui.resources.subtitle_style_position_down
import dev.piko.ui.resources.subtitle_style_position_middle
import dev.piko.ui.resources.subtitle_style_position_top
import dev.piko.ui.resources.subtitle_style_position_up
import dev.piko.ui.resources.subtitle_style_reset
import dev.piko.ui.resources.subtitle_style_size
import dev.piko.ui.resources.subtitle_style_size_decrease
import dev.piko.ui.resources.subtitle_style_size_increase
import dev.piko.ui.resources.subtitle_style_title
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.tabular
import kotlin.math.roundToInt

internal enum class PlayerSheet { Episodes, Settings, Tracks, DriveSubtitles, SubtitleStyle, Stats, Speed, Quality }

private fun PlayerSheet.parentSheet(): PlayerSheet? = when (this) {
    PlayerSheet.DriveSubtitles -> PlayerSheet.Tracks
    PlayerSheet.SubtitleStyle -> PlayerSheet.Settings
    else -> null
}

/**
 * 播放器的面板容器：横屏是贴右侧的浮动 side sheet，竖屏是 bottom sheet。
 *
 * 横屏用 bottom sheet 会盖住大半个画面，而规范在宽窗口下本就建议换成 side sheet；
 * 右侧浮动面板让左侧画面继续可见，选集时还能看着当前这集。Material 3 的 Compose 库
 * 没有 side sheet 组件，这里按规格自己拼：贴右缘、最宽 400dp、靠画面一侧圆角 16dp。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerSheetHost(
    sheet: PlayerSheet?,
    isLandscape: Boolean,
    availableTabs: List<PlayerSheet>,
    onSelectTab: (PlayerSheet) -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(PlayerSheet) -> Unit,
) {
    // 退场动画期间 sheet 已是 null，内容仍要按上一个面板画完
    var lastSheet by remember { mutableStateOf(sheet) }
    if (sheet != null) lastSheet = sheet

    if (isLandscape) {
        PlayerSideSheet(
            visible = sheet != null,
            activeSheet = lastSheet,
            availableTabs = availableTabs,
            onSelectTab = onSelectTab,
            onDismiss = onDismiss,
        ) {
            lastSheet?.let { content(it) }
        }
    } else if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = withoutSavedState { rememberModalBottomSheetState(skipPartiallyExpanded = true) },
        ) {
            // 选集与网盘字幕固定占七成高：按内容定高时几十项会顶满全屏，几项时又只露一条，
            // 字幕目录里进出一层面板还会跟着跳。其余面板内容少，按内容定高
            val fixedHeight = sheet == PlayerSheet.Episodes || sheet == PlayerSheet.DriveSubtitles
            val height = if (fixedHeight) Modifier.fillMaxHeight(EPISODE_SHEET_HEIGHT_FRACTION) else Modifier
            Column(height.wheelStaysInSheet()) {
                val parentSheet = sheet.parentSheet()
                if (parentSheet != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { onSelectTab(parentSheet) }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("返回", "Back", "戻る", "뒤로", "返回"))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(text = sheet.sheetTitle(), style = MaterialTheme.typography.titleLarge)
                    }
                } else if (availableTabs.size > 1 && sheet in availableTabs) {
                    PlayerSheetTabStrip(
                        tabs = availableTabs,
                        selected = sheet,
                        onSelect = onSelectTab,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    )
                } else {
                    Text(
                        text = sheet.sheetTitle(),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                    )
                }
                content(sheet)
            }
        }
    }
}

@Composable
private fun PlayerSheet.tabLabel(): String = when (this) {
    PlayerSheet.Episodes -> stringResource(Res.string.player_tab_episodes)
    PlayerSheet.Tracks -> stringResource(Res.string.player_tab_tracks)
    PlayerSheet.Settings -> stringResource(Res.string.player_tab_settings)
    PlayerSheet.Stats -> stringResource(Res.string.player_tab_stats)
    PlayerSheet.DriveSubtitles -> sheetTitle()
    PlayerSheet.SubtitleStyle -> stringResource(Res.string.subtitle_style_title)
    PlayerSheet.Speed -> sheetTitle()
    PlayerSheet.Quality -> stringResource(Res.string.player_quality)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerSheetTabStrip(
    tabs: List<PlayerSheet>,
    selected: PlayerSheet,
    onSelect: (PlayerSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        tabs.forEachIndexed { index, tab ->
            ToggleButton(
                checked = tab == selected,
                onCheckedChange = { if (it) onSelect(tab) },
                shapes = connectedToggleShapes(index, tabs.size),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = tab.tabLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlayerSheet.sheetTitle(): String = when (this) {
    PlayerSheet.Episodes -> tr("选集", "Playlist", "再生リスト", "재생 목록", "選集")
    PlayerSheet.Settings -> tr("播放设置", "Playback settings", "再生設定", "재생 설정", "播放設定")
    PlayerSheet.Tracks -> tr("音轨与字幕", "Audio and subtitles", "音声と字幕", "오디오 및 자막", "音軌與字幕")
    PlayerSheet.DriveSubtitles -> tr("从网盘选择字幕", "Choose subtitles from drive", "ドライブから字幕を選択", "드라이브에서 자막 선택", "從雲端硬碟選擇字幕")
    PlayerSheet.SubtitleStyle -> stringResource(Res.string.subtitle_style_title)
    PlayerSheet.Stats -> tr("详细信息", "Details", "詳細情報", "세부 정보", "詳細資訊")
    PlayerSheet.Speed -> tr("倍速", "Speed", "再生速度", "재생 속도", "倍速")
    PlayerSheet.Quality -> tr("清晰度", "Quality", "画質", "화질", "畫質")
}

private data class DecodeBadgeInfo(val isHardware: Boolean, val backend: String?)

private fun extractDecodeBadge(sections: List<PlaybackStatsSection>): DecodeBadgeInfo? {
    val decoderLabel = tr("解码", "Decoder", "デコード", "디코딩", "解碼")
    val hwPrefix = tr("硬件", "Hardware", "ハードウェア", "하드웨어", "硬體")
    for (section in sections) {
        for ((label, value) in section.rows) {
            if (label == decoderLabel) {
                val isHw = value.startsWith(hwPrefix, ignoreCase = true)
                val backend = value.substringAfter('（', "").substringBefore('）', "").ifBlank {
                    value.substringAfter('(', "").substringBefore(')', "")
                }.takeIf { it.isNotBlank() }
                return DecodeBadgeInfo(isHardware = isHw, backend = backend)
            }
        }
    }
    return null
}

private fun formatDiagnosticsText(sections: List<PlaybackStatsSection>): String =
    sections.joinToString("\n\n") { section ->
        buildString {
            append('[').append(section.title).append(']')
            section.rows.forEach { (label, value) ->
                append('\n').append(label).append(": ").append(value)
            }
        }
    }

/**
 * 播放详细信息：顶部展示硬解/软解徽标与一键复制诊断信息按钮，下方分卡列出编码、分辨率、帧率、码率、丢帧与缓冲。
 * 开着时每秒重读一次；解码状态已由顶部徽标呈现，视频卡内不再重复列出同一行。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackStatsPanel(read: () -> List<PlaybackStatsSection>, modifier: Modifier = Modifier) {
    val platform = LocalPikoPlatform.current
    val latestRead by rememberUpdatedState(read)
    var sections by remember { mutableStateOf(read()) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(STATS_REFRESH_MILLIS)
            sections = latestRead()
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000L)
            copied = false
        }
    }
    val decodeBadge = remember(sections) { extractDecodeBadge(sections) }
    val decoderLabel = tr("解码", "Decoder", "デコード", "디코딩", "解碼")
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (decodeBadge != null) {
                Surface(
                    shape = CircleShape,
                    color = if (decodeBadge.isHardware) colors.tertiaryContainer else colors.surfaceContainerHigh,
                    contentColor = if (decodeBadge.isHardware) colors.onTertiaryContainer else colors.onSurfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (decodeBadge.isHardware) Icons.Outlined.Memory else Icons.Outlined.DeveloperBoard,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        val base = stringResource(if (decodeBadge.isHardware) Res.string.player_hw_decode else Res.string.player_sw_decode)
                        val text = decodeBadge.backend?.let { "$base ($it)" } ?: base
                        Text(text = text, style = MaterialTheme.typography.labelMedium.tabular, maxLines = 1)
                    }
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            FilledTonalButton(
                onClick = {
                    val report = formatDiagnosticsText(sections)
                    if (report.isNotBlank()) {
                        platform.copyToClipboard("Piko Diagnostics", report)
                        copied = true
                    }
                },
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                modifier = Modifier.heightIn(min = 34.dp).handCursor(),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(if (copied) Res.string.player_diagnostics_copied else Res.string.player_copy_diagnostics),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sections.forEach { section ->
                    val filteredRows = if (decodeBadge != null) {
                        section.rows.filterNot { it.first == decoderLabel }
                    } else {
                        section.rows
                    }
                    if (filteredRows.isEmpty()) return@forEach
                    Surface(
                        shape = RoundedCornerShape(SEGMENT_OUTER_CORNER),
                        color = colors.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.primary,
                            )
                            filteredRows.forEach { (label, value) ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.width(STATS_LABEL_WIDTH),
                                    )
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.bodyMedium.tabular,
                                        color = colors.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val STATS_REFRESH_MILLIS = 1_000L
private val STATS_LABEL_WIDTH = 88.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerSideSheet(
    visible: Boolean,
    activeSheet: PlayerSheet?,
    availableTabs: List<PlayerSheet>,
    onSelectTab: (PlayerSheet) -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val fixed = LocalFixedColors.current
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val panelWidth = when (activeSheet) {
        PlayerSheet.Quality -> SIDE_SHEET_NARROW_WIDTH
        PlayerSheet.Speed -> (windowWidth * SIDE_SHEET_WIDTH_FRACTION).coerceIn(SIDE_SHEET_MIN_WIDTH, SIDE_SHEET_SPEED_MAX_WIDTH)
        else -> (windowWidth * SIDE_SHEET_WIDTH_FRACTION).coerceIn(SIDE_SHEET_MIN_WIDTH, SIDE_SHEET_WIDTH)
    }
    BackHandler(enabled = visible) {
        val parent = activeSheet?.parentSheet()
        if (parent != null) onSelectTab(parent) else onDismiss()
    }
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
                        onClickLabel = tr("关闭面板", "Close panel", "パネルを閉じる", "패널 닫기", "關閉面板"),
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
            exit = slideOutHorizontally(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = SIDE_SHEET_CORNER, bottomStart = SIDE_SHEET_CORNER),
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, fixed.HudSpecularBorder),
                modifier = Modifier.width(panelWidth).fillMaxHeight(),
            ) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val parentSheet = activeSheet?.parentSheet()
                        if (parentSheet != null && activeSheet != null) {
                            IconButton(onClick = { onSelectTab(parentSheet) }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("返回", "Back", "戻る", "뒤로", "返回"))
                            }
                            Text(activeSheet.sheetTitle(), style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                        } else if (availableTabs.size > 1 && activeSheet != null && activeSheet in availableTabs) {
                            PlayerSheetTabStrip(
                                tabs = availableTabs,
                                selected = activeSheet,
                                onSelect = onSelectTab,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Text(
                                text = activeSheet?.sheetTitle().orEmpty(),
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                modifier = Modifier.weight(1f).padding(start = 4.dp),
                            )
                        }
                        IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Filled.Close, contentDescription = tr("关闭", "Close", "閉じる", "닫기", "關閉"))
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
            val sectionScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalWheelScrollsRow(sectionScroll)
                    .horizontalScroll(sectionScroll)
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
                    contentDescription = tr("正在播放", "Now playing", "再生中", "재생 중", "正在播放"),
                    modifier = Modifier.padding(start = 12.dp).size(20.dp),
                )
            }
        }
    }
}

/**
 * 单独打开倍速时的 M3E 精调面板（与 Bilby `ExpandedSpeedPanel` 对齐）：
 * 顶层 tonal 卡片承载大号等宽读数、±0.05x 步进微调按钮、一键复位 1x 与连续无挡点滑杆 + 刻度，
 * 下方配两行四列常用快捷倍速；兼顾整高侧栏居中排布与底部弹出面板自适应滚动。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpandedSpeedPanel(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val hundredths = speedHundredths(speed)
    val isDefault = abs(speed - 1f) < SPEED_MATCH_TOLERANCE
    val lessLabel = stringResource(Res.string.player_speed_decrease)
    val moreLabel = stringResource(Res.string.player_speed_increase)
    val resetLabel = stringResource(Res.string.player_speed_reset)
    val defaultLabel = stringResource(Res.string.player_speed_default)

    val panelBody: @Composable () -> Unit = {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilledTonalIconButton(
                        onClick = { onSpeedChange(stepFineSpeed(speed, -1)) },
                        enabled = hundredths > SPEED_MIN_HUNDREDTHS,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier
                            .semantics { contentDescription = lessLabel }
                            .handCursor(hundredths > SPEED_MIN_HUNDREDTHS),
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = null)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = speedNumber(hundredths),
                                style = MaterialTheme.typography.displayMedium.tabular,
                                color = if (isDefault) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                maxLines = 1,
                                modifier = Modifier.alignByBaseline(),
                            )
                            Text(
                                text = "x",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.alignByBaseline().padding(start = 2.dp),
                            )
                        }
                        if (isDefault) {
                            Text(
                                text = defaultLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else {
                            TextButton(
                                onClick = { onSpeedChange(1f) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(SPEED_RESET_ACTION_HEIGHT).handCursor(),
                            ) {
                                Text(resetLabel, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    FilledTonalIconButton(
                        onClick = { onSpeedChange(stepFineSpeed(speed, 1)) },
                        enabled = hundredths < SPEED_MAX_HUNDREDTHS,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier
                            .semantics { contentDescription = moreLabel }
                            .handCursor(hundredths < SPEED_MAX_HUNDREDTHS),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                FineSpeedSlider(
                    speed = speed,
                    onSpeedChange = onSpeedChange,
                    showTicks = true,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SpeedPresetGrid(speed = speed, onSpeedChange = onSpeedChange)
    }

    if (!fillHeight) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            panelBody()
            Spacer(modifier = Modifier.height(16.dp))
        }
        return
    }

    val density = LocalDensity.current
    var panelHeight by remember { mutableStateOf(SPEED_COMPACT_FALLBACK_HEIGHT) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { panelHeight = with(density) { it.height.toDp() } }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (panelHeight < SPEED_COMPACT_FALLBACK_HEIGHT) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                CompactSpeedControls(speed = speed, onSpeedChange = onSpeedChange)
            }
            return@Box
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.weight(0.75f))
            panelBody()
            Spacer(modifier = Modifier.weight(1.1f))
        }
    }
}

/** 整块播放设置面板与超矮窗口内使用的紧凑倍速区：±0.05x 微调 + 滑杆 + 2×4 快捷网格。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CompactSpeedControls(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    val hundredths = speedHundredths(speed)
    val isDefault = abs(speed - 1f) < SPEED_MATCH_TOLERANCE
    val lessLabel = stringResource(Res.string.player_speed_decrease)
    val moreLabel = stringResource(Res.string.player_speed_increase)
    val resetLabel = stringResource(Res.string.player_speed_reset)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Spacer(Modifier.weight(1f))
        if (!isDefault) {
            TextButton(
                onClick = { onSpeedChange(1f) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(SPEED_COMPACT_STEPPER_HEIGHT).handCursor(),
            ) {
                Text(resetLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
        FilledTonalIconButton(
            onClick = { onSpeedChange(stepFineSpeed(speed, -1)) },
            enabled = hundredths > SPEED_MIN_HUNDREDTHS,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier
                .semantics { contentDescription = lessLabel }
                .handCursor(hundredths > SPEED_MIN_HUNDREDTHS),
        ) {
            Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(
            text = formatSpeed(speed),
            style = MaterialTheme.typography.titleSmall.tabular,
            color = if (isDefault) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        FilledTonalIconButton(
            onClick = { onSpeedChange(stepFineSpeed(speed, 1)) },
            enabled = hundredths < SPEED_MAX_HUNDREDTHS,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier
                .semantics { contentDescription = moreLabel }
                .handCursor(hundredths < SPEED_MAX_HUNDREDTHS),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
    FineSpeedSlider(speed = speed, onSpeedChange = onSpeedChange, showTicks = false)
    Spacer(Modifier.height(6.dp))
    SpeedPresetGrid(speed = speed, onSpeedChange = onSpeedChange)
}

/** 连续无挡点倍速滑杆：拖动按 0.05x 精度实时吸附，并可选展示可点击刻度标尺。 */
@Composable
private fun FineSpeedSlider(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    showTicks: Boolean,
) {
    val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = clamped,
            onValueChange = { raw ->
                val snapped = snapFineSpeed(raw)
                if (abs(snapped - speed) >= SPEED_MATCH_TOLERANCE) onSpeedChange(snapped)
            },
            valueRange = MIN_SPEED..MAX_SPEED,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = tr("播放倍速", "Playback speed", "再生速度", "재생 속도", "播放倍速") },
        )
        if (showTicks) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            ) {
                SpeedScaleTicks.forEach { tick ->
                    val active = abs(tick - speed) < 0.01f
                    Text(
                        text = formatSpeed(tick),
                        style = MaterialTheme.typography.labelSmall.tabular,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable(role = Role.Button) { onSpeedChange(tick) }
                            .handCursor()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeedPresetGrid(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        SpeedQuickPresets.chunked(SPEED_PRESET_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    ToggleButton(
                        checked = abs(preset - speed) < 0.01f,
                        onCheckedChange = { onSpeedChange(preset) },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 0.dp)
                            .semantics { role = Role.RadioButton }
                            .handCursor(),
                    ) {
                        Text(
                            text = formatSpeed(preset),
                            style = MaterialTheme.typography.labelLarge.tabular,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单独打开清晰度时的单列 M3E `ToggleButton` 选择面板（与 Bilby `QualitySection(titled = false)` 对齐）：
 * 窄侧栏内一行一项清晰扫览，保留 Piko 的「自动」实际落地档位角标。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun QualityPickerPanel(
    qualityOptions: List<String>,
    currentQuality: String?,
    onQualityChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoResolved = LocalAutoResolvedQuality.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
    ) {
        QualityChoiceGrid(
            options = qualityOptions,
            currentQuality = currentQuality,
            autoResolved = autoResolved,
            onSelect = onQualityChange,
            columns = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QualityChoiceGrid(
    options: List<String>,
    currentQuality: String?,
    autoResolved: String?,
    onSelect: (String) -> Unit,
    columns: Int,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        options.chunked(columns).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { option ->
                    val isSelected = option == currentQuality
                    ToggleButton(
                        checked = isSelected,
                        onCheckedChange = { onSelect(option) },
                        modifier = Modifier
                            .weight(1f)
                            .semantics { role = Role.RadioButton }
                            .handCursor(),
                    ) {
                        Text(
                            text = option.qualityLabel(autoResolved, selected = isSelected),
                            style = MaterialTheme.typography.labelLarge.tabular,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 完整的播放设置：倍速、清晰度、画面比例，以及手势的进退步长与长按倍速。旋转在底栏，一次转 90 度。
 * 都是单选，用连接式按钮组；倍速另有铺满的滑块，当前值写在小节标题的右端，不占滑块的宽度。
 * 步长与长按倍速放在这里而不是应用的设置页：要调它的时候人正在看片、刚觉得一步太大。
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
    // 完整文件名。顶栏的标题单行、中间省略，长文件名在播放时只能到这里看全
    title: String? = null,
    /** 去处：音轨与字幕、详细信息。 */
    actions: List<SheetAction> = emptyList(),
    /** 对文件本身的操作：分享、下载。与文件名同为「文件」一组，与 [actions] 排在同一排，在前。 */
    fileActions: List<SheetAction> = emptyList(),
    seekStepSeconds: Int = PlayerGestureDefaults.SEEK_STEP_SECONDS,
    onSeekStepChange: ((Int) -> Unit)? = null,
    longPressSpeed: Float = PlayerGestureDefaults.BOOST_SPEED,
    onLongPressSpeedChange: ((Float) -> Unit)? = null,
    onOpenSubtitleStyle: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // 文件操作排成一排，带圆角底色块与清晰短标签
        val rowActions = fileActions + actions
        if (title != null || rowActions.isNotEmpty()) {
            SettingsSection(tr("文件", "Files", "ファイル", "파일", "檔案")) {
                if (title != null) {
                    // 可选中：文件名常要拿去搜字幕、查资料
                    SelectionContainer {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (rowActions.isNotEmpty()) {
                    if (title != null) Spacer(Modifier.height(10.dp))
                    ActionIconRow(
                        rowActions,
                        onAction = { it.onClick() },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                }
            }
        }
        if (playbackSpeed != null) {
            SettingsSection(tr("倍速", "Speed", "再生速度", "재생 속도", "倍速")) {
                CompactSpeedControls(speed = playbackSpeed, onSpeedChange = onSpeedChange)
            }
        }
        if (qualityOptions.size > 1) {
            val autoResolved = LocalAutoResolvedQuality.current
            SettingsSection(tr("清晰度", "Quality", "画質", "화질", "畫質")) {
                QualityChoiceGrid(
                    options = qualityOptions,
                    currentQuality = currentQuality,
                    autoResolved = autoResolved,
                    onSelect = onQualityChange,
                    columns = QUALITY_GRID_COLUMNS,
                )
            }
        }
        // 后端不支持画面比例时整节隐藏，避免点了没反应
        if (aspectRatio != null) {
            SettingsSection(tr("画面比例", "Aspect ratio", "アスペクト比", "화면 비율", "畫面比例")) {
                ConnectedChoiceRow(
                    options = PlayerAspectRatio.entries,
                    isSelected = { it == aspectRatio },
                    optionLabel = { it.label },
                    onSelect = onAspectRatioChange,
                )
            }
        }
        if (onOpenSubtitleStyle != null) {
            SettingsSection(tr("字幕", "Subtitles", "字幕", "자막", "字幕")) {
                Surface(
                    onClick = onOpenSubtitleStyle,
                    shape = RoundedCornerShape(SEGMENT_OUTER_CORNER),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).handCursor(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(Res.string.subtitle_style_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp).size(20.dp),
                        )
                    }
                }
            }
        }
        if (onSeekStepChange != null) {
            SettingsSection(tr("进退步长", "Seek step", "シーク間隔", "탐색 간격", "快轉與倒轉間隔"), trailing = tr("$seekStepSeconds 秒", "$seekStepSeconds sec", "$seekStepSeconds 秒", "${seekStepSeconds}초", "$seekStepSeconds 秒")) {
                ConnectedChoiceRow(
                    options = PlayerGestureDefaults.SeekStepChoices,
                    isSelected = { it == seekStepSeconds },
                    optionLabel = Int::toString,
                    onSelect = onSeekStepChange,
                )
            }
        }
        // 后端不能变速时长按本来就不加速，这一项也不给
        if (playbackSpeed != null && onLongPressSpeedChange != null) {
            SettingsSection(tr("长按倍速", "Hold speed", "長押し時の再生速度", "길게 누르기 재생 속도", "長按倍速"), trailing = formatSpeed(longPressSpeed)) {
                ConnectedChoiceRow(
                    options = PlayerGestureDefaults.BoostSpeedChoices,
                    isSelected = { abs(longPressSpeed - it) < SPEED_MATCH_TOLERANCE },
                    optionLabel = ::formatSpeedPreset,
                    onSelect = onLongPressSpeedChange,
                )
            }
        }
    }
}

/**
 * 音轨与字幕。轨道名常带语言、字幕组与「外挂」，长短不一，按钮组放不下，所以一行一条、单选。
 * 音轨只有一条时整节不出现；字幕第一项是关闭，末尾是从本机或网盘另挑一个字幕文件的入口，
 * 平台或后端做不到时为 null，不显示。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TracksPanel(
    audioTracks: List<MediaTrack>,
    selectedAudioTrackId: String?,
    onSelectAudio: (MediaTrack) -> Unit,
    subtitleTracks: List<MediaTrack>,
    selectedSubtitleTrackId: String?,
    onSelectSubtitle: (MediaTrack?) -> Unit,
    onPickLocalSubtitle: (() -> Unit)?,
    onPickDriveSubtitle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    originalSubtitleHints: List<OriginalSubtitleTrackInfo> = emptyList(),
    onSwitchToOriginalQuality: (() -> Unit)? = null,
) {
    val audioLabels = trackDisplayNames(audioTracks)
    val subtitleLabels = trackDisplayNames(subtitleTracks)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (audioTracks.size > 1) {
            TrackSection(tr("音轨", "Audio track", "音声トラック", "오디오 트랙", "音軌")) {
                audioTracks.forEachIndexed { index, track ->
                    TrackRow(
                        label = audioLabels[index],
                        selected = track.id == selectedAudioTrackId,
                        index = index,
                        count = audioTracks.size,
                        onClick = { onSelectAudio(track) },
                    )
                }
            }
        }
        TrackSection(tr("字幕", "Subtitles", "字幕", "자막", "字幕")) {
            val showOriginalHint = originalSubtitleHints.isNotEmpty() && onSwitchToOriginalQuality != null
            val pickerCount = (if (showOriginalHint) 1 else 0) +
                (if (onPickLocalSubtitle != null) 1 else 0) +
                (if (onPickDriveSubtitle != null) 1 else 0)
            val totalSubtitleRows = 1 + subtitleTracks.size + pickerCount
            TrackRow(
                label = tr("关闭", "Close", "閉じる", "닫기", "關閉"),
                selected = selectedSubtitleTrackId == null,
                index = 0,
                count = totalSubtitleRows,
                onClick = { onSelectSubtitle(null) },
            )
            subtitleTracks.forEachIndexed { index, track ->
                TrackRow(
                    label = subtitleLabels[index],
                    selected = track.id == selectedSubtitleTrackId,
                    index = index + 1,
                    count = totalSubtitleRows,
                    onClick = { onSelectSubtitle(track) },
                )
            }
            var nextPickerIndex = 1 + subtitleTracks.size
            if (showOriginalHint && onSwitchToOriginalQuality != null) {
                val summary = originalSubtitleHints
                    .mapNotNull { it.title?.takeIf(String::isNotBlank) ?: it.language?.uppercase() }
                    .distinct()
                    .take(3)
                    .joinToString(" / ")
                val label = if (summary.isNotBlank()) {
                    tr(
                        "切换原画以加载内封字幕（$summary）",
                        "Switch to Original for embedded subtitles ($summary)",
                        "オリジナルに切り替えて内蔵字幕を表示（$summary）",
                        "내장 자막을 보려면 원본 화질로 전환 ($summary)",
                        "切換原畫以載入內嵌字幕（$summary）",
                    )
                } else {
                    tr(
                        "切换原画以加载内封字幕",
                        "Switch to Original for embedded subtitles",
                        "オリジナルに切り替えて内蔵字幕を表示",
                        "내장 자막을 보려면 원본 화질로 전환",
                        "切換原畫以載入內嵌字幕",
                    )
                }
                SegmentedPickerRow(
                    icon = Icons.Outlined.Subtitles,
                    label = label,
                    index = nextPickerIndex++,
                    count = totalSubtitleRows,
                    onClick = onSwitchToOriginalQuality,
                )
            }
            if (onPickLocalSubtitle != null) {
                SegmentedPickerRow(
                    icon = Icons.Outlined.FileOpen,
                    label = tr("从本机选择字幕…", "Choose subtitles from device…", "この端末から字幕を選択…", "기기에서 자막 선택…", "從本機選擇字幕…"),
                    index = nextPickerIndex++,
                    count = totalSubtitleRows,
                    onClick = onPickLocalSubtitle,
                )
            }
            if (onPickDriveSubtitle != null) {
                SegmentedPickerRow(
                    icon = Icons.Outlined.Cloud,
                    label = tr("从网盘选择字幕…", "Choose subtitles from drive…", "ドライブから字幕を選択…", "드라이브에서 자막 선택…", "從雲端硬碟選擇字幕…"),
                    index = nextPickerIndex,
                    count = totalSubtitleRows,
                    onClick = onPickDriveSubtitle,
                )
            }
        }
    }
}

/**
 * 字幕外观实时调节面板：支持开关半透明深色底色、切换字体预设与已安装系统字体、调节字号与纵向位置，
 * 以及可选覆盖 ASS 特效样式。在横屏侧边栏与竖屏底部面板中拖动滑块或点击开关均即时作用于播放画面。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SubtitleStylePanel(
    style: SubtitleStylePrefs,
    onPreview: (SubtitleStylePrefs) -> Unit,
    onCommit: (SubtitleStylePrefs) -> Unit,
    modifier: Modifier = Modifier,
    selectedSubtitleTrack: MediaTrack? = null,
) {
    val platform = LocalPikoPlatform.current
    var draft by remember(style) { mutableStateOf(style.sanitize()) }
    var availableFonts by remember { mutableStateOf<List<String>>(emptyList()) }
    var fontsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(platform) {
        availableFonts = platform.listSubtitleFonts()
        fontsLoaded = true
    }

    val applyInstant: (SubtitleStylePrefs) -> Unit = { updated ->
        val clean = updated.sanitize()
        draft = clean
        onCommit(clean)
    }

    val presetOptions = remember {
        listOf(
            SubtitleFontPreset.Default,
            SubtitleFontPreset.Serif,
            SubtitleFontPreset.Monospace,
        )
    }
    val defaultFontLabel = stringResource(Res.string.subtitle_style_font_default)
    val serifFontLabel = stringResource(Res.string.subtitle_style_font_serif)
    val monoFontLabel = stringResource(Res.string.subtitle_style_font_monospace)
    val customFontMissing = fontsLoaded &&
        draft.fontPreset == SubtitleFontPreset.Custom &&
        draft.customFontFamily != null &&
        availableFonts.isNotEmpty() &&
        availableFonts.none { it.equals(draft.customFontFamily, ignoreCase = true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (selectedSubtitleTrack?.isImage == true) {
            val formatLabel = imageSubtitleFormatLabel(selectedSubtitleTrack.codec)
            val noticeText = if (formatLabel != null) {
                stringResource(Res.string.subtitle_style_image_notice, formatLabel)
            } else {
                stringResource(Res.string.subtitle_style_image_notice_generic)
            }
            Surface(
                shape = RoundedCornerShape(SEGMENT_OUTER_CORNER),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.padding(top = 1.dp).size(18.dp),
                    )
                    Text(
                        text = noticeText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        SubtitleBackgroundCard(
            draft = draft,
            onToggle = { checked -> applyInstant(draft.copy(showBackground = checked)) },
            onSelectColor = { rgb -> applyInstant(draft.copy(backgroundColorRgb = rgb)) },
            onPreviewOpacity = { nextOpacity ->
                val next = draft.copy(backgroundOpacity = nextOpacity)
                draft = next
                onPreview(next)
            },
            onCommitOpacity = { onCommit(draft) },
            onStepOpacity = { nextOpacity ->
                applyInstant(draft.copy(backgroundOpacity = nextOpacity))
            },
        )

        SettingsSection(
            title = stringResource(Res.string.subtitle_style_font),
        ) {
            ConnectedChoiceRow(
                options = presetOptions,
                isSelected = { preset -> draft.fontPreset == preset },
                optionLabel = { preset ->
                    when (preset) {
                        SubtitleFontPreset.Default -> defaultFontLabel
                        SubtitleFontPreset.Serif -> serifFontLabel
                        SubtitleFontPreset.Monospace -> monoFontLabel
                        SubtitleFontPreset.Custom -> defaultFontLabel
                    }
                },
                onSelect = { preset ->
                    applyInstant(draft.copy(fontPreset = preset, customFontFamily = null))
                },
            )
            if (availableFonts.isNotEmpty() || draft.fontPreset == SubtitleFontPreset.Custom) {
                Spacer(Modifier.height(10.dp))
                var fontMenuExpanded by remember { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(SEGMENT_OUTER_CORNER),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.subtitle_style_font_system),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            FilledTonalButton(
                                onClick = { if (availableFonts.isNotEmpty()) fontMenuExpanded = true },
                                enabled = availableFonts.isNotEmpty(),
                                shapes = ButtonDefaults.shapes(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.heightIn(min = 36.dp).handCursor(availableFonts.isNotEmpty()),
                            ) {
                                Text(
                                    text = if (draft.fontPreset == SubtitleFontPreset.Custom && !draft.customFontFamily.isNullOrBlank()) {
                                        draft.customFontFamily.orEmpty()
                                    } else {
                                        stringResource(Res.string.subtitle_style_font_choose)
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 160.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Outlined.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            MenuMotion {
                                PikoDropdownMenu(
                                    expanded = fontMenuExpanded,
                                    onDismissRequest = { fontMenuExpanded = false },
                                    modifier = Modifier.heightIn(max = SUBTITLE_FONT_MENU_MAX_HEIGHT),
                                ) {
                                    availableFonts.forEachIndexed { index, family ->
                                        val isChosen = draft.fontPreset == SubtitleFontPreset.Custom &&
                                            draft.customFontFamily.equals(family, ignoreCase = true)
                                        DropdownMenuItem(
                                            onClick = {
                                                fontMenuExpanded = false
                                                applyInstant(
                                                    draft.copy(
                                                        fontPreset = SubtitleFontPreset.Custom,
                                                        customFontFamily = family,
                                                    ),
                                                )
                                            },
                                            text = {
                                                Text(
                                                    text = family,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            shape = menuItemShape(index, availableFonts.size),
                                            trailingContent = if (isChosen) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (customFontMissing) {
                    Text(
                        text = stringResource(Res.string.subtitle_style_font_missing_fallback),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                    )
                }
            }
        }

        val sizeLabel = stringResource(Res.string.subtitle_style_size)
        val sizeDecreaseLabel = stringResource(Res.string.subtitle_style_size_decrease)
        val sizeIncreaseLabel = stringResource(Res.string.subtitle_style_size_increase)
        SettingsSection(
            title = sizeLabel,
            trailing = draft.fontSize.toString(),
        ) {
            Surface(
                shape = RoundedCornerShape(SEGMENT_OUTER_CORNER),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val canDecrease = draft.fontSize > SubtitleStylePrefs.MIN_FONT_SIZE
                    FilledTonalIconButton(
                        onClick = {
                            applyInstant(draft.copy(fontSize = draft.fontSize - SubtitleStylePrefs.FONT_SIZE_STEP))
                        },
                        enabled = canDecrease,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier
                            .size(36.dp)
                            .semantics { contentDescription = sizeDecreaseLabel }
                            .handCursor(canDecrease),
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Slider(
                        value = draft.fontSize.toFloat(),
                        onValueChange = { raw ->
                            val nextSize = raw.roundToInt()
                                .coerceIn(SubtitleStylePrefs.MIN_FONT_SIZE, SubtitleStylePrefs.MAX_FONT_SIZE)
                            if (nextSize != draft.fontSize) {
                                val next = draft.copy(fontSize = nextSize)
                                draft = next
                                onPreview(next)
                            }
                        },
                        onValueChangeFinished = { onCommit(draft) },
                        valueRange = SubtitleStylePrefs.MIN_FONT_SIZE.toFloat()..SubtitleStylePrefs.MAX_FONT_SIZE.toFloat(),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = sizeLabel },
                    )
                    val canIncrease = draft.fontSize < SubtitleStylePrefs.MAX_FONT_SIZE
                    FilledTonalIconButton(
                        onClick = {
                            applyInstant(draft.copy(fontSize = draft.fontSize + SubtitleStylePrefs.FONT_SIZE_STEP))
                        },
                        enabled = canIncrease,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier
                            .size(36.dp)
                            .semantics { contentDescription = sizeIncreaseLabel }
                            .handCursor(canIncrease),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        val posLabel = stringResource(Res.string.subtitle_style_position)
        val posUpLabel = stringResource(Res.string.subtitle_style_position_up)
        val posDownLabel = stringResource(Res.string.subtitle_style_position_down)
        SettingsSection(
            title = posLabel,
            trailing = "${draft.position}%",
        ) {
            Surface(
                shape = RoundedCornerShape(SEGMENT_OUTER_CORNER),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val canMoveUp = draft.position > SubtitleStylePrefs.MIN_POSITION
                        FilledTonalIconButton(
                            onClick = {
                                applyInstant(draft.copy(position = draft.position - SubtitleStylePrefs.POSITION_STEP))
                            },
                            enabled = canMoveUp,
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier
                                .size(36.dp)
                                .semantics { contentDescription = posUpLabel }
                                .handCursor(canMoveUp),
                        ) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Slider(
                            value = draft.position.toFloat(),
                            onValueChange = { raw ->
                                val nextPos = raw.roundToInt()
                                    .coerceIn(SubtitleStylePrefs.MIN_POSITION, SubtitleStylePrefs.MAX_POSITION)
                                if (nextPos != draft.position) {
                                    val next = draft.copy(position = nextPos)
                                    draft = next
                                    onPreview(next)
                                }
                            },
                            onValueChangeFinished = { onCommit(draft) },
                            valueRange = SubtitleStylePrefs.MIN_POSITION.toFloat()..SubtitleStylePrefs.MAX_POSITION.toFloat(),
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = posLabel },
                        )
                        val canMoveDown = draft.position < SubtitleStylePrefs.MAX_POSITION
                        FilledTonalIconButton(
                            onClick = {
                                applyInstant(draft.copy(position = draft.position + SubtitleStylePrefs.POSITION_STEP))
                            },
                            enabled = canMoveDown,
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier
                                .size(36.dp)
                                .semantics { contentDescription = posDownLabel }
                                .handCursor(canMoveDown),
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 44.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val ticks = listOf(
                            0 to stringResource(Res.string.subtitle_style_position_top),
                            50 to stringResource(Res.string.subtitle_style_position_middle),
                            100 to stringResource(Res.string.subtitle_style_position_bottom),
                        )
                        ticks.forEach { (value, label) ->
                            val active = draft.position == value
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .clickable(role = Role.Button) { applyInstant(draft.copy(position = value)) }
                                    .handCursor()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        SubtitleStyleSwitchCard(
            title = stringResource(Res.string.subtitle_style_override_ass),
            description = stringResource(Res.string.subtitle_style_override_ass_desc),
            checked = draft.overrideAssStyles,
            onCheckedChange = { checked -> applyInstant(draft.copy(overrideAssStyles = checked)) },
        )

        if (!draft.isDefault) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { applyInstant(SubtitleStylePrefs.DEFAULT) },
                    modifier = Modifier.handCursor(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.subtitle_style_reset))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SubtitleBackgroundCard(
    draft: SubtitleStylePrefs,
    onToggle: (Boolean) -> Unit,
    onSelectColor: (Int) -> Unit,
    onPreviewOpacity: (Int) -> Unit,
    onCommitOpacity: () -> Unit,
    onStepOpacity: (Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(SEGMENT_OUTER_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Switch) { onToggle(!draft.showBackground) }
                    .handCursor()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(Res.string.subtitle_style_background),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.subtitle_style_background_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = draft.showBackground,
                    onCheckedChange = null,
                )
            }

            if (draft.showBackground) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.subtitle_style_background_color),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SubtitleBackgroundPreset.entries.forEach { preset ->
                            val isSelected = (draft.backgroundColorRgb and 0x00FFFFFF) == preset.rgb
                            val swatchLabel = preset.localizedName()
                            val swatchColor = Color(0xFF000000L or preset.rgb.toLong())
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .selectable(
                                        selected = isSelected,
                                        role = Role.RadioButton,
                                        onClick = { onSelectColor(preset.rgb) },
                                    )
                                    .semantics { contentDescription = swatchLabel }
                                    .handCursor(),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = swatchColor,
                                    border = BorderStroke(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                    ),
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    if (isSelected) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val opacityLabel = stringResource(Res.string.subtitle_style_background_opacity)
                    val opacityDecreaseLabel = stringResource(Res.string.subtitle_style_background_opacity_decrease)
                    val opacityIncreaseLabel = stringResource(Res.string.subtitle_style_background_opacity_increase)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = opacityLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${draft.backgroundOpacity}%",
                            style = MaterialTheme.typography.titleMediumEmphasized.tabular,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val canDecrease = draft.backgroundOpacity > SubtitleStylePrefs.MIN_BACKGROUND_OPACITY
                        FilledTonalIconButton(
                            onClick = {
                                val next = (draft.backgroundOpacity - SubtitleStylePrefs.BACKGROUND_OPACITY_STEP)
                                    .coerceIn(
                                        SubtitleStylePrefs.MIN_BACKGROUND_OPACITY,
                                        SubtitleStylePrefs.MAX_BACKGROUND_OPACITY,
                                    )
                                onStepOpacity(next)
                            },
                            enabled = canDecrease,
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier
                                .size(36.dp)
                                .semantics { contentDescription = opacityDecreaseLabel }
                                .handCursor(canDecrease),
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Slider(
                            value = draft.backgroundOpacity.toFloat(),
                            onValueChange = { raw ->
                                val step = SubtitleStylePrefs.BACKGROUND_OPACITY_STEP
                                val snapped = ((raw / step).roundToInt() * step).coerceIn(
                                    SubtitleStylePrefs.MIN_BACKGROUND_OPACITY,
                                    SubtitleStylePrefs.MAX_BACKGROUND_OPACITY,
                                )
                                if (snapped != draft.backgroundOpacity) {
                                    onPreviewOpacity(snapped)
                                }
                            },
                            onValueChangeFinished = onCommitOpacity,
                            valueRange = SubtitleStylePrefs.MIN_BACKGROUND_OPACITY.toFloat()..SubtitleStylePrefs.MAX_BACKGROUND_OPACITY.toFloat(),
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = opacityLabel },
                        )
                        val canIncrease = draft.backgroundOpacity < SubtitleStylePrefs.MAX_BACKGROUND_OPACITY
                        FilledTonalIconButton(
                            onClick = {
                                val next = (draft.backgroundOpacity + SubtitleStylePrefs.BACKGROUND_OPACITY_STEP)
                                    .coerceIn(
                                        SubtitleStylePrefs.MIN_BACKGROUND_OPACITY,
                                        SubtitleStylePrefs.MAX_BACKGROUND_OPACITY,
                                    )
                                onStepOpacity(next)
                            },
                            enabled = canIncrease,
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier
                                .size(36.dp)
                                .semantics { contentDescription = opacityIncreaseLabel }
                                .handCursor(canIncrease),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleBackgroundPreset.localizedName(): String = when (this) {
    SubtitleBackgroundPreset.Black -> stringResource(Res.string.subtitle_style_bg_black)
    SubtitleBackgroundPreset.Charcoal -> stringResource(Res.string.subtitle_style_bg_charcoal)
    SubtitleBackgroundPreset.MidnightBlue -> stringResource(Res.string.subtitle_style_bg_midnight)
    SubtitleBackgroundPreset.DeepForest -> stringResource(Res.string.subtitle_style_bg_forest)
    SubtitleBackgroundPreset.DeepPlum -> stringResource(Res.string.subtitle_style_bg_plum)
    SubtitleBackgroundPreset.WarmEspresso -> stringResource(Res.string.subtitle_style_bg_espresso)
}

@Composable
private fun SubtitleStyleSwitchCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(SEGMENT_OUTER_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SEGMENT_OUTER_CORNER))
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .handCursor(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}

/** 字幕列表末尾的入口行：与轨道行同高，前面带图标，看得出它不是一条轨道。 */
@Composable
internal fun PickerRow(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SegmentedPickerRow(
    icon: ImageVector,
    label: String,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    showChevron: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = ListItemDefaults.segmentedShapes(index = index, count = count).shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).handCursor(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            if (showChevron) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun TrackSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
        )
        content()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackRow(
    label: String,
    selected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = if (selected) {
        RoundedCornerShape(SEGMENT_OUTER_CORNER)
    } else {
        ListItemDefaults.segmentedShapes(index = index, count = count).shape
    }
    Surface(
        shape = shape,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainer,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 52.dp)
            .handCursor(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = if (selected) MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) else MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.onSecondaryContainer,
                    modifier = Modifier.padding(start = 12.dp).size(20.dp),
                )
            }
        }
    }
}

// 原画与自动的选项值是共用层的常量（英文），只在显示时换成多语言标签
private fun String.qualityLabel(autoResolved: String?, selected: Boolean): String = when (this) {
    AUTO_QUALITY -> if (selected && autoResolved != null) {
        "${AUTO_QUALITY.baseQualityLabel} (${autoResolved.baseQualityLabel})"
    } else {
        AUTO_QUALITY.baseQualityLabel
    }
    ORIGINAL_QUALITY -> ORIGINAL_QUALITY.baseQualityLabel
    else -> this
}

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
                    style = MaterialTheme.typography.titleMediumEmphasized.tabular,
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
                shapes = connectedToggleShapes(index, options.size),
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
        PlayerAspectRatio.Fit -> tr("适应屏幕", "Fit screen", "画面に合わせる", "화면에 맞춤", "符合螢幕")
        PlayerAspectRatio.Crop -> tr("裁剪填充", "Crop to fill", "切り抜いて拡大", "잘라서 채우기", "裁切填滿")
        PlayerAspectRatio.Stretch -> tr("拉伸全屏", "Stretch to fill", "引き伸ばして表示", "늘려서 채우기", "拉伸至全螢幕")
        PlayerAspectRatio.RegionCrop -> tr("框选填充", "Fill selected area", "選択範囲を拡大", "선택 영역 채우기", "框選填滿")
    }

// 设置面板与 [ ] 键步进共用的倍速预设
internal val PlayerSpeedPresets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)
private val SpeedQuickPresets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)
private val SpeedScaleTicks = listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 3.5f)
private const val SPEED_PRESET_COLUMNS = 4
private const val QUALITY_GRID_COLUMNS = 2
private const val SPEED_MIN_HUNDREDTHS = 50
private const val SPEED_MAX_HUNDREDTHS = 350
private const val SPEED_FINE_STEP_HUNDREDTHS = 5
private val SPEED_COMPACT_STEPPER_HEIGHT = 32.dp
private val SPEED_RESET_ACTION_HEIGHT = 28.dp
private val SPEED_COMPACT_FALLBACK_HEIGHT = 260.dp
private const val SPEED_MATCH_TOLERANCE = 0.005f
private const val CONNECTED_MAX_OPTIONS = 4

private fun snapFineSpeed(raw: Float): Float {
    val hundredths = ((raw * 100f / SPEED_FINE_STEP_HUNDREDTHS).roundToInt() * SPEED_FINE_STEP_HUNDREDTHS)
        .coerceIn(SPEED_MIN_HUNDREDTHS, SPEED_MAX_HUNDREDTHS)
    return hundredths / 100f
}

private fun stepFineSpeed(current: Float, deltaSteps: Int): Float {
    val base = speedHundredths(current)
    val snapped = ((base.toFloat() / SPEED_FINE_STEP_HUNDREDTHS).roundToInt() + deltaSteps) * SPEED_FINE_STEP_HUNDREDTHS
    return snapped.coerceIn(SPEED_MIN_HUNDREDTHS, SPEED_MAX_HUNDREDTHS) / 100f
}

private const val SIDE_SHEET_WIDTH_FRACTION = 0.42f
private val SIDE_SHEET_MIN_WIDTH = 280.dp
private val SIDE_SHEET_SPEED_MAX_WIDTH = 360.dp
private val SIDE_SHEET_NARROW_WIDTH = 240.dp
private val SIDE_SHEET_WIDTH = 400.dp
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
private val SUBTITLE_FONT_MENU_MAX_HEIGHT = 320.dp
