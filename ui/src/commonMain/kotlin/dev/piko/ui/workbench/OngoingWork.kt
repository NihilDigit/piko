package dev.piko.ui.workbench

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.shared.presentation.localizedName
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.components.onSecondaryPress
import dev.piko.ui.platform.ModalCaptionBlocker
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.screens.instant.summary
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.tabular
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch

internal fun workspaceTitle(model: WorkspaceViewModel, context: WorkspaceContext): String = when (context) {
    is WorkspaceContext.File -> tr("文件", "Files", "ファイル", "파일", "檔案")
    is WorkspaceContext.Duplicate -> tr("查重：${context.root.localizedName}", "Duplicates: ${context.root.localizedName}", "重複検索：${context.root.localizedName}", "중복 검색: ${context.root.localizedName}", "查重：${context.root.localizedName}")
    is WorkspaceContext.ImportDraft -> model.importModels[context.id]?.state?.summary()?.title ?: tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OngoingWorkSheet(model: WorkspaceViewModel, onDismiss: () -> Unit) {
    val wide = isDesktopLayout()
    LaunchedEffect(wide) { if (wide) onDismiss() }
    if (wide) return
    ModalCaptionBlocker()
    val contexts by model.contexts.contextsFlow.collectAsStateWithLifecycle()
    val active by model.contexts.activeContextId.collectAsStateWithLifecycle()
    val files by model.driveWorkspace.tabsFlow.collectAsStateWithLifecycle()
    val caption = rememberCaptionSlot()
    val colors = MaterialTheme.colorScheme
    val gridState = rememberLazyGridState()
    val hasClosableOthers = contexts.any { it.id != active && !model.isContextPinned(it.id) && model.canCloseContext(it.id) }
    val closeOthersLabel = stringResource(Res.string.text_close_other_tabs)

    LaunchedEffect(Unit) {
        val activeIndex = contexts.indexOfFirst { it.id == active }
        if (activeIndex > 1) {
            gridState.scrollToItem(activeIndex)
        }
    }

    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize(), color = colors.surfaceContainerHigh) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(caption.modifier)
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TooltipIconButton(
                        icon = Icons.Outlined.Close,
                        label = tr("关闭标签总览", "Close tab overview", "タブ一覧を閉じる", "탭 개요 닫기", "關閉分頁總覽"),
                        onClick = onDismiss,
                    )
                    Text(
                        text = tr("${contexts.size} 个标签页", "${contexts.size} tabs", "${contexts.size} タブ", "탭 ${contexts.size}개", "${contexts.size} 個分頁"),
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        style = MaterialTheme.typography.titleLargeEmphasized.tabular,
                        maxLines = 1,
                    )
                    if (hasClosableOthers) {
                        TooltipIconButton(
                            icon = Icons.Outlined.ClearAll,
                            label = closeOthersLabel,
                            onClick = { model.requestCloseOtherContexts(active) },
                        )
                    }
                    caption.buttons?.invoke()
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    state = gridState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(contexts, key = { it.id }) { context ->
                        val isActive = context.id == active
                        val file = files.firstOrNull { it.id == context.id }
                        val pinned = file?.isPinned == true
                        val canPin = model.canPinContext(context.id)
                        val canRename = model.canEditContextNote(context.id)
                        val title = file?.displayTitle ?: workspaceTitle(model, context)
                        val icon = when (context) {
                            is WorkspaceContext.File -> Icons.Outlined.Folder
                            is WorkspaceContext.Duplicate -> Icons.Outlined.FileCopy
                            is WorkspaceContext.ImportDraft -> Icons.Outlined.Link
                        }
                        val canClose = !pinned && model.canCloseContext(context.id)
                        val canCloseOtherFromCard = contexts.any { it.id != context.id && !model.isContextPinned(it.id) && model.canCloseContext(it.id) }
                        val snapshot = model.pageSnapshots[context.id]
                        SwipeToCloseTabContainer(
                            enabled = canClose,
                            canCloseImmediately = { model.willCloseContextImmediately(context.id) },
                            onClose = { model.requestCloseContext(context.id) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                                placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                                fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                            ),
                        ) {
                            TabOverviewCard(
                                title = title,
                                icon = icon,
                                isActive = isActive,
                                pinned = pinned,
                                snapshot = snapshot,
                                canRename = canRename,
                                canPin = canPin,
                                canClose = canClose,
                                canCloseOthers = canCloseOtherFromCard,
                                closeOthersLabel = closeOthersLabel,
                                onSelect = {
                                    model.activateContext(context.id)
                                    onDismiss()
                                },
                                onRename = { model.requestEditContextNote(context.id) },
                                onTogglePin = { model.togglePinContext(context.id) },
                                onClose = { model.requestCloseContext(context.id) },
                                onCloseOthers = { model.requestCloseOtherContexts(context.id) },
                            )
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    model.newFileContext()
                    onDismiss()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(Res.string.text_new_tab_alt),
                )
            }
        }
    }
}

/**
 * 面向双列卡片网格的物理横向滑出容器：
 * - 严格水平方向锁定（`|dx| >= |dy| * 1.35`），避免斜向滚动网格时误扯卡片；
 * - 无底槽，仅通过 `graphicsLayer` 驱动平移、轻微倾角与淡出，越过阈值触发轻触觉反馈；
 * - 无需二次确认的标签顺势飞出后再移除，需弹窗确认的标签弹性回位并弹出确认框。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwipeToCloseTabContainer(
    enabled: Boolean,
    canCloseImmediately: () -> Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val reducedMotion = LocalPikoMotion.current.reduced
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val offsetX = remember { Animatable(0f) }
    var cardWidthPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    val latestCanCloseImmediately by rememberUpdatedState(canCloseImmediately)
    val latestOnClose by rememberUpdatedState(onClose)
    val minDismissPx = with(density) { 64.dp.toPx() }
    val flingVelocityPx = with(density) { 800.dp.toPx() }
    val minFlingDistancePx = with(density) { 24.dp.toPx() }

    Box(
        modifier = modifier
            .zIndex(if (dragging || dismissing || offsetX.value != 0f) 2f else 0f)
            .onSizeChanged { cardWidthPx = it.width.toFloat() }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (dismissing) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    var totalDx = 0f
                    var totalDy = 0f
                    var dragOffset = offsetX.value
                    var lockedHorizontal = false
                    val velocityTracker = VelocityTracker()
                    var crossedThreshold = false
                    var cancelled = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            if (lockedHorizontal) change.consume()
                            break
                        }
                        val delta = change.positionChange()
                        if (!lockedHorizontal) {
                            totalDx += delta.x
                            totalDy += delta.y
                            val absDx = abs(totalDx)
                            val absDy = abs(totalDy)
                            if (absDy >= slop && absDx < absDy * 1.35f) {
                                // 纵向滚动主导，立刻交还给 LazyVerticalGrid
                                return@awaitEachGesture
                            }
                            if (absDx >= slop && absDx >= absDy * 1.35f) {
                                lockedHorizontal = true
                                dragging = true
                                dragOffset = offsetX.value + totalDx
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, Offset(dragOffset, 0f))
                                val target = dragOffset
                                scope.launch { offsetX.snapTo(target) }
                            }
                        } else {
                            if (change.isConsumed) {
                                cancelled = true
                                break
                            }
                            change.consume()
                            dragOffset += delta.x
                            velocityTracker.addPosition(change.uptimeMillis, Offset(dragOffset, 0f))
                            val threshold = (cardWidthPx * 0.42f).coerceAtLeast(minDismissPx)
                            val nowCrossed = abs(dragOffset) >= threshold
                            if (nowCrossed && !crossedThreshold) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            crossedThreshold = nowCrossed
                            val target = dragOffset
                            scope.launch { offsetX.snapTo(target) }
                        }
                    }

                    if (!lockedHorizontal) return@awaitEachGesture
                    dragging = false

                    val currentOffset = dragOffset
                    val velocityX = if (cancelled) 0f else velocityTracker.calculateVelocity().x
                    val threshold = (cardWidthPx * 0.42f).coerceAtLeast(minDismissPx)
                    val reachedDistance = abs(currentOffset) >= threshold
                    val reachedFling = abs(velocityX) >= flingVelocityPx &&
                        abs(currentOffset) >= minFlingDistancePx &&
                        velocityX.sign == currentOffset.sign
                    val shouldDismiss = !cancelled && (reachedDistance || reachedFling)

                    if (shouldDismiss) {
                        if (latestCanCloseImmediately()) {
                            dismissing = true
                            val direction = if (currentOffset >= 0f) 1f else -1f
                            val exitTarget = direction * (cardWidthPx.coerceAtLeast(minDismissPx) * 1.35f)
                            scope.launch {
                                try {
                                    if (!reducedMotion) {
                                        offsetX.animateTo(
                                            targetValue = exitTarget,
                                            animationSpec = spatialSpec,
                                            initialVelocity = velocityX,
                                        )
                                    }
                                    latestOnClose()
                                } finally {
                                    dismissing = false
                                }
                            }
                        } else {
                            scope.launch {
                                offsetX.animateTo(0f, animationSpec = spatialSpec)
                            }
                            latestOnClose()
                        }
                    } else {
                        scope.launch {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spatialSpec,
                                initialVelocity = velocityX,
                            )
                        }
                    }
                }
            }
            .graphicsLayer {
                val width = cardWidthPx.coerceAtLeast(1f)
                val x = offsetX.value
                val progress = (abs(x) / (width * 1.15f)).coerceIn(0f, 1f)
                translationX = x
                if (!reducedMotion) {
                    rotationZ = (x / width).coerceIn(-1.2f, 1.2f) * 5.5f
                    val scale = 1f - progress * 0.04f
                    scaleX = scale
                    scaleY = scale
                }
                alpha = (1f - progress * 0.92f).coerceIn(0f, 1f)
            },
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabOverviewCard(
    title: String,
    icon: ImageVector,
    isActive: Boolean,
    pinned: Boolean,
    snapshot: androidx.compose.ui.graphics.ImageBitmap?,
    canRename: Boolean,
    canPin: Boolean,
    canClose: Boolean,
    canCloseOthers: Boolean,
    closeOthersLabel: String,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val shape = MaterialTheme.shapes.largeIncreased
    var menuExpanded by remember { mutableStateOf(false) }
    val hasMenuActions = canRename || canPin || canCloseOthers

    val cardBorderColor by animateColorAsState(
        targetValue = if (isActive) colors.primary else colors.outlineVariant.copy(alpha = 0.5f),
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "tab_card_border",
    )
    val cardColor by animateColorAsState(
        targetValue = if (isActive) colors.secondaryContainer else colors.surfaceContainerHighest,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "tab_card_bg",
    )
    val headerContentColor = if (isActive) colors.onSecondaryContainer else colors.onSurface
    val mutedIconColor = if (isActive) colors.onSecondaryContainer.copy(alpha = 0.78f) else colors.onSurfaceVariant

    Surface(
        modifier = Modifier
            .clip(shape)
            .onSecondaryPress(enabled = hasMenuActions) { menuExpanded = true }
            .combinedClickable(
                onClick = onSelect,
                onLongClick = if (hasMenuActions) {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuExpanded = true
                    }
                } else {
                    null
                },
            ),
        shape = shape,
        border = BorderStroke(if (isActive) 2.5.dp else 1.dp, cardBorderColor),
        color = cardColor,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (pinned) Icons.Filled.PushPin else icon,
                    contentDescription = null,
                    tint = if (pinned || isActive) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = headerContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasMenuActions) {
                    Box {
                        CompactCardIconButton(
                            icon = Icons.Outlined.MoreVert,
                            contentDescription = tr("更多操作", "More options", "その他の操作", "더보기", "更多操作"),
                            tint = mutedIconColor,
                            onClick = { menuExpanded = true },
                        )
                        PikoDropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            val entries = buildList {
                                if (canRename) {
                                    add(
                                        Triple(
                                            Icons.Outlined.DriveFileRenameOutline,
                                            tr("重命名标签页", "Rename tab", "タブの名前を変更", "탭 이름 바꾸기", "重新命名分頁"),
                                            onRename,
                                        ),
                                    )
                                }
                                if (canPin) {
                                    add(
                                        Triple(
                                            if (pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                                            if (pinned) tr("取消固定标签页", "Unpin tab", "タブの固定を解除", "탭 고정 해제", "取消固定分頁")
                                            else tr("固定标签页", "Pin tab", "タブを固定", "탭 고정", "固定分頁"),
                                            onTogglePin,
                                        ),
                                    )
                                }
                                if (canCloseOthers) {
                                    add(
                                        Triple(
                                            Icons.Outlined.ClearAll,
                                            closeOthersLabel,
                                            onCloseOthers,
                                        ),
                                    )
                                }
                            }
                            entries.forEachIndexed { index, (actionIcon, label, action) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = actionIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                    shape = menuItemShape(index, entries.size),
                                    onClick = {
                                        menuExpanded = false
                                        action()
                                    },
                                )
                            }
                        }
                    }
                }
                if (canClose) {
                    CompactCardIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = tr("关闭 $title", "Close $title", "$title を閉じる", "$title 닫기", "關閉 $title"),
                        tint = mutedIconColor,
                        onClick = onClose,
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.82f), contentAlignment = Alignment.Center) {
                    if (snapshot != null) {
                        Image(
                            bitmap = snapshot,
                            contentDescription = tr("页面快照：$title", "Page snapshot: $title", "ページスナップショット：$title", "페이지 스냅샷: $title", "頁面快照：$title"),
                            contentScale = ContentScale.FillWidth,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = colors.outlineVariant,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactCardIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}
