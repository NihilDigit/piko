package dev.piko.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.CenterSlotAction
import dev.piko.shared.media.player.CenterSlotConfig
import dev.piko.shared.media.player.CenterSlotPosition
import dev.piko.shared.media.player.CenterSlotScale
import dev.piko.shared.media.player.ListenCoverStyle
import dev.piko.shared.media.player.ListenLayoutConfig
import dev.piko.shared.media.player.ListenQuickChip
import dev.piko.shared.media.player.MAX_DOUBLE_TAP_ZONE_FRACTION
import dev.piko.shared.media.player.MAX_SEEK_SECONDS
import dev.piko.shared.media.player.MAX_VERTICAL_ZONE_FRACTION
import dev.piko.shared.media.player.MIN_DOUBLE_TAP_ZONE_FRACTION
import dev.piko.shared.media.player.MIN_SEEK_SECONDS
import dev.piko.shared.media.player.MIN_VERTICAL_ZONE_FRACTION
import dev.piko.shared.media.player.PlayerBarSlot
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerFormFactorLayout
import dev.piko.shared.media.player.PlayerGestureConfig
import dev.piko.shared.media.player.PlayerLayoutMode
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.media.player.PlayerLayoutPreset
import dev.piko.shared.media.player.PlayerPanelItem
import dev.piko.shared.media.player.PlayerStatusBarConfig
import dev.piko.shared.media.player.SEEK_SECONDS_PRESETS
import dev.piko.shared.media.player.SwipeSeekSensitivity
import dev.piko.shared.media.player.VerticalSwipeAction
import dev.piko.shared.media.player.allowedControlsFor
import dev.piko.shared.media.player.allowsSpacerIn
import dev.piko.shared.state.Clip
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.IslandHeaderSpace
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.MenuMotion
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoTextField
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.PlayerNetworkType
import dev.piko.ui.platform.PlayerSystemStatusInfo
import dev.piko.ui.resources.*
import dev.piko.ui.screens.clips.BottomScrim as ClipBottomScrim
import dev.piko.ui.screens.clips.ClipCaption
import dev.piko.ui.screens.clips.ScopeTitle
import dev.piko.ui.screens.clips.TopScrim as ClipTopScrim
import dev.piko.ui.screens.clips.chromeContainer
import dev.piko.ui.screens.player.BottomScrim
import dev.piko.ui.screens.player.ControlBarTransportCluster
import dev.piko.ui.screens.player.FullscreenPromptButtonContent
import dev.piko.ui.screens.player.ListenActionPill
import dev.piko.ui.screens.player.ListenStaticCover
import dev.piko.ui.screens.player.ListenTimeReadout
import dev.piko.ui.screens.player.ListenTransportRow
import dev.piko.ui.screens.player.ListenVinylDiscVisual
import dev.piko.ui.screens.player.PlayerCenterCluster
import dev.piko.ui.screens.player.PlayerChipButton
import dev.piko.ui.screens.player.PlayerIconButton
import dev.piko.ui.screens.player.PlayerSeekBar
import dev.piko.ui.screens.player.PlayerSystemStatusCapsule
import dev.piko.ui.screens.player.PlayerTextShadow
import dev.piko.ui.screens.player.PlayerTheme
import dev.piko.ui.screens.player.PlayerTimeReadout
import dev.piko.ui.screens.player.PlayerVolumeControl
import dev.piko.ui.screens.player.TopScrim
import dev.piko.ui.screens.player.handCursor
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular
import kotlin.math.roundToInt

private enum class CustomizationTab {
    VideoLandscape,
    VideoPortrait,
    ClipsLandscape,
    ClipsPortrait,
    Listen,
    Gestures,
    ;

    fun toLayoutModeOrNull(): PlayerLayoutMode? = when (this) {
        VideoLandscape -> PlayerLayoutMode.VideoLandscape
        VideoPortrait -> PlayerLayoutMode.VideoPortrait
        ClipsLandscape -> PlayerLayoutMode.ClipsLandscape
        ClipsPortrait -> PlayerLayoutMode.ClipsPortrait
        Listen, Gestures -> null
    }
}

private sealed interface SlotSelection {
    data class CenterSlot(val position: CenterSlotPosition) : SlotSelection
    data class PanelItem(val bar: PlayerBarSlot, val index: Int) : SlotSelection
    data object PortraitFullscreenPrompt : SlotSelection
}

/**
 * 播放器自定义页面：覆盖四态（横屏视频 / 竖屏视频 / 横屏信息流 / 竖屏信息流）、听音频与手势。
 *
 * 采用 M3 Expressive 三区辅助面板布局：
 * - 宽屏双栏：左侧 1:1 实件预览画布与预设开关，右侧上方可用控件托盘 + 下方紧凑属性检查器（短距离横拖）；
 * - 窄屏/竖屏：顶部固定模式切换与实时预览（不随下方滚动移出视野），下方独立滚动区域紧贴可用控件托盘与检查器。
 *
 * 严格遵循 Piko 桌面与移动安全约束：无 `BoxWithConstraints`、无 `ListItem`/`SegmentedListItem`、无 `AlertDialog`、
 * 无中点文案分隔符，并兼容水墨屏与减少动画。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerCustomizationPage(
    prefs: PlayerLayoutPrefs,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeTab by rememberSaveable { mutableStateOf(CustomizationTab.VideoLandscape) }
    val activeMode = activeTab.toLayoutModeOrNull()
    val activeLayout = activeMode?.let(prefs::layoutFor)
    val defaultTouchPreview = LocalPointerSource.current.isTouchLike
    var previewTouchMode by rememberSaveable { mutableStateOf(defaultTouchPreview) }
    var selection by remember { mutableStateOf<SlotSelection?>(null) }
    var selectedListenSlot by rememberSaveable { mutableStateOf(CenterSlotPosition.InnerLeft) }
    var resetDialogOpen by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val dragState = remember { PlayerEditorDragState() }

    val containerWidthDp = with(density) { containerSize.width.toDp() }
    val containerHeightDp = with(density) { containerSize.height.toDp() }
    val isWideTwoPane = containerWidthDp >= 840.dp &&
        (containerSize.height == 0 || containerSize.width > containerSize.height)

    val transportInCenter = activeMode != null &&
        activeLayout != null &&
        activeMode.isVideo &&
        prefs.isCenterControlsActive(previewTouchMode) &&
        activeLayout.center.showCenterPlay

    // 切换模式、布局形态或视角时取消进行中拖拽并清空过期坐标缓存
    LaunchedEffect(activeTab, isWideTwoPane, previewTouchMode) {
        dragState.cancelDrag()
        dragState.clearBounds()
    }

    LaunchedEffect(transportInCenter) {
        dragState.centerSlotBounds.clear()
    }

    // 切换模式或栏位长度变化时自动修正越界选中索引与清理越界边界
    LaunchedEffect(activeTab, activeLayout?.topBar?.size, activeLayout?.bottomBar?.size) {
        if (activeLayout != null) {
            dragState.pruneBarBounds(activeLayout.topBar.size, activeLayout.bottomBar.size)
        }
        val current = selection
        if (activeMode == null || activeLayout == null) {
            selection = null
            return@LaunchedEffect
        }
        if (current == SlotSelection.PortraitFullscreenPrompt && activeMode != PlayerLayoutMode.VideoPortrait) {
            selection = null
            return@LaunchedEffect
        }
        if (current is SlotSelection.CenterSlot && activeMode.isClips) {
            selection = null
            return@LaunchedEffect
        }
        if (current is SlotSelection.PanelItem) {
            val size = activeLayout.itemsFor(current.bar).size
            if (current.index !in 0 until size) {
                selection = if (size > 0) SlotSelection.PanelItem(current.bar, size - 1) else null
            }
        }
    }

    fun resolveCurrentDropTarget(): DropTarget? = when {
        activeMode != null && activeLayout != null -> dragState.resolveDropTarget(
            layout = activeLayout,
            mode = activeMode,
            seekSeconds = prefs.seekButtonSeconds,
        )
        activeTab == CustomizationTab.Listen -> dragState.resolveListenDropTarget(
            seekSeconds = prefs.seekButtonSeconds,
        )
        else -> null
    }

    val currentDropTarget: DropTarget? = resolveCurrentDropTarget()

    val finishDrag: () -> Unit = {
        val drag = dragState.activeDrag
        val target = if (drag != null) resolveCurrentDropTarget() else null
        dragState.cancelDrag()
        if (drag != null && target != null) {
            when (target) {
                is DropTarget.CenterSlot -> {
                    val action = drag.payload.toCenterSlotActionOrNull(prefs.seekButtonSeconds)
                    if (action != null) {
                        if (activeMode != null) {
                            onUpdate { p ->
                                p.updateLayout(activeMode) { layout ->
                                    val existing = layout.center.slot(target.position)
                                    layout.withCenterSlot(
                                        position = target.position,
                                        config = existing.copy(action = action),
                                        mode = activeMode,
                                    )
                                }
                            }
                            selection = SlotSelection.CenterSlot(target.position)
                        } else if (activeTab == CustomizationTab.Listen) {
                            onUpdate { p ->
                                p.updateListen { listen ->
                                    val existing = listen.slot(target.position)
                                    listen.withSlot(target.position, existing.copy(action = action))
                                }
                            }
                            selectedListenSlot = target.position
                        }
                    }
                }

                is DropTarget.BarInsert -> {
                    if (activeMode != null && activeLayout != null) {
                        when (val payload = drag.payload) {
                            is DragPayload.PlacedBarItem -> {
                                val nextLayout = activeLayout.moveBarItemToBarAt(
                                    fromBar = payload.bar,
                                    fromIndex = payload.index,
                                    toBar = target.bar,
                                    toIndex = target.insertIndex,
                                    mode = activeMode,
                                )
                                val newIndex = if (payload.bar == target.bar && payload.index < target.insertIndex) {
                                    (target.insertIndex - 1).coerceAtLeast(0)
                                } else {
                                    target.insertIndex
                                }.coerceIn(0, (nextLayout.itemsFor(target.bar).size - 1).coerceAtLeast(0))
                                onUpdate { p ->
                                    p.updateLayout(activeMode) { layout ->
                                        layout.moveBarItemToBarAt(
                                            fromBar = payload.bar,
                                            fromIndex = payload.index,
                                            toBar = target.bar,
                                            toIndex = target.insertIndex,
                                            mode = activeMode,
                                        )
                                    }
                                }
                                selection = SlotSelection.PanelItem(target.bar, newIndex)
                            }

                            else -> {
                                val panelItem = payload.toPanelItemOrNull()
                                if (panelItem != null && activeLayout.isItemAllowedInBar(panelItem, target.bar, activeMode)) {
                                    val nextLayout = activeLayout.insertIntoBar(
                                        bar = target.bar,
                                        index = target.insertIndex,
                                        item = panelItem,
                                        mode = activeMode,
                                    )
                                    val insertedIdx = if (panelItem is PlayerPanelItem.Control) {
                                        nextLayout.itemsFor(target.bar)
                                            .indexOfFirst { it is PlayerPanelItem.Control && it.id == panelItem.id }
                                            .takeIf { it >= 0 }
                                            ?: target.insertIndex.coerceIn(0, (nextLayout.itemsFor(target.bar).size - 1).coerceAtLeast(0))
                                    } else {
                                        target.insertIndex.coerceIn(0, (nextLayout.itemsFor(target.bar).size - 1).coerceAtLeast(0))
                                    }
                                    onUpdate { p ->
                                        p.updateLayout(activeMode) { layout ->
                                            layout.insertIntoBar(
                                                bar = target.bar,
                                                index = target.insertIndex,
                                                item = panelItem,
                                                mode = activeMode,
                                            )
                                        }
                                    }
                                    selection = SlotSelection.PanelItem(target.bar, insertedIdx)
                                }
                            }
                        }
                    }
                }

                DropTarget.Remove -> {
                    val placed = drag.payload as? DragPayload.PlacedBarItem
                    if (
                        placed != null &&
                        activeMode != null &&
                        activeLayout != null &&
                        activeLayout.canRemoveBarItem(placed.bar, placed.index, activeMode)
                    ) {
                        onUpdate { p ->
                            p.updateLayout(activeMode) { layout ->
                                layout.removeBarItem(placed.bar, placed.index, activeMode)
                            }
                        }
                        if (selection == SlotSelection.PanelItem(placed.bar, placed.index)) {
                            selection = null
                        }
                    }
                }
            }
        }
    }

    val onQuickAddControl: (PlayerControlId) -> Unit = { controlId ->
        if (activeMode != null && activeLayout != null) {
            val bar = defaultBarForQuickAdd(controlId, activeMode)
            val nextLayout = activeLayout.addToBar(bar, PlayerPanelItem.Control(controlId), activeMode)
            val addedIndex = nextLayout.itemsFor(bar)
                .indexOfFirst { it is PlayerPanelItem.Control && it.id == controlId }
            onUpdate { p ->
                p.updateLayout(activeMode) { it.addToBar(bar, PlayerPanelItem.Control(controlId), activeMode) }
            }
            if (addedIndex >= 0) {
                selection = SlotSelection.PanelItem(bar, addedIndex)
            }
        }
    }

    val onQuickAddSpacer: () -> Unit = {
        if (activeMode != null && activeLayout != null) {
            val bar = defaultBarForQuickAddSpacer(activeMode)
            if (bar != null) {
                val nextIdx = activeLayout.itemsFor(bar).size
                onUpdate { p ->
                    p.updateLayout(activeMode) { it.addToBar(bar, PlayerPanelItem.Spacer, activeMode) }
                }
                selection = SlotSelection.PanelItem(bar, nextIdx)
            }
        }
    }

    val onQuickTapCenterAction: (CenterSlotAction) -> Unit = { action ->
        if (activeMode != null && activeLayout != null) {
            val selectedCenter = (selection as? SlotSelection.CenterSlot)?.position
                ?.takeIf { it != CenterSlotPosition.Center }
            if (selectedCenter != null) {
                onUpdate { p ->
                    p.updateLayout(activeMode) { layout ->
                        val existing = layout.center.slot(selectedCenter)
                        layout.withCenterSlot(selectedCenter, existing.copy(action = action), activeMode)
                    }
                }
            } else {
                val controlId = action.toControlIdOrNull()
                if (controlId != null && !activeLayout.hasInTopBar(controlId) && !activeLayout.hasInBottomBar(controlId)) {
                    onQuickAddControl(controlId)
                }
            }
        } else if (activeTab == CustomizationTab.Listen) {
            onUpdate { p ->
                p.updateListen { listen ->
                    val existing = listen.slot(selectedListenSlot)
                    listen.withSlot(selectedListenSlot, existing.copy(action = action))
                }
            }
        }
    }

    val pageTitle = stringResource(Res.string.player_custom_title)
    val resetLabel = stringResource(Res.string.player_custom_reset)
    val isAllDefault = prefs == PlayerLayoutPrefs.Default

    if (resetDialogOpen) {
        SettingsChoiceDialog(
            icon = Icons.Outlined.RestartAlt,
            title = resetLabel,
            onDismiss = { resetDialogOpen = false },
        ) {
            if (activeMode != null) {
                SettingsChoiceOption(
                    title = stringResource(Res.string.player_custom_reset_current),
                    selected = false,
                    onClick = {
                        onUpdate { it.resetMode(activeMode) }
                        resetDialogOpen = false
                    },
                )
            } else if (activeTab == CustomizationTab.Listen) {
                SettingsChoiceOption(
                    title = stringResource(Res.string.player_custom_reset_current),
                    selected = false,
                    onClick = {
                        onUpdate { it.copy(listen = ListenLayoutConfig.Default).sanitize() }
                        resetDialogOpen = false
                    },
                )
            } else {
                SettingsChoiceOption(
                    title = stringResource(Res.string.player_custom_reset_current),
                    selected = false,
                    onClick = {
                        onUpdate { it.copy(gestures = PlayerGestureConfig.Default).sanitize() }
                        resetDialogOpen = false
                    },
                )
            }
            SettingsChoiceOption(
                title = stringResource(Res.string.player_custom_reset_all),
                selected = false,
                onClick = {
                    onUpdate { PlayerLayoutPrefs.Default }
                    resetDialogOpen = false
                },
            )
        }
    }

    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        island = IslandPage(header = {
            TooltipIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                label = stringResource(Res.string.player_custom_ctrl_back),
                onClick = onBackClick,
            )
            IslandTitle(pageTitle)
            IslandHeaderSpace()
            if (!isAllDefault) {
                TooltipIconButton(
                    icon = Icons.Outlined.RestartAlt,
                    label = resetLabel,
                    onClick = { resetDialogOpen = true },
                )
            }
        }),
        topBar = {
            PikoTopBar(
                title = pageTitle,
                onBackClick = onBackClick,
                actions = buildList {
                    if (!isAllDefault) {
                        add(
                            iconBarItem(
                                icon = Icons.Outlined.RestartAlt,
                                label = resetLabel,
                                priority = PinnedPriority,
                                onClick = { resetDialogOpen = true },
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onSizeChanged { containerSize = it }
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) dragState.rootOriginInWindow = coords.positionInWindow()
                }
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        keyEvent.key == Key.Escape -> {
                            when {
                                dragState.activeDrag != null -> {
                                    dragState.cancelDrag()
                                    true
                                }
                                selection != null -> {
                                    selection = null
                                    true
                                }
                                else -> false
                            }
                        }

                        activeMode != null && activeLayout != null && selection is SlotSelection.PanelItem -> {
                            val sel = selection as SlotSelection.PanelItem
                            val list = activeLayout.itemsFor(sel.bar)
                            val item = list.getOrNull(sel.index) ?: return@onPreviewKeyEvent false
                            val vertical = isBarVerticalRail(activeMode, sel.bar)
                            val moveBackwardKey = if (vertical) Key.DirectionUp else Key.DirectionLeft
                            val moveForwardKey = if (vertical) Key.DirectionDown else Key.DirectionRight
                            when {
                                keyEvent.isAltPressed && keyEvent.key == moveBackwardKey -> {
                                    val canMove = sel.index > 0 && !(
                                        activeMode.isVideo &&
                                            sel.bar == PlayerBarSlot.Top &&
                                            sel.index == 1 &&
                                            (list.firstOrNull() as? PlayerPanelItem.Control)?.id == PlayerControlId.Back
                                        )
                                    if (canMove) {
                                        val target = sel.index - 1
                                        onUpdate { p -> p.updateLayout(activeMode) { it.moveBarItem(sel.bar, sel.index, target, activeMode) } }
                                        selection = SlotSelection.PanelItem(sel.bar, target)
                                        true
                                    } else {
                                        false
                                    }
                                }

                                keyEvent.isAltPressed && keyEvent.key == moveForwardKey -> {
                                    val canMove = sel.index < list.lastIndex && !(
                                        activeMode.isVideo &&
                                            sel.bar == PlayerBarSlot.Top &&
                                            sel.index == 0 &&
                                            (item as? PlayerPanelItem.Control)?.id == PlayerControlId.Back
                                        )
                                    if (canMove) {
                                        val target = sel.index + 1
                                        onUpdate { p -> p.updateLayout(activeMode) { it.moveBarItem(sel.bar, sel.index, target, activeMode) } }
                                        selection = SlotSelection.PanelItem(sel.bar, target)
                                        true
                                    } else {
                                        false
                                    }
                                }

                                (keyEvent.key == Key.Delete || keyEvent.key == Key.Backspace) &&
                                    activeLayout.canRemoveBarItem(sel.bar, sel.index, activeMode) -> {
                                    onUpdate { p -> p.updateLayout(activeMode) { it.removeBarItem(sel.bar, sel.index, activeMode) } }
                                    selection = null
                                    true
                                }

                                else -> false
                            }
                        }

                        else -> false
                    }
                },
        ) {
            if (isWideTwoPane) {
                // 宽屏三区辅助面板：左栏（60%）模式栏 + 1:1 预览画布 + 底部预设/触控开关；
                // 右栏（40%，300..420dp）上方可用控件托盘 + 下方紧凑选中项检查器（右向左短距拖放）。
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.60f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            CustomizationTabSelector(
                                selected = activeTab,
                                onSelect = { activeTab = it },
                                modifier = Modifier.weight(1f),
                            )
                            if (activeMode?.isVideo == true) {
                                PreviewPointerModeToggle(
                                    isTouch = previewTouchMode,
                                    onTouchChange = { previewTouchMode = it },
                                )
                            }
                        }

                        PreviewCanvasSection(
                            activeTab = activeTab,
                            activeMode = activeMode,
                            activeLayout = activeLayout,
                            prefs = prefs,
                            transportInCenter = transportInCenter,
                            selection = selection,
                            onSelect = { selection = it },
                            selectedListenSlot = selectedListenSlot,
                            onSelectListenSlot = { selectedListenSlot = it },
                            dragState = dragState,
                            dropTarget = currentDropTarget,
                            onFinishDrag = finishDrag,
                            compactPreviewHeight = null,
                            onTogglePortraitFullscreenPrompt = {
                                onUpdate { it.copy(showPortraitFullscreenPrompt = !it.showPortraitFullscreenPrompt) }
                                selection = SlotSelection.PortraitFullscreenPrompt
                            },
                        )

                        if (activeMode != null) {
                            PresetAndTouchStrip(
                                prefs = prefs,
                                showTouchCenterToggle = activeMode.isVideo,
                                onUpdate = onUpdate,
                            )
                        } else if (activeTab == CustomizationTab.Listen) {
                            PresetAndTouchStrip(
                                prefs = prefs,
                                showTouchCenterToggle = false,
                                onUpdate = onUpdate,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(0.40f)
                            .widthIn(min = 300.dp, max = 420.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EditorSupportingPaneContent(
                            activeTab = activeTab,
                            activeMode = activeMode,
                            activeLayout = activeLayout,
                            prefs = prefs,
                            selection = selection,
                            onSelect = { selection = it },
                            selectedListenSlot = selectedListenSlot,
                            dragState = dragState,
                            dropTarget = currentDropTarget,
                            onFinishDrag = finishDrag,
                            onQuickAddControl = onQuickAddControl,
                            onQuickAddSpacer = onQuickAddSpacer,
                            onQuickTapCenterAction = onQuickTapCenterAction,
                            onUpdate = onUpdate,
                        )
                    }
                }
            } else {
                // 窄屏/竖屏三区布局：顶部固定（不随下方滚动）模式切换 + 预览画布；
                // 下方独立滚动：可用控件托盘紧贴预览画布下方（便于向上拖放）→ 紧凑选中项检查器 → 预设与开关。
                val inlinePointerToggle = containerWidthDp >= 520.dp
                val maxPortraitPreviewHeight = if (containerHeightDp > 0.dp) {
                    minOf(340.dp, (containerHeightDp * 0.44f).coerceAtLeast(200.dp))
                } else {
                    300.dp
                }
                val maxLandscapePreviewHeight = if (containerHeightDp > 0.dp) {
                    (containerHeightDp * 0.36f).coerceAtLeast(160.dp)
                } else {
                    220.dp
                }
                val compactHeightLimit = when {
                    activeMode != null && !activeMode.isLandscape -> maxPortraitPreviewHeight
                    activeTab == CustomizationTab.Listen -> if (containerHeightDp > 0.dp) {
                        (containerHeightDp * 0.42f).coerceAtLeast(190.dp)
                    } else {
                        260.dp
                    }
                    else -> maxLandscapePreviewHeight
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .readableWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 10.dp),
                    ) {
                        if (activeMode?.isVideo == true && inlinePointerToggle) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                CustomizationTabSelector(
                                    selected = activeTab,
                                    onSelect = { activeTab = it },
                                    modifier = Modifier.weight(1f),
                                )
                                PreviewPointerModeToggle(
                                    isTouch = previewTouchMode,
                                    onTouchChange = { previewTouchMode = it },
                                )
                            }
                        } else {
                            CustomizationTabSelector(
                                selected = activeTab,
                                onSelect = { activeTab = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (activeMode?.isVideo == true) {
                                Row(
                                    horizontalArrangement = Arrangement.End,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    PreviewPointerModeToggle(
                                        isTouch = previewTouchMode,
                                        onTouchChange = { previewTouchMode = it },
                                    )
                                }
                            }
                        }

                        PreviewCanvasSection(
                            activeTab = activeTab,
                            activeMode = activeMode,
                            activeLayout = activeLayout,
                            prefs = prefs,
                            transportInCenter = transportInCenter,
                            selection = selection,
                            onSelect = { selection = it },
                            selectedListenSlot = selectedListenSlot,
                            onSelectListenSlot = { selectedListenSlot = it },
                            dragState = dragState,
                            dropTarget = currentDropTarget,
                            onFinishDrag = finishDrag,
                            compactPreviewHeight = compactHeightLimit,
                            onTogglePortraitFullscreenPrompt = {
                                onUpdate { it.copy(showPortraitFullscreenPrompt = !it.showPortraitFullscreenPrompt) }
                                selection = SlotSelection.PortraitFullscreenPrompt
                            },
                        )
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                    ) {
                        EditorSupportingPaneContent(
                            activeTab = activeTab,
                            activeMode = activeMode,
                            activeLayout = activeLayout,
                            prefs = prefs,
                            selection = selection,
                            onSelect = { selection = it },
                            selectedListenSlot = selectedListenSlot,
                            dragState = dragState,
                            dropTarget = currentDropTarget,
                            onFinishDrag = finishDrag,
                            onQuickAddControl = onQuickAddControl,
                            onQuickAddSpacer = onQuickAddSpacer,
                            onQuickTapCenterAction = onQuickTapCenterAction,
                            onUpdate = onUpdate,
                        )
                        if (activeMode != null) {
                            PresetAndTouchStrip(
                                prefs = prefs,
                                showTouchCenterToggle = activeMode.isVideo,
                                onUpdate = onUpdate,
                            )
                        } else if (activeTab == CustomizationTab.Listen) {
                            PresetAndTouchStrip(
                                prefs = prefs,
                                showTouchCenterToggle = false,
                                onUpdate = onUpdate,
                            )
                        }
                    }
                }
            }

            // 拖拽浮层幽灵（跟随鼠标与触控指针，拖入移除区切换为错误色+删除图标）
            val activeDrag = dragState.activeDrag
            if (activeDrag != null) {
                val removing = currentDropTarget == DropTarget.Remove &&
                    activeMode != null &&
                    activeLayout != null &&
                    (activeDrag.payload as? DragPayload.PlacedBarItem)?.let {
                        activeLayout.canRemoveBarItem(it.bar, it.index, activeMode)
                    } == true
                DragGhostOverlay(
                    payload = activeDrag.payload,
                    pointerInRoot = activeDrag.pointerInWindow - dragState.rootOriginInWindow,
                    isClips = activeMode?.isClips == true,
                    removing = removing,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CustomizationTabSelector(
    selected: CustomizationTab,
    onSelect: (CustomizationTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = CustomizationTab.entries
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .verticalWheelScrollsRow(scrollState)
            .horizontalScroll(scrollState)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        tabs.forEachIndexed { index, tab ->
            FilledTonalToggleButton(
                checked = tab == selected,
                onCheckedChange = { if (it) onSelect(tab) },
                shapes = connectedToggleShapes(index, tabs.size),
            ) {
                Text(tabLabel(tab), maxLines = 1)
            }
        }
    }
}

/** 预览视角切换（触屏 / 键鼠）：仅切换视频预览中主控组在画面中央或底栏的呈现位置，不拆分配置。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewPointerModeToggle(
    isTouch: Boolean,
    onTouchChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        true to stringResource(Res.string.player_custom_preview_touch),
        false to stringResource(Res.string.player_custom_preview_mouse),
    )
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, (touchOption, label) ->
            FilledTonalToggleButton(
                checked = isTouch == touchOption,
                onCheckedChange = { if (it) onTouchChange(touchOption) },
                shapes = connectedToggleShapes(index, options.size),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PreviewCanvasSection(
    activeTab: CustomizationTab,
    activeMode: PlayerLayoutMode?,
    activeLayout: PlayerFormFactorLayout?,
    prefs: PlayerLayoutPrefs,
    transportInCenter: Boolean,
    selection: SlotSelection?,
    onSelect: (SlotSelection) -> Unit,
    selectedListenSlot: CenterSlotPosition,
    onSelectListenSlot: (CenterSlotPosition) -> Unit,
    dragState: PlayerEditorDragState,
    dropTarget: DropTarget?,
    onFinishDrag: () -> Unit,
    compactPreviewHeight: Dp?,
    onTogglePortraitFullscreenPrompt: () -> Unit,
) {
    when {
        activeMode != null && activeLayout != null -> {
            PlayerPreviewCanvas(
                mode = activeMode,
                layout = activeLayout,
                statusBar = prefs.statusBar,
                seekButtonSeconds = prefs.seekButtonSeconds,
                showPortraitFullscreenPrompt = prefs.showPortraitFullscreenPrompt,
                transportInCenter = transportInCenter,
                selection = selection,
                dragState = dragState,
                dropTarget = dropTarget,
                compactMaxHeight = compactPreviewHeight,
                onSelect = onSelect,
                onFinishDrag = onFinishDrag,
                onTogglePortraitFullscreenPrompt = onTogglePortraitFullscreenPrompt,
            )
        }

        activeTab == CustomizationTab.Listen -> {
            ListenPreviewCanvas(
                config = prefs.listen,
                selectedSlot = selectedListenSlot,
                dropTargetSlot = (dropTarget as? DropTarget.CenterSlot)?.position,
                compactMaxHeight = compactPreviewHeight,
                onSelectSlot = onSelectListenSlot,
                onSlotBounds = { pos, rect -> dragState.centerSlotBounds[pos] = rect },
            )
        }

        activeTab == CustomizationTab.Gestures -> {
            GesturePreviewCanvas(
                gestures = prefs.gestures,
                compactMaxHeight = compactPreviewHeight,
            )
        }
    }
}

@Composable
private fun EditorSupportingPaneContent(
    activeTab: CustomizationTab,
    activeMode: PlayerLayoutMode?,
    activeLayout: PlayerFormFactorLayout?,
    prefs: PlayerLayoutPrefs,
    selection: SlotSelection?,
    onSelect: (SlotSelection?) -> Unit,
    selectedListenSlot: CenterSlotPosition,
    dragState: PlayerEditorDragState,
    dropTarget: DropTarget?,
    onFinishDrag: () -> Unit,
    onQuickAddControl: (PlayerControlId) -> Unit,
    onQuickAddSpacer: () -> Unit,
    onQuickTapCenterAction: (CenterSlotAction) -> Unit,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    when {
        activeMode != null && activeLayout != null -> {
            // 托盘紧贴预览（右侧顶部 / 窄屏预览正下方），缩短拖放路径
            ControlPaletteTray(
                mode = activeMode,
                layout = activeLayout,
                seekButtonSeconds = prefs.seekButtonSeconds,
                dragState = dragState,
                dropTarget = dropTarget,
                onFinishDrag = onFinishDrag,
                onQuickAddControl = onQuickAddControl,
                onQuickAddSpacer = onQuickAddSpacer,
                onQuickTapCenterAction = onQuickTapCenterAction,
            )
            SelectionInspectorCard(
                mode = activeMode,
                layout = activeLayout,
                prefs = prefs,
                selection = selection,
                onSelect = onSelect,
                onUpdate = onUpdate,
            )
        }

        activeTab == CustomizationTab.Listen -> {
            ListenCenterActionsTray(
                seekButtonSeconds = prefs.seekButtonSeconds,
                dragState = dragState,
                onFinishDrag = onFinishDrag,
                onTapAction = onQuickTapCenterAction,
            )
            ListenCustomizationEditor(
                config = prefs.listen,
                seekSeconds = prefs.seekButtonSeconds,
                selectedSlot = selectedListenSlot,
                onUpdate = onUpdate,
            )
        }

        activeTab == CustomizationTab.Gestures -> {
            GestureCustomizationEditor(
                prefs = prefs,
                onUpdate = onUpdate,
            )
        }
    }
}

private val PreviewSampleSystemStatus = PlayerSystemStatusInfo(
    batteryPercent = 86,
    isCharging = false,
    networkType = PlayerNetworkType.Wifi,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerPreviewCanvas(
    mode: PlayerLayoutMode,
    layout: PlayerFormFactorLayout,
    statusBar: PlayerStatusBarConfig,
    seekButtonSeconds: Int,
    showPortraitFullscreenPrompt: Boolean,
    transportInCenter: Boolean,
    selection: SlotSelection?,
    dragState: PlayerEditorDragState,
    dropTarget: DropTarget?,
    compactMaxHeight: Dp?,
    onSelect: (SlotSelection) -> Unit,
    onFinishDrag: () -> Unit,
    onTogglePortraitFullscreenPrompt: () -> Unit,
) {
    val activePlaced = dragState.activeDrag?.payload as? DragPayload.PlacedBarItem
    val topBarDropIndex = (dropTarget as? DropTarget.BarInsert)
        ?.takeIf { it.bar == PlayerBarSlot.Top }?.insertIndex
    val bottomBarDropIndex = (dropTarget as? DropTarget.BarInsert)
        ?.takeIf { it.bar == PlayerBarSlot.Bottom }?.insertIndex
    val hoveredCenterSlot = (dropTarget as? DropTarget.CenterSlot)?.position
    val draggingCenterAction = mode.isVideo &&
        dragState.activeDrag?.payload?.toCenterSlotActionOrNull(seekButtonSeconds) != null

    val canvasModifier = when {
        mode.isLandscape -> {
            val base = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            if (compactMaxHeight != null) base.heightIn(max = compactMaxHeight) else base
        }
        mode == PlayerLayoutMode.ClipsPortrait -> {
            val targetH = compactMaxHeight ?: 450.dp
            Modifier.widthIn(max = if (compactMaxHeight != null) 276.dp else 316.dp)
                .fillMaxWidth()
                .height(targetH)
        }
        else -> {
            val targetH = compactMaxHeight ?: 420.dp
            Modifier.widthIn(max = if (compactMaxHeight != null) 276.dp else 316.dp)
                .fillMaxWidth()
                .height(targetH)
        }
    }
    val sampleFolder = stringResource(Res.string.drive_custom_sample_folder_title)
    val sampleClipName = stringResource(Res.string.player_custom_sample_title)
    val sampleClip = remember(sampleClipName) {
        Clip(
            fileId = "preview-clip",
            name = sampleClipName,
            parentId = "preview-folder",
            startMs = 754_000L,
            videoDurationMs = 2_700_000L,
            occurrence = 1L,
        )
    }
    val eInk = LocalAppearance.current.isEInk
    val outlineColor = MaterialTheme.colorScheme.outline
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    PlayerTheme {
        val primary = MaterialTheme.colorScheme.primary
        // 预览画布内禁用 M3 默认 48dp 最小触控尺寸外扩，避免微缩圆形按钮的阴影/边框重叠
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.largeIncreased,
                    color = Color(0xFF101318),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (eInk) outlineColor else outlineVariant.copy(alpha = 0.45f),
                    ),
                    modifier = canvasModifier.onGloballyPositioned { coords ->
                        if (coords.isAttached) dragState.previewCanvasBounds = coords.boundsInWindow()
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color(0xFF18202B), Color(0xFF0C0F14), Color(0xFF141922)),
                                ),
                            ),
                    ) {
                        // 顶栏预览：复用真实 TopScrim / ClipTopScrim 与播放器实件，支持拖拽重排/跨栏与实时插入线
                        PreviewBarRow(
                            mode = mode,
                            bar = PlayerBarSlot.Top,
                            items = layout.topBar,
                            layout = layout,
                            statusBar = statusBar,
                            transportInCenter = transportInCenter,
                            selection = selection,
                            hoveredCenterSlot = hoveredCenterSlot,
                            draggingCenterAction = draggingCenterAction,
                            activePlaced = activePlaced,
                            dropIndex = topBarDropIndex,
                            dragState = dragState,
                            accent = primary,
                            onSelect = onSelect,
                            onFinishDrag = onFinishDrag,
                            onBarBounds = { dragState.topBarBounds = it },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(Brush.verticalGradient(if (mode.isClips) ClipTopScrim else TopScrim))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )

                        if (mode.isVideo) {
                            // 视频模式中央 5 槽位簇预览：复用真实 PlayerCenterCluster(previewMode = true)
                            if (transportInCenter) {
                                PlayerCenterCluster(
                                    config = layout.center,
                                    centerVisible = true,
                                    showSideButtons = true,
                                    isPlaying = false,
                                    isLoading = false,
                                    indicator = null,
                                    large = mode.isLandscape,
                                    canPrevious = true,
                                    canNext = true,
                                    allowSeek = true,
                                    onPlayPause = { onSelect(SlotSelection.CenterSlot(CenterSlotPosition.Center)) },
                                    onPrevious = {},
                                    onNext = {},
                                    onSeekDeltaMillis = {},
                                    previewMode = true,
                                    selectedSlot = (selection as? SlotSelection.CenterSlot)?.position,
                                    onSelectSlot = { pos -> onSelect(SlotSelection.CenterSlot(pos)) },
                                    dropTargetSlot = hoveredCenterSlot,
                                    onSlotBounds = { pos, rect -> dragState.centerSlotBounds[pos] = rect },
                                    scaleMultiplier = if (mode.isLandscape) 0.76f else 0.62f,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }

                            // 竖屏视频悬浮「全屏播放」入口
                            if (mode == PlayerLayoutMode.VideoPortrait) {
                                val promptSelected = selection == SlotSelection.PortraitFullscreenPrompt
                                val bottomPad = if (compactMaxHeight != null && compactMaxHeight < 300.dp) 68.dp else 94.dp
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = bottomPad)
                                        .then(
                                            if (promptSelected) {
                                                Modifier
                                                    .border(2.dp, primary, CircleShape)
                                                    .padding(3.dp)
                                            } else {
                                                Modifier
                                            },
                                        ),
                                ) {
                                    FullscreenPromptButtonContent(
                                        onClick = onTogglePortraitFullscreenPrompt,
                                        muted = !showPortraitFullscreenPrompt,
                                        modifier = Modifier.alpha(if (showPortraitFullscreenPrompt) 1f else 0.58f),
                                    )
                                }
                            }

                            // 视频底部真实 PlayerSeekBar + 底栏预览
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(Brush.verticalGradient(BottomScrim))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                PlayerSeekBar(
                                    positionMillisProvider = { 754_000L },
                                    durationMillis = 2_700_000L,
                                    bufferedPositionMillisProvider = { 1_350_000L },
                                    onSeek = {},
                                    isPlaying = false,
                                    thumbOnHoverOnly = false,
                                    wavy = false,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                PreviewBarRow(
                                    mode = mode,
                                    bar = PlayerBarSlot.Bottom,
                                    items = layout.bottomBar,
                                    layout = layout,
                                    statusBar = statusBar,
                                    transportInCenter = transportInCenter,
                                    selection = selection,
                                    hoveredCenterSlot = hoveredCenterSlot,
                                    draggingCenterAction = draggingCenterAction,
                                    activePlaced = activePlaced,
                                    dropIndex = bottomBarDropIndex,
                                    dragState = dragState,
                                    accent = primary,
                                    onSelect = onSelect,
                                    onFinishDrag = onFinishDrag,
                                    onBarBounds = { dragState.bottomBarBounds = it },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            // 信息流（ClipsPortrait 竖屏右列带标签 / ClipsLandscape 横屏右下横排纯图标）1:1 对齐真实 ClipChrome
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .fillMaxHeight(if (mode.isLandscape) 0.48f else 0.62f),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Brush.verticalGradient(ClipBottomScrim)),
                                )
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(
                                            start = if (mode.isLandscape) 16.dp else 12.dp,
                                            end = if (mode.isLandscape) 14.dp else 8.dp,
                                            bottom = 24.dp,
                                        ),
                                    horizontalArrangement = if (mode.isLandscape) Arrangement.SpaceBetween else Arrangement.Start,
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    ClipCaption(
                                        clip = sampleClip,
                                        startMs = sampleClip.startMs,
                                        folderName = sampleFolder,
                                        compact = true,
                                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    )
                                    PreviewClipActionRail(
                                        items = layout.bottomBar,
                                        horizontal = mode.isLandscape,
                                        selection = selection,
                                        activePlaced = activePlaced,
                                        dropIndex = bottomBarDropIndex,
                                        dragState = dragState,
                                        accent = primary,
                                        onSelect = onSelect,
                                        onFinishDrag = onFinishDrag,
                                        onRailBounds = { dragState.bottomBarBounds = it },
                                    )
                                }
                                PlayerSeekBar(
                                    positionMillisProvider = { 12_000L },
                                    durationMillis = 30_000L,
                                    bufferedPositionMillisProvider = { 24_000L },
                                    onSeek = {},
                                    isPlaying = false,
                                    thumbOnHoverOnly = true,
                                    wavy = false,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp),
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
private fun BarInsertionIndicator(height: Dp = 26.dp) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun RailInsertionIndicator(width: Dp = 36.dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun PreviewClipActionRail(
    items: List<PlayerPanelItem>,
    horizontal: Boolean,
    selection: SlotSelection?,
    activePlaced: DragPayload.PlacedBarItem?,
    dropIndex: Int?,
    dragState: PlayerEditorDragState,
    accent: Color,
    onSelect: (SlotSelection) -> Unit,
    onFinishDrag: () -> Unit,
    onRailBounds: (Rect) -> Unit,
) {
    val latestFinishDrag by rememberUpdatedState(onFinishDrag)
    val fixed = LocalFixedColors.current
    val buttonSize = if (horizontal) 36.dp else 32.dp
    val iconSize = if (horizontal) 20.dp else 18.dp
    val railHighlightMod = if (dropIndex != null) {
        Modifier
            .border(1.5.dp, accent.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
            .padding(4.dp)
    } else {
        Modifier
    }
    val containerMod = Modifier
        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
        .onGloballyPositioned { coords ->
            if (coords.isAttached) onRailBounds(coords.boundsInWindow())
        }
        .then(railHighlightMod)

    val content: @Composable () -> Unit = {
        items.forEachIndexed { index, item ->
            if (dropIndex == index) {
                if (horizontal) BarInsertionIndicator(height = buttonSize) else RailInsertionIndicator()
            }
            val control = item as? PlayerPanelItem.Control ?: return@forEachIndexed
            key(PlayerBarSlot.Bottom, index, control.id) {
                val isSelected = selection == SlotSelection.PanelItem(PlayerBarSlot.Bottom, index)
                val isBeingDragged = activePlaced?.bar == PlayerBarSlot.Bottom && activePlaced.index == index
                val coordsRef = remember { LayoutCoordsRef() }
                val dragMod = Modifier
                    .alpha(if (isBeingDragged) 0.32f else 1f)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            coordsRef.value = coords
                            dragState.barItemBounds[PlayerBarSlot.Bottom to index] = coords.boundsInWindow()
                        }
                    }
                    .playerEditorDraggable(
                        key1 = PlayerBarSlot.Bottom,
                        key2 = index,
                        key3 = item,
                        requireLongPressOnTouch = false,
                        coordsRef = coordsRef,
                        onDragStart = { pos ->
                            dragState.startDrag(DragPayload.PlacedBarItem(PlayerBarSlot.Bottom, index, item), pos)
                        },
                        onDragMove = { pos -> dragState.updateDrag(pos) },
                        onDragEnd = { latestFinishDrag() },
                        onDragCancel = { dragState.cancelDrag() },
                    )
                    .handCursor()

                if (horizontal) {
                    Surface(
                        shape = CircleShape,
                        color = if (control.showBackground) chromeContainer() else Color.Transparent,
                        contentColor = fixed.OnMedia,
                        modifier = dragMod
                            .size(buttonSize)
                            .clip(CircleShape)
                            .then(
                                if (isSelected) Modifier.border(2.dp, accent, CircleShape) else Modifier,
                            )
                            .clickable { onSelect(SlotSelection.PanelItem(PlayerBarSlot.Bottom, index)) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = controlIcon(control.id, isClips = true),
                                contentDescription = controlLabel(control.id),
                                modifier = Modifier.size(iconSize),
                            )
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = dragMod
                            .clip(RoundedCornerShape(10.dp))
                            .then(
                                if (isSelected) Modifier.border(2.dp, accent, RoundedCornerShape(10.dp)).padding(2.dp) else Modifier,
                            )
                            .clickable { onSelect(SlotSelection.PanelItem(PlayerBarSlot.Bottom, index)) },
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (control.showBackground) chromeContainer() else Color.Transparent,
                            contentColor = fixed.OnMedia,
                            modifier = Modifier.size(buttonSize),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = controlIcon(control.id, isClips = true),
                                    contentDescription = controlLabel(control.id),
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }
                        Text(
                            text = controlLabel(control.id),
                            style = MaterialTheme.typography.labelSmall.copy(shadow = PlayerTextShadow),
                            color = fixed.OnMedia,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 56.dp).padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        if (dropIndex == items.size) {
            if (horizontal) BarInsertionIndicator(height = buttonSize) else RailInsertionIndicator()
        }
    }

    if (horizontal) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = containerMod,
        ) { content() }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = containerMod,
        ) { content() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewBarRow(
    mode: PlayerLayoutMode,
    bar: PlayerBarSlot,
    items: List<PlayerPanelItem>,
    layout: PlayerFormFactorLayout,
    statusBar: PlayerStatusBarConfig,
    transportInCenter: Boolean,
    selection: SlotSelection?,
    hoveredCenterSlot: CenterSlotPosition?,
    draggingCenterAction: Boolean,
    activePlaced: DragPayload.PlacedBarItem?,
    dropIndex: Int?,
    dragState: PlayerEditorDragState,
    accent: Color,
    onSelect: (SlotSelection) -> Unit,
    onFinishDrag: () -> Unit,
    onBarBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestFinishDrag by rememberUpdatedState(onFinishDrag)
    val sampleTitle = stringResource(Res.string.player_custom_sample_title)
    val sampleFolder = stringResource(Res.string.drive_custom_sample_folder_title)
    val fixed = LocalFixedColors.current

    val titleIndex = items.indexOfFirst { it is PlayerPanelItem.Control && it.id == PlayerControlId.Title }
    val adjacentSpacerIndex = if (titleIndex >= 0 && items.getOrNull(titleIndex + 1) == PlayerPanelItem.Spacer) {
        titleIndex + 1
    } else {
        -1
    }
    val hasOtherSpacer = items.withIndex().any { (idx, it) ->
        it == PlayerPanelItem.Spacer && idx != adjacentSpacerIndex
    }
    val transportInAnyBar = mode.isVideo && !transportInCenter &&
        (layout.hasInTopBar(PlayerControlId.PlayPause) || layout.hasInBottomBar(PlayerControlId.PlayPause))

    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .onGloballyPositioned { coords ->
                if (coords.isAttached) onBarBounds(coords.boundsInWindow())
            }
            .then(
                if (dropIndex != null) {
                    Modifier.border(1.5.dp, accent.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { index, item ->
            if (dropIndex == index) {
                BarInsertionIndicator(height = 28.dp)
            }

            // 对齐 PlayerBarRenderer：主控组已在栏位展开时，不再重复绘制独立的上一集/下一集/快进快退键
            if (
                item is PlayerPanelItem.Control &&
                transportInAnyBar &&
                item.id != PlayerControlId.PlayPause &&
                layout.center.hasControl(item.id)
            ) {
                dragState.barItemBounds.remove(bar to index)
                return@forEachIndexed
            }

            key(bar, index, item) {
                val isSelected = selection == SlotSelection.PanelItem(bar, index)
                val isBeingDragged = activePlaced?.bar == bar && activePlaced.index == index
                val canDrag = !(
                    mode.isVideo &&
                        bar == PlayerBarSlot.Top &&
                        item is PlayerPanelItem.Control &&
                        item.id == PlayerControlId.Back
                    )
                val coordsRef = remember { LayoutCoordsRef() }
                val baseDragMod = Modifier
                    .alpha(if (isBeingDragged) 0.32f else 1f)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            coordsRef.value = coords
                            dragState.barItemBounds[bar to index] = coords.boundsInWindow()
                        }
                    }
                    .playerEditorDraggable(
                        key1 = bar,
                        key2 = index,
                        key3 = item,
                        enabled = canDrag,
                        requireLongPressOnTouch = false,
                        coordsRef = coordsRef,
                        onDragStart = { pos ->
                            dragState.startDrag(DragPayload.PlacedBarItem(bar, index, item), pos)
                        },
                        onDragMove = { pos -> dragState.updateDrag(pos) },
                        onDragEnd = { latestFinishDrag() },
                        onDragCancel = { dragState.cancelDrag() },
                    )
                    .handCursor(canDrag)

                when (item) {
                    PlayerPanelItem.Spacer -> {
                        val spacerMod = if (index == adjacentSpacerIndex) {
                            Modifier.width(22.dp)
                        } else {
                            Modifier.weight(1f)
                        }
                        Box(
                            modifier = spacerMod
                                .then(baseDragMod)
                                .height(24.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) accent else fixed.HudSpecularBorder,
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .clickable { onSelect(SlotSelection.PanelItem(bar, index)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SpaceBar,
                                contentDescription = stringResource(Res.string.player_custom_spacer),
                                tint = fixed.OnMedia.copy(alpha = 0.45f),
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }

                    is PlayerPanelItem.Control -> {
                        // 键鼠视角或关闭中央播放键时，底栏 PlayPause 位置复用真实 ControlBarTransportCluster
                        if (item.id == PlayerControlId.PlayPause && mode.isVideo && !transportInCenter) {
                            ControlBarTransportCluster(
                                config = layout.center,
                                isPlaying = false,
                                showEpisodeSkip = true,
                                canPrevious = true,
                                canNext = true,
                                allowSeek = true,
                                onTogglePlayPause = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                onPrevious = {},
                                onNext = {},
                                onSeekDeltaMillis = {},
                                previewMode = true,
                                selectedSlot = (selection as? SlotSelection.CenterSlot)?.position,
                                isPlaySelected = isSelected,
                                onSelectSlot = { pos -> onSelect(SlotSelection.CenterSlot(pos)) },
                                dropTargetSlot = hoveredCenterSlot,
                                showEmptySlots = draggingCenterAction,
                                onSlotBounds = { pos, rect -> dragState.centerSlotBounds[pos] = rect },
                                modifier = baseDragMod,
                            )
                        } else {
                            val hiddenByCenter = mode.isVideo && layout.isHiddenByCenter(item.id, transportInCenter)
                        val selectMod = if (isSelected) {
                            Modifier
                                .border(2.dp, accent, CircleShape)
                                .padding(2.dp)
                        } else {
                            Modifier
                        }

                        when (item.id) {
                            PlayerControlId.Title -> {
                                val titleBoxMod = if (hasOtherSpacer) {
                                    Modifier.weight(1f, fill = false)
                                } else {
                                    Modifier.weight(1f)
                                }
                                if (mode.isClips && bar == PlayerBarSlot.Top) {
                                    Box(
                                        modifier = titleBoxMod.then(baseDragMod),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        ScopeTitle(
                                            title = sampleFolder,
                                            compact = true,
                                            showBackground = item.showBackground,
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .then(
                                                    if (isSelected) Modifier.border(2.dp, accent, CircleShape) else Modifier,
                                                )
                                                .clickable { onSelect(SlotSelection.PanelItem(bar, index)) },
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = titleBoxMod
                                            .then(baseDragMod)
                                            .alpha(if (hiddenByCenter) 0.32f else 1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .then(
                                                if (isSelected) {
                                                    Modifier.border(2.dp, accent, RoundedCornerShape(8.dp))
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .clickable { onSelect(SlotSelection.PanelItem(bar, index)) }
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = sampleTitle,
                                            style = MaterialTheme.typography.titleMediumEmphasized.copy(shadow = PlayerTextShadow),
                                            color = fixed.OnMedia,
                                            maxLines = 1,
                                            overflow = TextOverflow.MiddleEllipsis,
                                        )
                                    }
                                }
                            }

                            PlayerControlId.TimeLabel -> Box(
                                modifier = baseDragMod
                                    .alpha(if (hiddenByCenter) 0.32f else 1f)
                                    .clip(CircleShape)
                                    .then(selectMod)
                                    .clickable { onSelect(SlotSelection.PanelItem(bar, index)) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            ) {
                                PlayerTimeReadout(
                                    positionMillisProvider = { 754_000L },
                                    scrubPositionMillis = null,
                                    previewPositionMillis = null,
                                    durationMillis = 2_700_000L,
                                )
                            }

                            PlayerControlId.SystemStatus -> Box(
                                modifier = baseDragMod
                                    .alpha(if (hiddenByCenter) 0.32f else 1f)
                                    .clip(CircleShape)
                                    .then(selectMod)
                                    .clickable { onSelect(SlotSelection.PanelItem(bar, index)) },
                            ) {
                                if (statusBar.hasAnyItem) {
                                    PlayerSystemStatusCapsule(
                                        showBackground = item.showBackground,
                                        config = statusBar,
                                        previewStatus = PreviewSampleSystemStatus,
                                        previewClockText = "16:20",
                                    )
                                } else {
                                    Text(
                                        text = "—",
                                        style = MaterialTheme.typography.labelSmall.tabular,
                                        color = fixed.OnMedia.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }

                            PlayerControlId.Volume -> if (mode.isVideo && mode.isLandscape && bar == PlayerBarSlot.Bottom) {
                                Box(
                                    modifier = baseDragMod
                                        .alpha(if (hiddenByCenter) 0.32f else 1f)
                                        .then(selectMod),
                                ) {
                                    PlayerVolumeControl(
                                        volume = 0.72f,
                                        showSlider = true,
                                        onVolumeChange = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                        onToggleMute = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                        onDraggingChange = {},
                                        showBackground = item.showBackground,
                                    )
                                }
                            } else {
                                PreviewBarIconButton(
                                    icon = controlIcon(item.id, isClips = mode.isClips),
                                    label = controlLabel(item.id),
                                    isClips = mode.isClips,
                                    isBack = false,
                                    showBackground = item.showBackground,
                                    isSelected = isSelected,
                                    hiddenByCenter = hiddenByCenter,
                                    accent = accent,
                                    onClick = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                    modifier = baseDragMod,
                                )
                            }

                            PlayerControlId.Episodes -> if (mode.isVideo && mode.isLandscape && bar == PlayerBarSlot.Bottom) {
                                Box(
                                    modifier = baseDragMod
                                        .alpha(if (hiddenByCenter) 0.32f else 1f)
                                        .then(selectMod),
                                ) {
                                    PlayerChipButton(
                                        text = tr("选集", "Playlist", "再生リスト", "재생 목록", "選集"),
                                        icon = Icons.Outlined.VideoLibrary,
                                        onClick = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                        tooltip = null,
                                        showBackground = item.showBackground,
                                    )
                                }
                            } else {
                                PreviewBarIconButton(
                                    icon = controlIcon(item.id, isClips = mode.isClips),
                                    label = controlLabel(item.id),
                                    isClips = mode.isClips,
                                    isBack = false,
                                    showBackground = item.showBackground,
                                    isSelected = isSelected,
                                    hiddenByCenter = hiddenByCenter,
                                    accent = accent,
                                    onClick = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                    modifier = baseDragMod,
                                )
                            }

                            PlayerControlId.Speed -> Box(
                                modifier = baseDragMod
                                    .alpha(if (hiddenByCenter) 0.32f else 1f)
                                    .then(selectMod),
                            ) {
                                PlayerChipButton(
                                    text = "1.0x",
                                    onClick = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                    tooltip = null,
                                    showBackground = item.showBackground,
                                )
                            }

                            PlayerControlId.Quality -> Box(
                                modifier = baseDragMod
                                    .alpha(if (hiddenByCenter) 0.32f else 1f)
                                    .then(selectMod),
                            ) {
                                PlayerChipButton(
                                    text = "1080P",
                                    onClick = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                    tooltip = null,
                                    showBackground = item.showBackground,
                                )
                            }

                            else -> PreviewBarIconButton(
                                icon = controlIcon(item.id, isClips = mode.isClips),
                                label = controlLabel(item.id),
                                isClips = mode.isClips,
                                isBack = item.id == PlayerControlId.Back,
                                showBackground = item.showBackground,
                                isSelected = isSelected,
                                hiddenByCenter = hiddenByCenter,
                                accent = accent,
                                onClick = { onSelect(SlotSelection.PanelItem(bar, index)) },
                                modifier = baseDragMod,
                            )
                        }
                        }
                    }
                }
            }
        }
        if (dropIndex == items.size) {
            BarInsertionIndicator(height = 28.dp)
        }
    }
}

@Composable
private fun PreviewBarIconButton(
    icon: ImageVector,
    label: String,
    isClips: Boolean,
    isBack: Boolean,
    showBackground: Boolean,
    isSelected: Boolean,
    hiddenByCenter: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fixed = LocalFixedColors.current
    if (isClips) {
        Surface(
            shape = CircleShape,
            color = if (showBackground) chromeContainer() else Color.Transparent,
            contentColor = fixed.OnMedia,
            modifier = modifier
                .size(32.dp)
                .alpha(if (hiddenByCenter) 0.32f else 1f)
                .clip(CircleShape)
                .then(if (isSelected) Modifier.border(2.dp, accent, CircleShape) else Modifier)
                .clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    } else {
        Box(
            modifier = modifier
                .alpha(if (hiddenByCenter) 0.32f else 1f)
                .then(
                    if (isSelected) Modifier.border(2.dp, accent, CircleShape).padding(1.dp) else Modifier,
                ),
        ) {
            PlayerIconButton(
                icon = icon,
                label = label,
                onClick = onClick,
                showBackground = showBackground,
                containerSize = if (isBack) DpSize(30.dp, 36.dp) else DpSize(36.dp, 36.dp),
                iconSize = 18.dp,
            )
        }
    }
}

/**
 * 可用控件托盘：同时承担拖放移除区。
 * - 平时展示当前模式尚未放置的栏位控件、弹性空白与（视频模式）中央快捷动作；
 * - 拖拽预览中已放置的栏位控件时，托盘边框与标题动态切换为「松手移除」或「必需控件」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlPaletteTray(
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
                            icon = controlIcon(id, isClips = mode.isClips),
                            label = controlLabel(id),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListenCenterActionsTray(
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
private fun CenterActionPaletteChipsRow(
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
private fun DragGhostOverlay(
    payload: DragPayload,
    pointerInRoot: Offset,
    isClips: Boolean,
    removing: Boolean,
) {
    val eInk = LocalAppearance.current.isEInk
    val icon: ImageVector = when (payload) {
        is DragPayload.PaletteControl -> controlIcon(payload.id, isClips = isClips)
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
            is PlayerPanelItem.Control -> controlIcon(payload.item.id, isClips = isClips)
        }
    }
    val label: String = when (payload) {
        is DragPayload.PaletteControl -> controlLabel(payload.id)
        DragPayload.PaletteSpacer -> stringResource(Res.string.player_custom_spacer)
        is DragPayload.PaletteCenterAction -> centerActionLabel(payload.action)
        is DragPayload.PlacedBarItem -> when (payload.item) {
            PlayerPanelItem.Spacer -> stringResource(Res.string.player_custom_spacer)
            is PlayerPanelItem.Control -> controlLabel(payload.item.id)
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

/** 紧凑的预设下拉 + 触屏中央控件开关条（避免铺满一排高权重 FilterChip）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetAndTouchStrip(
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

private fun supportsBackgroundToggle(id: PlayerControlId, mode: PlayerLayoutMode): Boolean = when (id) {
    PlayerControlId.TimeLabel, PlayerControlId.PlayPause -> false
    PlayerControlId.Title -> mode.isClips
    else -> true
}

/**
 * 紧凑选中项属性检查器：
 * - 单行头部 + 紧凑图标按钮条（左/上移、右/下移、换栏、移除、关闭）；
 * - 动作、尺寸（CenterSlotScale）与步长秒数（含「自定义…」输入弹窗）均使用 `PikoDropdownMenu`。
 */
@Composable
private fun SelectionInspectorCard(
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
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        CompactSwitchRow(
                            title = stringResource(Res.string.player_custom_slot_center),
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
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                                modifier = Modifier.size(36.dp),
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
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            TooltipIconButton(
                                icon = Icons.Filled.Close,
                                label = closeLabel,
                                onClick = { onSelect(null) },
                                modifier = Modifier.size(36.dp),
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
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    CompactSwitchRow(
                        title = stringResource(Res.string.player_custom_portrait_fullscreen_prompt),
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
                            is PlayerPanelItem.Control -> controlLabel(item.id)
                        }
                        val itemIcon = when (item) {
                            PlayerPanelItem.Spacer -> Icons.Filled.SpaceBar
                            is PlayerPanelItem.Control -> controlIcon(item.id, isClips = mode.isClips)
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
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                                modifier = Modifier.size(36.dp),
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
                                modifier = Modifier.size(36.dp),
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
                                    modifier = Modifier.size(36.dp),
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
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            TooltipIconButton(
                                icon = Icons.Filled.Close,
                                label = closeLabel,
                                onClick = { onSelect(null) },
                                modifier = Modifier.size(36.dp),
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
private fun CenterSlotCompactControls(
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
private fun CompactSwitchRow(
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
private fun <T> InspectorDropdownRow(
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

@Composable
private fun <T> InspectorDropdownButton(
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
            contentPadding = PaddingValues(start = 10.dp, end = 6.dp),
            modifier = Modifier.defaultMinSize(minWidth = 1.dp),
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
private fun SeekSecondsDropdownRow(
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ListenPreviewCanvas(
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
    val coverSize = if (compact) 84.dp else 144.dp

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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            if (config.coverStyle == ListenCoverStyle.VinylDisc) {
                ListenVinylDiscVisual(
                    coverUrl = "",
                    discSize = coverSize,
                )
            } else {
                ListenStaticCover(
                    coverUrl = "",
                    maxWidth = coverSize,
                    maxHeight = coverSize,
                )
            }

            Text(
                text = displayTitle,
                style = if (compact) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.headlineSmallEmphasized,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!compact) {
                ListenTimeReadout(
                    positionMillis = 88_000L,
                    durationMillis = 255_000L,
                    active = false,
                    cancelArmed = false,
                )
            }

            PlayerSeekBar(
                positionMillisProvider = { 88_000L },
                durationMillis = 255_000L,
                bufferedPositionMillisProvider = { 160_000L },
                onSeek = {},
                isPlaying = previewPlaying,
                wavy = true,
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            )

            ListenTransportRow(
                config = config,
                isPlaying = previewPlaying,
                hasPrevious = true,
                hasNext = true,
                onTogglePlayPause = { previewPlaying = !previewPlaying },
                onPrevious = {},
                onNext = {},
                onSeekBySeconds = {},
                previewMode = true,
                selectedSlot = selectedSlot,
                onSelectSlot = onSelectSlot,
                dropTargetSlot = dropTargetSlot,
                onSlotBounds = onSlotBounds,
            )

            if (!compact && config.visibleChips.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    config.visibleChips.forEach { chip ->
                        val chipIcon = when (chip) {
                            ListenQuickChip.Speed -> Icons.Filled.Speed
                            ListenQuickChip.SleepTimer -> Icons.Filled.Bedtime
                            ListenQuickChip.Queue -> Icons.AutoMirrored.Outlined.QueueMusic
                        }
                        val chipLabel = when (chip) {
                            ListenQuickChip.Speed -> stringResource(Res.string.listen_speed)
                            ListenQuickChip.SleepTimer -> stringResource(Res.string.listen_sleep_timer)
                            ListenQuickChip.Queue -> stringResource(Res.string.listen_queue_count_fmt, 12)
                        }
                        ListenActionPill(
                            icon = chipIcon,
                            label = chipLabel,
                            highlighted = false,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListenCustomizationEditor(
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                    modifier = Modifier.size(36.dp),
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
                        modifier = Modifier.size(36.dp),
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

    // 2. 封面样式与底部快捷入口
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

@Composable
private fun GesturePreviewCanvas(
    gestures: PlayerGestureConfig,
    compactMaxHeight: Dp?,
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val eInk = LocalAppearance.current.isEInk
    val baseMod = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = Color(0xFF101318),
        border = BorderStroke(
            width = 1.dp,
            color = if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
        modifier = if (compactMaxHeight != null) baseMod.heightIn(max = compactMaxHeight) else baseMod,
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val vWidth = size.width * gestures.verticalZoneFraction
                val dtWidth = size.width * gestures.doubleTapZoneFraction
                if (gestures.swipeBrightnessVolumeEnabled && gestures.leftVerticalSwipe != VerticalSwipeAction.None) {
                    drawRoundRect(
                        color = primary.copy(alpha = 0.22f),
                        topLeft = Offset.Zero,
                        size = Size(vWidth, size.height),
                        cornerRadius = CornerRadius(12f, 12f),
                    )
                }
                if (gestures.swipeBrightnessVolumeEnabled && gestures.rightVerticalSwipe != VerticalSwipeAction.None) {
                    drawRoundRect(
                        color = tertiary.copy(alpha = 0.22f),
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
            Row(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = verticalActionLabel(gestures.leftVerticalSwipe),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                if (gestures.swipeSeekEnabled) {
                    Text(
                        text = sensitivityLabel(gestures.swipeSeekSensitivity),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                Text(
                    text = verticalActionLabel(gestures.rightVerticalSwipe),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun GestureCustomizationEditor(
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
    SettingsGroup(stringResource(Res.string.player_custom_mode_gestures)) {
        SettingsSwitchRow(
            icon = Icons.Filled.FastForward,
            title = stringResource(Res.string.player_custom_gesture_double_tap),
            supporting = null,
            checked = g.doubleTapSeekEnabled,
            onCheckedChange = { enabled -> onUpdate { p -> p.updateGestures { it.copy(doubleTapSeekEnabled = enabled) } } },
        )
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

@Composable
private fun barSlotLabel(mode: PlayerLayoutMode, bar: PlayerBarSlot): String = when {
    bar == PlayerBarSlot.Top -> stringResource(Res.string.player_custom_section_top_bar)
    mode == PlayerLayoutMode.ClipsPortrait -> stringResource(Res.string.player_custom_right_rail)
    else -> stringResource(Res.string.player_custom_section_bottom_bar)
}

@Composable
private fun tabLabel(tab: CustomizationTab): String = when (tab) {
    CustomizationTab.VideoLandscape -> stringResource(Res.string.player_custom_mode_video_land)
    CustomizationTab.VideoPortrait -> stringResource(Res.string.player_custom_mode_video_port)
    CustomizationTab.ClipsLandscape -> stringResource(Res.string.player_custom_mode_clips_land)
    CustomizationTab.ClipsPortrait -> stringResource(Res.string.player_custom_mode_clips_port)
    CustomizationTab.Listen -> stringResource(Res.string.player_custom_mode_listen)
    CustomizationTab.Gestures -> stringResource(Res.string.player_custom_mode_gestures)
}

@Composable
private fun presetLabel(preset: PlayerLayoutPreset): String = when (preset) {
    PlayerLayoutPreset.Default -> stringResource(Res.string.player_custom_preset_default)
    PlayerLayoutPreset.Minimal -> stringResource(Res.string.player_custom_preset_minimal)
    PlayerLayoutPreset.Podcast -> stringResource(Res.string.player_custom_preset_podcast)
}

@Composable
private fun centerSlotLabel(pos: CenterSlotPosition): String = when (pos) {
    CenterSlotPosition.OuterLeft -> stringResource(Res.string.player_custom_slot_outer_left)
    CenterSlotPosition.InnerLeft -> stringResource(Res.string.player_custom_slot_inner_left)
    CenterSlotPosition.Center -> stringResource(Res.string.player_custom_slot_center)
    CenterSlotPosition.InnerRight -> stringResource(Res.string.player_custom_slot_inner_right)
    CenterSlotPosition.OuterRight -> stringResource(Res.string.player_custom_slot_outer_right)
}

@Composable
private fun centerActionLabel(action: CenterSlotAction): String = when (action) {
    CenterSlotAction.None -> stringResource(Res.string.player_custom_slot_action_none)
    CenterSlotAction.Previous -> stringResource(Res.string.player_custom_slot_action_prev)
    CenterSlotAction.Next -> stringResource(Res.string.player_custom_slot_action_next)
    is CenterSlotAction.SeekBackward -> stringResource(Res.string.player_custom_slot_action_rewind)
    is CenterSlotAction.SeekForward -> stringResource(Res.string.player_custom_slot_action_forward)
}

@Composable
private fun scaleLabel(scale: CenterSlotScale): String = when (scale) {
    CenterSlotScale.Large -> stringResource(Res.string.player_custom_scale_large)
    CenterSlotScale.Medium -> stringResource(Res.string.player_custom_scale_medium)
    CenterSlotScale.Small -> stringResource(Res.string.player_custom_scale_small)
    CenterSlotScale.ExtraSmall -> stringResource(Res.string.player_custom_scale_xs)
}

@Composable
private fun listenChipLabel(chip: ListenQuickChip): String = when (chip) {
    ListenQuickChip.Speed -> stringResource(Res.string.player_custom_listen_chip_speed)
    ListenQuickChip.SleepTimer -> stringResource(Res.string.player_custom_listen_chip_sleep)
    ListenQuickChip.Queue -> stringResource(Res.string.player_custom_listen_chip_queue)
}

@Composable
private fun verticalActionLabel(action: VerticalSwipeAction): String = when (action) {
    VerticalSwipeAction.Brightness -> stringResource(Res.string.player_custom_gesture_act_brightness)
    VerticalSwipeAction.Volume -> stringResource(Res.string.player_custom_gesture_act_volume)
    VerticalSwipeAction.Episode -> stringResource(Res.string.player_custom_gesture_act_episode)
    VerticalSwipeAction.None -> stringResource(Res.string.player_custom_gesture_act_none)
}

@Composable
private fun sensitivityLabel(sensitivity: SwipeSeekSensitivity): String = when (sensitivity) {
    SwipeSeekSensitivity.Low -> stringResource(Res.string.player_custom_gesture_sens_low)
    SwipeSeekSensitivity.Standard -> stringResource(Res.string.player_custom_gesture_sens_standard)
    SwipeSeekSensitivity.High -> stringResource(Res.string.player_custom_gesture_sens_high)
}

@Composable
private fun controlLabel(id: PlayerControlId): String = when (id) {
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

private fun controlIcon(id: PlayerControlId, isClips: Boolean = false): ImageVector = when (id) {
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
