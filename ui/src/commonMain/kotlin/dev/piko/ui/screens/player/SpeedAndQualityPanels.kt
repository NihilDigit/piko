package dev.piko.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.AUTO_QUALITY
import dev.piko.shared.media.ORIGINAL_QUALITY
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.ui.components.ActionIconRow
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_speed_decrease
import dev.piko.ui.resources.player_speed_default
import dev.piko.ui.resources.player_speed_increase
import dev.piko.ui.resources.player_speed_reset
import dev.piko.ui.resources.subtitle_style_title
import dev.piko.ui.theme.tabular
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 单独打开倍速时的 M3E 精调面板（与 Bilby `ExpandedSpeedPanel` 对齐）：
 * 顶层 tonal 卡片承载大号等宽读数、±0.05x 步进微调按钮、一键复位 1x 与连续无挡点滑杆 + 刻度，
 * 下方配两行四列连体快捷倍速；兼顾整高侧栏居中排布与底部弹出面板自适应滚动。
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

/** 整块播放设置面板与超矮窗口内使用的紧凑倍速区：±0.05x 微调 + 滑杆 + 2×4 连体快捷网格。 */
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
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                row.forEachIndexed { index, preset ->
                    ToggleButton(
                        checked = abs(preset - speed) < 0.01f,
                        onCheckedChange = { onSpeedChange(preset) },
                        shapes = connectedToggleShapes(index, row.size),
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
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEachIndexed { index, option ->
                    val isSelected = option == currentQuality
                    ToggleButton(
                        checked = isSelected,
                        onCheckedChange = { onSelect(option) },
                        shapes = connectedToggleShapes(index, row.size),
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
 * 完整的播放设置：倍速、清晰度、画面比例、字幕样式，以及手势的进退步长与长按倍速。
 * 均采用连体按钮组排版，当前值写在小节标题右端。
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
            PlayerPanelSection(tr("文件", "Files", "ファイル", "파일", "檔案")) {
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
            PlayerPanelSection(tr("倍速", "Speed", "再生速度", "재생 속도", "倍速")) {
                CompactSpeedControls(speed = playbackSpeed, onSpeedChange = onSpeedChange)
            }
        }
        if (qualityOptions.size > 1) {
            val autoResolved = LocalAutoResolvedQuality.current
            PlayerPanelSection(tr("清晰度", "Quality", "画質", "화질", "畫質")) {
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
            PlayerPanelSection(tr("画面比例", "Aspect ratio", "アスペクト比", "화면 비율", "畫面比例")) {
                ConnectedChoiceRow(
                    options = PlayerAspectRatio.entries,
                    isSelected = { it == aspectRatio },
                    optionLabel = { it.label },
                    onSelect = onAspectRatioChange,
                )
            }
        }
        if (onOpenSubtitleStyle != null) {
            PlayerPanelSection(tr("字幕", "Subtitles", "字幕", "자막", "字幕")) {
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
            PlayerPanelSection(tr("进退步长", "Seek step", "シーク間隔", "탐색 간격", "快轉與倒轉間隔"), trailing = tr("$seekStepSeconds 秒", "$seekStepSeconds sec", "$seekStepSeconds 秒", "${seekStepSeconds}초", "$seekStepSeconds 秒")) {
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
            PlayerPanelSection(tr("长按倍速", "Hold speed", "長押し時の再生速度", "길게 누르기 재생 속도", "長按倍速"), trailing = formatSpeed(longPressSpeed)) {
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
