package dev.piko.ui.screens.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.LocalWindowVisible
import dev.piko.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 进度条（移植自 Bilby `SeekBar.kt`）：
 * - 触屏相对拖动（落指不跳针，按水平位移增量微调）、键鼠绝对拖动，单击/轻点独立提交定位避免暂停-恢复顿挫；
 * - M3E `4dp -> 6dp` 轨道与 `5dp -> 8dp` 手柄 `fastSpatialSpec` 形变、已缓冲段与末尾 `2dp` 停止圆点；
 * - 播放中且未交互、未开减少动画且窗口可见时，按 30fps（33ms）在绘制阶段推进波浪进度，零重组；
 * - 零布局高度预览气泡支持实时缩略帧（经 [LocalScrubFrameCache] 共享）与时间/相对偏移胶囊。
 */
@Composable
internal fun PlayerSeekBar(
    positionMillisProvider: () -> Long,
    durationMillis: Long,
    bufferedPositionMillisProvider: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    playbackSpeed: Float = 1f,
    wavy: Boolean = false,
    thumbOnHoverOnly: Boolean = false,
    onScrub: (Long?) -> Unit = {},
    // 在别处拖动进度（播放器画面上的横滑）时的目标处：手柄与时间气泡照拖动进度条本身的样子显示在这里
    previewMillis: Long? = null,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var hoverFraction by remember { mutableStateOf<Float?>(null) }
    var scrubOriginMillis by remember { mutableLongStateOf(0L) }
    // 松手到新位置回报之间有一段延迟，这段时间里滑块停在目标处，不回跳到旧位置
    var pendingSeekMillis by remember { mutableStateOf<Long?>(null) }
    var pendingSeekSeq by remember { mutableIntStateOf(0) }
    val latestPositionProvider by rememberUpdatedState(positionMillisProvider)
    val latestBufferedProvider by rememberUpdatedState(bufferedPositionMillisProvider)
    val currentDurationMillis by rememberUpdatedState(durationMillis)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentSpeed by rememberUpdatedState(playbackSpeed.coerceIn(0.25f, 4f))
    val relativeMode by rememberUpdatedState(LocalPointerSource.current.isTouchLike)

    LaunchedEffect(pendingSeekMillis, pendingSeekSeq) {
        val pending = pendingSeekMillis ?: return@LaunchedEffect
        withTimeoutOrNull(SEEK_SETTLE_TIMEOUT_MILLIS) {
            snapshotFlow { latestPositionProvider() }
                .first { abs(it - pending) < SEEK_SETTLE_TOLERANCE_MILLIS }
        }
        pendingSeekMillis = null
    }

    // 捕获横向手势预览起点，供拖动气泡计算相对偏移 ±mm:ss
    LaunchedEffect(previewMillis != null) {
        if (previewMillis != null) {
            scrubOriginMillis = latestPositionProvider()
        }
    }

    val enabled = durationMillis > 0
    fun fractionOf(millis: Long): Float =
        if (enabled) (millis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f

    // 正在拖向的位置：拖进度条本身，或在画面上横滑
    val scrubFraction = dragFraction ?: previewMillis?.let(::fractionOf)
    val isHovered by remember { derivedStateOf { hoverFraction != null } }
    val focusInteraction = remember { MutableInteractionSource() }
    val isFocused by focusInteraction.collectIsFocusedAsState()
    val isEngaged = scrubFraction != null || isHovered || isFocused
    val scheme = MaterialTheme.colorScheme
    val trackColors = SeekTrackColors(
        active = scheme.primary,
        inactive = scheme.onSurface.copy(alpha = INACTIVE_TRACK_ALPHA),
        buffered = scheme.onSurface.copy(alpha = BUFFERED_TRACK_ALPHA),
    )
    val motion = MaterialTheme.motionScheme
    val thickness by animateDpAsState(if (isEngaged) SeekTrackEngagedThickness else SeekTrackThickness, motion.fastSpatialSpec())
    val thumbRadius by animateDpAsState(
        when {
            scrubFraction != null -> SeekThumbDraggingRadius
            // 键盘停在进度条上时手柄要露出来，否则看不出焦点在这里、方向键会动它
            thumbOnHoverOnly && !isHovered && !isFocused -> 0.dp
            else -> SeekThumbRadius
        },
        motion.fastSpatialSpec(),
    )

    // 仅音频播放启用 M3E 30fps（33ms）绘制阶段波形推进；视频与信息流保持干脆直轨
    val animateWave = wavy && isPlaying && !isEngaged && enabled && !LocalPikoMotion.current.reduced && LocalWindowVisible.current
    val wavePhase = remember { mutableFloatStateOf(0f) }
    val wavePath = remember { Path() }
    val waveFactor by animateFloatAsState(if (animateWave) 1f else 0f, motion.fastEffectsSpec())
    LaunchedEffect(animateWave) {
        if (!animateWave) return@LaunchedEffect
        while (true) {
            delay(WAVE_FRAME_DELAY_MS)
            val periodMs = (WAVE_PERIOD_MILLIS / currentSpeed).coerceIn(800f, 6_400f)
            val step = TWO_PI * (WAVE_FRAME_DELAY_MS / periodMs)
            wavePhase.floatValue = (wavePhase.floatValue + step) % TWO_PI
        }
    }

    fun commitSeek(target: Float) {
        val millis = (target * currentDurationMillis).toLong().coerceIn(0L, currentDurationMillis.coerceAtLeast(0L))
        pendingSeekMillis = millis
        pendingSeekSeq += 1
        currentOnSeek(millis)
    }

    // 方向键一步 SEEK_STEP_MILLIS，与播放器其余地方的进退同一步长，每按一下即提交，相当于拖动后松手。
    // 连按时从上一步的目标接着算，位置回报还没跟上也不会原地打转
    fun stepBy(deltaMillis: Long): Boolean {
        if (!enabled) return false
        val base = pendingSeekMillis ?: latestPositionProvider()
        commitSeek(fractionOf(base + deltaMillis))
        return true
    }

    // 无障碍语义按整秒（1000ms）粒度经 derivedStateOf 量化，播放期间 1Hz 更新，拖动/预览期间即时更新
    val latestPreview by rememberUpdatedState(previewMillis)
    val semanticsSeconds by remember(enabled, durationMillis) {
        derivedStateOf {
            if (!enabled) {
                0L
            } else {
                val drag = dragFraction
                val preview = latestPreview
                val millis = when {
                    drag != null -> (drag * durationMillis).toLong().coerceIn(0L, durationMillis)
                    preview != null -> preview.coerceIn(0L, durationMillis)
                    else -> (pendingSeekMillis ?: latestPositionProvider()).coerceIn(0L, durationMillis)
                }
                millis / 1_000L
            }
        }
    }
    val semanticsPositionMillis = semanticsSeconds * 1_000L
    val semanticsFraction = fractionOf(semanticsPositionMillis)
    val progressLabel = tr("播放进度", "Playback progress", "再生位置", "재생 위치", "播放進度")
    val semanticsStateDescription = remember(semanticsPositionMillis, durationMillis) {
        "${formatTime(semanticsPositionMillis)} / ${formatTime(durationMillis)}"
    }
    val setSemanticsProgress: (Float) -> Boolean = remember {
        { target ->
            commitSeek(target.coerceIn(0f, 1f))
            true
        }
    }

    val focusRingColor = scheme.secondary
    val barWidthPx = remember { intArrayOf(0) }

    Box(
        modifier = modifier
            .height(SeekBarHeight)
            .onSizeChanged { barWidthPx[0] = it.width },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                // 布局仍占 SeekBarHeight，接触摸的这一层撑到 48dp、上下各溢出一截，轨道画在正中不挪位置。
                // 不能指望 Compose 的最小触控区自动外扩：它只在别处都没接住时才生效，而进度条底下铺着整屏的手势层
                .requiredHeight(SeekTouchHeight)
                .handCursor(enabled)
                // 单击/轻点直接定位：不触发拖动态与暂停/恢复顿挫（与 Bilby SeekBar 一致）
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val inset = SeekThumbDraggingRadius.toPx()
                    detectTapGestures { offset ->
                        commitSeek(seekFractionAt(offset.x, size.width.toFloat(), inset))
                    }
                }
                // 横向拖动：触屏走相对位移（抓任意位置不跳针），鼠标走绝对位置（松手才提交 seek）
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val inset = SeekThumbDraggingRadius.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val origin = pendingSeekMillis ?: latestPositionProvider()
                            scrubOriginMillis = origin
                            val startFrac = if (relativeMode) {
                                fractionOf(origin)
                            } else {
                                seekFractionAt(offset.x, size.width.toFloat(), inset)
                            }
                            dragFraction = startFrac
                            currentOnScrub((startFrac * currentDurationMillis).toLong())
                        },
                        onDragEnd = {
                            dragFraction?.let(::commitSeek)
                            dragFraction = null
                            currentOnScrub(null)
                        },
                        onDragCancel = {
                            dragFraction = null
                            currentOnScrub(null)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val usable = (size.width - 2f * inset).coerceAtLeast(1f)
                            val target = if (relativeMode) {
                                ((dragFraction ?: fractionOf(scrubOriginMillis)) + dragAmount / usable).coerceIn(0f, 1f)
                            } else {
                                seekFractionAt(change.position.x, size.width.toFloat(), inset)
                            }
                            dragFraction = target
                            currentOnScrub((target * currentDurationMillis).toLong())
                        },
                    )
                }
                // 只有鼠标会悬停；触屏的移动都是拖动，交给上面处理
                .pointerInput(enabled) {
                    val inset = SeekThumbDraggingRadius.toPx()
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            hoverFraction = when {
                                !enabled || change.type != PointerType.Mouse || event.type == PointerEventType.Exit -> null
                                else -> seekFractionAt(change.position.x, size.width.toFloat(), inset)
                            }
                        }
                    }
                }
                .drawBehind {
                    // 高频位置、缓冲与波形相位仅在绘制阶段读取，0 重组
                    val liveFraction = scrubFraction ?: fractionOf(pendingSeekMillis ?: latestPositionProvider())
                    val liveBufferedFraction = fractionOf(latestBufferedProvider())
                    drawSeekTrack(
                        fraction = liveFraction,
                        bufferedFraction = liveBufferedFraction,
                        colors = trackColors,
                        thickness = thickness.toPx(),
                        thumbRadius = thumbRadius.toPx(),
                        inset = SeekThumbDraggingRadius.toPx(),
                        focusRing = if (isFocused) focusRingColor else null,
                        waveAmplitudePx = SeekWaveAmplitude.toPx() * waveFactor,
                        waveLengthPx = SeekWaveLength.toPx(),
                        waveGapPx = SeekWaveGap.toPx() * waveFactor,
                        wavePhase = wavePhase.floatValue,
                        wavePath = wavePath,
                        showStopDot = enabled,
                    )
                }
                // M3 滑块的键盘约定：Tab 停到手柄上，方向键调值。在播放器与信息流里，上级根节点的
                // onPreviewKeyEvent 先接住左右方向键（同为 SEEK_STEP_MILLIS 一步，另有按住快进），这里收不到；
                // 让上级赢，是因为那边的进退有读数反馈与按住加速，焦点停在进度条上时行为也不变。
                // 这里的处理留给没有上级快捷键的场合
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionRight -> stepBy(SEEK_STEP_MILLIS)
                        Key.DirectionLeft -> stepBy(-SEEK_STEP_MILLIS)
                        else -> false
                    }
                }
                .focusable(enabled, focusInteraction)
                .semantics {
                    contentDescription = progressLabel
                    stateDescription = semanticsStateDescription
                    progressBarRangeInfo = ProgressBarRangeInfo(semanticsFraction, 0f..1f)
                    if (enabled) {
                        setProgress(action = setSemanticsProgress)
                    }
                },
        )

        // 拖动或悬停时的预览气泡隔离为子组件：鼠标在轨道上滑动只更新气泡自身，不重组外层进度条
        if (scrubFraction != null || isHovered) {
            SeekPreviewBubble(
                scrubFraction = scrubFraction,
                hoverFractionProvider = { hoverFraction },
                durationMillis = durationMillis,
                originMillis = scrubOriginMillis,
                barWidthPxProvider = { barWidthPx[0] },
            )
        }
    }
}

@Composable
private fun SeekPreviewBubble(
    scrubFraction: Float?,
    hoverFractionProvider: () -> Float?,
    durationMillis: Long,
    originMillis: Long,
    barWidthPxProvider: () -> Int,
) {
    val shown = scrubFraction ?: hoverFractionProvider() ?: return
    val density = LocalDensity.current
    val inset = with(density) { SeekThumbDraggingRadius.toPx() }
    val gapPx = with(density) { SeekBubbleGap.roundToPx() }
    val dragging = scrubFraction != null
    val scheme = MaterialTheme.colorScheme
    val fixed = LocalFixedColors.current
    val targetMillis = (shown * durationMillis).toLong().coerceIn(0L, durationMillis.coerceAtLeast(0L))
    val deltaMillis = targetMillis - originMillis

    val frameCache = LocalScrubFrameCache.current
    val hasStoryboard = frameCache != null && durationMillis > 0L
    val previewFrame = if (frameCache != null && durationMillis > 0L) {
        // 读取 revision 使新解码帧到位后仅刷新气泡自身；O(log N) 真实 PTS 二分查帧 < 1ms，0 网络
        frameCache.revision
        val quantizedBoostMs = (targetMillis / 2_000L) * 2_000L
        LaunchedEffect(frameCache, quantizedBoostMs) {
            frameCache.notifyUserFocus(targetMillis)
        }
        frameCache.frameAt(targetMillis)
    } else {
        null
    }

    // 必须传无界 Constraints()：外层 PlayerSeekBar 布局高度仅 32dp，若继承父 maxHeight 会把 16:9 预览卡压扁成胶囊
    val bubbleModifier = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints())
        val widthPx = when {
            barWidthPxProvider() > 0 -> barWidthPxProvider()
            constraints.hasBoundedWidth -> constraints.maxWidth
            else -> 0
        }
        val trackWidthPx = (widthPx - 2 * inset).coerceAtLeast(0f)
        val anchorPx = inset + shown * trackWidthPx
        val maxX = (widthPx - placeable.width).coerceAtLeast(0)
        val x = (anchorPx - placeable.width / 2f).roundToInt().coerceIn(0, maxX)
        layout(0, 0) { placeable.place(x, -placeable.height - gapPx) }
    }

    if (hasStoryboard) {
        val barWidthPx = barWidthPxProvider()
        val compactPreview = barWidthPx > 0 && with(density) { barWidthPx.toDp() } < SeekPreviewCompactThreshold
        val previewWidth = if (compactPreview) SeekPreviewWidthCompact else SeekPreviewWidthWide
        val previewHeight = if (compactPreview) SeekPreviewHeightCompact else SeekPreviewHeightWide
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF101218),
            border = BorderStroke(1.dp, if (dragging) scheme.primary else fixed.HudSpecularBorder),
            shadowElevation = 8.dp,
            modifier = bubbleModifier.testTag(SEEK_PREVIEW_CARD_TEST_TAG),
        ) {
            Box(
                modifier = Modifier.size(width = previewWidth, height = previewHeight),
                contentAlignment = Alignment.Center,
            ) {
                if (previewFrame != null) {
                    Image(
                        bitmap = previewFrame,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Movie,
                        contentDescription = null,
                        tint = fixed.OnMedia.copy(alpha = 0.28f),
                        modifier = Modifier.size(28.dp),
                    )
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.58f to Color.Transparent,
                                1f to Color(0xD9000000),
                            ),
                        ),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xA6000000))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatTime(targetMillis),
                        style = MaterialTheme.typography.labelMedium.tabular.copy(shadow = PlayerTextShadow),
                        color = fixed.OnMedia,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (dragging && abs(deltaMillis) >= 1_000L) {
                        Text(
                            text = formatSeekDelta(deltaMillis),
                            style = MaterialTheme.typography.labelSmall.tabular.copy(shadow = PlayerTextShadow),
                            color = scheme.primaryContainer,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    } else {
        Surface(
            shape = MaterialTheme.shapes.small,
            // 拖动时是要跳过去的位置，用主题色强调；悬停只是看看，用半透明高光深色胶囊
            color = if (dragging) scheme.primary else fixed.ScrimOnMediaStrong,
            contentColor = if (dragging) scheme.onPrimary else fixed.OnMedia,
            border = if (dragging) null else BorderStroke(1.dp, fixed.HudSpecularBorder),
            modifier = bubbleModifier,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(targetMillis),
                    style = MaterialTheme.typography.labelLarge.tabular,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (dragging && abs(deltaMillis) >= 1_000L) {
                    Text(
                        text = formatSeekDelta(deltaMillis),
                        style = MaterialTheme.typography.labelSmall.tabular,
                        color = scheme.onPrimary.copy(alpha = 0.82f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun formatSeekDelta(deltaMillis: Long): String {
    val sign = if (deltaMillis >= 0L) "+" else "−"
    return "$sign${formatTime(abs(deltaMillis))}"
}

/** 指针横坐标对应的进度。两端各让出手柄放大后的半径，拖到头时手柄不出界。 */
internal fun seekFractionAt(x: Float, width: Float, inset: Float): Float =
    ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)

@Immutable
internal data class SeekTrackColors(val active: Color, val inactive: Color, val buffered: Color)

/**
 * 轨道从左到右：已播放段（播放中可选 M3E 波浪）、手柄、缓冲段、未播放段与末尾停止圆点。[inset] 是两端给手柄留的边，
 * 与 [seekFractionAt] 的换算一致，手柄画在哪、点下去就是哪。
 */
internal fun DrawScope.drawSeekTrack(
    fraction: Float,
    bufferedFraction: Float,
    colors: SeekTrackColors,
    thickness: Float,
    thumbRadius: Float,
    inset: Float,
    focusRing: Color?,
    waveAmplitudePx: Float = 0f,
    waveLengthPx: Float = 0f,
    waveGapPx: Float = 0f,
    wavePhase: Float = 0f,
    wavePath: Path? = null,
    showStopDot: Boolean = false,
) {
    val centerY = size.height / 2
    val start = inset
    val end = size.width - inset
    val thumbX = start + (end - start) * fraction
    val bufferedX = start + (end - start) * bufferedFraction

    drawLine(colors.inactive, Offset(thumbX, centerY), Offset(end, centerY), thickness, StrokeCap.Round)
    if (bufferedX > thumbX) {
        drawLine(colors.buffered, Offset(thumbX, centerY), Offset(bufferedX, centerY), thickness, StrokeCap.Round)
    }
    if (showStopDot && fraction < 0.985f && end > thumbX + SeekStopDotRadius.toPx() * 2f) {
        drawCircle(colors.active, SeekStopDotRadius.toPx(), Offset(end, centerY))
    }

    val activeEnd = (thumbX - waveGapPx).coerceAtLeast(start)
    val activeSpan = activeEnd - start
    if (activeSpan > 0f) {
        if (wavePath != null && waveAmplitudePx > 0.05f && waveLengthPx > 0f && activeSpan > waveLengthPx * 0.6f) {
            wavePath.reset()
            val stepPx = 3f
            val taperPx = waveLengthPx * 0.5f
            val freq = TWO_PI / waveLengthPx
            var x = 0f
            wavePath.moveTo(start, centerY)
            while (x < activeSpan) {
                x = (x + stepPx).coerceAtMost(activeSpan)
                val edgeStart = (x / taperPx).coerceIn(0f, 1f)
                val edgeEnd = ((activeSpan - x) / taperPx).coerceIn(0f, 1f)
                val env = minOf(edgeStart, edgeEnd)
                val y = centerY + sin(x * freq - wavePhase) * waveAmplitudePx * env
                wavePath.lineTo(start + x, y)
            }
            drawPath(
                path = wavePath,
                color = colors.active,
                style = Stroke(width = thickness, cap = StrokeCap.Round),
            )
        } else {
            drawLine(colors.active, Offset(start, centerY), Offset(thumbX, centerY), thickness, StrokeCap.Round)
        }
    }
    if (thumbRadius > 0f) drawCircle(colors.active, thumbRadius, Offset(thumbX, centerY))
    // 键盘焦点照 M3 画在手柄外面一圈，隔开一道缝，不与手柄连成一块
    if (focusRing != null) {
        val ringWidth = SeekFocusRingWidth.toPx()
        val ringRadius = thumbRadius + SeekFocusRingGap.toPx() + ringWidth / 2
        drawCircle(focusRing, ringRadius, Offset(thumbX, centerY), style = Stroke(ringWidth))
    }
}

// 进度条的布局高度与轨道几何。布局高度决定它在底栏里占多少地方，ClipChrome 按它把细线挪到底边，
// 不随触控区改；触控区另取 48dp 的最小触控尺寸，细轨道要能点中
private val SeekBarHeight = 32.dp
private val SeekTouchHeight = 48.dp
private val SeekFocusRingWidth = 3.dp
private val SeekFocusRingGap = 2.dp
internal val SeekTrackThickness = 4.dp
internal val SeekTrackEngagedThickness = 6.dp
internal val SeekThumbRadius = 5.dp
internal val SeekThumbDraggingRadius = 8.dp
private val SeekStopDotRadius = 2.dp
private val SeekBubbleGap = 10.dp
private val SeekPreviewWidthWide = 192.dp
private val SeekPreviewHeightWide = 108.dp
private val SeekPreviewWidthCompact = 160.dp
private val SeekPreviewHeightCompact = 90.dp
private val SeekPreviewCompactThreshold = 560.dp
internal const val SEEK_PREVIEW_CARD_TEST_TAG = "seek-preview-card"
private val SeekWaveAmplitude = 3.dp
private val SeekWaveLength = 28.dp
private val SeekWaveGap = 4.dp
private const val WAVE_FRAME_DELAY_MS = 33L
private const val WAVE_PERIOD_MILLIS = 3_200f
private const val TWO_PI = (PI * 2.0).toFloat()
internal const val INACTIVE_TRACK_ALPHA = 0.28f
private const val BUFFERED_TRACK_ALPHA = 0.55f
private const val SEEK_SETTLE_TOLERANCE_MILLIS = 1_500L
private const val SEEK_SETTLE_TIMEOUT_MILLIS = 1_500L
