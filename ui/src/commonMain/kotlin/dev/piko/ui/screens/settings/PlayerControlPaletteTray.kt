package dev.piko.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.piko.shared.media.player.CenterSlotAction
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerBarSlot
import dev.piko.shared.media.player.PlayerFormFactorLayout
import dev.piko.shared.media.player.PlayerLayoutMode
import dev.piko.shared.media.player.PlayerPanelItem
import dev.piko.shared.media.player.allowedControlsFor
import dev.piko.shared.media.player.allowsSpacerIn
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.*
import dev.piko.ui.screens.player.handCursor
import dev.piko.ui.screens.player.playerControlIcon
import dev.piko.ui.screens.player.playerControlLabel
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import kotlin.math.roundToInt

/**
 * 可用控件托盘：同时承担拖放移除区。
 * - 平时展示当前模式尚未放置的栏位控件、弹性空白与（视频模式）中央快捷动作；
 * - 拖拽预览中已放置的栏位控件时，托盘边框与标题动态切换为「松手移除」或「必需控件」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ControlPaletteTray(
    mode: PlayerLayoutMode,
    layout: PlayerFormFactorLayout,
    seekButtonSeconds: Int,
    dragState: PlayerEditorDragState,
    dropTarget: DropTarget?,
    onFinishDrag: () -> Unit,
    onQuickAddControl: (PlayerControlId) -> Unit,
    onQuickAddSpacer: () -> Unit,
    onQuickTapCenterAction: (CenterSlotAction) -> Unit,
) {
    val eInk = LocalAppearance.current.isEInk
    val activePlaced = dragState.activeDrag?.payload as? DragPayload.PlacedBarItem
    val isRemovingHover = dropTarget == DropTarget.Remove && activePlaced != null
    val canRemovePlaced = activePlaced != null &&
        layout.canRemoveBarItem(activePlaced.bar, activePlaced.index, mode)

    val unusedControls = remember(mode, layout) {
        allowedControlsFor(mode).filter { id ->
            id != PlayerControlId.Back && !layout.hasInTopBar(id) && !layout.hasInBottomBar(id)
        }
    }
    val canAddSpacer = allowsSpacerIn(mode, PlayerBarSlot.Top) || allowsSpacerIn(mode, PlayerBarSlot.Bottom)

    val shape = MaterialTheme.shapes.large
    val borderColor = when {
        isRemovingHover && canRemovePlaced -> MaterialTheme.colorScheme.error
        eInk -> MaterialTheme.colorScheme.outline
        activePlaced != null -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val containerColor = when {
        isRemovingHover && canRemovePlaced -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        eInk -> Color.White
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    Surface(
        shape = shape,
        color = containerColor,
        border = BorderStroke(if (isRemovingHover && canRemovePlaced) 2.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                if (coords.isAttached) dragState.paletteBounds = coords.boundsInWindow()
            },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = when {
                        activePlaced != null && canRemovePlaced -> stringResource(Res.string.player_custom_palette_drop_remove)
                        activePlaced != null -> stringResource(Res.string.player_custom_palette_drop_required)
                        else -> stringResource(Res.string.player_custom_section_palette)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        isRemovingHover && canRemovePlaced -> MaterialTheme.colorScheme.error
                        activePlaced != null -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (activePlaced == null) {
                    Text(
                        text = stringResource(Res.string.player_custom_palette_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 1. 栏位控件（含弹性空白）
            Text(
                text = stringResource(Res.string.player_custom_palette_bar_controls),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canAddSpacer) {
                    DraggablePaletteChip(
                        icon = Icons.Filled.SpaceBar,
                        label = stringResource(Res.string.player_custom_spacer),
                        payload = DragPayload.PaletteSpacer,
                        dragState = dragState,
                        onFinishDrag = onFinishDrag,
                        onTap = onQuickAddSpacer,
                    )
                }
                for (id in unusedControls) {
                    key(id) {
                        DraggablePaletteChip(
                            icon = playerControlIcon(id, isClips = mode.isClips),
                            label = playerControlLabel(id),
                            payload = DragPayload.PaletteControl(id),
                            dragState = dragState,
                            onFinishDrag = onFinishDrag,
                            onTap = { onQuickAddControl(id) },
                        )
                    }
                }
                if (unusedControls.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.player_custom_palette_all_placed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }

            // 2. 视频模式中央/播放快捷动作（可拖入中央或底栏主控簇 4 侧槽位，也可拖入顶/底栏）
            if (mode.isVideo) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.player_custom_palette_center_actions),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CenterActionPaletteChipsRow(
                    seekButtonSeconds = seekButtonSeconds,
                    dragState = dragState,
                    onFinishDrag = onFinishDrag,
                    onTapAction = onQuickTapCenterAction,
                )
            }
        }
    }
}

@Composable
internal fun ListenCenterActionsTray(
    seekButtonSeconds: Int,
    dragState: PlayerEditorDragState,
    onFinishDrag: () -> Unit,
    onTapAction: (CenterSlotAction) -> Unit,
) {
    val eInk = LocalAppearance.current.isEInk
    val shape = MaterialTheme.shapes.large
    Surface(
        shape = shape,
        color = if (eInk) Color.White else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Text(
                text = stringResource(Res.string.player_custom_palette_center_actions),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.player_custom_listen_drag_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CenterActionPaletteChipsRow(
                seekButtonSeconds = seekButtonSeconds,
                dragState = dragState,
                onFinishDrag = onFinishDrag,
                onTapAction = onTapAction,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CenterActionPaletteChipsRow(
    seekButtonSeconds: Int,
    dragState: PlayerEditorDragState,
    onFinishDrag: () -> Unit,
    onTapAction: (CenterSlotAction) -> Unit,
) {
    val centerActions = listOf(
        Triple(
            CenterSlotAction.Previous,
            Icons.Filled.SkipPrevious,
            stringResource(Res.string.player_custom_slot_action_prev),
        ),
        Triple(
            CenterSlotAction.Next,
            Icons.Filled.SkipNext,
            stringResource(Res.string.player_custom_slot_action_next),
        ),
        Triple(
            CenterSlotAction.SeekBackward(seekButtonSeconds),
            Icons.Filled.FastRewind,
            stringResource(Res.string.player_custom_slot_action_rewind),
        ),
        Triple(
            CenterSlotAction.SeekForward(seekButtonSeconds),
            Icons.Filled.FastForward,
            stringResource(Res.string.player_custom_slot_action_forward),
        ),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((action, icon, label) in centerActions) {
            DraggablePaletteChip(
                icon = icon,
                label = label,
                payload = DragPayload.PaletteCenterAction(action),
                dragState = dragState,
                onFinishDrag = onFinishDrag,
                onTap = { onTapAction(action) },
            )
        }
    }
}

@Composable
private fun DraggablePaletteChip(
    icon: ImageVector,
    label: String,
    payload: DragPayload,
    dragState: PlayerEditorDragState,
    onFinishDrag: () -> Unit,
    onTap: () -> Unit,
) {
    val latestFinishDrag by rememberUpdatedState(onFinishDrag)
    val haptics = LocalHapticFeedback.current
    val eInk = LocalAppearance.current.isEInk
    val coordsRef = remember { LayoutCoordsRef() }
    val shape = CircleShape

    Surface(
        shape = shape,
        color = if (eInk) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = 1.dp,
            color = if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
        modifier = Modifier
            .semantics { contentDescription = label }
            .onGloballyPositioned { coords ->
                if (coords.isAttached) coordsRef.value = coords
            }
            .clip(shape)
            .clickable(onClick = onTap)
            .handCursor()
            .playerEditorDraggable(
                key1 = payload,
                requireLongPressOnTouch = true,
                coordsRef = coordsRef,
                onDragActivated = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                onDragStart = { pos -> dragState.startDrag(payload, pos) },
                onDragMove = { pos -> dragState.updateDrag(pos) },
                onDragEnd = { latestFinishDrag() },
                onDragCancel = { dragState.cancelDrag() },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun DragGhostOverlay(
    payload: DragPayload,
    pointerInRoot: Offset,
    isClips: Boolean,
    removing: Boolean,
) {
    val eInk = LocalAppearance.current.isEInk
    val icon: ImageVector = when (payload) {
        is DragPayload.PaletteControl -> playerControlIcon(payload.id, isClips = isClips)
        DragPayload.PaletteSpacer -> Icons.Filled.SpaceBar
        is DragPayload.PaletteCenterAction -> when (payload.action) {
            CenterSlotAction.Previous -> Icons.Filled.SkipPrevious
            CenterSlotAction.Next -> Icons.Filled.SkipNext
            is CenterSlotAction.SeekBackward -> Icons.Filled.FastRewind
            is CenterSlotAction.SeekForward -> Icons.Filled.FastForward
            CenterSlotAction.None -> Icons.Filled.Add
        }
        is DragPayload.PlacedBarItem -> when (payload.item) {
            PlayerPanelItem.Spacer -> Icons.Filled.SpaceBar
            is PlayerPanelItem.Control -> playerControlIcon(payload.item.id, isClips = isClips)
        }
    }
    val label: String = when (payload) {
        is DragPayload.PaletteControl -> playerControlLabel(payload.id)
        DragPayload.PaletteSpacer -> stringResource(Res.string.player_custom_spacer)
        is DragPayload.PaletteCenterAction -> centerActionLabel(payload.action)
        is DragPayload.PlacedBarItem -> when (payload.item) {
            PlayerPanelItem.Spacer -> stringResource(Res.string.player_custom_spacer)
            is PlayerPanelItem.Control -> playerControlLabel(payload.item.id)
        }
    }
    val containerColor = when {
        removing -> MaterialTheme.colorScheme.errorContainer.copy(alpha = if (eInk) 1f else 0.94f)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (eInk) 1f else 0.94f)
    }
    val contentColor = if (removing) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val border = if (eInk) {
        BorderStroke(1.dp, if (removing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
    } else {
        null
    }

    Surface(
        shape = CircleShape,
        color = containerColor,
        border = border,
        shadowElevation = if (eInk) 0.dp else 8.dp,
        modifier = Modifier
            .zIndex(100f)
            .offset {
                val offsetX = 48.dp.toPx()
                val offsetY = 44.dp.toPx()
                val safeX = if (pointerInRoot.x.isFinite()) (pointerInRoot.x - offsetX).roundToInt() else 0
                val safeY = if (pointerInRoot.y.isFinite()) (pointerInRoot.y - offsetY).roundToInt() else 0
                IntOffset(x = safeX, y = safeY)
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Icon(
                imageVector = if (removing) Icons.Outlined.DeleteOutline else icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}
