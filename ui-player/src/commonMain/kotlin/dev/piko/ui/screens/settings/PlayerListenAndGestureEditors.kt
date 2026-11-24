package dev.piko.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import dev.piko.shared.i18n.tr
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.CenterSlotAction
import dev.piko.shared.media.player.CenterSlotPosition
import dev.piko.shared.media.player.ListenCoverStyle
import dev.piko.shared.media.player.ListenLayoutConfig
import dev.piko.shared.media.player.ListenQuickChip
import dev.piko.shared.media.player.MAX_DOUBLE_TAP_ZONE_FRACTION
import dev.piko.shared.media.player.MAX_SEEK_SECONDS
import dev.piko.shared.media.player.MAX_VERTICAL_ZONE_FRACTION
import dev.piko.shared.media.player.MIN_DOUBLE_TAP_ZONE_FRACTION
import dev.piko.shared.media.player.MIN_SEEK_SECONDS
import dev.piko.shared.media.player.MIN_VERTICAL_ZONE_FRACTION
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.media.player.SwipeSeekSensitivity
import dev.piko.shared.media.player.VerticalSwipeAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.*
import dev.piko.ui.screens.player.DoubleTapSeekHint
import dev.piko.ui.screens.player.EpisodeSwipeHud
import dev.piko.ui.screens.player.ListenEditHooks
import dev.piko.ui.screens.player.ListenMainContent
import dev.piko.ui.screens.player.ListenQuickActionItem
import dev.piko.ui.screens.player.PlayerGesture
import dev.piko.ui.screens.player.PlayerLevelEdgeBar
import dev.piko.ui.screens.player.PlayerTheme
import dev.piko.ui.screens.player.SeekHudOverlay
import dev.piko.ui.screens.player.VerticalAdjust
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import kotlin.math.roundToInt

/**
 * 听音频预览画布：直接委托 [ListenMainContent] 单一权威组件渲染，消除重复的纵向排版与胶囊循环代码。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ListenPreviewCanvas(
    config: ListenLayoutConfig,
    selectedSlot: CenterSlotPosition,
    dropTargetSlot: CenterSlotPosition?,
    compactMaxHeight: Dp?,
    onSelectSlot: (CenterSlotPosition) -> Unit,
    onSlotBounds: (CenterSlotPosition, Rect) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val eInk = LocalAppearance.current.isEInk
    var previewPlaying by remember { mutableStateOf(true) }
    val rawAudioTitle = stringResource(Res.string.player_custom_sample_audio_title)
    val displayTitle = rawAudioTitle.substringBeforeLast('.').ifBlank { rawAudioTitle }
    val compact = compactMaxHeight != null && compactMaxHeight < 280.dp

    val speedLabel = stringResource(Res.string.listen_speed)
    val sleepLabel = stringResource(Res.string.listen_sleep_timer)
    val queueLabel = stringResource(Res.string.listen_queue_count_fmt, 12)
    val quickActions = config.visibleChips.map { chip ->
        when (chip) {
            ListenQuickChip.Speed -> ListenQuickActionItem(Icons.Filled.Speed, speedLabel, highlighted = false, onClick = {})
            ListenQuickChip.SleepTimer -> ListenQuickActionItem(Icons.Filled.Bedtime, sleepLabel, highlighted = false, onClick = {})
            ListenQuickChip.Queue -> ListenQuickActionItem(Icons.AutoMirrored.Outlined.QueueMusic, queueLabel, highlighted = false, onClick = {})
        }
    }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(
            shape = MaterialTheme.shapes.largeIncreased,
            color = if (eInk) Color.White else colors.surface,
            contentColor = colors.onSurface,
            border = BorderStroke(
                width = 1.dp,
                color = if (eInk) colors.outline else colors.outlineVariant.copy(alpha = 0.5f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (compactMaxHeight != null) Modifier.heightIn(max = compactMaxHeight) else Modifier),
        ) {
            ListenMainContent(
                title = displayTitle,
                coverUrl = "",
                listenLayout = config,
                isPlaying = previewPlaying,
                positionMillisProvider = { 88_000L },
                readoutPositionMillis = 88_000L,
                durationMillis = 255_000L,
                bufferedPositionMillisProvider = { 160_000L },
                onTogglePlayPause = { previewPlaying = !previewPlaying },
                hasPrevious = true,
                hasNext = true,
                quickActions = quickActions,
                compact = compact,
                editHooks = ListenEditHooks(
                    selectedSlot = selectedSlot,
                    dropTargetSlot = dropTargetSlot,
                    onSelectSlot = onSelectSlot,
                    onSlotBounds = onSlotBounds,
                ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListenCustomizationEditor(
    config: ListenLayoutConfig,
    seekSeconds: Int,
    selectedSlot: CenterSlotPosition,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    val eInk = LocalAppearance.current.isEInk
    val cardBorder = BorderStroke(
        width = 1.dp,
        color = if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
    val vinylLabel = stringResource(Res.string.player_custom_listen_vinyl)
    val staticLabel = stringResource(Res.string.player_custom_listen_static)
    val listenSlot = config.slot(selectedSlot)
    val mirrorLabel = stringResource(Res.string.player_custom_mirror_opposite)
    val clearLabel = stringResource(Res.string.player_custom_clear_slot)

    // 1. 当前选中侧槽位紧凑检查器
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (eInk) Color.White else MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = centerSlotLabel(selectedSlot),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TooltipIconButton(
                    icon = Icons.Outlined.SwapHoriz,
                    label = mirrorLabel,
                    onClick = { onUpdate { it.updateListen { l -> l.withSymmetricSlot(selectedSlot) } } },
                )
                if (listenSlot.action != CenterSlotAction.None) {
                    TooltipIconButton(
                        icon = Icons.Outlined.DeleteOutline,
                        label = clearLabel,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = {
                            onUpdate {
                                it.updateListen { l ->
                                    l.withSlot(selectedSlot, listenSlot.copy(action = CenterSlotAction.None))
                                }
                            }
                        },
                    )
                }
            }

            CenterSlotCompactControls(
                slot = listenSlot,
                symmetric = config.symmetricSideSlots,
                seekSeconds = seekSeconds,
                onSymmetricChange = { sym ->
                    onUpdate { it.updateListen { l -> l.withSymmetricSideSlots(sym) } }
                },
                onUpdateSlot = { next ->
                    onUpdate { it.updateListen { l -> l.withSlot(selectedSlot, next) } }
                },
                onSeekSecondsChange = { sec ->
                    val clamped = sec.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
                    val nextAction = when (listenSlot.action) {
                        is CenterSlotAction.SeekBackward -> CenterSlotAction.SeekBackward(clamped)
                        is CenterSlotAction.SeekForward -> CenterSlotAction.SeekForward(clamped)
                        else -> listenSlot.action
                    }
                    onUpdate { p ->
                        p.copy(seekButtonSeconds = clamped)
                            .updateListen { l -> l.withSlot(selectedSlot, listenSlot.copy(action = nextAction)) }
                    }
                },
            )
        }
    }

    // 2. 封面样式、播放按钮样式与底部快捷入口
    SettingsGroup(null) {
        SettingsRow(
            icon = Icons.Filled.Headphones,
            title = stringResource(Res.string.player_custom_listen_cover),
            supporting = null,
            below = {
                SettingsSegmentedChoice(
                    options = ListenCoverStyle.entries,
                    selected = config.coverStyle,
                    label = { style -> if (style == ListenCoverStyle.VinylDisc) vinylLabel else staticLabel },
                    onSelect = { style -> onUpdate { it.updateListen { l -> l.copy(coverStyle = style) } } },
                    fill = true,
                )
            },
        )
        SettingsSwitchRow(
            icon = Icons.Filled.PlayArrow,
            title = tr("半透明播放按钮", "Translucent play button", "半透明の再生ボタン", "반투명 재생 버튼", "半透明播放按鈕"),
            supporting = null,
            checked = config.translucentPlayButton,
            onCheckedChange = { enabled -> onUpdate { it.updateListen { l -> l.copy(translucentPlayButton = enabled) } } },
        )
        SettingsRow(
            icon = Icons.Outlined.Tune,
            title = stringResource(Res.string.player_custom_listen_chips),
            supporting = null,
            below = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ListenQuickChip.entries.forEach { chip ->
                        val active = config.hasChip(chip)
                        FilterChip(
                            selected = active,
                            onClick = { onUpdate { it.updateListen { l -> l.toggleChip(chip, !active) } } },
                            leadingIcon = if (active) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null,
                            label = { Text(listenChipLabel(chip)) },
                        )
                    }
                }
            },
        )
    }
}

/**
 * 手势预览画布：保留两侧纵划热区、双击分界虚线与顶部两角取消区示意，同时在 `PlayerTheme` 下直接挂载
 * `PlayerHud.kt` 中的真实 [PlayerLevelEdgeBar]、[DoubleTapSeekHint]、[SeekHudOverlay] 与 [EpisodeSwipeHud]。
 */
@Composable
internal fun GesturePreviewCanvas(
    prefs: PlayerLayoutPrefs,
    compactMaxHeight: Dp?,
) {
    val gestures = prefs.gestures
    val effectiveDoubleTapSeconds = if (prefs.syncDoubleTapWithSeekButton) {
        prefs.seekButtonSeconds
    } else {
        prefs.doubleTapSeekSeconds
    }
    val swipeSeekSampleDeltaMs = when (gestures.swipeSeekSensitivity) {
        SwipeSeekSensitivity.Low -> 20_000L
        SwipeSeekSensitivity.Standard -> 45_000L
        SwipeSeekSensitivity.High -> 90_000L
    }
    val eInk = LocalAppearance.current.isEInk
    val outlineColor = MaterialTheme.colorScheme.outline
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val baseMod = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    val hudScale = if (compactMaxHeight != null) 0.78f else 0.88f

    PlayerTheme {
        val primary = MaterialTheme.colorScheme.primary
        val tertiary = MaterialTheme.colorScheme.tertiary
        val error = MaterialTheme.colorScheme.error

        Surface(
            shape = MaterialTheme.shapes.largeIncreased,
            color = Color(0xFF101318),
            border = BorderStroke(
                width = 1.dp,
                color = if (eInk) outlineColor else outlineVariant.copy(alpha = 0.45f),
            ),
            modifier = if (compactMaxHeight != null) baseMod.heightIn(max = compactMaxHeight) else baseMod,
        ) {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    val vWidth = size.width * gestures.verticalZoneFraction
                    val dtWidth = size.width * gestures.doubleTapZoneFraction
                    if (gestures.swipeBrightnessVolumeEnabled && gestures.leftVerticalSwipe != VerticalSwipeAction.None) {
                        drawRoundRect(
                            color = primary.copy(alpha = 0.20f),
                            topLeft = Offset.Zero,
                            size = Size(vWidth, size.height),
                            cornerRadius = CornerRadius(12f, 12f),
                        )
                    }
                    if (gestures.swipeBrightnessVolumeEnabled && gestures.rightVerticalSwipe != VerticalSwipeAction.None) {
                        drawRoundRect(
                            color = tertiary.copy(alpha = 0.20f),
                            topLeft = Offset(size.width - vWidth, 0f),
                            size = Size(vWidth, size.height),
                            cornerRadius = CornerRadius(12f, 12f),
                        )
                    }
                    if (gestures.doubleTapSeekEnabled) {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                        drawLine(
                            color = Color.White.copy(alpha = 0.55f),
                            start = Offset(dtWidth, 0f),
                            end = Offset(dtWidth, size.height),
                            strokeWidth = 2f,
                            pathEffect = dash,
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.55f),
                            start = Offset(size.width - dtWidth, 0f),
                            end = Offset(size.width - dtWidth, size.height),
                            strokeWidth = 2f,
                            pathEffect = dash,
                        )
                    }
                    if (gestures.swipeSeekEnabled && gestures.swipeSeekCancelZone) {
                        drawRect(
                            color = error.copy(alpha = 0.20f),
                            topLeft = Offset.Zero,
                            size = Size(size.width, size.height * 0.22f),
                        )
                    }
                }

                // 左侧真实贴边电平指示器（映射左侧或右侧纵划：触屏纵划在真实播放时停靠对侧，预览直接展示在对应热区内便于对照）
                val showIndicatorBg = prefs.videoLandscape.showIndicatorBackground
                val leftAdjust = if (gestures.swipeBrightnessVolumeEnabled) {
                    gestures.leftVerticalSwipe.toVerticalAdjustOrNull()?.let { kind ->
                        PlayerGesture.Adjust(kind = kind, fraction = 0.68f, dockEnd = false)
                    }
                } else {
                    null
                }
                PlayerLevelEdgeBar(
                    adjust = leftAdjust,
                    dockEnd = false,
                    large = false,
                    showBackground = showIndicatorBg,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 10.dp)
                        .graphicsLayer {
                            scaleX = hudScale
                            scaleY = hudScale
                        },
                )

                // 右侧真实贴边电平指示器
                val rightAdjust = if (gestures.swipeBrightnessVolumeEnabled) {
                    gestures.rightVerticalSwipe.toVerticalAdjustOrNull()?.let { kind ->
                        PlayerGesture.Adjust(kind = kind, fraction = 0.72f, dockEnd = true)
                    }
                } else {
                    null
                }
                PlayerLevelEdgeBar(
                    adjust = rightAdjust,
                    dockEnd = true,
                    large = false,
                    showBackground = showIndicatorBg,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 10.dp)
                        .graphicsLayer {
                            scaleX = hudScale
                            scaleY = hudScale
                        },
                )

                // 双击进退真实贴边胶囊（实时反映当前生效秒数）
                DoubleTapSeekHint(
                    visible = gestures.doubleTapSeekEnabled,
                    forward = true,
                    seconds = effectiveDoubleTapSeconds,
                    showBackground = showIndicatorBg,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 10.dp)
                        .graphicsLayer {
                            scaleX = hudScale
                            scaleY = hudScale
                        },
                )

                // 中央横划进退真实胶囊（随灵敏度切换展示不同目标步进）
                SeekHudOverlay(
                    seek = if (gestures.swipeSeekEnabled) {
                        PlayerGesture.Seek(
                            startPositionMillis = 754_000L,
                            deltaMillis = swipeSeekSampleDeltaMs,
                            cancelled = false,
                        )
                    } else {
                        null
                    },
                    durationMillis = 2_700_000L,
                    showBackground = showIndicatorBg,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            scaleX = hudScale
                            scaleY = hudScale
                        },
                )

                // 纵划切集真实胶囊（任一侧纵划配置为切集时展示）
                val hasEpisodeVerticalSwipe = gestures.swipeBrightnessVolumeEnabled && (
                    gestures.leftVerticalSwipe == VerticalSwipeAction.Episode ||
                        gestures.rightVerticalSwipe == VerticalSwipeAction.Episode
                    )
                EpisodeSwipeHud(
                    visible = hasEpisodeVerticalSwipe,
                    direction = 1,
                    armed = true,
                    available = true,
                    showBackground = showIndicatorBg,
                    modifier = Modifier
                        .align(if (gestures.swipeSeekEnabled) Alignment.BottomCenter else Alignment.Center)
                        .padding(bottom = if (gestures.swipeSeekEnabled) 10.dp else 0.dp)
                        .graphicsLayer {
                            scaleX = hudScale
                            scaleY = hudScale
                        },
                )
            }
        }
    }
}

private fun VerticalSwipeAction.toVerticalAdjustOrNull(): VerticalAdjust? = when (this) {
    VerticalSwipeAction.Brightness -> VerticalAdjust.Brightness
    VerticalSwipeAction.Volume -> VerticalAdjust.Volume
    VerticalSwipeAction.Episode, VerticalSwipeAction.None -> null
}

@Composable
internal fun GestureCustomizationEditor(
    prefs: PlayerLayoutPrefs,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    val g = prefs.gestures
    val sensitivityLabels = mapOf(
        SwipeSeekSensitivity.Low to stringResource(Res.string.player_custom_gesture_sens_low),
        SwipeSeekSensitivity.Standard to stringResource(Res.string.player_custom_gesture_sens_standard),
        SwipeSeekSensitivity.High to stringResource(Res.string.player_custom_gesture_sens_high),
    )
    val verticalActionLabels = mapOf(
        VerticalSwipeAction.Brightness to stringResource(Res.string.player_custom_gesture_act_brightness),
        VerticalSwipeAction.Volume to stringResource(Res.string.player_custom_gesture_act_volume),
        VerticalSwipeAction.Episode to stringResource(Res.string.player_custom_gesture_act_episode),
        VerticalSwipeAction.None to stringResource(Res.string.player_custom_gesture_act_none),
    )
    // 1. 进度手势（双击与横划）
    SettingsGroup(
        tr("进度手势", "Seek gestures", "シークジェスチャー", "탐색 제스처", "進度手勢"),
    ) {
        SettingsSwitchRow(
            icon = Icons.Filled.FastForward,
            title = stringResource(Res.string.player_custom_gesture_double_tap),
            supporting = null,
            checked = g.doubleTapSeekEnabled,
            onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(doubleTapSeekEnabled = enabled) } } },
        )
        DependentRow(visible = g.doubleTapSeekEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsSwitchRow(
                    icon = Icons.Outlined.Timer,
                    title = stringResource(Res.string.player_custom_sync_double_tap),
                    supporting = null,
                    checked = prefs.syncDoubleTapWithSeekButton,
                    onCheckedChange = { synced -> onUpdate { it.copy(syncDoubleTapWithSeekButton = synced) } },
                )
                DependentRow(visible = !prefs.syncDoubleTapWithSeekButton) {
                    SettingsCard {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            SeekSecondsDropdownRow(
                                title = stringResource(Res.string.player_custom_double_tap_seconds),
                                seconds = prefs.doubleTapSeekSeconds,
                                onSecondsChange = { sec ->
                                    onUpdate { it.copy(doubleTapSeekSeconds = sec.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)).sanitize() }
                                },
                            )
                        }
                    }
                }
                SettingsRow(
                    icon = Icons.Outlined.SwapHoriz,
                    title = stringResource(Res.string.player_custom_gesture_double_tap_zone),
                    supporting = "${(g.doubleTapZoneFraction * 100).roundToInt()}%",
                    below = {
                        Slider(
                            value = g.doubleTapZoneFraction,
                            onValueChange = { value -> onUpdate { p -> p.updateGestures { it.copy(doubleTapZoneFraction = value) } } },
                            valueRange = MIN_DOUBLE_TAP_ZONE_FRACTION..MAX_DOUBLE_TAP_ZONE_FRACTION,
                        )
                    },
                )
            }
        }
        SettingsCard {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                SeekSecondsDropdownRow(
                    title = stringResource(Res.string.player_custom_seek_seconds),
                    seconds = prefs.seekButtonSeconds,
                    onSecondsChange = { sec ->
                        onUpdate { it.copy(seekButtonSeconds = sec.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)).sanitize() }
                    },
                )
            }
        }
        SettingsSwitchRow(
            icon = Icons.Outlined.SwapHoriz,
            title = stringResource(Res.string.player_custom_gesture_swipe_seek),
            supporting = null,
            checked = g.swipeSeekEnabled,
            onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(swipeSeekEnabled = enabled) } } },
        )
        DependentRow(visible = g.swipeSeekEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsSwitchRow(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(Res.string.player_custom_gesture_cancel_zone),
                    supporting = null,
                    checked = g.swipeSeekCancelZone,
                    onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(swipeSeekCancelZone = enabled) } } },
                )
                SettingsRow(
                    icon = Icons.Outlined.Speed,
                    title = stringResource(Res.string.player_custom_gesture_sensitivity),
                    supporting = null,
                    below = {
                        SettingsSegmentedChoice(
                            options = SwipeSeekSensitivity.entries,
                            selected = g.swipeSeekSensitivity,
                            label = { sensitivityLabels.getValue(it) },
                            onSelect = { sens -> onUpdate { p -> p.updateGestures { it.copy(swipeSeekSensitivity = sens) } } },
                            fill = true,
                        )
                    },
                )
            }
        }
    }

    // 2. 倍速手势
    SettingsGroup(
        tr("倍速手势", "Speed gestures", "速度ジェスチャー", "속도 제스처", "倍速手勢"),
    ) {
        SettingsSwitchRow(
            icon = Icons.Outlined.Speed,
            title = stringResource(Res.string.player_custom_gesture_long_press),
            supporting = null,
            checked = g.longPressSpeedEnabled,
            onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(longPressSpeedEnabled = enabled) } } },
        )
        DependentRow(visible = g.longPressSpeedEnabled) {
            SettingsSwitchRow(
                icon = Icons.Filled.Speed,
                title = stringResource(Res.string.player_custom_gesture_speed_dial),
                supporting = null,
                checked = g.longPressSpeedDialEnabled,
                onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(longPressSpeedDialEnabled = enabled) } } },
            )
        }
    }

    // 3. 纵划手势（亮度、音量与切集）
    SettingsGroup(
        tr("纵划手势", "Vertical swipe gestures", "上下スワイプ", "세로 스와이프", "縱劃手勢"),
    ) {
        SettingsSwitchRow(
            icon = Icons.Filled.SwapVert,
            title = stringResource(Res.string.player_custom_gesture_vertical),
            supporting = null,
            checked = g.swipeBrightnessVolumeEnabled,
            onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(swipeBrightnessVolumeEnabled = enabled) } } },
        )
        DependentRow(visible = g.swipeBrightnessVolumeEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsRow(
                    icon = Icons.Filled.SwapVert,
                    title = stringResource(Res.string.player_custom_gesture_left_swipe),
                    supporting = null,
                    below = {
                        SettingsSegmentedChoice(
                            options = VerticalSwipeAction.entries,
                            selected = g.leftVerticalSwipe,
                            label = { verticalActionLabels.getValue(it) },
                            onSelect = { act -> onUpdate { p -> p.updateGestures { it.copy(leftVerticalSwipe = act) } } },
                            fill = true,
                        )
                    },
                )
                SettingsRow(
                    icon = Icons.Filled.SwapVert,
                    title = stringResource(Res.string.player_custom_gesture_right_swipe),
                    supporting = null,
                    below = {
                        SettingsSegmentedChoice(
                            options = VerticalSwipeAction.entries,
                            selected = g.rightVerticalSwipe,
                            label = { verticalActionLabels.getValue(it) },
                            onSelect = { act -> onUpdate { p -> p.updateGestures { it.copy(rightVerticalSwipe = act) } } },
                            fill = true,
                        )
                    },
                )
                SettingsRow(
                    icon = Icons.Outlined.SwapHoriz,
                    title = stringResource(Res.string.player_custom_gesture_vertical_zone),
                    supporting = "${(g.verticalZoneFraction * 100).roundToInt()}%",
                    below = {
                        Slider(
                            value = g.verticalZoneFraction,
                            onValueChange = { value -> onUpdate { p -> p.updateGestures { it.copy(verticalZoneFraction = value) } } },
                            valueRange = MIN_VERTICAL_ZONE_FRACTION..MAX_VERTICAL_ZONE_FRACTION,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun listenChipLabel(chip: ListenQuickChip): String = when (chip) {
    ListenQuickChip.Speed -> stringResource(Res.string.player_custom_listen_chip_speed)
    ListenQuickChip.SleepTimer -> stringResource(Res.string.player_custom_listen_chip_sleep)
    ListenQuickChip.Queue -> stringResource(Res.string.player_custom_listen_chip_queue)
}
