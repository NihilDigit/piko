package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.wheelStaysInSheet
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_quality
import dev.piko.ui.resources.player_tab_episodes
import dev.piko.ui.resources.player_tab_settings
import dev.piko.ui.resources.player_tab_stats
import dev.piko.ui.resources.player_tab_tracks
import dev.piko.ui.resources.subtitle_style_title
import dev.piko.ui.screens.drive.withoutSavedState
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.tabular

internal enum class PlayerSheet { Episodes, Settings, Tracks, DriveSubtitles, SubtitleStyle, Stats, Speed, Quality }

internal fun PlayerSheet.parentSheet(): PlayerSheet? = when (this) {
    PlayerSheet.DriveSubtitles -> PlayerSheet.Tracks
    PlayerSheet.SubtitleStyle -> PlayerSheet.Settings
    else -> null
}

/**
 * 播放器的面板容器：横屏是贴右侧的浮动 side sheet，竖屏是 bottom sheet。
 *
 * 横屏用 bottom sheet 会盖住大半个画面，而规范在宽窗口下本就建议换成 side sheet；
 * 右侧浮动面板让左侧画面继续可见，选集时还能看着当前这集。
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
                modifier = Modifier.weight(1f).handCursor(),
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
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, fixed.HudSpecularBorder),
                modifier = Modifier.width(panelWidth).fillMaxHeight(),
            ) {
                Column(
                    Modifier
                        .wheelStaysInSheet()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)),
                ) {
                    val parentSheet = activeSheet?.parentSheet()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = if (parentSheet != null) 8.dp else 16.dp,
                                end = 8.dp,
                                top = 12.dp,
                                bottom = 10.dp,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (parentSheet != null && activeSheet != null) {
                            IconButton(
                                onClick = { onSelectTab(parentSheet) },
                                shapes = IconButtonDefaults.shapes(),
                                modifier = Modifier.handCursor(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("返回", "Back", "戻る", "뒤로", "返回"))
                            }
                            Text(
                                text = activeSheet.sheetTitle(),
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 4.dp),
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier.handCursor(),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = tr("关闭", "Close", "閉じる", "닫기", "關閉"))
                        }
                    }
                    content()
                }
            }
        }
    }
}

@Composable
internal fun PlayerPanelSection(
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> ConnectedChoiceRow(
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
                modifier = Modifier.weight(1f).handCursor(),
            ) {
                Text(optionLabel(option), maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
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
internal fun SegmentedPickerRow(
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

private const val SIDE_SHEET_WIDTH_FRACTION = 0.42f
private val SIDE_SHEET_MIN_WIDTH = 280.dp
private val SIDE_SHEET_SPEED_MAX_WIDTH = 360.dp
private val SIDE_SHEET_NARROW_WIDTH = 240.dp
private val SIDE_SHEET_WIDTH = 400.dp
private val SIDE_SHEET_CORNER = 16.dp
private const val SIDE_SHEET_SCRIM_ALPHA = 0.32f
private const val EPISODE_SHEET_HEIGHT_FRACTION = 0.7f

internal val SEGMENT_GAP = 2.dp
internal val SEGMENT_OUTER_CORNER = 16.dp
internal val SEGMENT_INNER_CORNER = 4.dp
