package dev.piko.ui.screens.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.LocalWindowVisible
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class DropBatchItem(
    val startAt: Float, // 在 0..1 投送窗口内的起始时刻
    val duration: Float,
    val startXFrac: Float,
    val startYDp: Float,
    val kind: Int,
    val tiltDeg: Float,
)

private data class ClipBeat(
    val watchFraction: Float,
    val colorIndex: Int,
)

/**
 * Welcome 屏四幕叙事画布（无章鱼连接线、无鬼畜弹跳）：
 * 1. 一笔画勾勒无蓝底 Piko 猫脸；
 * 2. 上方 7 枚文件快速噼里啪啦投入 Piko，Piko 平稳微胀；
 * 3. Piko 蓄力憋一下（压扁微眯眼）；
 * 4. Bang! 弹性复原并迸出微光短芒，同时从 Piko 中心向下方弹出「视频 · 信息流 · 瀑布流」三视图；
 * 5. 落位后 Piko 安静微笑（偶尔自然眨眼），下方三视图持续活跃播放与快速刷新；点击画布可重播。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PikoOnboardingCanvas(
    modifier: Modifier = Modifier,
    onBang: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val reduced = LocalPikoMotion.current.reduced
    val windowVisible = LocalWindowVisible.current
    val animate = !reduced && windowVisible
    val expressive = remember { MotionScheme.expressive() }

    var replayKey by remember { mutableIntStateOf(0) }

    // Act 1: 一笔画现身
    val outlineDraw = remember { Animatable(if (reduced) 1f else 0f) }
    val faceDetails = remember { Animatable(if (reduced) 1f else 0f) }
    // Act 2: 快速丢进一堆文件（0 -> 1）
    val dropBatch = remember { Animatable(if (reduced) 1f else 0f) }
    // Act 3: 憋一下蓄力（0 -> 1 压扁 + > < 眼；chargeShake 0 -> 1 憋劲抖动几下）
    val chargeAmount = remember { Animatable(0f) }
    val chargeShake = remember { Animatable(0f) }
    // Act 4: Bang 迸发微芒（0 -> 1）+ 三张卡片从 Piko 中心弹射落位（0 -> 1）
    val bangBurst = remember { Animatable(if (reduced) 1f else 0f) }
    val videoPop = remember { Animatable(if (reduced) 1f else 0f) }
    val clipsPop = remember { Animatable(if (reduced) 1f else 0f) }
    val galleryPop = remember { Animatable(if (reduced) 1f else 0f) }

    LaunchedEffect(reduced, replayKey) {
        if (reduced) {
            outlineDraw.snapTo(1f)
            faceDetails.snapTo(1f)
            dropBatch.snapTo(1f)
            chargeAmount.snapTo(0f)
            chargeShake.snapTo(0f)
            bangBurst.snapTo(1f)
            videoPop.snapTo(1f)
            clipsPop.snapTo(1f)
            galleryPop.snapTo(1f)
            onBang()
        } else {
            outlineDraw.snapTo(0f)
            faceDetails.snapTo(0f)
            dropBatch.snapTo(0f)
            chargeAmount.snapTo(0f)
            chargeShake.snapTo(0f)
            bangBurst.snapTo(0f)
            videoPop.snapTo(0f)
            clipsPop.snapTo(0f)
            galleryPop.snapTo(0f)

            // Act 1: 0..420ms 一笔画 Piko 轮廓
            launch { outlineDraw.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing)) }
            launch {
                delay(200)
                faceDetails.animateTo(1f, expressive.defaultSpatialSpec())
            }

            // Act 2: 340..1500ms 连续丢进 10 个清晰的文件/文件夹
            delay(340)
            dropBatch.animateTo(1f, tween(durationMillis = 1_160, easing = LinearEasing))

            // Act 3: 1500..2040ms Piko 憋一下（压扁 + > < 眼 + 蓄力抖动几下）
            launch { chargeAmount.animateTo(1f, tween(durationMillis = 210, easing = FastOutSlowInEasing)) }
            delay(110)
            chargeShake.animateTo(1f, tween(durationMillis = 430, easing = LinearEasing))

            // Act 4: Bang! 瞬间释放，复原笑脸 + 迸发短芒 + 三张视图从 Piko 中心蹦出
            chargeShake.snapTo(0f)
            onBang()
            launch { chargeAmount.animateTo(0f, expressive.fastSpatialSpec()) }
            launch { bangBurst.animateTo(1f, tween(durationMillis = 380, easing = FastOutSlowInEasing)) }
            launch { videoPop.animateTo(1f, expressive.defaultSpatialSpec()) }
            launch {
                delay(65)
                clipsPop.animateTo(1f, expressive.defaultSpatialSpec())
            }
            launch {
                delay(130)
                galleryPop.animateTo(1f, expressive.defaultSpatialSpec())
            }
        }
    }

    // 10 枚错落投送的文件/文件夹（kind: 0=视频文件, 1=图片文件, 2=文档文件, 3=文件夹）
    val batchItems = remember {
        listOf(
            DropBatchItem(startAt = 0.00f, duration = 0.34f, startXFrac = -0.36f, startYDp = 10f, kind = 0, tiltDeg = -16f),
            DropBatchItem(startAt = 0.07f, duration = 0.32f, startXFrac = 0.32f, startYDp = 6f, kind = 1, tiltDeg = 14f),
            DropBatchItem(startAt = 0.14f, duration = 0.34f, startXFrac = -0.10f, startYDp = 2f, kind = 3, tiltDeg = -8f),
            DropBatchItem(startAt = 0.22f, duration = 0.32f, startXFrac = 0.18f, startYDp = 12f, kind = 2, tiltDeg = 12f),
            DropBatchItem(startAt = 0.30f, duration = 0.34f, startXFrac = -0.28f, startYDp = 4f, kind = 1, tiltDeg = -14f),
            DropBatchItem(startAt = 0.38f, duration = 0.32f, startXFrac = 0.38f, startYDp = 14f, kind = 0, tiltDeg = 18f),
            DropBatchItem(startAt = 0.46f, duration = 0.34f, startXFrac = 0.02f, startYDp = 0f, kind = 2, tiltDeg = 6f),
            DropBatchItem(startAt = 0.53f, duration = 0.32f, startXFrac = -0.34f, startYDp = 14f, kind = 3, tiltDeg = -18f),
            DropBatchItem(startAt = 0.60f, duration = 0.33f, startXFrac = 0.24f, startYDp = 4f, kind = 0, tiltDeg = 10f),
            DropBatchItem(startAt = 0.67f, duration = 0.33f, startXFrac = -0.06f, startYDp = 2f, kind = 1, tiltDeg = -6f),
        )
    }

    // 信息流快速刷片节拍表：多数播到 22%~36% 就干脆上滑切下一条
    val clipBeats = remember {
        listOf(
            ClipBeat(watchFraction = 0.28f, colorIndex = 0),
            ClipBeat(watchFraction = 0.36f, colorIndex = 1),
            ClipBeat(watchFraction = 0.22f, colorIndex = 2),
            ClipBeat(watchFraction = 0.62f, colorIndex = 0),
            ClipBeat(watchFraction = 0.30f, colorIndex = 1),
            ClipBeat(watchFraction = 0.25f, colorIndex = 2),
        )
    }

    val clipsCycle: Float
    val scrollCycle: Float
    val idleBlinkCycle: Float
    if (animate) {
        val infinite = rememberInfiniteTransition(label = "piko-onboarding-live")
        clipsCycle = infinite.animateFloat(
            initialValue = 0f,
            targetValue = clipBeats.size.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 7_500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "clips-cycle",
        ).value
        scrollCycle = infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4_800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "scroll-cycle",
        ).value
        idleBlinkCycle = infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4_600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "idle-blink",
        ).value
    } else {
        clipsCycle = 0.3f
        scrollCycle = 0.2f
        idleBlinkCycle = 0f
    }

    val pikoFacePath = remember {
        Path().apply {
            moveTo(24f, 15f)
            lineTo(33f, 15f)
            arcTo(Rect(25.5f, 15f, 40.5f, 30f), -90f, 90f, false)
            lineTo(40.5f, 32.5f)
            arcTo(Rect(25.5f, 25f, 40.5f, 40f), 0f, 90f, false)
            lineTo(15f, 40f)
            arcTo(Rect(7.5f, 25f, 22.5f, 40f), 90f, 90f, false)
            lineTo(7.5f, 22.5f)
            arcTo(Rect(7.5f, 15f, 22.5f, 30f), 180f, 90f, false)
            close()
        }
    }
    val pikoEarLeft = remember {
        Path().apply {
            moveTo(10.5f, 16.8f)
            cubicTo(10.5f, 14.0f, 12.2f, 11.0f, 13.5f, 9.8f)
            cubicTo(14.2f, 9.0f, 15.5f, 9.2f, 16.0f, 10.2f)
            lineTo(20.8f, 15.2f)
            close()
        }
    }
    val pikoEarRight = remember {
        Path().apply {
            moveTo(37.5f, 16.8f)
            cubicTo(37.5f, 14.0f, 35.8f, 11.0f, 34.5f, 9.8f)
            cubicTo(33.8f, 9.0f, 32.5f, 9.2f, 32.0f, 10.2f)
            lineTo(27.2f, 15.2f)
            close()
        }
    }
    val pikoSmile = remember {
        Path().apply {
            moveTo(20.8f, 31.8f)
            quadraticTo(24f, 35.5f, 27.2f, 31.8f)
        }
    }
    val pikoSquintLeft = remember {
        Path().apply {
            moveTo(14.8f, 22.8f)
            lineTo(19.6f, 25.6f)
            lineTo(14.8f, 28.4f)
        }
    }
    val pikoSquintRight = remember {
        Path().apply {
            moveTo(33.2f, 22.8f)
            lineTo(28.4f, 25.6f)
            lineTo(33.2f, 28.4f)
        }
    }

    val measure = remember { PathMeasure() }
    val segmentPath = remember { Path() }
    val playTriPath = remember { Path() }
    val clipRoundPath = remember { Path() }
    val tokenBodyPath = remember { Path() }
    val tokenDetailPath = remember { Path() }

    val primary = colors.primary
    val primaryContainer = colors.primaryContainer
    val secondaryContainer = colors.secondaryContainer
    val tertiaryContainer = colors.tertiaryContainer
    val onPrimary = colors.onPrimary
    val onPrimaryContainer = colors.onPrimaryContainer
    val surfaceLow = colors.surfaceContainerLow
    val surfaceHigh = colors.surfaceContainerHigh
    val outline = colors.outlineVariant

    Canvas(
        modifier = modifier
            .height(256.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (!reduced) replayKey++
            },
    ) {
        val w = size.width
        val h = size.height
        val u = (w / 360.dp.toPx()).coerceIn(0.80f, 1.06f)
        val cx = w * 0.5f
        val pikoCenter = Offset(cx, h * 0.34f)
        val pikoSize = (76.dp.toPx() * u)
        val pikoScale = pikoSize / 48f
        val fills = arrayOf(secondaryContainer, primaryContainer, tertiaryContainer)

        // 1. Act 2：连续丢进一堆清晰的文件与文件夹
        val batchT = dropBatch.value
        if (batchT > 0f && batchT < 1f) {
            for (item in batchItems) {
                val local = ((batchT - item.startAt) / item.duration)
                if (local in 0f..1f) {
                    val eased = FastOutSlowInEasing.transform(local)
                    drawFallingToken(
                        progress = eased,
                        start = Offset(cx + w * item.startXFrac, item.startYDp.dp.toPx()),
                        end = Offset(cx + ((item.kind % 3) - 1) * 5.dp.toPx(), pikoCenter.y - 10.dp.toPx()),
                        fill = fills[item.kind % fills.size],
                        stroke = primary.copy(alpha = 0.78f),
                        kind = item.kind,
                        tiltDeg = item.tiltDeg,
                        bodyPath = tokenBodyPath,
                        detailPath = tokenDetailPath,
                    )
                }
            }
        }

        // 2. Act 4 瞬间：Bang! 6 道极克制放射小短芒（0.36s 出现即优雅消散）
        val burstT = bangBurst.value
        if (burstT in 0.02f..0.98f) {
            val burstAlpha = sin(burstT * PI).toFloat() * 0.65f
            val rIn = 30.dp.toPx() + burstT * 14.dp.toPx()
            val rOut = rIn + 7.dp.toPx() * (1f - burstT * 0.55f)
            for (i in 0 until 6) {
                val angle = (i * 60.0 - 15.0) * PI / 180.0
                val dx = cos(angle).toFloat()
                val dy = sin(angle).toFloat()
                drawLine(
                    color = primary.copy(alpha = burstAlpha),
                    start = Offset(pikoCenter.x + dx * rIn, pikoCenter.y + dy * rIn),
                    end = Offset(pikoCenter.x + dx * rOut, pikoCenter.y + dy * rOut),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        // 3. 中央无蓝底 Piko SVG（投送时平稳微胀 -> 蓄力憋一下：压扁 + > < 眼 + 抖动几下 -> Bang 弹回安静微笑）
        val fillSwell = if (batchT < 1f) batchT * 0.06f else if (burstT < 0.1f) 0.06f else 0f
        val ch = chargeAmount.value
        val shakeT = chargeShake.value
        // 蓄力抖动：振幅随憋劲逐步放大，共抖约 4.5 次
        val shakeAmp = if (shakeT in 0.01f..0.99f) (0.35f + 0.65f * shakeT) * sin(shakeT * 4.5 * 2.0 * PI).toFloat() else 0f
        val shakeDx = shakeAmp * 2.0.dp.toPx()
        val shakeRot = shakeAmp * 2.5f

        val scaleX = 1f + fillSwell + ch * 0.105f
        val scaleY = 1f + fillSwell - ch * 0.098f
        val chOffsetY = ch * 2.8.dp.toPx()
        val detail = faceDetails.value.coerceIn(0f, 1f)

        // 落位后安静待机时，每 4.6 秒自然眨一次眼
        val idleBlink = if (animate && burstT >= 0.99f && idleBlinkCycle in 0.88f..0.95f) {
            sin(((idleBlinkCycle - 0.88f) / 0.07f) * PI).toFloat()
        } else 0f
        val showSquint = ch > 0.22f

        withTransform({
            translate(left = pikoCenter.x + shakeDx, top = pikoCenter.y + chOffsetY)
            rotate(degrees = shakeRot, pivot = Offset.Zero)
            scale(scaleX = scaleX * pikoScale, scaleY = scaleY * pikoScale, pivot = Offset.Zero)
            translate(left = -24f, top = -25f)
        }) {
            if (detail > 0.01f) {
                val earTilt = ch * 6.3f
                withTransform({
                    rotate(degrees = -earTilt, pivot = Offset(15f, 16f))
                    scale(scaleX = 0.7f + 0.3f * detail, scaleY = 0.5f + 0.5f * detail, pivot = Offset(15f, 16f))
                }) {
                    drawPath(pikoEarLeft, color = primary.copy(alpha = detail))
                }
                withTransform({
                    rotate(degrees = earTilt, pivot = Offset(33f, 16f))
                    scale(scaleX = 0.7f + 0.3f * detail, scaleY = 0.5f + 0.5f * detail, pivot = Offset(33f, 16f))
                }) {
                    drawPath(pikoEarRight, color = primary.copy(alpha = detail))
                }
            }

            measure.setPath(pikoFacePath, forceClosed = false)
            segmentPath.reset()
            val drawLen = measure.length * outlineDraw.value.coerceIn(0f, 1f)
            if (drawLen > 0f) {
                measure.getSegment(0f, drawLen, segmentPath, startWithMoveTo = true)
                drawPath(
                    path = segmentPath,
                    color = primary,
                    style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            if (detail > 0.01f) {
                if (showSquint) {
                    val squintStroke = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawPath(pikoSquintLeft, color = primary.copy(alpha = detail), style = squintStroke)
                    drawPath(pikoSquintRight, color = primary.copy(alpha = detail), style = squintStroke)
                } else {
                    val eyeH = (6.2f * (1f - idleBlink * 0.85f)).coerceAtLeast(1.2f)
                    val eyeCy = 25.6f
                    drawRoundRect(
                        color = primary.copy(alpha = detail),
                        topLeft = Offset(15.8f, eyeCy - eyeH / 2f),
                        size = Size(3.4f, eyeH),
                        cornerRadius = CornerRadius(1.7f, 1.7f),
                    )
                    drawRoundRect(
                        color = primary.copy(alpha = detail),
                        topLeft = Offset(28.8f, eyeCy - eyeH / 2f),
                        size = Size(3.4f, eyeH),
                        cornerRadius = CornerRadius(1.7f, 1.7f),
                    )
                }
                drawPath(
                    path = pikoSmile,
                    color = primary.copy(alpha = detail),
                    style = Stroke(width = 2.2f, cap = StrokeCap.Round),
                )
            }
        }

        // 4. 下方三个微缩视图：直接从 Piko 中心 Bang! 蹦出来落位（彻底无章鱼连线）
        val cardsTopY = h * 0.62f
        val sideOffset = 108.dp.toPx() * u
        val leftX = cx - sideOffset
        val midX = cx
        val rightX = cx + sideOffset

        // 4a. 左 · 视频播放器（进度条持续平滑推进）
        val videoProgress = if (animate) (0.10f + scrollCycle * 0.82f) else 0.46f
        drawPoppingCard(
            progress = videoPop.value,
            origin = pikoCenter,
            targetCenterX = leftX,
            targetTopY = cardsTopY,
            cardW = 94.dp.toPx() * u,
            cardH = 68.dp.toPx() * u,
            bg = surfaceLow,
            border = outline,
        ) { rect, a ->
            val inset = 5.dp.toPx()
            val screenRect = Rect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - 13.dp.toPx())
            drawRoundRect(
                color = primaryContainer.copy(alpha = 0.66f * a),
                topLeft = screenRect.topLeft,
                size = screenRect.size,
                cornerRadius = CornerRadius(7.dp.toPx()),
            )
            val bc = screenRect.center
            drawCircle(color = primary.copy(alpha = a), radius = 8.dp.toPx(), center = bc)
            val tri = 3.4.dp.toPx()
            playTriPath.reset()
            playTriPath.moveTo(bc.x - tri * 0.4f, bc.y - tri * 0.78f)
            playTriPath.lineTo(bc.x + tri * 0.85f, bc.y)
            playTriPath.lineTo(bc.x - tri * 0.4f, bc.y + tri * 0.78f)
            playTriPath.close()
            drawPath(playTriPath, color = onPrimary.copy(alpha = a))

            val py = rect.bottom - 6.5.dp.toPx()
            val px1 = rect.left + 8.dp.toPx()
            val px2 = rect.right - 8.dp.toPx()
            val curX = px1 + (px2 - px1) * videoProgress
            drawLine(onPrimaryContainer.copy(alpha = 0.16f * a), Offset(px1, py), Offset(px2, py), 2.2.dp.toPx(), StrokeCap.Round)
            drawLine(primary.copy(alpha = a), Offset(px1, py), Offset(curX, py), 2.2.dp.toPx(), StrokeCap.Round)
            drawCircle(color = primary.copy(alpha = a), radius = 2.6.dp.toPx(), center = Offset(curX, py))
        }

        // 4b. 中 · 信息流 Clips（快刷节拍：多数播一小段就干脆上滑刷新）
        val beatIdx = clipsCycle.toInt() % clipBeats.size
        val nextBeatIdx = (beatIdx + 1) % clipBeats.size
        val beatLocal = clipsCycle - clipsCycle.toInt()
        val curBeat = clipBeats[beatIdx]
        val nextBeat = clipBeats[nextBeatIdx]
        val swipeStart = 0.70f
        val clipSlide = if (animate && beatLocal > swipeStart) {
            FastOutSlowInEasing.transform((beatLocal - swipeStart) / (1f - swipeStart))
        } else 0f
        val clipBarProgress = if (animate) {
            (beatLocal / swipeStart).coerceIn(0f, 1f) * curBeat.watchFraction
        } else 0.34f

        drawPoppingCard(
            progress = clipsPop.value,
            origin = pikoCenter,
            targetCenterX = midX,
            targetTopY = cardsTopY - 5.dp.toPx() * u,
            cardW = 64.dp.toPx() * u,
            cardH = 78.dp.toPx() * u,
            bg = surfaceLow,
            border = outline,
        ) { rect, a ->
            val inset = 4.5.dp.toPx()
            val inner = Rect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            clipRoundPath.reset()
            clipRoundPath.addRoundRect(RoundRect(inner, CornerRadius(8.dp.toPx())))

            clipPath(clipRoundPath) {
                val slideOffset = -clipSlide * inner.height
                val frameColors = arrayOf(secondaryContainer, tertiaryContainer, primaryContainer)
                val slotColors = arrayOf(frameColors[curBeat.colorIndex], frameColors[nextBeat.colorIndex])
                for (slot in 0..1) {
                    val topY = inner.top + slideOffset + slot * inner.height
                    drawRect(
                        color = slotColors[slot].copy(alpha = 0.80f * a),
                        topLeft = Offset(inner.left, topY),
                        size = inner.size,
                    )
                    val bc = Offset(inner.center.x - 3.dp.toPx(), topY + inner.height * 0.46f)
                    val tri = 4.2.dp.toPx()
                    playTriPath.reset()
                    playTriPath.moveTo(bc.x - tri * 0.4f, bc.y - tri * 0.75f)
                    playTriPath.lineTo(bc.x + tri * 0.85f, bc.y)
                    playTriPath.lineTo(bc.x - tri * 0.4f, bc.y + tri * 0.75f)
                    playTriPath.close()
                    drawPath(playTriPath, color = primary.copy(alpha = 0.82f * a))
                }
                val dotX = inner.right - 6.dp.toPx()
                for (i in 0..2) {
                    drawCircle(
                        color = primary.copy(alpha = (0.35f + i * 0.15f) * a),
                        radius = 2.dp.toPx(),
                        center = Offset(dotX, inner.bottom - 24.dp.toPx() + i * 7.dp.toPx()),
                    )
                }
                val py = inner.bottom - 5.dp.toPx()
                val px1 = inner.left + 6.dp.toPx()
                val px2 = inner.right - 12.dp.toPx()
                drawLine(onPrimaryContainer.copy(alpha = 0.15f * a), Offset(px1, py), Offset(px2, py), 2.dp.toPx(), StrokeCap.Round)
                drawLine(primary.copy(alpha = 0.85f * a), Offset(px1, py), Offset(px1 + (px2 - px1) * clipBarProgress, py), 2.dp.toPx(), StrokeCap.Round)
            }
        }

        // 4c. 右 · 瀑布流 Gallery（两列错落不等高网格持续无缝滚动刷新）
        drawPoppingCard(
            progress = galleryPop.value,
            origin = pikoCenter,
            targetCenterX = rightX,
            targetTopY = cardsTopY,
            cardW = 94.dp.toPx() * u,
            cardH = 68.dp.toPx() * u,
            bg = surfaceLow,
            border = outline,
        ) { rect, a ->
            val pad = 5.5.dp.toPx()
            val gap = 4.dp.toPx()
            val inner = Rect(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
            val colW = (inner.width - gap) / 2f
            val leftCol = inner.left
            val rightCol = leftCol + colW + gap
            val r = CornerRadius(5.dp.toPx())

            clipRoundPath.reset()
            clipRoundPath.addRoundRect(RoundRect(inner, CornerRadius(6.dp.toPx())))
            clipPath(clipRoundPath) {
                val leftHeights = floatArrayOf(24.dp.toPx(), 14.dp.toPx(), 18.dp.toPx())
                val leftColors = arrayOf(tertiaryContainer, surfaceHigh, primaryContainer)
                val leftPeriod = leftHeights.sum() + gap * leftHeights.size
                val leftShift = if (animate) scrollCycle * leftPeriod else 0f
                for (repeat in 0..1) {
                    var y = inner.top - leftShift + repeat * leftPeriod
                    for (i in leftHeights.indices) {
                        val bh = leftHeights[i]
                        drawRoundRect(leftColors[i].copy(alpha = 0.88f * a), Offset(leftCol, y), Size(colW, bh), r)
                        y += bh + gap
                    }
                }

                val rightHeights = floatArrayOf(15.dp.toPx(), 25.dp.toPx(), 16.dp.toPx())
                val rightColors = arrayOf(primaryContainer, secondaryContainer, tertiaryContainer)
                val rightPeriod = rightHeights.sum() + gap * rightHeights.size
                val rightShift = if (animate) ((scrollCycle + 0.18f) % 1f) * rightPeriod else 4.dp.toPx()
                for (repeat in 0..1) {
                    var y = inner.top - rightShift + repeat * rightPeriod
                    for (i in rightHeights.indices) {
                        val bh = rightHeights[i]
                        drawRoundRect(rightColors[i].copy(alpha = 0.86f * a), Offset(rightCol, y), Size(colW, bh), r)
                        y += bh + gap
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawFallingToken(
    progress: Float,
    start: Offset,
    end: Offset,
    fill: Color,
    stroke: Color,
    kind: Int,
    tiltDeg: Float,
    bodyPath: Path,
    detailPath: Path,
) {
    val t = progress.coerceIn(0f, 1f)
    if (t <= 0.01f || t >= 0.99f) return
    val alpha = when {
        t < 0.16f -> t / 0.16f
        t > 0.74f -> ((1f - t) / 0.26f).coerceIn(0f, 1f)
        else -> 1f
    }
    val scale = if (t > 0.74f) 1f - ((t - 0.74f) / 0.26f) * 0.55f else 0.88f + 0.12f * (t / 0.74f).coerceAtMost(1f)
    val ctrl = Offset((start.x + end.x) * 0.5f, start.y + (end.y - start.y) * 0.22f)
    val inv = 1f - t
    val x = inv * inv * start.x + 2f * inv * t * ctrl.x + t * t * end.x
    val y = inv * inv * start.y + 2f * inv * t * ctrl.y + t * t * end.y
    val rot = tiltDeg * (1f - t)
    val strokeW = 1.25.dp.toPx()
    val strokeStyle = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val ink = stroke.copy(alpha = 0.88f * alpha)

    withTransform({
        translate(left = x, top = y)
        rotate(degrees = rot, pivot = Offset.Zero)
        scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
    }) {
        if (kind == 3) {
            // 经典带标签文件夹轮廓
            val fw = 22.dp.toPx()
            val fh = 17.dp.toPx()
            val l = -fw / 2f
            val r = fw / 2f
            val top = -fh / 2f
            val b = fh / 2f
            val tabH = 3.5.dp.toPx()
            val tabEnd = l + fw * 0.44f
            bodyPath.reset()
            bodyPath.moveTo(l, top + tabH)
            bodyPath.lineTo(l, top + 1.5.dp.toPx())
            bodyPath.quadraticTo(l, top, l + 1.8.dp.toPx(), top)
            bodyPath.lineTo(tabEnd - 1.5.dp.toPx(), top)
            bodyPath.lineTo(tabEnd + 1.5.dp.toPx(), top + tabH)
            bodyPath.lineTo(r - 2.dp.toPx(), top + tabH)
            bodyPath.quadraticTo(r, top + tabH, r, top + tabH + 2.dp.toPx())
            bodyPath.lineTo(r, b - 2.dp.toPx())
            bodyPath.quadraticTo(r, b, r - 2.dp.toPx(), b)
            bodyPath.lineTo(l + 2.dp.toPx(), b)
            bodyPath.quadraticTo(l, b, l, b - 2.dp.toPx())
            bodyPath.close()

            drawPath(bodyPath, color = fill.copy(alpha = alpha))
            drawPath(bodyPath, color = ink, style = strokeStyle)
            drawLine(
                color = ink.copy(alpha = 0.45f * alpha),
                start = Offset(l + 2.dp.toPx(), top + tabH + 2.2.dp.toPx()),
                end = Offset(r - 2.dp.toPx(), top + tabH + 2.2.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        } else {
            // 经典竖版右上折角文件（dog-ear sheet）
            val fw = 18.dp.toPx()
            val fh = 23.dp.toPx()
            val l = -fw / 2f
            val r = fw / 2f
            val top = -fh / 2f
            val b = fh / 2f
            val fold = 5.5.dp.toPx()

            bodyPath.reset()
            bodyPath.moveTo(l + 2.dp.toPx(), top)
            bodyPath.lineTo(r - fold, top)
            bodyPath.lineTo(r, top + fold)
            bodyPath.lineTo(r, b - 2.dp.toPx())
            bodyPath.quadraticTo(r, b, r - 2.dp.toPx(), b)
            bodyPath.lineTo(l + 2.dp.toPx(), b)
            bodyPath.quadraticTo(l, b, l, b - 2.dp.toPx())
            bodyPath.lineTo(l, top + 2.dp.toPx())
            bodyPath.quadraticTo(l, top, l + 2.dp.toPx(), top)
            bodyPath.close()

            drawPath(bodyPath, color = fill.copy(alpha = alpha))
            drawPath(bodyPath, color = ink, style = strokeStyle)

            // 右上折角折痕
            detailPath.reset()
            detailPath.moveTo(r - fold, top)
            detailPath.lineTo(r - fold, top + fold)
            detailPath.lineTo(r, top + fold)
            drawPath(detailPath, color = ink.copy(alpha = 0.72f * alpha), style = strokeStyle)

            when (kind) {
                0 -> {
                    // 视频文件：中央小播放三角
                    val tri = 3.2.dp.toPx()
                    val cy = 2.2.dp.toPx()
                    detailPath.reset()
                    detailPath.moveTo(-tri * 0.45f, cy - tri * 0.85f)
                    detailPath.lineTo(tri * 0.95f, cy)
                    detailPath.lineTo(-tri * 0.45f, cy + tri * 0.85f)
                    detailPath.close()
                    drawPath(detailPath, color = ink)
                }
                1 -> {
                    // 图片文件：山峰折线 + 小太阳
                    drawCircle(color = ink, radius = 1.4.dp.toPx(), center = Offset(-2.6.dp.toPx(), 0f))
                    detailPath.reset()
                    detailPath.moveTo(l + 3.2.dp.toPx(), b - 3.6.dp.toPx())
                    detailPath.lineTo(-1.2.dp.toPx(), 2.2.dp.toPx())
                    detailPath.lineTo(1.2.dp.toPx(), 4.8.dp.toPx())
                    detailPath.lineTo(3.2.dp.toPx(), 2.8.dp.toPx())
                    detailPath.lineTo(r - 3.2.dp.toPx(), b - 3.6.dp.toPx())
                    drawPath(detailPath, color = ink, style = strokeStyle)
                }
                else -> {
                    // 文档文件：三条长短文本行
                    drawLine(ink, Offset(l + 3.5.dp.toPx(), 0f), Offset(r - 3.5.dp.toPx(), 0f), 1.3.dp.toPx(), StrokeCap.Round)
                    drawLine(ink, Offset(l + 3.5.dp.toPx(), 3.6.dp.toPx()), Offset(r - 3.5.dp.toPx(), 3.6.dp.toPx()), 1.3.dp.toPx(), StrokeCap.Round)
                    drawLine(ink, Offset(l + 3.5.dp.toPx(), 7.2.dp.toPx()), Offset(1.5.dp.toPx(), 7.2.dp.toPx()), 1.3.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

/** 从 Piko 中心点 Bang! 弹射展开至各自目标位置 */
private fun DrawScope.drawPoppingCard(
    progress: Float,
    origin: Offset,
    targetCenterX: Float,
    targetTopY: Float,
    cardW: Float,
    cardH: Float,
    bg: Color,
    border: Color,
    content: DrawScope.(Rect, Float) -> Unit,
) {
    val p = progress.coerceAtLeast(0f)
    if (p <= 0.01f) return
    val a = (p * 1.4f).coerceIn(0f, 1f)
    val targetCenter = Offset(targetCenterX, targetTopY + cardH / 2f)
    val curCenter = Offset(
        x = origin.x + (targetCenter.x - origin.x) * p,
        y = origin.y + (targetCenter.y - origin.y) * p,
    )
    val scale = (0.28f + 0.72f * p).coerceAtLeast(0.01f)
    val rect = Rect(curCenter.x - cardW / 2f, curCenter.y - cardH / 2f, curCenter.x + cardW / 2f, curCenter.y + cardH / 2f)

    withTransform({
        scale(scaleX = scale, scaleY = scale, pivot = curCenter)
    }) {
        drawRoundRect(
            color = bg.copy(alpha = a),
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(12.dp.toPx()),
        )
        drawRoundRect(
            color = border.copy(alpha = 0.6f * a),
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
        content(rect, a)
    }
}
