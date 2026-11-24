package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_rapid_scrub_label
import dev.piko.ui.resources.player_speed_coarse_horizontal
import dev.piko.ui.resources.player_speed_coarse_horizontal_top
import dev.piko.ui.resources.player_speed_coarse_vertical
import dev.piko.ui.resources.player_speed_coarse_vertical_right
import dev.piko.ui.resources.player_speed_lock_action
import dev.piko.ui.resources.player_speed_locked
import dev.piko.ui.resources.player_speed_release_to_lock
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

private object SpeedDialSize {
    val HorizontalThickness = 54.dp
    val VerticalThickness = 92.dp
    val BlockWidth = 76.dp
    val HorizontalBlockWidth = 68.dp
    val BlockHeight = 38.dp
    val UnselectedCorner = 10.dp
    val EdgeFade = 24.dp
}

private val SpeedDialScrimMid = Color(0x52000000)
private val SpeedDialScrimEdge = Color(0xB3000000)

private data class SpeedHintState(
    val visible: Boolean,
    val text: String,
    val lockedIcon: Boolean,
)

@Composable
private fun rememberSpeedHintState(
    controller: PlaybackSpeedController,
    showDial: Boolean = true,
): SpeedHintState {
    val selection = controller.selection
    val window = LocalWindowInfo.current.containerSize
    val vertical = window.width > window.height
    val locked = controller.lockedHint
    val releaseToLock = selection?.locked == true
    val dockEnd = vertical && selection?.dockEnd == true
    val dockTop = !vertical && selection?.dockTop == true
    val text = when {
        locked != null -> stringResource(Res.string.player_speed_locked, formatSpeed(locked / 100f))
        !showDial -> formatSpeed(selection?.speed ?: controller.speed)
        releaseToLock -> stringResource(Res.string.player_speed_release_to_lock)
        !vertical && dockTop -> stringResource(Res.string.player_speed_coarse_horizontal_top)
        !vertical -> stringResource(Res.string.player_speed_coarse_horizontal)
        dockEnd -> stringResource(Res.string.player_speed_coarse_vertical_right)
        else -> stringResource(Res.string.player_speed_coarse_vertical)
    }
    var shownText by remember { mutableStateOf(text) }
    var shownLockedIcon by remember { mutableStateOf(false) }
    val visible = (selection != null && !selection.isRapidScrub) || locked != null
    if (visible) {
        shownText = text
        shownLockedIcon = locked != null || releaseToLock
    }
    val revision = controller.hintRevision
    LaunchedEffect(revision) {
        if (controller.lockedHint == null) return@LaunchedEffect
        delay(900)
        if (controller.hintRevision == revision) controller.clearHint()
    }
    return SpeedHintState(visible = visible, text = shownText, lockedIcon = shownLockedIcon)
}

@Composable
private fun SpeedHintCapsule(
    text: String,
    lockedIcon: Boolean,
    showBackground: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val fixed = LocalFixedColors.current
    val textStyle = MaterialTheme.typography.labelMedium.tabular.let {
        if (showBackground) it else it.copy(shadow = PlayerTextShadow)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .then(
                if (showBackground) {
                    Modifier
                        .clip(CircleShape)
                        .background(fixed.ScrimOnMedia)
                        .border(1.dp, fixed.HudSpecularBorder, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                } else {
                    Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                },
            ),
    ) {
        Icon(
            imageVector = if (lockedIcon) Icons.Filled.Lock else Icons.Filled.FastForward,
            contentDescription = null,
            tint = fixed.OnMedia,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = textStyle,
            color = fixed.OnMedia,
            maxLines = 1,
        )
    }
}

/**
 * 贴边长按倍速选档覆盖层（移植自 Bilby）：
 * - 横屏停靠左/右边缘（与按下手指对侧），上下跟手滑动切档，横向跨轴滑动锁定；
 * - 竖屏停靠顶/底边缘（与按下手指对侧），左右跟手滑动切档，纵向跨轴滑动锁定；
 * - 支持直接点击档位锁定、滚轮步进与键盘方向键/Enter/Esc，关闭拨盘时仅显示轻量倍速提示胶囊。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SpeedDialOverlay(
    controller: PlaybackSpeedController,
    isFullscreen: Boolean = false,
    showDial: Boolean = true,
    showIndicatorBackground: Boolean = true,
    durationMillis: Long = 0L,
    modifier: Modifier = Modifier,
) {
    var shownSelection by remember { mutableStateOf<SpeedSelection?>(null) }
    controller.selection?.let { shownSelection = it }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(controller) {
        snapshotFlow { controller.coarseStepRevision }.drop(1).collect {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val window = LocalWindowInfo.current.containerSize
    val vertical = window.width > window.height
    val activeOrLast = controller.selection ?: shownSelection
    val effectiveShowDial = showDial && (activeOrLast?.showDial != false)
    val dockEnd = vertical && activeOrLast?.dockEnd == true
    val dockTop = !vertical && activeOrLast?.dockTop == true
    val hintState = rememberSpeedHintState(controller, showDial = effectiveShowDial)
    val focus = remember { FocusRequester() }
    val reduced = LocalPikoMotion.current.reduced
    BackHandler(enabled = controller.active && effectiveShowDial) { controller.cancel() }
    LaunchedEffect(controller.active, effectiveShowDial) { if (controller.active && effectiveShowDial) runCatching { focus.requestFocus() } }

    val motion = MaterialTheme.motionScheme
    val spatialOffset = motion.fastSpatialSpec<IntOffset>()
    val effects = motion.fastEffectsSpec<Float>()

    Box(
        modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !controller.active || !effectiveShowDial) return@onKeyEvent false
                when (event.key) {
                    Key.Escape -> controller.cancel()
                    Key.Enter -> {
                        if (controller.isRapidScrubActive) {
                            controller.commitRapidScrub()
                        } else {
                            controller.lock()
                            controller.finish()
                        }
                    }
                    Key.MoveHome -> {
                        if (controller.isRapidScrubActive) {
                            controller.setRapidScrubPosition(0L)
                        } else {
                            return@onKeyEvent false
                        }
                    }
                    Key.MoveEnd -> {
                        if (controller.isRapidScrubActive) {
                            controller.setRapidScrubPosition(durationMillis)
                        } else {
                            return@onKeyEvent false
                        }
                    }
                    Key.DirectionUp -> when {
                        controller.rapidScrubBrowseMode -> return@onKeyEvent false
                        vertical -> controller.step(-1)
                        dockTop -> {
                            if (!controller.isRapidScrubActive) controller.lock()
                            controller.finish()
                        }
                        else -> return@onKeyEvent false
                    }
                    Key.DirectionDown -> when {
                        controller.rapidScrubBrowseMode -> return@onKeyEvent false
                        vertical -> controller.step(1)
                        !dockTop -> {
                            if (!controller.isRapidScrubActive) controller.lock()
                            controller.finish()
                        }
                        else -> return@onKeyEvent false
                    }
                    Key.DirectionRight -> when {
                        controller.isRapidScrubActive -> controller.scrubRapidByMillis(filmstripStepMillis(durationMillis))
                        !vertical -> controller.step(1)
                        dockEnd -> {
                            controller.lock()
                            controller.finish()
                        }
                        else -> return@onKeyEvent false
                    }
                    Key.DirectionLeft -> when {
                        controller.isRapidScrubActive -> controller.scrubRapidByMillis(-filmstripStepMillis(durationMillis))
                        !vertical -> controller.step(-1)
                        !dockEnd -> {
                            controller.lock()
                            controller.finish()
                        }
                        else -> return@onKeyEvent false
                    }
                    else -> return@onKeyEvent false
                }
                true
            }
            .then(if (controller.active && effectiveShowDial) Modifier.focusRequester(focus).focusable() else Modifier),
    ) {
        if (controller.active && effectiveShowDial) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (controller.isRapidScrubActive && durationMillis > 0L) {
                            Modifier.background(Color.Black.copy(alpha = 0.52f))
                        } else {
                            Modifier
                        },
                    )
                    .pointerInput(controller) {
                        detectTapGestures {
                            if (controller.isRapidScrubActive) controller.cancel() else controller.finish()
                        }
                    },
            )
        }

        val dialEnter = if (reduced) {
            fadeIn(effects)
        } else {
            when {
                !vertical && dockTop -> slideInVertically(spatialOffset) { -it / 2 }
                !vertical -> slideInVertically(spatialOffset) { it / 2 }
                dockEnd -> slideInHorizontally(spatialOffset) { it / 2 }
                else -> slideInHorizontally(spatialOffset) { -it / 2 }
            } + fadeIn(effects)
        }
        val dialExit = if (reduced) {
            fadeOut(effects)
        } else {
            when {
                !vertical && dockTop -> slideOutVertically(spatialOffset) { -it / 2 }
                !vertical -> slideOutVertically(spatialOffset) { it / 2 }
                dockEnd -> slideOutHorizontally(spatialOffset) { it / 2 }
                else -> slideOutHorizontally(spatialOffset) { -it / 2 }
            } + fadeOut(effects)
        }

        AnimatedVisibility(
            visible = controller.active && effectiveShowDial && !controller.rapidScrubBrowseMode,
            enter = dialEnter,
            exit = dialExit,
            modifier = Modifier.align(
                when {
                    !vertical && dockTop -> Alignment.TopCenter
                    !vertical -> Alignment.BottomCenter
                    dockEnd -> Alignment.CenterEnd
                    else -> Alignment.CenterStart
                },
            ),
        ) {
            val selection = controller.selection ?: shownSelection
            if (selection != null) {
                val scrim = when {
                    !vertical && selection.dockTop -> Brush.verticalGradient(
                        listOf(SpeedDialScrimEdge, SpeedDialScrimMid, Color.Transparent),
                    )
                    !vertical -> Brush.verticalGradient(
                        listOf(Color.Transparent, SpeedDialScrimMid, SpeedDialScrimEdge),
                    )
                    selection.dockEnd -> Brush.horizontalGradient(
                        listOf(Color.Transparent, SpeedDialScrimMid, SpeedDialScrimEdge),
                    )
                    else -> Brush.horizontalGradient(
                        listOf(SpeedDialScrimEdge, SpeedDialScrimMid, Color.Transparent),
                    )
                }
                val insetSides = when {
                    !vertical && selection.dockTop -> WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                    !vertical -> WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    selection.dockEnd -> WindowInsetsSides.End + WindowInsetsSides.Vertical
                    else -> WindowInsetsSides.Start + WindowInsetsSides.Vertical
                }
                SpeedDialStrip(
                    controller = controller,
                    selection = selection,
                    vertical = vertical,
                    modifier = Modifier
                        .background(scrim)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(insetSides))
                        .then(
                            if (vertical) Modifier.width(SpeedDialSize.VerticalThickness).fillMaxHeight()
                            else Modifier.height(SpeedDialSize.HorizontalThickness).fillMaxWidth(),
                        ),
                )
            }
        }

        AnimatedVisibility(
            visible = controller.isRapidScrubActive && durationMillis > 0L,
            enter = fadeIn(effects),
            exit = fadeOut(effects),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp),
        ) {
            RapidScrubFilmstripOverlay(
                targetMillis = controller.rapidScrubPositionMillis,
                originMillis = controller.rapidScrubOriginMillis,
                durationMillis = durationMillis,
                isHoldingDrag = controller.dragging,
                onTargetMillisChange = controller::setRapidScrubPosition,
                onCommit = { controller.commitRapidScrub(it) },
                onCancel = { controller.cancel() },
            )
        }

        val hintAtBottom = effectiveShowDial && !vertical && dockTop
        val hintInsetSides = WindowInsetsSides.Horizontal +
            if (hintAtBottom) WindowInsetsSides.Bottom else WindowInsetsSides.Top
        AnimatedVisibility(
            visible = hintState.visible,
            enter = if (reduced) fadeIn(effects) else slideInVertically(spatialOffset) { if (hintAtBottom) it / 2 else -it / 2 } + fadeIn(effects),
            exit = if (reduced) fadeOut(effects) else slideOutVertically(spatialOffset) { if (hintAtBottom) it / 2 else -it / 2 } + fadeOut(effects),
            modifier = Modifier
                .align(if (hintAtBottom) Alignment.BottomCenter else Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(hintInsetSides))
                .padding(16.dp),
        ) {
            SpeedHintCapsule(
                text = hintState.text,
                lockedIcon = hintState.lockedIcon,
                showBackground = showIndicatorBackground,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeedDialStrip(
    controller: PlaybackSpeedController,
    selection: SpeedSelection,
    vertical: Boolean,
    modifier: Modifier,
) {
    val description = tr("倍速", "Speed", "再生速度", "재생 속도", "倍速")
    val lockAction = stringResource(Res.string.player_speed_lock_action)
    val rapidScrubLabel = stringResource(Res.string.player_rapid_scrub_label)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val valueDescription = if (selection.isRapidScrub) rapidScrubLabel else formatSpeed(selection.speed)
    val fadePx = with(density) { SpeedDialSize.EdgeFade.toPx() }

    val input = modifier
        .clipToBounds()
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val span = if (vertical) size.height else size.width
            if (span > fadePx * 2f) {
                val startFraction = (fadePx / span).coerceIn(0f, 0.25f)
                val stops = arrayOf(
                    0f to Color.Transparent,
                    startFraction to Color.Black,
                    (1f - startFraction) to Color.Black,
                    1f to Color.Transparent,
                )
                val mask = if (vertical) Brush.verticalGradient(*stops) else Brush.horizontalGradient(*stops)
                drawRect(brush = mask, blendMode = BlendMode.DstIn)
            }
        }
        .semantics {
            contentDescription = description
            stateDescription = valueDescription
            progressBarRangeInfo = ProgressBarRangeInfo(
                current = selection.speed,
                range = MIN_SPEED..MAX_SPEED,
                steps = (selection.choices.size - 2).coerceAtLeast(0),
            )
            setProgress {
                controller.adjustTo(it)
                controller.lock()
                controller.finish()
                true
            }
            customActions = listOf(
                CustomAccessibilityAction(lockAction) {
                    controller.lock()
                    controller.finish()
                    true
                },
            )
        }
        .pointerInput(controller) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll || controller.dragging) continue
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.scrollDelta.y != 0f) {
                        controller.step(if (change.scrollDelta.y > 0) 1 else -1)
                        change.consume()
                    }
                }
            }
        }
        .pointerInput(controller, vertical) {
            var owned = false
            var displacement = Offset.Zero
            try {
                detectDragGestures(
                    onDragStart = {
                        owned = controller.beginDrag(vertical = vertical)
                        displacement = Offset.Zero
                    },
                    onDragEnd = {
                        if (owned) controller.releaseHoldDrag()
                        owned = false
                    },
                    onDragCancel = {
                        if (owned) controller.cancel()
                        owned = false
                    },
                ) { change, amount ->
                    change.consume()
                    if (owned) {
                        displacement += amount
                        val before = controller.selection
                        val acrossPx = when {
                            !vertical && before?.dockTop == true -> -displacement.y
                            !vertical -> displacement.y
                            before?.dockEnd == true -> displacement.x
                            else -> -displacement.x
                        }
                        controller.move(
                            alongDp = (if (vertical) displacement.y else displacement.x) / density.density,
                            acrossDp = acrossPx / density.density,
                        )
                        val after = controller.selection
                        if (after?.locked != before?.locked) {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                    }
                }
            } finally {
                if (owned) controller.cancel()
            }
        }

    val animatedPositionState = animateFloatAsState(
        targetValue = selection.position,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "speed-blocks",
    )
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    Box(input.onSizeChanged { containerSize = it }) {
        val width = if (vertical) SpeedDialSize.BlockWidth else SpeedDialSize.HorizontalBlockWidth
        val height = SpeedDialSize.BlockHeight
        val widthPx = with(density) { width.toPx() }
        val heightPx = with(density) { height.toPx() }
        val stepDp = if (vertical) SpeedVerticalBlockStepDp else SpeedHorizontalBlockStepDp
        val stepPx = with(density) { stepDp.dp.toPx() }
        val colors = MaterialTheme.colorScheme
        val unselectedContainer = playerContainerColor()

        selection.choices.forEachIndexed { index, value ->
            val selected = value == selection.value
            val corner by animateDpAsState(
                targetValue = if (selected) SpeedDialSize.BlockHeight / 2 else SpeedDialSize.UnselectedCorner,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "speed-corner",
            )
            val containerColor by animateColorAsState(
                targetValue = when {
                    selected && selection.locked -> colors.primaryContainer
                    selected -> colors.primary
                    else -> unselectedContainer
                },
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "speed-container",
            )
            val contentColor by animateColorAsState(
                targetValue = when {
                    selected && selection.locked -> colors.onPrimaryContainer
                    selected -> colors.onPrimary
                    else -> colors.onSurfaceVariant
                },
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "speed-content",
            )
            Box(
                Modifier
                    .offset {
                        val pos = if (controller.dragging) selection.position else animatedPositionState.value
                        val center = if (vertical) containerSize.height / 2f else containerSize.width / 2f
                        val along = center + (index - pos) * stepPx
                        IntOffset(
                            (if (vertical) (containerSize.width - widthPx) / 2 else along - widthPx / 2).roundToInt(),
                            (if (vertical) along - heightPx / 2 else (containerSize.height - heightPx) / 2).roundToInt(),
                        )
                    }
                    .size(width, height)
                    .clip(RoundedCornerShape(corner))
                    .background(containerColor)
                    .clickable(role = Role.Button) { controller.choose(value) }
                    .handCursor(),
                contentAlignment = Alignment.Center,
            ) {
                if (value == RAPID_SCRUB_VALUE) {
                    Icon(
                        imageVector = Icons.Rounded.ViewCarousel,
                        contentDescription = rapidScrubLabel,
                        tint = contentColor,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    val label = speedChoiceLabel(value)
                    val textStyle = (if (selected) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium)
                        .tabular
                    if (selected) {
                        val icon = if (selection.locked) Icons.Filled.Lock else Icons.Filled.FastForward
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(label, style = textStyle, color = contentColor, maxLines = 1)
                        }
                    } else {
                        Text(label, style = textStyle, color = contentColor, maxLines = 1)
                    }
                }
            }
        }
    }
}
