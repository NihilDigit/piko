package dev.piko.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.SubtitleBackgroundPreset
import dev.piko.shared.media.player.SubtitleFontPreset
import dev.piko.shared.media.player.SubtitleStylePrefs
import dev.piko.shared.media.player.imageSubtitleFormatLabel
import dev.piko.ui.components.MenuMotion
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.Res
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
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular
import kotlin.math.roundToInt

/**
 * 单一字幕台词预览渲染器：按 `720p` 基准高度换算字号、字体预设、统一底框颜色/不透明度与 `0..100%` 纵向锚点，
 * 与 [SubtitleStylePrefs.toMpvProperties] 下发给 `libmpv` 的参数严格同源，确保无台词帧或暂停态下仍能直观预览样式。
 */
@Composable
internal fun SubtitleCueRenderer(
    text: String,
    style: SubtitleStylePrefs,
    modifier: Modifier = Modifier,
) {
    val clean = remember(style) { style.sanitize() }
    val density = LocalDensity.current
    val eInk = LocalAppearance.current.isEInk
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var cueHeightPx by remember { mutableIntStateOf(0) }

    val fontFamily = when (clean.fontPreset) {
        SubtitleFontPreset.Default, SubtitleFontPreset.Custom -> FontFamily.SansSerif
        SubtitleFontPreset.Serif -> FontFamily.Serif
        SubtitleFontPreset.Monospace -> FontFamily.Monospace
    }
    val scaledFontSp = with(density) {
        val hPx = viewportHeightPx.takeIf { it > 0 } ?: 140.dp.roundToPx()
        val fontPx = (clean.fontSize / 720f * hPx).coerceIn(10.dp.toPx(), 42.dp.toPx())
        fontPx.toSp()
    }
    val bgAlpha = (clean.backgroundOpacity.coerceIn(10, 100) / 100f)
    val bgRgb = clean.backgroundColorRgb and 0x00FFFFFF
    val boxColor = Color(
        red = ((bgRgb ushr 16) and 0xFF) / 255f,
        green = ((bgRgb ushr 8) and 0xFF) / 255f,
        blue = (bgRgb and 0xFF) / 255f,
        alpha = bgAlpha,
    )
    val cueShadow = if (clean.showBackground) {
        null
    } else {
        Shadow(color = Color.Black, offset = Offset(0f, 1.5f), blurRadius = 5f)
    }

    Surface(
        shape = RoundedCornerShape(SEGMENT_OUTER_CORNER),
        color = Color(0xFF101318),
        border = BorderStroke(
            width = 1.dp,
            color = if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 7.5f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1C2533), Color(0xFF0E131B), Color(0xFF171E29)),
                    ),
                )
                .onSizeChanged { viewportHeightPx = it.height },
        ) {
            val verticalPaddingPx = with(density) { 8.dp.roundToPx() }
            val availableTravelPx = (viewportHeightPx - cueHeightPx - verticalPaddingPx * 2).coerceAtLeast(0)
            val yPx = verticalPaddingPx + (availableTravelPx * (clean.position / 100f)).roundToInt()

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, yPx) }
                    .padding(horizontal = 12.dp)
                    .onSizeChanged { cueHeightPx = it.height }
                    .then(
                        if (clean.showBackground) {
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(boxColor)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = TextStyle(
                        fontFamily = fontFamily,
                        fontSize = scaledFontSp,
                        lineHeight = scaledFontSp * 1.28f,
                        fontWeight = FontWeight.Medium,
                        shadow = cueShadow,
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 字幕外观实时调节面板：顶部挂载单一权威 [SubtitleCueRenderer] 实时画面预览，下方支持开关半透明深色底色、
 * 切换字体预设与已安装系统字体、调节字号与纵向位置，以及可选覆盖 ASS 特效样式。
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
        SubtitleCueRenderer(
            text = tr(
                "夜色穿过远山的雾，灯火在河面轻轻摇晃",
                "Night drifts through distant mountain mist, lights shimmering softly on the river",
                "夜霧が遠い山並みを抜け、川面に灯りが静かに揺れる",
                "밤안개가 먼 산을 지나 강물 위로 불빛이 잔잔히 흔들린다",
                "夜色穿過遠山的霧，燈火在河面輕輕搖晃",
            ),
            style = draft,
        )

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

        PlayerPanelSection(
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
        PlayerPanelSection(
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
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val canDecrease = draft.fontSize > SubtitleStylePrefs.MIN_FONT_SIZE
                    FilledTonalIconButton(
                        onClick = {
                            applyInstant(draft.copy(fontSize = draft.fontSize - SubtitleStylePrefs.FONT_SIZE_STEP))
                        },
                        enabled = canDecrease,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
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
                            .minimumInteractiveComponentSize()
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
        PlayerPanelSection(
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
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val canMoveUp = draft.position > SubtitleStylePrefs.MIN_POSITION
                        FilledTonalIconButton(
                            onClick = {
                                applyInstant(draft.copy(position = draft.position - SubtitleStylePrefs.POSITION_STEP))
                            },
                            enabled = canMoveUp,
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
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
                                .minimumInteractiveComponentSize()
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
                            .padding(horizontal = 48.dp),
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
                    shapes = ButtonDefaults.shapes(),
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
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.subtitle_style_background_color),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
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
                                    .size(48.dp)
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
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
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                                .minimumInteractiveComponentSize()
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
                                .minimumInteractiveComponentSize()
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

private val SUBTITLE_FONT_MENU_MAX_HEIGHT = 320.dp
