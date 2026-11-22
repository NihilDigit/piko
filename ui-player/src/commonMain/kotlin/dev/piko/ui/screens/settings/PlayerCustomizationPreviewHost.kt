package dev.piko.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.CenterSlotPosition
import dev.piko.shared.media.player.PlayerBarSlot
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerFormFactorLayout
import dev.piko.shared.media.player.PlayerLayoutMode
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.media.player.PlayerPanelItem
import dev.piko.shared.media.player.PlayerStatusBarConfig
import dev.piko.shared.state.Clip
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.drive_custom_sample_folder_title
import dev.piko.ui.resources.player_custom_sample_title
import dev.piko.ui.resources.player_custom_spacer
import dev.piko.ui.screens.clips.BottomScrim as ClipBottomScrim
import dev.piko.ui.screens.clips.ClipControls
import dev.piko.ui.screens.clips.ClipFeedTopBar
import dev.piko.ui.screens.player.CustomizablePlayerBottomBar
import dev.piko.ui.screens.player.CustomizablePlayerTopBar
import dev.piko.ui.screens.player.FullscreenPromptButtonContent
import dev.piko.ui.screens.player.LocalPlayerLayoutPrefs
import dev.piko.ui.screens.player.PlayerBarEditScope
import dev.piko.ui.screens.player.PlayerBarEnvironment
import dev.piko.ui.screens.player.PlayerCenterCluster
import dev.piko.ui.screens.player.PlayerSeekBar
import dev.piko.ui.screens.player.PlayerTheme
import dev.piko.ui.screens.player.handCursor
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.isEInk

@Composable
internal fun PreviewCanvasSection(
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
    CompositionLocalProvider(LocalPlayerLayoutPrefs provides prefs) {
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
                    prefs = prefs,
                    compactMaxHeight = compactPreviewHeight,
                )
            }
        }
    }
}

/**
 * 零分叉预览宿主：直接挂载真实 [CustomizablePlayerTopBar]、[CustomizablePlayerBottomBar]、
 * [ClipFeedTopBar] 与 [ClipControls]，仅通过 [PlayerBarEditScope] 注入拖放热区、实时插入线与选中描边。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerPreviewCanvas(
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
    val sampleTitle = stringResource(Res.string.player_custom_sample_title)
    val sampleClip = remember(sampleTitle) {
        Clip(
            fileId = "preview-clip",
            name = sampleTitle,
            parentId = "preview-folder",
            startMs = 754_000L,
            videoDurationMs = 2_700_000L,
            occurrence = 1L,
        )
    }
    val eInk = LocalAppearance.current.isEInk
    val outlineColor = MaterialTheme.colorScheme.outline
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val latestFinishDrag by rememberUpdatedState(onFinishDrag)
    val latestOnSelect by rememberUpdatedState(onSelect)

    PlayerTheme {
        val primary = MaterialTheme.colorScheme.primary
        val fixed = LocalFixedColors.current

        val editScope = remember(
            mode,
            transportInCenter,
            selection,
            activePlaced,
            topBarDropIndex,
            bottomBarDropIndex,
            hoveredCenterSlot,
            draggingCenterAction,
            primary,
            fixed,
        ) {
            object : PlayerBarEditScope {
                override val selectedCenterSlot: CenterSlotPosition? =
                    (selection as? SlotSelection.CenterSlot)?.position
                override val hoveredCenterSlot: CenterSlotPosition? = hoveredCenterSlot
                override val draggingCenterAction: Boolean = draggingCenterAction

                private fun dropIndexFor(bar: PlayerBarSlot): Int? =
                    if (bar == PlayerBarSlot.Top) topBarDropIndex else bottomBarDropIndex

                override fun barContainerModifier(bar: PlayerBarSlot): Modifier {
                    val hasDrop = dropIndexFor(bar) != null
                    return Modifier
                        .heightIn(min = 36.dp)
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached) {
                                val rect = coords.boundsInWindow()
                                if (bar == PlayerBarSlot.Top) dragState.topBarBounds = rect
                                else dragState.bottomBarBounds = rect
                            }
                        }
                        .then(
                            if (hasDrop) {
                                Modifier.border(1.5.dp, primary.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                            } else {
                                Modifier
                            },
                        )
                }

                @Composable
                override fun RenderDropIndicator(bar: PlayerBarSlot, index: Int, vertical: Boolean) {
                    if (dropIndexFor(bar) == index) {
                        if (vertical) RailInsertionIndicator() else BarInsertionIndicator()
                    }
                }

                override fun onSlotSkipped(bar: PlayerBarSlot, index: Int) {
                    dragState.barItemBounds.remove(bar to index)
                }

                override fun isSlotSelected(bar: PlayerBarSlot, index: Int): Boolean =
                    selection == SlotSelection.PanelItem(bar, index)

                override fun onSelectSlot(bar: PlayerBarSlot, index: Int) {
                    latestOnSelect(SlotSelection.PanelItem(bar, index))
                }

                override fun onSelectCenterSlot(position: CenterSlotPosition) {
                    latestOnSelect(SlotSelection.CenterSlot(position))
                }

                override fun onCenterSlotBounds(position: CenterSlotPosition, bounds: Rect) {
                    dragState.centerSlotBounds[position] = bounds
                }

                @Composable
                override fun DecorateSlot(
                    bar: PlayerBarSlot,
                    index: Int,
                    item: PlayerPanelItem,
                    hiddenByCenter: Boolean,
                    clickableContainer: Boolean,
                    modifier: Modifier,
                    content: @Composable () -> Unit,
                ) {
                    val isSelected = isSlotSelected(bar, index)
                    val isBeingDragged = activePlaced?.bar == bar && activePlaced.index == index
                    val canDrag = !(
                        mode.isVideo &&
                            bar == PlayerBarSlot.Top &&
                            item is PlayerPanelItem.Control &&
                            item.id == PlayerControlId.Back
                        )
                    val coordsRef = remember { LayoutCoordsRef() }
                    val isExpandedPlayCluster = mode.isVideo &&
                        !transportInCenter &&
                        item is PlayerPanelItem.Control &&
                        item.id == PlayerControlId.PlayPause
                    val isTitleBlock = item is PlayerPanelItem.Control && item.id == PlayerControlId.Title
                    val isPortraitRailItem = mode == PlayerLayoutMode.ClipsPortrait && bar == PlayerBarSlot.Bottom
                    val ringShape = if (isTitleBlock || isPortraitRailItem) RoundedCornerShape(10.dp) else CircleShape

                    Box(
                        modifier = modifier
                            .alpha(if (isBeingDragged || hiddenByCenter) 0.32f else 1f)
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
                            .then(
                                if (clickableContainer) {
                                    Modifier
                                        .clip(ringShape)
                                        .clickable { onSelectSlot(bar, index) }
                                } else {
                                    Modifier
                                },
                            )
                            .then(
                                if (isSelected && !isExpandedPlayCluster) {
                                    Modifier
                                        .border(2.dp, primary, ringShape)
                                        .padding(2.dp)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        content()
                    }
                }

                @Composable
                override fun SpacerVisual(
                    bar: PlayerBarSlot,
                    index: Int,
                    modifier: Modifier,
                ) {
                    val isSelected = isSlotSelected(bar, index)
                    val isBeingDragged = activePlaced?.bar == bar && activePlaced.index == index
                    val coordsRef = remember { LayoutCoordsRef() }
                    val spacerShape = RoundedCornerShape(8.dp)

                    Box(
                        modifier = modifier
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
                                key3 = PlayerPanelItem.Spacer,
                                enabled = true,
                                requireLongPressOnTouch = false,
                                coordsRef = coordsRef,
                                onDragStart = { pos ->
                                    dragState.startDrag(DragPayload.PlacedBarItem(bar, index, PlayerPanelItem.Spacer), pos)
                                },
                                onDragMove = { pos -> dragState.updateDrag(pos) },
                                onDragEnd = { latestFinishDrag() },
                                onDragCancel = { dragState.cancelDrag() },
                            )
                            .handCursor()
                            .height(24.dp)
                            .clip(spacerShape)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) primary else fixed.HudSpecularBorder,
                                shape = spacerShape,
                            )
                            .clickable { onSelectSlot(bar, index) },
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
            }
        }

        // 预览画布内禁用 M3 默认 48dp 最小触控尺寸外扩，避免微缩圆形按钮的阴影与边框重叠
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
                        if (mode.isVideo) {
                            val previewEnv = remember(
                                mode,
                                layout,
                                statusBar,
                                seekButtonSeconds,
                                transportInCenter,
                                sampleTitle,
                            ) {
                                PlayerBarEnvironment.preview(
                                    mode = mode,
                                    layout = layout,
                                    statusBar = statusBar,
                                    seekButtonSeconds = seekButtonSeconds,
                                    transportInCenter = transportInCenter,
                                    sampleTitle = sampleTitle,
                                    previewCompactScale = 0.86f,
                                )
                            }

                            CustomizablePlayerTopBar(
                                env = previewEnv,
                                editScope = editScope,
                                modifier = Modifier.align(Alignment.TopCenter),
                            )

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

                            CustomizablePlayerBottomBar(
                                env = previewEnv,
                                thumbOnHoverOnly = false,
                                bufferedPositionMillisProvider = { 1_350_000L },
                                onSeek = {},
                                onScrubPositionChange = {},
                                onScrubbingChange = {},
                                editScope = editScope,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        } else {
                            ClipFeedTopBar(
                                title = sampleFolder,
                                muted = false,
                                onToggleMute = {},
                                onClose = {},
                                compact = true,
                                landscapeLayout = mode.isLandscape,
                                layout = layout,
                                paused = true,
                                editScope = editScope,
                                statusBarConfig = statusBar,
                                modifier = Modifier.align(Alignment.TopCenter),
                            )

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
                                ClipControls(
                                    clip = sampleClip,
                                    startMs = sampleClip.startMs,
                                    folderName = sampleFolder,
                                    starred = true,
                                    onToggleStar = {},
                                    onPlayFull = {},
                                    onLocate = {},
                                    compact = true,
                                    landscape = mode.isLandscape,
                                    layout = layout,
                                    paused = true,
                                    canPrevious = true,
                                    canNext = true,
                                    seekStepMillis = seekButtonSeconds * 1_000L,
                                    editScope = editScope,
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
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
internal fun BarInsertionIndicator(height: Dp = 28.dp) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
internal fun RailInsertionIndicator(width: Dp = 36.dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}
