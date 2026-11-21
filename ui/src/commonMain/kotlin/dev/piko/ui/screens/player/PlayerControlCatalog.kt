package dev.piko.ui.screens.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import dev.piko.shared.media.player.CenterSlotPosition
import dev.piko.shared.media.player.PlayerBarSlot
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerPanelItem
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.*

/**
 * 播放器栏位与信息流栏位的单一权威编辑钩子接口。
 * 真实播放时默认传 `null`，不分配任何包装节点或坐标跟踪器；
 * 仅在 `PlayerCustomizationPage` 挂载预览画布时注入，实现零分叉同源渲染。
 */
internal interface PlayerBarEditScope {
    val selectedCenterSlot: CenterSlotPosition?
    val hoveredCenterSlot: CenterSlotPosition?
    val draggingCenterAction: Boolean

    fun barContainerModifier(bar: PlayerBarSlot): Modifier

    @Composable
    fun RenderDropIndicator(bar: PlayerBarSlot, index: Int, vertical: Boolean = false)

    fun onSlotSkipped(bar: PlayerBarSlot, index: Int)

    fun isSlotSelected(bar: PlayerBarSlot, index: Int): Boolean

    fun onSelectSlot(bar: PlayerBarSlot, index: Int)

    fun onSelectCenterSlot(position: CenterSlotPosition)

    fun onCenterSlotBounds(position: CenterSlotPosition, bounds: Rect)

    @Composable
    fun DecorateSlot(
        bar: PlayerBarSlot,
        index: Int,
        item: PlayerPanelItem,
        hiddenByCenter: Boolean = false,
        clickableContainer: Boolean = false,
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    )

    @Composable
    fun SpacerVisual(
        bar: PlayerBarSlot,
        index: Int,
        modifier: Modifier = Modifier,
    )
}

@Composable
internal inline fun RenderEditableSlot(
    editScope: PlayerBarEditScope?,
    bar: PlayerBarSlot,
    index: Int,
    item: PlayerPanelItem,
    hiddenByCenter: Boolean = false,
    clickableContainer: Boolean = false,
    modifier: Modifier = Modifier,
    crossinline content: @Composable (Modifier) -> Unit,
) {
    if (editScope == null) {
        content(modifier)
    } else {
        key(bar, index, item) {
            editScope.DecorateSlot(
                bar = bar,
                index = index,
                item = item,
                hiddenByCenter = hiddenByCenter,
                clickableContainer = clickableContainer,
                modifier = modifier,
            ) {
                content(Modifier)
            }
        }
    }
}

/**
 * 全部 [PlayerControlId] 的单一权威文案与图标定义，供真实播放栏、自定义托盘、拖拽幽灵与检查器同源读取。
 */
@Composable
internal fun playerControlLabel(id: PlayerControlId): String = when (id) {
    PlayerControlId.Back -> stringResource(Res.string.player_custom_ctrl_back)
    PlayerControlId.Title -> stringResource(Res.string.player_custom_ctrl_title)
    PlayerControlId.SystemStatus -> stringResource(Res.string.player_custom_ctrl_system_status)
    PlayerControlId.PlayPause -> stringResource(Res.string.player_custom_ctrl_play_pause)
    PlayerControlId.Previous -> stringResource(Res.string.player_custom_ctrl_previous)
    PlayerControlId.Next -> stringResource(Res.string.player_custom_ctrl_next)
    PlayerControlId.SeekBackward -> stringResource(Res.string.player_custom_ctrl_seek_back)
    PlayerControlId.SeekForward -> stringResource(Res.string.player_custom_ctrl_seek_forward)
    PlayerControlId.TimeLabel -> stringResource(Res.string.player_custom_ctrl_time)
    PlayerControlId.Volume -> stringResource(Res.string.player_custom_ctrl_volume)
    PlayerControlId.Quality -> stringResource(Res.string.player_custom_ctrl_quality)
    PlayerControlId.Speed -> stringResource(Res.string.player_custom_ctrl_speed)
    PlayerControlId.Episodes -> stringResource(Res.string.player_custom_ctrl_episodes)
    PlayerControlId.Tracks -> stringResource(Res.string.player_custom_ctrl_tracks)
    PlayerControlId.Listen -> stringResource(Res.string.player_custom_ctrl_listen)
    PlayerControlId.Rotate -> stringResource(Res.string.player_custom_ctrl_rotate)
    PlayerControlId.AspectRatio -> stringResource(Res.string.player_custom_ctrl_aspect)
    PlayerControlId.Lock -> stringResource(Res.string.player_custom_ctrl_lock)
    PlayerControlId.Stats -> stringResource(Res.string.player_custom_ctrl_stats)
    PlayerControlId.Settings -> stringResource(Res.string.player_custom_ctrl_settings)
    PlayerControlId.Fullscreen -> stringResource(Res.string.player_custom_ctrl_fullscreen)
    PlayerControlId.PinOnTop -> stringResource(Res.string.player_custom_ctrl_pin)
    PlayerControlId.AutoAdvance -> stringResource(Res.string.player_custom_ctrl_auto_advance)
    PlayerControlId.Star -> stringResource(Res.string.player_custom_ctrl_star)
    PlayerControlId.Vault -> stringResource(Res.string.player_custom_ctrl_vault)
    PlayerControlId.OpenFull -> stringResource(Res.string.player_custom_ctrl_open_full)
    PlayerControlId.OpenFolder -> stringResource(Res.string.player_custom_ctrl_open_folder)
    PlayerControlId.Download -> stringResource(Res.string.player_custom_ctrl_download)
    PlayerControlId.HideChrome -> stringResource(Res.string.player_custom_ctrl_hide_chrome)
}

internal fun playerControlIcon(id: PlayerControlId, isClips: Boolean = false): ImageVector = when (id) {
    PlayerControlId.Back -> if (isClips) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack
    PlayerControlId.Title -> Icons.Outlined.Info
    PlayerControlId.SystemStatus -> Icons.Outlined.Timer
    PlayerControlId.PlayPause -> Icons.Filled.PlayArrow
    PlayerControlId.Previous -> Icons.Filled.SkipPrevious
    PlayerControlId.Next -> Icons.Filled.SkipNext
    PlayerControlId.SeekBackward -> Icons.Filled.FastRewind
    PlayerControlId.SeekForward -> Icons.Filled.FastForward
    PlayerControlId.TimeLabel -> Icons.Outlined.Timer
    PlayerControlId.Volume -> Icons.AutoMirrored.Filled.VolumeUp
    PlayerControlId.Quality -> Icons.Filled.HighQuality
    PlayerControlId.Speed -> Icons.Filled.Speed
    PlayerControlId.Episodes -> Icons.Outlined.VideoLibrary
    PlayerControlId.Tracks -> Icons.Outlined.Subtitles
    PlayerControlId.Listen -> Icons.Outlined.Headphones
    PlayerControlId.Rotate -> if (isClips) Icons.Filled.StayCurrentLandscape else Icons.Filled.Rotate90DegreesCw
    PlayerControlId.AspectRatio -> Icons.Outlined.AspectRatio
    PlayerControlId.Lock -> Icons.Filled.LockOpen
    PlayerControlId.Stats -> Icons.Outlined.Info
    PlayerControlId.Settings -> Icons.Outlined.Tune
    PlayerControlId.Fullscreen -> Icons.Filled.Fullscreen
    PlayerControlId.PinOnTop -> Icons.Outlined.PushPin
    PlayerControlId.AutoAdvance -> Icons.Filled.SwipeVertical
    PlayerControlId.Star -> Icons.Filled.Star
    PlayerControlId.Vault -> Icons.Outlined.Folder
    PlayerControlId.OpenFull -> Icons.Outlined.OpenInFull
    PlayerControlId.OpenFolder -> Icons.Outlined.FolderOpen
    PlayerControlId.Download -> Icons.Outlined.Download
    PlayerControlId.HideChrome -> Icons.Filled.VisibilityOff
}
