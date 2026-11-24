package dev.piko.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.ScreenRotation
import dev.piko.shared.i18n.tr
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.CenterSlotAction
import dev.piko.shared.media.player.CenterSlotPosition
import dev.piko.shared.media.player.ListenLayoutConfig
import dev.piko.shared.media.player.PlayerBarSlot
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerFormFactorLayout
import dev.piko.shared.media.player.PlayerGestureConfig
import dev.piko.shared.media.player.PlayerLayoutMode
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.media.player.PlayerPanelItem
import dev.piko.ui.adaptive.readableWidth
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.IslandHeaderSpace
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.MenuMotion
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.captionExclusion
import dev.piko.ui.resources.*
import dev.piko.ui.screens.player.handCursor
import dev.piko.ui.theme.LocalPikoMotion

internal enum class CustomizationTab {
    Video,
    Clips,
    Listen,
    Gestures,
    ;

    fun toLayoutModeOrNull(landscape: Boolean): PlayerLayoutMode? = when (this) {
        Video -> if (landscape) PlayerLayoutMode.VideoLandscape else PlayerLayoutMode.VideoPortrait
        Clips -> if (landscape) PlayerLayoutMode.ClipsLandscape else PlayerLayoutMode.ClipsPortrait
        Listen, Gestures -> null
    }
}

internal sealed interface SlotSelection {
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerCustomizationPage(
    prefs: PlayerLayoutPrefs,
    onUpdate: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeTab by rememberSaveable { mutableStateOf(CustomizationTab.Video) }
    var isLandscape by rememberSaveable { mutableStateOf(true) }
    val activeMode = activeTab.toLayoutModeOrNull(isLandscape)
    val activeLayout = activeMode?.let(prefs::layoutFor)
    val defaultTouchPreview = LocalPointerSource.current.isTouchLike
    var previewTouchMode by rememberSaveable { mutableStateOf(defaultTouchPreview) }
    var previewIndicators by rememberSaveable { mutableStateOf(false) }
    var showAddControlSheet by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<SlotSelection?>(null) }
    var selectedListenSlot by rememberSaveable { mutableStateOf(CenterSlotPosition.InnerLeft) }
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

    // 切换模式、横竖屏、布局形态或视角时取消进行中拖拽并清空过期坐标缓存
    LaunchedEffect(activeTab, isLandscape, isWideTwoPane, previewTouchMode) {
        dragState.cancelDrag()
        dragState.clearBounds()
        if (activeMode == null) previewIndicators = false
    }

    LaunchedEffect(dragState.activeDrag != null) {
        if (dragState.activeDrag != null && previewIndicators) {
            previewIndicators = false
        }
    }

    LaunchedEffect(transportInCenter) {
        dragState.centerSlotBounds.clear()
    }

    // 切换模式、横竖屏或栏位长度变化时自动修正越界选中索引与清理越界边界
    LaunchedEffect(activeTab, isLandscape, activeLayout?.topBar?.size, activeLayout?.bottomBar?.size) {
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
    val resetCurrentLabel = stringResource(Res.string.player_custom_reset_current)
    val resetAllLabel = stringResource(Res.string.player_custom_reset_all)
    val isAllDefault = prefs == PlayerLayoutPrefs.Default
    val isCurrentDefault = when {
        activeMode != null -> prefs.layoutFor(activeMode) == PlayerLayoutPrefs.Default.layoutFor(activeMode)
        activeTab == CustomizationTab.Listen -> prefs.listen == ListenLayoutConfig.Default
        else -> prefs.gestures == PlayerGestureConfig.Default
    }
    val resetCurrentTab: () -> Unit = {
        when {
            activeMode != null -> onUpdate { it.resetMode(activeMode) }
            activeTab == CustomizationTab.Listen -> onUpdate { it.copy(listen = ListenLayoutConfig.Default).sanitize() }
            else -> onUpdate { it.copy(gestures = PlayerGestureConfig.Default).sanitize() }
        }
    }
    val resetAll: () -> Unit = {
        onUpdate { PlayerLayoutPrefs.Default }
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
                ResetDefaultSplitButton(
                    resetLabel = resetLabel,
                    resetCurrentLabel = resetCurrentLabel,
                    resetAllLabel = resetAllLabel,
                    canResetCurrent = !isCurrentDefault,
                    onResetCurrent = resetCurrentTab,
                    onResetAll = resetAll,
                    modifier = Modifier.captionExclusion(),
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
                            BarItem(
                                key = "reset_default",
                                priority = PinnedPriority,
                                overflow = buildList {
                                    if (!isCurrentDefault) {
                                        add(SheetAction(Icons.Outlined.RestartAlt, resetCurrentLabel, resetCurrentTab))
                                    }
                                    add(SheetAction(Icons.Outlined.DeleteOutline, resetAllLabel, resetAll))
                                },
                            ) {
                                ResetDefaultSplitButton(
                                    resetLabel = resetLabel,
                                    resetCurrentLabel = resetCurrentLabel,
                                    resetAllLabel = resetAllLabel,
                                    canResetCurrent = !isCurrentDefault,
                                    onResetCurrent = resetCurrentTab,
                                    onResetAll = resetAll,
                                )
                            },
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            CustomizationTabSelector(
                                selected = activeTab,
                                onSelect = { activeTab = it },
                                modifier = Modifier.weight(1f),
                            )
                            if (activeMode != null) {
                                OrientationRotateButton(
                                    isLandscape = isLandscape,
                                    onToggle = { isLandscape = !isLandscape },
                                )
                            }
                        }

                        val motion = LocalPikoMotion.current
                        AnimatedContent(
                            targetState = activeMode to activeTab,
                            transitionSpec = {
                                if (motion.reduced) {
                                    fadeIn() togetherWith fadeOut()
                                } else {
                                    (fadeIn() + scaleIn(initialScale = 0.94f)) togetherWith fadeOut()
                                }
                            },
                            label = "player-preview-orientation",
                        ) { (modeState, tabState) ->
                            val layoutState = modeState?.let(prefs::layoutFor)
                            val centerTransport = modeState != null &&
                                layoutState != null &&
                                modeState.isVideo &&
                                prefs.isCenterControlsActive(previewTouchMode) &&
                                layoutState.center.showCenterPlay
                            PreviewCanvasSection(
                                activeTab = tabState,
                                activeMode = modeState,
                                activeLayout = layoutState,
                                prefs = prefs,
                                transportInCenter = centerTransport,
                                previewIndicators = previewIndicators,
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
                        }

                        if (activeMode != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                PreviewLayerToggle(
                                    showIndicators = previewIndicators,
                                    onShowIndicatorsChange = { previewIndicators = it },
                                )
                                if (activeMode.isVideo) {
                                    PreviewPointerModeToggle(
                                        isTouch = previewTouchMode,
                                        onTouchChange = { previewTouchMode = it },
                                    )
                                }
                            }
                        }

                        if (activeMode != null || activeTab == CustomizationTab.Listen) {
                            PresetAndTouchStrip(
                                prefs = prefs,
                                activeMode = activeMode,
                                showIndicators = previewIndicators,
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
                val maxPortraitVideoPreviewHeight = if (containerHeightDp > 0.dp) {
                    minOf(240.dp, (containerHeightDp * 0.30f).coerceAtLeast(168.dp))
                } else {
                    216.dp
                }
                val maxPortraitClipsPreviewHeight = if (containerHeightDp > 0.dp) {
                    minOf(270.dp, (containerHeightDp * 0.34f).coerceAtLeast(180.dp))
                } else {
                    240.dp
                }
                val maxLandscapePreviewHeight = if (containerHeightDp > 0.dp) {
                    minOf(210.dp, (containerHeightDp * 0.28f).coerceAtLeast(148.dp))
                } else {
                    188.dp
                }
                val compactHeightLimit = when {
                    activeMode == PlayerLayoutMode.VideoPortrait -> maxPortraitVideoPreviewHeight
                    activeMode == PlayerLayoutMode.ClipsPortrait -> maxPortraitClipsPreviewHeight
                    activeTab == CustomizationTab.Listen -> if (containerHeightDp > 0.dp) {
                        minOf(240.dp, (containerHeightDp * 0.34f).coerceAtLeast(172.dp))
                    } else {
                        220.dp
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
                            if (activeMode != null) {
                                OrientationRotateButton(
                                    isLandscape = isLandscape,
                                    onToggle = { isLandscape = !isLandscape },
                                )
                            }
                        }

                        val motion = LocalPikoMotion.current
                        AnimatedContent(
                            targetState = activeMode to activeTab,
                            transitionSpec = {
                                if (motion.reduced) {
                                    fadeIn() togetherWith fadeOut()
                                } else {
                                    (fadeIn() + scaleIn(initialScale = 0.94f)) togetherWith fadeOut()
                                }
                            },
                            label = "player-preview-orientation-compact",
                        ) { (modeState, tabState) ->
                            val layoutState = modeState?.let(prefs::layoutFor)
                            val centerTransport = modeState != null &&
                                layoutState != null &&
                                modeState.isVideo &&
                                prefs.isCenterControlsActive(previewTouchMode) &&
                                layoutState.center.showCenterPlay
                            PreviewCanvasSection(
                                activeTab = tabState,
                                activeMode = modeState,
                                activeLayout = layoutState,
                                prefs = prefs,
                                transportInCenter = centerTransport,
                                previewIndicators = previewIndicators,
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

                        if (activeMode != null) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                PreviewLayerToggle(
                                    showIndicators = previewIndicators,
                                    onShowIndicatorsChange = { previewIndicators = it },
                                )
                                if (activeMode.isVideo) {
                                    PreviewPointerModeToggle(
                                        isTouch = previewTouchMode,
                                        onTouchChange = { previewTouchMode = it },
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                    ) {
                        if (activeMode != null && activeLayout != null) {
                            if (previewIndicators) {
                                PresetAndTouchStrip(
                                    prefs = prefs,
                                    activeMode = activeMode,
                                    showIndicators = true,
                                    onUpdate = onUpdate,
                                )
                            } else {
                                SelectionInspectorCard(
                                    mode = activeMode,
                                    layout = activeLayout,
                                    prefs = prefs,
                                    selection = selection,
                                    onSelect = { selection = it },
                                    onUpdate = onUpdate,
                                )
                                FilledTonalButton(
                                    onClick = { showAddControlSheet = true },
                                    modifier = Modifier.fillMaxWidth().handCursor(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(tr("添加控件到栏位", "Add control to bar", "コントロールを追加", "컨트롤 추가", "新增控制項到欄位"))
                                }
                                PresetAndTouchStrip(
                                    prefs = prefs,
                                    activeMode = activeMode,
                                    showIndicators = false,
                                    onUpdate = onUpdate,
                                )
                            }
                        } else {
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
                            if (activeTab == CustomizationTab.Listen) {
                                PresetAndTouchStrip(
                                    prefs = prefs,
                                    activeMode = null,
                                    showIndicators = false,
                                    onUpdate = onUpdate,
                                )
                            }
                        }
                    }

                    if (showAddControlSheet && activeMode != null && activeLayout != null) {
                        PikoSheet(
                            onDismissRequest = { showAddControlSheet = false },
                            sideSheetTitle = tr("添加控件", "Add control", "コントロールを追加", "컨트롤 추가", "新增控制項"),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ControlPaletteTray(
                                    mode = activeMode,
                                    layout = activeLayout,
                                    seekButtonSeconds = prefs.seekButtonSeconds,
                                    dragState = dragState,
                                    dropTarget = currentDropTarget,
                                    onFinishDrag = finishDrag,
                                    onQuickAddControl = { id ->
                                        onQuickAddControl(id)
                                        showAddControlSheet = false
                                    },
                                    onQuickAddSpacer = {
                                        onQuickAddSpacer()
                                        showAddControlSheet = false
                                    },
                                    onQuickTapCenterAction = { action ->
                                        onQuickTapCenterAction(action)
                                        showAddControlSheet = false
                                    },
                                )
                            }
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
private fun ResetDefaultSplitButton(
    resetLabel: String,
    resetCurrentLabel: String,
    resetAllLabel: String,
    canResetCurrent: Boolean,
    onResetCurrent: () -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.TonalLeadingButton(
                    onClick = { if (canResetCurrent) onResetCurrent() else onResetAll() },
                    modifier = Modifier.handCursor(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(
                        text = if (canResetCurrent) resetCurrentLabel else resetAllLabel,
                        maxLines = 1,
                    )
                }
            },
            trailingButton = {
                SplitButtonDefaults.TonalTrailingButton(
                    checked = menuExpanded,
                    onCheckedChange = { menuExpanded = it },
                    modifier = Modifier.handCursor(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        contentDescription = resetLabel,
                        modifier = Modifier.size(SplitButtonDefaults.TrailingIconSize),
                    )
                }
            },
        )
        MenuMotion {
            PikoDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                val itemsCount = if (canResetCurrent) 2 else 1
                var idx = 0
                if (canResetCurrent) {
                    DropdownMenuItem(
                        text = { Text(resetCurrentLabel) },
                        leadingIcon = {
                            Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        shape = menuItemShape(idx++, itemsCount),
                        onClick = {
                            menuExpanded = false
                            onResetCurrent()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(resetAllLabel) },
                    leadingIcon = {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    shape = menuItemShape(idx, itemsCount),
                    onClick = {
                        menuExpanded = false
                        onResetAll()
                    },
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
                modifier = Modifier.handCursor(),
            ) {
                Text(tabLabel(tab), maxLines = 1)
            }
        }
    }
}

/** 横竖屏旋转切换按钮：点击时图标顺滑旋转 90°，将原本 6 个 Tab 精简为 4 个。 */
@Composable
private fun OrientationRotateButton(
    isLandscape: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalPikoMotion.current.reduced
    val rotation by animateFloatAsState(
        targetValue = if (isLandscape) 0f else 90f,
        label = "player-orientation-icon",
    )
    FilledTonalButton(
        onClick = onToggle,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = modifier.handCursor(),
    ) {
        Icon(
            imageVector = Icons.Outlined.ScreenRotation,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = if (reduced) (if (isLandscape) 0f else 90f) else rotation },
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (isLandscape) {
                tr("横屏", "Landscape", "横向き", "가로", "橫向")
            } else {
                tr("竖屏", "Portrait", "縦向き", "세로", "直向")
            },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** 预览图层切换（控件 / 指示器）：与触屏/键鼠视角对称成组，调整控件或指示器外观与位置时互不遮挡、所见即所得。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewLayerToggle(
    showIndicators: Boolean,
    onShowIndicatorsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        false to tr("控件", "Controls", "コントロール", "컨트롤", "控制項"),
        true to tr("指示器", "Indicators", "インジケーター", "표시기", "指示器"),
    )
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, (isIndicator, label) ->
            FilledTonalToggleButton(
                checked = showIndicators == isIndicator,
                onCheckedChange = { if (it) onShowIndicatorsChange(isIndicator) },
                shapes = connectedToggleShapes(index, options.size),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.handCursor(),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
                modifier = Modifier.handCursor(),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
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

@Composable
private fun tabLabel(tab: CustomizationTab): String = when (tab) {
    CustomizationTab.Video -> tr("视频", "Video", "動画", "동영상", "影片")
    CustomizationTab.Clips -> tr("信息流", "Clips", "フィード", "피드", "資訊流")
    CustomizationTab.Listen -> stringResource(Res.string.player_custom_mode_listen)
    CustomizationTab.Gestures -> stringResource(Res.string.player_custom_mode_gestures)
}
