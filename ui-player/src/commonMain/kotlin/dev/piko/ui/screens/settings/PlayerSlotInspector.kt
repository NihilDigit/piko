package dev.piko.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.CenterSlotAction
import dev.piko.shared.media.player.CenterSlotConfig
import dev.piko.shared.media.player.CenterSlotPosition
import dev.piko.shared.media.player.CenterSlotScale
import dev.piko.shared.media.player.MAX_SEEK_SECONDS
import dev.piko.shared.media.player.MIN_SEEK_SECONDS
import dev.piko.shared.media.player.PlayerBarSlot
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerFormFactorLayout
import dev.piko.shared.media.player.PlayerLayoutMode
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.media.player.PlayerLayoutPreset
import dev.piko.shared.media.player.PlayerPanelItem
import dev.piko.shared.media.player.SEEK_SECONDS_PRESETS
import dev.piko.shared.i18n.tr
import dev.piko.ui.components.MenuMotion
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.PikoTextField
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.*
import dev.piko.ui.screens.player.handCursor
import dev.piko.ui.screens.player.playerControlIcon
import dev.piko.ui.screens.player.playerControlLabel
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular

/** 紧凑的预设下拉 + 触屏中央控件开关条（避免铺满一排高权重 FilterChip）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PresetAndTouchStrip(
    prefs: PlayerLayoutPrefs,
    showTouchCenterToggle: Boolean,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    val eInk = LocalAppearance.current.isEInk
    val matchedPreset = PlayerLayoutPreset.entries.firstOrNull { preset ->
        val target = PlayerLayoutPrefs.preset(preset)
        prefs.videoLandscape == target.videoLandscape &&
            prefs.videoPortrait == target.videoPortrait &&
            prefs.clipsLandscape == target.clipsLandscape &&
            prefs.clipsPortrait == target.clipsPortrait
    }
    val presetOptions = PlayerLayoutPreset.entries.map { it to presetLabel(it) }
    val selectedPresetLabel = matchedPreset?.let { presetLabel(it) }
        ?: stringResource(Res.string.player_custom_custom_option).trimEnd('…')

    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (eInk) Color.White else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(Res.string.player_custom_presets),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                InspectorDropdownButton(
                    selectedLabel = selectedPresetLabel,
                    options = presetOptions,
                    isSelected = { it == matchedPreset },
                    onSelect = { preset -> onUpdate { PlayerLayoutPrefs.preset(preset) } },
                )
            }

            if (showTouchCenterToggle) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .clip(CircleShape)
                            .toggleable(
                                value = prefs.translucentPlayButton,
                                role = Role.Switch,
                                onValueChange = { enabled ->
                                    onUpdate { it.copy(translucentPlayButton = enabled) }
                                },
                            )
                            .handCursor()
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = tr("半透明播放按钮", "Translucent play button", "半透明の再生ボタン", "반투명 재생 버튼", "半透明播放按鈕"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Switch(
                            checked = prefs.translucentPlayButton,
                            onCheckedChange = null,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .clip(CircleShape)
                            .toggleable(
                                value = prefs.showCenterControlsOnTouch,
                                role = Role.Switch,
                                onValueChange = { enabled ->
                                    onUpdate { it.copy(showCenterControlsOnTouch = enabled) }
                                },
                            )
                            .handCursor()
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.player_custom_show_center_on_touch),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Switch(
                            checked = prefs.showCenterControlsOnTouch,
                            onCheckedChange = null,
                        )
                    }
                }
            }
        }
    }
}

private fun supportsBackgroundToggle(id: PlayerControlId, mode: PlayerLayoutMode): Boolean = when (id) {
    PlayerControlId.TimeLabel, PlayerControlId.PlayPause -> false
    PlayerControlId.Title -> mode.isClips
    else -> true
}

/**
 * 紧凑选中项属性检查器：
 * - 单行头部 + 图标按钮条（左/上移、右/下移、换栏、移除、关闭，保持标准 48dp 无障碍触控热区）；
 * - 动作、尺寸（CenterSlotScale）与步长秒数（含「自定义…」输入弹窗）均使用 `PikoDropdownMenu`。
 */
@Composable
internal fun SelectionInspectorCard(
    mode: PlayerLayoutMode,
    layout: PlayerFormFactorLayout,
    prefs: PlayerLayoutPrefs,
    selection: SlotSelection?,
    onSelect: (SlotSelection?) -> Unit,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    val eInk = LocalAppearance.current.isEInk
    val cardBorder = BorderStroke(
        width = 1.dp,
        color = if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )

    if (selection == null) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (eInk) Color.White else MaterialTheme.colorScheme.surfaceContainerLow,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(Res.string.player_custom_inspector_drag_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val closeLabel = stringResource(Res.string.common_close)

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
            when (selection) {
                is SlotSelection.CenterSlot -> {
                    val pos = selection.position
                    if (pos == CenterSlotPosition.Center) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = centerSlotLabel(CenterSlotPosition.Center),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TooltipIconButton(
                                icon = Icons.Filled.Close,
                                label = closeLabel,
                                onClick = { onSelect(null) },
                            )
                        }
                        CompactSwitchRow(
                            title = tr("在画面中央显示", "Show in center", "画面中央に表示", "화면 중앙에 표시", "在畫面中央顯示"),
                            checked = layout.center.showCenterPlay,
                            onCheckedChange = { show ->
                                onUpdate { p -> p.updateLayout(mode) { it.withCenterPlay(show, mode) } }
                            },
                        )
                        CompactSwitchRow(
                            title = stringResource(Res.string.player_custom_symmetric_sides),
                            checked = layout.center.symmetricSideSlots,
                            onCheckedChange = { sym ->
                                onUpdate { p -> p.updateLayout(mode) { it.withSymmetricSideSlots(sym, mode) } }
                            },
                        )
                    } else {
                        val slot = layout.center.slot(pos)
                        val mirrorLabel = stringResource(Res.string.player_custom_mirror_opposite)
                        val clearLabel = stringResource(Res.string.player_custom_clear_slot)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = centerSlotLabel(pos),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TooltipIconButton(
                                icon = Icons.Outlined.SwapHoriz,
                                label = mirrorLabel,
                                onClick = {
                                    onUpdate { p -> p.updateLayout(mode) { it.withSymmetricCenterSlot(pos, mode) } }
                                },
                            )
                            if (slot.action != CenterSlotAction.None) {
                                TooltipIconButton(
                                    icon = Icons.Outlined.DeleteOutline,
                                    label = clearLabel,
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = {
                                        onUpdate { p ->
                                            p.updateLayout(mode) {
                                                it.withCenterSlot(pos, slot.copy(action = CenterSlotAction.None), mode)
                                            }
                                        }
                                    },
                                )
                            }
                            TooltipIconButton(
                                icon = Icons.Filled.Close,
                                label = closeLabel,
                                onClick = { onSelect(null) },
                            )
                        }

                        CenterSlotCompactControls(
                            slot = slot,
                            symmetric = layout.center.symmetricSideSlots,
                            seekSeconds = prefs.seekButtonSeconds,
                            onSymmetricChange = { sym ->
                                onUpdate { p -> p.updateLayout(mode) { it.withSymmetricSideSlots(sym, mode) } }
                            },
                            onUpdateSlot = { next ->
                                onUpdate { p -> p.updateLayout(mode) { it.withCenterSlot(pos, next, mode) } }
                            },
                            onSeekSecondsChange = { sec ->
                                val clamped = sec.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
                                val nextAction = when (slot.action) {
                                    is CenterSlotAction.SeekBackward -> CenterSlotAction.SeekBackward(clamped)
                                    is CenterSlotAction.SeekForward -> CenterSlotAction.SeekForward(clamped)
                                    else -> slot.action
                                }
                                onUpdate { p ->
                                    p.copy(seekButtonSeconds = clamped)
                                        .updateLayout(mode) { it.withCenterSlot(pos, slot.copy(action = nextAction), mode) }
                                }
                            },
                        )
                    }
                }

                SlotSelection.PortraitFullscreenPrompt -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Fullscreen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(Res.string.player_custom_portrait_fullscreen_prompt),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TooltipIconButton(
                            icon = Icons.Filled.Close,
                            label = closeLabel,
                            onClick = { onSelect(null) },
                        )
                    }
                    CompactSwitchRow(
                        title = tr("显示悬浮按钮", "Show floating prompt", "フローティングボタンを表示", "플로팅 버튼 표시", "顯示懸浮按鈕"),
                        subtitle = stringResource(Res.string.player_custom_portrait_fullscreen_prompt_desc),
                        checked = prefs.showPortraitFullscreenPrompt,
                        onCheckedChange = { show ->
                            onUpdate { it.copy(showPortraitFullscreenPrompt = show) }
                        },
                    )
                }

                is SlotSelection.PanelItem -> {
                    val bar = selection.bar
                    val list = layout.itemsFor(bar)
                    val item = list.getOrNull(selection.index)
                    if (item == null) {
                        Text(
                            text = stringResource(Res.string.player_custom_inspector_drag_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val itemTitle = when (item) {
                            PlayerPanelItem.Spacer -> stringResource(Res.string.player_custom_spacer)
                            is PlayerPanelItem.Control -> playerControlLabel(item.id)
                        }
                        val itemIcon = when (item) {
                            PlayerPanelItem.Spacer -> Icons.Filled.SpaceBar
                            is PlayerPanelItem.Control -> playerControlIcon(item.id, isClips = mode.isClips)
                        }
                        val vertical = isBarVerticalRail(mode, bar)
                        val canMoveBack = selection.index > 0 && !(
                            mode.isVideo &&
                                bar == PlayerBarSlot.Top &&
                                selection.index == 1 &&
                                (list.firstOrNull() as? PlayerPanelItem.Control)?.id == PlayerControlId.Back
                            )
                        val canMoveForward = selection.index < list.lastIndex && !(
                            mode.isVideo &&
                                bar == PlayerBarSlot.Top &&
                                selection.index == 0 &&
                                (item as? PlayerPanelItem.Control)?.id == PlayerControlId.Back
                            )
                        val canSwitchBar = layout.canMoveToOtherBar(bar, selection.index, mode)
                        val canRemove = layout.canRemoveBarItem(bar, selection.index, mode)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = itemIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = itemTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (!canRemove) {
                                        stringResource(Res.string.player_custom_palette_drop_required)
                                    } else {
                                        barSlotLabel(mode, bar)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TooltipIconButton(
                                icon = if (vertical) Icons.Filled.ArrowUpward else Icons.AutoMirrored.Filled.ArrowBack,
                                label = stringResource(
                                    if (vertical) Res.string.player_custom_move_up else Res.string.player_custom_move_left,
                                ),
                                enabled = canMoveBack,
                                onClick = {
                                    val target = selection.index - 1
                                    onUpdate { p -> p.updateLayout(mode) { it.moveBarItem(bar, selection.index, target, mode) } }
                                    onSelect(SlotSelection.PanelItem(bar, target))
                                },
                            )
                            TooltipIconButton(
                                icon = if (vertical) Icons.Filled.ArrowDownward else Icons.AutoMirrored.Filled.ArrowForward,
                                label = stringResource(
                                    if (vertical) Res.string.player_custom_move_down else Res.string.player_custom_move_right,
                                ),
                                enabled = canMoveForward,
                                onClick = {
                                    val target = selection.index + 1
                                    onUpdate { p -> p.updateLayout(mode) { it.moveBarItem(bar, selection.index, target, mode) } }
                                    onSelect(SlotSelection.PanelItem(bar, target))
                                },
                            )
                            if (canSwitchBar) {
                                TooltipIconButton(
                                    icon = Icons.Filled.SwapVert,
                                    label = stringResource(Res.string.player_custom_move_other_bar),
                                    onClick = {
                                        val otherBar = bar.other()
                                        val targetIndex = layout.itemsFor(otherBar).size
                                        onUpdate { p ->
                                            p.updateLayout(mode) { it.moveBarItemToOtherBar(bar, selection.index, mode) }
                                        }
                                        onSelect(SlotSelection.PanelItem(otherBar, targetIndex))
                                    },
                                )
                            }
                            if (canRemove) {
                                TooltipIconButton(
                                    icon = Icons.Outlined.DeleteOutline,
                                    label = stringResource(Res.string.player_custom_remove),
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = {
                                        onUpdate { p -> p.updateLayout(mode) { it.removeBarItem(bar, selection.index, mode) } }
                                        onSelect(null)
                                    },
                                )
                            }
                            TooltipIconButton(
                                icon = Icons.Filled.Close,
                                label = closeLabel,
                                onClick = { onSelect(null) },
                            )
                        }

                        if (item is PlayerPanelItem.Control) {
                            if (item.id == PlayerControlId.SystemStatus) {
                                val sb = prefs.statusBar
                                CompactSwitchRow(
                                    title = stringResource(Res.string.player_custom_status_clock),
                                    checked = sb.showClock,
                                    onCheckedChange = { on -> onUpdate { p -> p.updateStatusBar { it.copy(showClock = on) } } },
                                )
                                CompactSwitchRow(
                                    title = stringResource(Res.string.player_custom_status_network),
                                    checked = sb.showNetwork,
                                    onCheckedChange = { on -> onUpdate { p -> p.updateStatusBar { it.copy(showNetwork = on) } } },
                                )
                                CompactSwitchRow(
                                    title = stringResource(Res.string.player_custom_status_battery),
                                    checked = sb.showBattery,
                                    onCheckedChange = { on -> onUpdate { p -> p.updateStatusBar { it.copy(showBattery = on) } } },
                                )
                                CompactSwitchRow(
                                    title = stringResource(Res.string.player_custom_status_icons),
                                    checked = sb.showIcons,
                                    onCheckedChange = { on -> onUpdate { p -> p.updateStatusBar { it.copy(showIcons = on) } } },
                                )
                            }
                            if (item.id == PlayerControlId.SeekBackward || item.id == PlayerControlId.SeekForward) {
                                SeekSecondsDropdownRow(
                                    title = stringResource(Res.string.player_custom_seek_seconds),
                                    seconds = prefs.seekButtonSeconds,
                                    onSecondsChange = { sec ->
                                        onUpdate { it.copy(seekButtonSeconds = sec.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)).sanitize() }
                                    },
                                )
                            }
                            if (supportsBackgroundToggle(item.id, mode)) {
                                CompactSwitchRow(
                                    title = stringResource(Res.string.player_custom_show_bg),
                                    checked = item.showBackground,
                                    onCheckedChange = { showBg ->
                                        onUpdate { p ->
                                            p.updateLayout(mode) { it.withBarItemBackground(bar, selection.index, showBg, mode) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CenterSlotCompactControls(
    slot: CenterSlotConfig,
    symmetric: Boolean,
    seekSeconds: Int,
    onSymmetricChange: (Boolean) -> Unit,
    onUpdateSlot: (CenterSlotConfig) -> Unit,
    onSeekSecondsChange: (Int) -> Unit,
) {
    val activeSeekSeconds = slot.action.seekSecondsOrNull() ?: seekSeconds
    val actionOptions = listOf(
        CenterSlotAction.None to stringResource(Res.string.player_custom_slot_action_none),
        CenterSlotAction.Previous to stringResource(Res.string.player_custom_slot_action_prev),
        CenterSlotAction.Next to stringResource(Res.string.player_custom_slot_action_next),
        CenterSlotAction.SeekBackward(activeSeekSeconds) to stringResource(Res.string.player_custom_slot_action_rewind),
        CenterSlotAction.SeekForward(activeSeekSeconds) to stringResource(Res.string.player_custom_slot_action_forward),
    )
    InspectorDropdownRow(
        label = stringResource(Res.string.player_custom_action_label),
        selectedLabel = centerActionLabel(slot.action),
        options = actionOptions,
        isSelected = { candidate -> slot.action::class == candidate::class },
        onSelect = { candidate -> onUpdateSlot(slot.copy(action = candidate)) },
    )

    if (slot.action is CenterSlotAction.SeekBackward || slot.action is CenterSlotAction.SeekForward) {
        SeekSecondsDropdownRow(
            title = stringResource(Res.string.player_custom_seek_seconds),
            seconds = activeSeekSeconds,
            onSecondsChange = onSeekSecondsChange,
        )
    }

    val scaleOptions = CenterSlotScale.entries.map { it to scaleLabel(it) }
    InspectorDropdownRow(
        label = stringResource(Res.string.player_custom_scale),
        selectedLabel = scaleLabel(slot.scale),
        options = scaleOptions,
        isSelected = { it == slot.scale },
        onSelect = { scale -> onUpdateSlot(slot.copy(scale = scale)) },
    )

    CompactSwitchRow(
        title = stringResource(Res.string.player_custom_symmetric_sides),
        checked = symmetric,
        onCheckedChange = onSymmetricChange,
    )

    if (slot.action != CenterSlotAction.None) {
        CompactSwitchRow(
            title = stringResource(Res.string.player_custom_show_bg),
            checked = slot.showBackground,
            onCheckedChange = { showBg -> onUpdateSlot(slot.copy(showBackground = showBg)) },
        )
    }
}

@Composable
internal fun CompactSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .handCursor()
            .padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun <T> InspectorDropdownRow(
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    valueTabular: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        InspectorDropdownButton(
            selectedLabel = selectedLabel,
            options = options,
            isSelected = isSelected,
            onSelect = onSelect,
            valueTabular = valueTabular,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> InspectorDropdownButton(
    selectedLabel: String,
    options: List<Pair<T, String>>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    valueTabular: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(start = 10.dp, end = 6.dp),
            modifier = Modifier.defaultMinSize(minWidth = 1.dp).handCursor(),
        ) {
            Text(
                text = selectedLabel,
                style = if (valueTabular) MaterialTheme.typography.labelLarge.tabular else MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
            )
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        MenuMotion {
            PikoDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEachIndexed { index, (value, optionLabel) ->
                    val selected = isSelected(value)
                    DropdownMenuItem(
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                        text = {
                            Text(
                                text = optionLabel,
                                style = if (valueTabular) MaterialTheme.typography.bodyLarge.tabular else MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            )
                        },
                        shape = menuItemShape(index, options.size),
                        trailingContent = if (selected) ({
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }) else null,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SeekSecondsDropdownRow(
    title: String,
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
) {
    var showCustomDialog by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf(seconds.toString()) }
    val customOptionSentinel = -1
    val options = SEEK_SECONDS_PRESETS.map { preset ->
        preset to stringResource(Res.string.player_custom_seconds_fmt, preset)
    } + (customOptionSentinel to stringResource(Res.string.player_custom_custom_option))

    InspectorDropdownRow(
        label = title,
        selectedLabel = stringResource(Res.string.player_custom_seconds_fmt, seconds),
        options = options,
        isSelected = { opt ->
            if (opt == customOptionSentinel) seconds !in SEEK_SECONDS_PRESETS else seconds == opt
        },
        onSelect = { opt ->
            if (opt == customOptionSentinel) {
                customInput = seconds.toString()
                showCustomDialog = true
            } else {
                onSecondsChange(opt)
            }
        },
        valueTabular = true,
    )

    if (showCustomDialog) {
        val parsed = customInput.toIntOrNull()
        val valid = parsed != null && parsed in MIN_SEEK_SECONDS..MAX_SEEK_SECONDS
        SettingsInputDialog(
            icon = Icons.Outlined.Timer,
            title = title,
            onSave = {
                if (parsed != null && parsed in MIN_SEEK_SECONDS..MAX_SEEK_SECONDS) {
                    onSecondsChange(parsed)
                }
                showCustomDialog = false
            },
            saveEnabled = valid,
            onDismiss = { showCustomDialog = false },
        ) {
            PikoTextField(
                value = customInput,
                onValueChange = { text ->
                    customInput = text.filter { it.isDigit() }.take(3)
                },
                label = title,
                supportingText = stringResource(
                    Res.string.player_custom_seek_seconds_hint,
                    MIN_SEEK_SECONDS,
                    MAX_SEEK_SECONDS,
                ),
                isError = customInput.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}

@Composable
internal fun barSlotLabel(mode: PlayerLayoutMode, bar: PlayerBarSlot): String = when {
    bar == PlayerBarSlot.Top -> stringResource(Res.string.player_custom_section_top_bar)
    mode == PlayerLayoutMode.ClipsPortrait -> stringResource(Res.string.player_custom_right_rail)
    else -> stringResource(Res.string.player_custom_section_bottom_bar)
}

@Composable
internal fun presetLabel(preset: PlayerLayoutPreset): String = when (preset) {
    PlayerLayoutPreset.Default -> stringResource(Res.string.player_custom_preset_default)
    PlayerLayoutPreset.Minimal -> stringResource(Res.string.player_custom_preset_minimal)
    PlayerLayoutPreset.Podcast -> stringResource(Res.string.player_custom_preset_podcast)
}

@Composable
internal fun centerSlotLabel(pos: CenterSlotPosition): String = when (pos) {
    CenterSlotPosition.OuterLeft -> stringResource(Res.string.player_custom_slot_outer_left)
    CenterSlotPosition.InnerLeft -> stringResource(Res.string.player_custom_slot_inner_left)
    CenterSlotPosition.Center -> stringResource(Res.string.player_custom_slot_center)
    CenterSlotPosition.InnerRight -> stringResource(Res.string.player_custom_slot_inner_right)
    CenterSlotPosition.OuterRight -> stringResource(Res.string.player_custom_slot_outer_right)
}

@Composable
internal fun centerActionLabel(action: CenterSlotAction): String = when (action) {
    CenterSlotAction.None -> stringResource(Res.string.player_custom_slot_action_none)
    CenterSlotAction.Previous -> stringResource(Res.string.player_custom_slot_action_prev)
    CenterSlotAction.Next -> stringResource(Res.string.player_custom_slot_action_next)
    is CenterSlotAction.SeekBackward -> stringResource(Res.string.player_custom_slot_action_rewind)
    is CenterSlotAction.SeekForward -> stringResource(Res.string.player_custom_slot_action_forward)
}

@Composable
internal fun scaleLabel(scale: CenterSlotScale): String = when (scale) {
    CenterSlotScale.Large -> stringResource(Res.string.player_custom_scale_large)
    CenterSlotScale.Medium -> stringResource(Res.string.player_custom_scale_medium)
    CenterSlotScale.Small -> stringResource(Res.string.player_custom_scale_small)
    CenterSlotScale.ExtraSmall -> stringResource(Res.string.player_custom_scale_xs)
}
