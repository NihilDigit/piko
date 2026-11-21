package dev.piko.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_rapid_scrub_hint
import dev.piko.ui.resources.player_rapid_scrub_jump
import dev.piko.ui.resources.player_rapid_scrub_label
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 为整片时长计算从左到右胶片视图的自适应抽帧步长（毫秒）：
 * - 短片（≤ 3m20s）：2s–10s，生成约 15–24 帧；
 * - 常规片（≤ 30m）：从 10s / 15s / 20s / 30s 中挑出落在 20–60 帧区间的最整齐间隔；
 * - 长片（> 30m）：按 60 帧封顶向上取整至 10s 整数倍，兼顾全片覆盖与解码流畅度。
 */
internal fun filmstripStepMillis(durationMillis: Long): Long {
    if (durationMillis <= 0L) return 10_000L
    if (durationMillis <= 200_000L) {
        val rawSec = (durationMillis / 1_000L / 20L).coerceIn(2L, 10L)
        return rawSec * 1_000L
    }
    val preferredSteps = longArrayOf(10_000L, 15_000L, 20_000L, 30_000L)
    for (step in preferredSteps) {
        val count = (durationMillis / step) + 1L
        if (count in 20L..60L) return step
    }
    if (durationMillis <= 1_800_000L) return 30_000L
    val rawStepSec = ((durationMillis / 1_000L) + 59L) / 60L
    val roundedStepSec = ((rawStepSec + 9L) / 10L) * 10L
    return roundedStepSec.coerceAtLeast(30L) * 1_000L
}

/**
 * 生成从 0 到片尾均匀分布的胶片时间点序列（单调递增，所有点均落在 `0..durationMillis` 内）。
 */
internal fun buildFilmstripTimestamps(durationMillis: Long): List<Long> {
    if (durationMillis <= 0L) return emptyList()
    val step = filmstripStepMillis(durationMillis)
    val result = ArrayList<Long>(((durationMillis / step).toInt() + 2).coerceIn(2, 80))
    var t = 0L
    while (t < durationMillis) {
        result += t
        t += step
    }
    if (result.isEmpty() || durationMillis - result.last() >= step / 2L) {
        result += durationMillis
    }
    return result
}

private fun nearestFrameIndex(frames: List<Long>, targetMillis: Long): Int {
    if (frames.isEmpty()) return 0
    var bestIdx = 0
    var bestDist = Long.MAX_VALUE
    for (i in frames.indices) {
        val d = abs(frames[i] - targetMillis)
        if (d < bestDist) {
            bestDist = d
            bestIdx = i
        }
    }
    return bestIdx
}

private fun centeredFrameIndex(listState: LazyListState, fallback: Int): Int {
    val info = listState.layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return fallback
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return items.minByOrNull { item ->
        abs((item.offset + item.size / 2) - viewportCenter)
    }?.index ?: fallback
}

/**
 * `∞` 粗剪从左到右可滚动胶片视图：
 * - 沿整片时间轴均匀排布 20–60 帧缩略图（`LazyRow`），打开即定位于当前播放时刻；
 * - 经 [LocalScrubFrameCache] 从视口中心向外渐进解码可见帧与 ±4 邻近帧，快速滚动自动取消过时任务；
 * - 支持滚轮横向翻阅（`verticalWheelScrollsRow`）、鼠标按住左右拖动、触屏惯性滑动、键盘 `←/→/Home/End/Enter/Esc`
 *   以及底部迷你时间轴直达；点击任意画面或「跳转到此处」即刻定位并退出，`Esc` / 关闭 / 点击外部取消。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RapidScrubFilmstripOverlay(
    targetMillis: Long,
    originMillis: Long,
    durationMillis: Long,
    isHoldingDrag: Boolean,
    onTargetMillisChange: (Long) -> Unit,
    onCommit: (Long) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fixed = LocalFixedColors.current
    val scheme = MaterialTheme.colorScheme
    val frameCache = LocalScrubFrameCache.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val safeDuration = durationMillis.coerceAtLeast(1L)
    val cacheRevision = frameCache?.revision ?: 0
    val displaySlots = remember(frameCache, cacheRevision, durationMillis) {
        if (frameCache != null) {
            frameCache.displaySlots()
        } else {
            buildFilmstripTimestamps(durationMillis).mapIndexed { idx, ts ->
                StoryboardDisplaySlot(
                    slotIndex = idx,
                    timestampMillis = ts,
                    bitmap = null,
                    isDecoded = false,
                )
            }
        }
    }
    val frames = remember(displaySlots) { displaySlots.map { it.timestampMillis } }
    val originIndex = remember(displaySlots, frameCache, originMillis) {
        if (frameCache != null) frameCache.findDisplaySlotIndex(displaySlots, originMillis)
        else nearestFrameIndex(frames, originMillis)
    }
    val initialIndex = remember(durationMillis, originMillis) { originIndex }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    var stripWidthPx by remember { mutableIntStateOf(0) }
    var centeredIndex by remember(durationMillis, initialIndex) { mutableIntStateOf(initialIndex) }
    var initializedScroll by remember(durationMillis) { mutableStateOf(false) }

    val stripWidthDp = with(density) { stripWidthPx.toDp() }
    val compactStrip = stripWidthDp > 0.dp && stripWidthDp < 600.dp
    val tileWidth = if (compactStrip) FILMSTRIP_TILE_WIDTH_COMPACT else FILMSTRIP_TILE_WIDTH_WIDE
    val tileHeight = if (compactStrip) FILMSTRIP_TILE_HEIGHT_COMPACT else FILMSTRIP_TILE_HEIGHT_WIDE
    val sidePadding = if (stripWidthPx > 0) {
        ((stripWidthDp - tileWidth) / 2f).coerceAtLeast(16.dp)
    } else {
        16.dp
    }

    val latestOnTargetChange by rememberUpdatedState(onTargetMillisChange)
    val latestFrames by rememberUpdatedState(frames)

    // 首次拿到容器宽度后精确把起点帧对准正中播放头
    LaunchedEffect(stripWidthPx > 0, initialIndex) {
        if (stripWidthPx > 0 && !initializedScroll && frames.isNotEmpty()) {
            listState.scrollToItem(initialIndex)
            centeredIndex = initialIndex
            initializedScroll = true
        }
    }

    // 监听胶片条滚动：实时把居中帧的时间码同步到播放头读数与控制器
    LaunchedEffect(listState, frames.isNotEmpty()) {
        if (latestFrames.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val idx = centeredFrameIndex(listState, centeredIndex)
            idx to listState.isScrollInProgress
        }
            .distinctUntilChanged()
            .collect { (idx, scrolling) ->
                val currentFrames = latestFrames
                if (idx in currentFrames.indices) {
                    centeredIndex = idx
                    if (scrolling) {
                        latestOnTargetChange(currentFrames[idx])
                    }
                }
            }
    }

    // 外部变更（键盘 ←/→、长按拖动、迷你时间轴直达）驱动胶片条同步滚动到对应帧
    LaunchedEffect(targetMillis, isHoldingDrag, initializedScroll) {
        val currentFrames = latestFrames
        if (!initializedScroll || currentFrames.isEmpty() || listState.isScrollInProgress) return@LaunchedEffect
        val targetIdx = nearestFrameIndex(currentFrames, targetMillis)
        if (targetIdx != centeredIndex) {
            centeredIndex = targetIdx
            if (isHoldingDrag) {
                listState.scrollToItem(targetIdx)
            } else {
                listState.animateScrollToItem(targetIdx)
            }
        }
    }

    // 打开或滚动「速览」时通知雪碧图引擎优先抓取当前视口中心附近槽位（Stage c 交互覆盖）：
    // 仅按 frames.isNotEmpty() 建协程，避免后续缩略图批次到达时重启协程并把焦点冲回 originMillis。
    LaunchedEffect(frameCache, listState, frames.isNotEmpty()) {
        if (frameCache == null || latestFrames.isEmpty()) return@LaunchedEffect
        var initialBoostSent = false
        snapshotFlow {
            val idx = centeredFrameIndex(listState, centeredIndex)
            latestFrames.getOrNull(idx) ?: originMillis
        }
            .distinctUntilChanged()
            .collectLatest { focusMs ->
                if (!initialBoostSent) {
                    initialBoostSent = true
                    frameCache.notifyUserFocus(focusMs)
                } else {
                    delay(FOCUS_BOOST_COALESCE_MS)
                    frameCache.notifyUserFocus(focusMs)
                }
            }
    }

    val activeMillis = frames.getOrNull(centeredIndex) ?: targetMillis.coerceIn(0L, safeDuration)
    val deltaMillis = activeMillis - originMillis

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = fixed.ScrimOnMediaStrong,
        border = BorderStroke(1.dp, fixed.HudSpecularBorder),
        shadowElevation = 12.dp,
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = FILMSTRIP_MAX_WIDTH)
            // 吞掉面板内的点击，防止穿透到外层遮罩误关闭
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            modifier = Modifier.padding(top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 顶栏：速览徽章 + 实时播放头时间码/相对偏移 + 「跳转到此处」按钮 + 关闭键
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(scheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ViewCarousel,
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(Res.string.player_rapid_scrub_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onPrimaryContainer,
                        maxLines = 1,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = formatTime(activeMillis),
                        style = MaterialTheme.typography.titleMedium.tabular,
                        fontWeight = FontWeight.SemiBold,
                        color = fixed.OnMedia,
                        maxLines = 1,
                    )
                    if (abs(deltaMillis) >= 1_000L) {
                        val sign = if (deltaMillis >= 0L) "+" else "−"
                        Text(
                            text = "$sign${formatTime(abs(deltaMillis))}",
                            style = MaterialTheme.typography.labelMedium.tabular,
                            color = scheme.primary,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = "/ ${formatTime(safeDuration)}",
                        style = MaterialTheme.typography.labelMedium.tabular,
                        color = fixed.OnMedia.copy(alpha = 0.65f),
                        maxLines = 1,
                    )
                }

                FilledTonalButton(
                    onClick = { onCommit(activeMillis) },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 36.dp).handCursor(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(Res.string.player_rapid_scrub_jump),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }

                IconButton(
                    onClick = onCancel,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.handCursor(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("关闭", "Close", "閉じる", "닫기", "關閉"),
                        tint = fixed.OnMedia.copy(alpha = 0.85f),
                    )
                }
            }

            // 核心：从左到右可滚动类胶片时间轴（带上下齿孔轨、渐进缩略图与正中红色/主题色播放头）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D0F13))
                    .onSizeChanged { stripWidthPx = it.width },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    FilmstripSprocketRail(modifier = Modifier.fillMaxWidth().height(FILMSTRIP_SPROCKET_HEIGHT))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(tileHeight + FILMSTRIP_VERTICAL_PADDING * 2)
                            .verticalWheelScrollsRow(listState)
                            .mouseDragHorizontalScroll(listState),
                    ) {
                        LazyRow(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = sidePadding, vertical = FILMSTRIP_VERTICAL_PADDING),
                            horizontalArrangement = Arrangement.spacedBy(FILMSTRIP_FRAME_GAP),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                items = displaySlots,
                                key = { index, slot -> "${slot.slotIndex}_$index" },
                            ) { index, slot ->
                                FilmstripTile(
                                    bitmap = slot.bitmap,
                                    timestampText = formatTime(slot.timestampMillis),
                                    width = tileWidth,
                                    height = tileHeight,
                                    isCentered = index == centeredIndex,
                                    isCurrentPlaybackSlot = index == originIndex,
                                    onClick = { onCommit(slot.timestampMillis) },
                                )
                            }
                        }

                        // 左右两侧柔和暗角，突出正中播放头对准区间
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .width(28.dp)
                                .background(Brush.horizontalGradient(listOf(Color(0xCC0D0F13), Color.Transparent))),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(28.dp)
                                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xCC0D0F13)))),
                        )

                        // 正中播放头竖线与顶部/底部定位帽
                        CenterPlayheadIndicator(
                            color = scheme.primary,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxHeight()
                                .width(12.dp),
                        )
                    }

                    FilmstripSprocketRail(modifier = Modifier.fillMaxWidth().height(FILMSTRIP_SPROCKET_HEIGHT))
                }
            }

            // 底部可点可拖的迷你全片总览时间轴
            FilmstripOverviewScrubber(
                activeMillis = activeMillis,
                originMillis = originMillis,
                durationMillis = safeDuration,
                onScrubToMillis = { target ->
                    latestOnTargetChange(target)
                    val idx = nearestFrameIndex(latestFrames, target)
                    centeredIndex = idx
                    scope.launch { listState.scrollToItem(idx) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            // 单行精简提示（无重复表意）
            Text(
                text = stringResource(Res.string.player_rapid_scrub_hint),
                style = MaterialTheme.typography.labelSmall,
                color = fixed.OnMedia.copy(alpha = 0.68f),
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * 桌面鼠标按住左右拖拽胶片条：仅响应 `PointerType.Mouse` 且跨过 `touchSlop` 后才接管，
 * 避免与单帧点击跳转或触屏 `LazyRow` 原生滚动冲突。
 */
private fun Modifier.mouseDragHorizontalScroll(listState: LazyListState): Modifier = pointerInput(listState) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type != PointerType.Mouse) return@awaitEachGesture
        var dragging = false
        var accumX = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.positionChange()
            if (!dragging) {
                accumX += delta.x
                if (abs(accumX) > slop) {
                    dragging = true
                    listState.dispatchRawDelta(-accumX)
                    change.consume()
                }
            } else if (delta.x != 0f) {
                listState.dispatchRawDelta(-delta.x)
                change.consume()
            }
        }
    }
}

@Composable
private fun FilmstripSprocketRail(modifier: Modifier = Modifier) {
    val holeColor = Color.White.copy(alpha = 0.14f)
    Canvas(modifier = modifier) {
        val holeW = 10.dp.toPx()
        val holeH = 5.dp.toPx()
        val pitch = 22.dp.toPx()
        val corner = 1.5.dp.toPx()
        val y = (size.height - holeH) / 2f
        var x = 8.dp.toPx()
        while (x + holeW < size.width) {
            drawRoundRect(
                color = holeColor,
                topLeft = Offset(x, y),
                size = Size(holeW, holeH),
                cornerRadius = CornerRadius(corner, corner),
            )
            x += pitch
        }
    }
}

@Composable
private fun CenterPlayheadIndicator(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val lineW = 2.5.dp.toPx()
        val capW = 8.dp.toPx()
        val capH = 4.dp.toPx()
        val capR = 2.dp.toPx()
        drawLine(
            color = color,
            start = Offset(cx, capH),
            end = Offset(cx, size.height - capH),
            strokeWidth = lineW,
            cap = StrokeCap.Round,
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(cx - capW / 2f, 0f),
            size = Size(capW, capH),
            cornerRadius = CornerRadius(capR, capR),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(cx - capW / 2f, size.height - capH),
            size = Size(capW, capH),
            cornerRadius = CornerRadius(capR, capR),
        )
    }
}

@Composable
private fun FilmstripTile(
    bitmap: ImageBitmap?,
    timestampText: String,
    width: Dp,
    height: Dp,
    isCentered: Boolean,
    isCurrentPlaybackSlot: Boolean = false,
    onClick: () -> Unit,
) {
    val fixed = LocalFixedColors.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val borderColor = when {
        isCentered -> scheme.primary
        isCurrentPlaybackSlot -> scheme.tertiary
        else -> fixed.HudSpecularBorder
    }
    val borderWidth = when {
        isCentered -> 2.5.dp
        isCurrentPlaybackSlot -> 2.dp
        else -> 1.dp
    }

    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(Color(0xFF12141A))
            .border(borderWidth, borderColor, shape)
            .clickable(onClick = onClick)
            .handCursor(),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = timestampText,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.62f to Color.Transparent,
                            1f to Color(0xB8000000),
                        ),
                    ),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Movie,
                contentDescription = null,
                tint = fixed.OnMedia.copy(alpha = if (isCentered) 0.38f else 0.22f),
                modifier = Modifier.size(28.dp),
            )
        }

        if (isCurrentPlaybackSlot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(scheme.tertiary),
            )
        }

        Text(
            text = timestampText,
            style = MaterialTheme.typography.labelSmall.tabular,
            fontWeight = if (isCentered || isCurrentPlaybackSlot) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                isCentered -> scheme.onPrimary
                isCurrentPlaybackSlot -> scheme.onTertiary
                else -> fixed.OnMedia
            },
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        isCentered -> scheme.primary.copy(alpha = 0.94f)
                        isCurrentPlaybackSlot -> scheme.tertiary.copy(alpha = 0.94f)
                        else -> Color(0xB3000000)
                    },
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun FilmstripOverviewScrubber(
    activeMillis: Long,
    originMillis: Long,
    durationMillis: Long,
    onScrubToMillis: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fixed = LocalFixedColors.current
    val scheme = MaterialTheme.colorScheme
    val latestOnScrub by rememberUpdatedState(onScrubToMillis)
    val safeDuration = durationMillis.coerceAtLeast(1L)
    val targetFraction = (activeMillis.toFloat() / safeDuration).coerceIn(0f, 1f)
    val originFraction = (originMillis.toFloat() / safeDuration).coerceIn(0f, 1f)
    val activeColor = scheme.primary
    val inactiveColor = fixed.OnMedia.copy(alpha = 0.24f)
    val originMarkerColor = fixed.OnMedia.copy(alpha = 0.72f)

    fun millisForX(x: Float, width: Float, inset: Float): Long {
        val span = (width - inset * 2f).coerceAtLeast(1f)
        val frac = ((x - inset) / span).coerceIn(0f, 1f)
        return (frac * safeDuration).toLong().coerceIn(0L, safeDuration)
    }

    Canvas(
        modifier = modifier
            .height(22.dp)
            .handCursor()
            .pointerInput(safeDuration) {
                val inset = 6.dp.toPx()
                detectTapGestures { offset ->
                    latestOnScrub(millisForX(offset.x, size.width.toFloat(), inset))
                }
            }
            .pointerInput(safeDuration) {
                val inset = 6.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        latestOnScrub(millisForX(offset.x, size.width.toFloat(), inset))
                    },
                ) { change, _ ->
                    change.consume()
                    latestOnScrub(millisForX(change.position.x, size.width.toFloat(), inset))
                }
            },
    ) {
        val inset = 6.dp.toPx()
        val cy = size.height / 2f
        val startX = inset
        val endX = (size.width - inset).coerceAtLeast(startX)
        val span = endX - startX
        val targetX = startX + span * targetFraction
        val originX = startX + span * originFraction
        val trackStroke = 4.dp.toPx()
        drawLine(inactiveColor, Offset(startX, cy), Offset(endX, cy), trackStroke, StrokeCap.Round)
        drawLine(activeColor, Offset(startX, cy), Offset(targetX, cy), trackStroke, StrokeCap.Round)
        drawLine(
            color = originMarkerColor,
            start = Offset(originX, cy - 5.dp.toPx()),
            end = Offset(originX, cy + 5.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(activeColor, 6.dp.toPx(), Offset(targetX, cy))
    }
}

private val FILMSTRIP_MAX_WIDTH = 1280.dp
private val FILMSTRIP_TILE_WIDTH_WIDE = 240.dp
private val FILMSTRIP_TILE_HEIGHT_WIDE = 135.dp
private val FILMSTRIP_TILE_WIDTH_COMPACT = 184.dp
private val FILMSTRIP_TILE_HEIGHT_COMPACT = 104.dp
private val FILMSTRIP_FRAME_GAP = 10.dp
private val FILMSTRIP_VERTICAL_PADDING = 12.dp
private val FILMSTRIP_SPROCKET_HEIGHT = 14.dp
private const val FOCUS_BOOST_COALESCE_MS = 60L
