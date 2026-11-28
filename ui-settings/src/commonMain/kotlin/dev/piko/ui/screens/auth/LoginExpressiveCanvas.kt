package dev.piko.ui.screens.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.LocalWindowVisible
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class DropBatchItem(
    val startAt: Float,
    val duration: Float,
    val startXFrac: Float,
    val startYDp: Float,
    val kind: Int, // 0=视频, 1=图片, 2=文档, 3=文件夹, 4=磁力, 5=压缩包
    val tiltDeg: Float,
)

/**
 * Welcome 屏四幕叙事画布（融合 Banner 透视虫洞、六类多源图标与响应式双形态屏幕）：
 * 1. 一笔画勾勒无蓝底 Piko 猫脸；
 * 2. 10 枚多源条目（视频、图片、文档、文件夹、磁力、压缩包）在虫洞上方生成，沿透视收缩环加速吸入 Piko；
 * 3. Piko 蓄力憋一下（压扁 + `> <` 眼 + 轻微抖动）；
 * 4. Bang! 弹性复原并迸出微光短芒，下方弹出响应式 Piko 核心屏幕，持续在「桌面宽窗 ↔ 手机竖屏 + 伴随双卡」间平滑变形；
 * 5. 点击画布可重播。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PikoOnboardingCanvas(
    modifier: Modifier = Modifier,
    onBang: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val portraitStage = currentWidthClass() == WidthClass.Compact
    val reduced = LocalPikoMotion.current.reduced
    val windowVisible = LocalWindowVisible.current
    val animate = !reduced && windowVisible
    val expressive = remember { MotionScheme.expressive() }
    val dropBatchEasing = remember { CubicBezierEasing(0.36f, 0f, 0.80f, 0.44f) }
    val tokenFallEasing = remember { CubicBezierEasing(0.32f, 0f, 0.85f, 0.30f) }

    var replayKey by remember { mutableIntStateOf(0) }

    // Act 1: 一笔画现身
    val outlineDraw = remember { Animatable(if (reduced) 1f else 0f) }
    val faceDetails = remember { Animatable(if (reduced) 1f else 0f) }
    // Act 2: 加速投入多源条目（0 -> 1）
    val dropBatch = remember { Animatable(if (reduced) 1f else 0f) }
    // Act 3: 憋劲蓄力（压扁 + > < 眼 + 轻微抖动）
    val chargeAmount = remember { Animatable(0f) }
    val chargeShake = remember { Animatable(0f) }
    // Act 4: Bang 迸发微芒 + 下方响应式屏幕从 Piko 中心弹出落位
    val bangBurst = remember { Animatable(if (reduced) 1f else 0f) }
    val stagePop = remember { Animatable(if (reduced) 1f else 0f) }

    LaunchedEffect(reduced, replayKey) {
        if (reduced) {
            outlineDraw.snapTo(1f)
            faceDetails.snapTo(1f)
            dropBatch.snapTo(1f)
            chargeAmount.snapTo(0f)
            chargeShake.snapTo(0f)
            bangBurst.snapTo(1f)
            stagePop.snapTo(1f)
            onBang()
        } else {
            outlineDraw.snapTo(0f)
            faceDetails.snapTo(0f)
            dropBatch.snapTo(0f)
            chargeAmount.snapTo(0f)
            chargeShake.snapTo(0f)
            bangBurst.snapTo(0f)
            stagePop.snapTo(0f)

            // Act 1: 0..340ms 一笔画 Piko 轮廓
            launch { outlineDraw.animateTo(1f, tween(durationMillis = 340, easing = FastOutSlowInEasing)) }
            launch {
                delay(160)
                faceDetails.animateTo(1f, expressive.defaultSpatialSpec())
            }

            // Act 2: 260..1180ms 加速连续吸入 10 枚多源条目
            delay(260)
            dropBatch.animateTo(1f, tween(durationMillis = 920, easing = dropBatchEasing))

            // Act 3: 1180..1605ms Piko 憋劲蓄力
            launch { chargeAmount.animateTo(1f, tween(durationMillis = 165, easing = FastOutSlowInEasing)) }
            delay(85)
            chargeShake.animateTo(1f, tween(durationMillis = 340, easing = LinearEasing))

            // Act 4: Bang! 复原笑脸 + 微芒 + 弹出下方响应式屏幕
            chargeShake.snapTo(0f)
            onBang()
            launch { chargeAmount.animateTo(0f, expressive.fastSpatialSpec()) }
            launch { bangBurst.animateTo(1f, tween(durationMillis = 320, easing = FastOutSlowInEasing)) }
            launch { stagePop.animateTo(1f, expressive.defaultSpatialSpec()) }
        }
    }

    // 10 枚在虫洞上方生成的多源条目（startYDp 严格高于虫洞最上环顶边）
    val batchItems = remember {
        listOf(
            DropBatchItem(startAt = 0.00f, duration = 0.34f, startXFrac = -0.34f, startYDp = 12f, kind = 4, tiltDeg = -16f),
            DropBatchItem(startAt = 0.07f, duration = 0.32f, startXFrac = 0.30f, startYDp = 10f, kind = 0, tiltDeg = 14f),
            DropBatchItem(startAt = 0.14f, duration = 0.34f, startXFrac = -0.10f, startYDp = 8f, kind = 3, tiltDeg = -8f),
            DropBatchItem(startAt = 0.22f, duration = 0.32f, startXFrac = 0.20f, startYDp = 14f, kind = 5, tiltDeg = 12f),
            DropBatchItem(startAt = 0.30f, duration = 0.34f, startXFrac = -0.26f, startYDp = 10f, kind = 1, tiltDeg = -14f),
            DropBatchItem(startAt = 0.38f, duration = 0.32f, startXFrac = 0.34f, startYDp = 16f, kind = 4, tiltDeg = 18f),
            DropBatchItem(startAt = 0.46f, duration = 0.34f, startXFrac = 0.02f, startYDp = 8f, kind = 2, tiltDeg = 6f),
            DropBatchItem(startAt = 0.53f, duration = 0.32f, startXFrac = -0.32f, startYDp = 15f, kind = 5, tiltDeg = -18f),
            DropBatchItem(startAt = 0.60f, duration = 0.33f, startXFrac = 0.22f, startYDp = 10f, kind = 0, tiltDeg = 10f),
            DropBatchItem(startAt = 0.67f, duration = 0.33f, startXFrac = -0.06f, startYDp = 9f, kind = 3, tiltDeg = -6f),
        )
    }

    val livePhase: Float
    val idleBlinkCycle: Float
    if (animate) {
        val infinite = rememberInfiniteTransition(label = "piko-onboarding-live")
        livePhase = infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                // 宽屏一屏合一时左侧背景放缓至舒缓呼吸节奏，避免干扰右侧输入；手机竖屏保持明快节拍
                animation = tween(durationMillis = if (portraitStage) 3_800 else 9_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "stage-cycle",
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
        livePhase = 0.14f
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
    val clipRoundPath = remember { Path() }
    val gridClipPath = remember { Path() }
    val feedClipPath = remember { Path() }
    val tokenBodyPath = remember { Path() }
    val tokenDetailPath = remember { Path() }

    val primary = colors.primary
    val primaryContainer = colors.primaryContainer
    val secondaryContainer = colors.secondaryContainer
    val tertiaryContainer = colors.tertiaryContainer
    val onPrimary = colors.onPrimary
    val onPrimaryContainer = colors.onPrimaryContainer
    val onSecondaryContainer = colors.onSecondaryContainer
    val onTertiaryContainer = colors.onTertiaryContainer
    val surfaceBg = colors.surfaceContainerLowest
    val surfaceLow = colors.surfaceContainerLow
    val surfaceHigh = colors.surfaceContainerHigh
    val outline = colors.outlineVariant

    Canvas(
        modifier = modifier
            .height(if (portraitStage) 368.dp else 388.dp)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (!reduced) replayKey++
            },
    ) {
        val w = size.width
        val h = size.height
        val u = (w / 360.dp.toPx()).coerceIn(0.82f, 1.12f)
        val cx = w * 0.5f
        val pikoCenter = Offset(cx, h * 0.29f)
        val pikoSize = (68.dp.toPx() * u)
        val pikoScale = pikoSize / 48f
        val fills = arrayOf(secondaryContainer, primaryContainer, tertiaryContainer)

        // =============================================================
        // 1. Act 2：统一垂直重力虫洞（最上环顶边严格低于文件出生线）+ 多源条目加速吸入
        // =============================================================
        val batchT = dropBatch.value
        val tunnelFade = when {
            batchT <= 0.01f -> 0f
            batchT < 0.12f -> batchT / 0.12f
            batchT < 1f -> 1f
            else -> (1f - chargeAmount.value * 1.4f).coerceIn(0f, 1f) * (1f - bangBurst.value).coerceIn(0f, 1f)
        }
        if (tunnelFade > 0.01f) {
            val ringCount = 5
            val tunnelTop = 46.dp.toPx()
            val tunnelEndY = pikoCenter.y - pikoSize * 0.34f
            val tunnelSpan = (tunnelEndY - tunnelTop).coerceAtLeast(1f)
            for (r in 0 until ringCount) {
                val raw = (batchT * 2.2f + r.toFloat() / ringCount) % 1f
                val depth = raw.pow(1.35f)
                val rx = (94f - 68f * depth).dp.toPx() * u
                val ry = (12f - 8f * depth).dp.toPx() * u
                val ringY = tunnelTop + tunnelSpan * (0.04f + 0.96f * depth)
                val ringAlpha = when {
                    raw < 0.15f -> (raw / 0.15f) * 0.20f
                    raw > 0.80f -> ((1f - raw) / 0.20f) * 0.20f
                    else -> 0.20f
                } * tunnelFade
                drawOval(
                    color = primary.copy(alpha = ringAlpha),
                    topLeft = Offset(cx - rx, ringY - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = Stroke(width = (1.35f - 0.65f * depth).dp.toPx()),
                )
            }
        }
        if (batchT > 0f && batchT < 1f) {
            val tokenScaleFactor = 1.15f * u
            for (item in batchItems) {
                val local = ((batchT - item.startAt) / item.duration)
                if (local in 0f..1f) {
                    val eased = tokenFallEasing.transform(local)
                    drawFallingToken(
                        progress = eased,
                        start = Offset(cx + w * item.startXFrac, item.startYDp.dp.toPx()),
                        end = Offset(cx + ((item.kind % 3) - 1) * 5.dp.toPx(), pikoCenter.y - 8.dp.toPx()),
                        fill = fills[item.kind % fills.size],
                        stroke = primary.copy(alpha = 0.80f),
                        kind = item.kind,
                        tiltDeg = item.tiltDeg,
                        sizeMultiplier = tokenScaleFactor,
                        bodyPath = tokenBodyPath,
                        detailPath = tokenDetailPath,
                    )
                }
            }
        }
        val topFadeH = 14.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = 0f,
                endY = topFadeH,
            ),
            topLeft = Offset.Zero,
            size = Size(w, topFadeH),
            blendMode = BlendMode.DstIn,
        )

        // =============================================================
        // 2. Act 4 瞬间：Bang! 6 道克制放射微芒
        // =============================================================
        val burstT = bangBurst.value
        if (burstT in 0.02f..0.98f) {
            val burstAlpha = sin(burstT * PI).toFloat() * 0.65f
            val rIn = 30.dp.toPx() * u + burstT * 14.dp.toPx()
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

        // =============================================================
        // 3. Piko 猫脸（Act 2 微胀 -> Act 3 憋劲压扁抖动 -> Act 4 复原微笑眨眼）
        // =============================================================
        val c = chargeAmount.value
        val shakeT = chargeShake.value
        val shakeEnvelope = if (shakeT > 0f && shakeT < 1f) sin(shakeT * PI).toFloat() else 0f
        val shakeDx = sin(shakeT * PI * 9.0).toFloat() * 3.2.dp.toPx() * shakeEnvelope
        val shakeDy = cos(shakeT * PI * 7.0).toFloat() * 1.1.dp.toPx() * shakeEnvelope
        val eatSwell = if (batchT > 0f && batchT < 1f) sin(batchT * PI).toFloat() * 0.06f else 0f
        val sx = 1f + eatSwell + c * 0.14f
        val sy = 1f + eatSwell - c * 0.14f
        val detail = faceDetails.value.coerceIn(0f, 1f)
        val showSquint = c > 0.25f
        val idleBlink = if (idleBlinkCycle in 0.90f..0.97f && !showSquint) {
            val bt = (idleBlinkCycle - 0.90f) / 0.07f
            sin(bt * PI).toFloat()
        } else 0f

        val chargeDropPx = c * 4.dp.toPx()
        withTransform({
            translate(
                left = pikoCenter.x + shakeDx - 24f * pikoScale * sx,
                top = pikoCenter.y + shakeDy - 24f * pikoScale * sy + chargeDropPx,
            )
            scale(scaleX = pikoScale * sx, scaleY = pikoScale * sy, pivot = Offset.Zero)
        }) {
            if (detail > 0.01f) {
                val earScale = 0.4f + 0.6f * detail
                withTransform({ scale(earScale, earScale, pivot = Offset(15.5f, 15.2f)) }) {
                    drawPath(pikoEarLeft, color = primary.copy(alpha = detail))
                }
                withTransform({ scale(earScale, earScale, pivot = Offset(32.5f, 15.2f)) }) {
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

        // =============================================================
        // 4. Bang 后：下方居中弹出设备屏幕（窄屏为手机主屏 + 双卡，宽屏左栏为桌面宽窗）
        // =============================================================
        val pop = stagePop.value.coerceAtLeast(0f)
        if (pop > 0.01f) {
            val stageAlpha = (pop * 1.35f).coerceIn(0f, 1f)
            val targetStageCenter = Offset(cx, h * 0.71f)
            val widthFill = if (portraitStage) 0.76f else 0.84f
            val maxByWidth = (w * widthFill) / 192.dp.toPx()
            val maxByHeight = ((h - (pikoCenter.y + pikoSize * 0.58f) - 14.dp.toPx()).coerceAtLeast(1f)) / 128.dp.toPx()
            val stageScale = minOf(maxByWidth, maxByHeight).coerceIn(0.85f, if (portraitStage) 1.32f else 1.56f)

            val curCenter = Offset(
                x = pikoCenter.x + (targetStageCenter.x - pikoCenter.x) * pop,
                y = pikoCenter.y + (targetStageCenter.y - pikoCenter.y) * pop,
            )
            val popScale = (0.28f + 0.72f * pop).coerceAtLeast(0.01f) * stageScale

            // 手机竖屏直接展示手机主屏 + 伴随双卡，桌面宽屏直接展示桌面宽窗，不做无意义的跨端形态来回变形
            val morph = if (portraitStage) 1f else 0f

            withTransform({
                translate(left = curCenter.x, top = curCenter.y)
                scale(scaleX = popScale, scaleY = popScale, pivot = Offset.Zero)
            }) {
                val frameW = lerp(188f, 74f, morph).dp.toPx()
                val frameH = lerp(118f, 126f, morph).dp.toPx()
                val frameCx = lerp(0f, -48.dp.toPx(), morph)
                val frameRadius = lerp(12f, 16f, morph).dp.toPx()
                val frameRect = Rect(
                    left = frameCx - frameW / 2f,
                    top = -frameH / 2f,
                    right = frameCx + frameW / 2f,
                    bottom = frameH / 2f,
                )

                // 手机态伴随双卡（硬解播放器 + 空间分布图 Treemap）
                if (morph > 0.02f) {
                    val compAlpha = ((morph - 0.08f) / 0.92f).coerceIn(0f, 1f) * stageAlpha
                    val compX = lerp(18.dp.toPx(), 44.dp.toPx(), morph)
                    val compW = 86.dp.toPx()

                    // 上卡：硬解播放器微缩窗
                    val playerRect = Rect(compX - compW / 2f, -60.dp.toPx(), compX + compW / 2f, -4.dp.toPx())
                    drawRoundRect(surfaceLow.copy(alpha = compAlpha), playerRect.topLeft, playerRect.size, CornerRadius(11.dp.toPx()))
                    drawRoundRect(outline.copy(alpha = 0.65f * compAlpha), playerRect.topLeft, playerRect.size, CornerRadius(11.dp.toPx()), style = Stroke(1.dp.toPx()))
                    val pScreen = Rect(playerRect.left + 5.dp.toPx(), playerRect.top + 5.dp.toPx(), playerRect.right - 5.dp.toPx(), playerRect.bottom - 13.dp.toPx())
                    drawRoundRect(primaryContainer.copy(alpha = 0.72f * compAlpha), pScreen.topLeft, pScreen.size, CornerRadius(6.dp.toPx()))
                    drawCircle(primary.copy(alpha = compAlpha), 7.dp.toPx(), pScreen.center)
                    tokenDetailPath.reset()
                    val tri = 3.dp.toPx()
                    tokenDetailPath.moveTo(pScreen.center.x - tri * 0.4f, pScreen.center.y - tri * 0.8f)
                    tokenDetailPath.lineTo(pScreen.center.x + tri * 0.85f, pScreen.center.y)
                    tokenDetailPath.lineTo(pScreen.center.x - tri * 0.4f, pScreen.center.y + tri * 0.8f)
                    tokenDetailPath.close()
                    drawPath(tokenDetailPath, onPrimary.copy(alpha = compAlpha))
                    val barY = playerRect.bottom - 6.5.dp.toPx()
                    val bx1 = playerRect.left + 8.dp.toPx()
                    val bx2 = playerRect.right - 8.dp.toPx()
                    val playProg = 0.12f + ((livePhase * 3f) % 1f) * 0.80f
                    val curX = bx1 + (bx2 - bx1) * playProg
                    drawLine(onPrimaryContainer.copy(alpha = 0.18f * compAlpha), Offset(bx1, barY), Offset(bx2, barY), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(primary.copy(alpha = compAlpha), Offset(bx1, barY), Offset(curX, barY), 2.dp.toPx(), StrokeCap.Round)
                    drawCircle(primary.copy(alpha = compAlpha), 2.4.dp.toPx(), Offset(curX, barY))

                    // 下卡：空间分布图 Treemap
                    val mapRect = Rect(compX - compW / 2f, 4.dp.toPx(), compX + compW / 2f, 60.dp.toPx())
                    drawRoundRect(surfaceLow.copy(alpha = compAlpha), mapRect.topLeft, mapRect.size, CornerRadius(11.dp.toPx()))
                    drawRoundRect(outline.copy(alpha = 0.65f * compAlpha), mapRect.topLeft, mapRect.size, CornerRadius(11.dp.toPx()), style = Stroke(1.dp.toPx()))
                    val pad = 5.5.dp.toPx()
                    val gap = 3.5.dp.toPx()
                    val inner = Rect(mapRect.left + pad, mapRect.top + pad, mapRect.right - pad, mapRect.bottom - pad)
                    val bigW = inner.width * 0.56f
                    val rSmall = CornerRadius(4.dp.toPx())
                    drawRoundRect(primaryContainer.copy(alpha = 0.86f * compAlpha), inner.topLeft, Size(bigW, inner.height), rSmall)
                    val rightW = inner.width - bigW - gap
                    val topH = inner.height * 0.54f
                    drawRoundRect(secondaryContainer.copy(alpha = 0.88f * compAlpha), Offset(inner.left + bigW + gap, inner.top), Size(rightW, topH), rSmall)
                    drawRoundRect(tertiaryContainer.copy(alpha = 0.88f * compAlpha), Offset(inner.left + bigW + gap, inner.top + topH + gap), Size(rightW, inner.height - topH - gap), rSmall)
                }

                // 主响应式窗口外框
                drawRoundRect(surfaceLow.copy(alpha = stageAlpha), frameRect.topLeft, frameRect.size, CornerRadius(frameRadius))
                drawRoundRect(outline.copy(alpha = 0.72f * stageAlpha), frameRect.topLeft, frameRect.size, CornerRadius(frameRadius), style = Stroke(1.2.dp.toPx()))

                clipRoundPath.reset()
                clipRoundPath.addRoundRect(RoundRect(frameRect, CornerRadius(frameRadius)))
                clipPath(clipRoundPath) {
                    // 桌面态内容（顶栏标签 + 左侧导航轨 + 两列无缝滚动瀑布流 + 右侧播放侧栏）
                    val desktopAlpha = (1f - morph * 1.45f).coerceIn(0f, 1f) * stageAlpha
                    if (desktopAlpha > 0.01f) {
                        val topBarH = 16.dp.toPx()
                        drawRect(surfaceHigh.copy(alpha = 0.55f * desktopAlpha), frameRect.topLeft, Size(frameRect.width, topBarH))
                        drawRoundRect(
                            primaryContainer.copy(alpha = 0.85f * desktopAlpha),
                            Offset(frameRect.left + 8.dp.toPx(), frameRect.top + 4.dp.toPx()),
                            Size(30.dp.toPx(), 8.dp.toPx()),
                            CornerRadius(4.dp.toPx()),
                        )
                        drawRoundRect(
                            surfaceBg.copy(alpha = 0.7f * desktopAlpha),
                            Offset(frameRect.left + 42.dp.toPx(), frameRect.top + 4.dp.toPx()),
                            Size(24.dp.toPx(), 8.dp.toPx()),
                            CornerRadius(4.dp.toPx()),
                        )
                        for (d in 0..2) {
                            drawCircle(
                                outline.copy(alpha = 0.9f * desktopAlpha),
                                2.dp.toPx(),
                                Offset(frameRect.right - 22.dp.toPx() + d * 7.dp.toPx(), frameRect.top + topBarH * 0.5f),
                            )
                        }

                        val railW = 20.dp.toPx()
                        val bodyTop = frameRect.top + topBarH + 4.dp.toPx()
                        val bodyBottom = frameRect.bottom - 5.dp.toPx()
                        for (n in 0..3) {
                            val active = n == 0
                            drawRoundRect(
                                color = if (active) primary.copy(alpha = 0.82f * desktopAlpha) else outline.copy(alpha = 0.5f * desktopAlpha),
                                topLeft = Offset(frameRect.left + 5.dp.toPx(), bodyTop + 3.dp.toPx() + n * 16.dp.toPx()),
                                size = Size(10.dp.toPx(), 8.dp.toPx()),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                            )
                        }

                        val gridLeft = frameRect.left + railW + 4.dp.toPx()
                        val gridW = 94.dp.toPx()
                        val gridRect = Rect(gridLeft, bodyTop, gridLeft + gridW, bodyBottom)
                        gridClipPath.reset()
                        gridClipPath.addRoundRect(RoundRect(gridRect, CornerRadius(6.dp.toPx())))
                        val scroll = if (animate) (livePhase * 3f) % 1f else 0.15f
                        val gap = 4.dp.toPx()
                        val colW = (gridW - gap) / 2f
                        clipPath(gridClipPath) {
                            val col1H = floatArrayOf(34.dp.toPx(), 22.dp.toPx(), 28.dp.toPx())
                            val col1C = arrayOf(primaryContainer, tertiaryContainer, surfaceHigh)
                            val p1 = col1H.sum() + gap * col1H.size
                            for (rep in 0..1) {
                                var y = gridRect.top - scroll * p1 + rep * p1
                                for (i in col1H.indices) {
                                    drawRoundRect(col1C[i].copy(alpha = 0.88f * desktopAlpha), Offset(gridLeft, y), Size(colW, col1H[i]), CornerRadius(5.dp.toPx()))
                                    y += col1H[i] + gap
                                }
                            }
                            val col2H = floatArrayOf(24.dp.toPx(), 36.dp.toPx(), 24.dp.toPx())
                            val col2C = arrayOf(secondaryContainer, primaryContainer, tertiaryContainer)
                            val p2 = col2H.sum() + gap * col2H.size
                            val shift2 = ((scroll + 0.25f) % 1f) * p2
                            for (rep in 0..1) {
                                var y = gridRect.top - shift2 + rep * p2
                                for (i in col2H.indices) {
                                    drawRoundRect(col2C[i].copy(alpha = 0.86f * desktopAlpha), Offset(gridLeft + colW + gap, y), Size(colW, col2H[i]), CornerRadius(5.dp.toPx()))
                                    y += col2H[i] + gap
                                }
                            }
                        }

                        val sideLeft = gridRect.right + 5.dp.toPx()
                        val sideRect = Rect(sideLeft, bodyTop, frameRect.right - 6.dp.toPx(), bodyBottom)
                        drawRoundRect(secondaryContainer.copy(alpha = 0.72f * desktopAlpha), sideRect.topLeft, sideRect.size, CornerRadius(7.dp.toPx()))
                        drawCircle(primary.copy(alpha = desktopAlpha), 8.dp.toPx(), Offset(sideRect.center.x, sideRect.center.y - 6.dp.toPx()))
                        tokenDetailPath.reset()
                        val tri = 3.2.dp.toPx()
                        val pc = Offset(sideRect.center.x, sideRect.center.y - 6.dp.toPx())
                        tokenDetailPath.moveTo(pc.x - tri * 0.4f, pc.y - tri * 0.8f)
                        tokenDetailPath.lineTo(pc.x + tri * 0.85f, pc.y)
                        tokenDetailPath.lineTo(pc.x - tri * 0.4f, pc.y + tri * 0.8f)
                        tokenDetailPath.close()
                        drawPath(tokenDetailPath, onPrimary.copy(alpha = desktopAlpha))
                        val sBarY = sideRect.bottom - 9.dp.toPx()
                        val sx1 = sideRect.left + 7.dp.toPx()
                        val sx2 = sideRect.right - 7.dp.toPx()
                        val sProg = 0.14f + ((livePhase * 4f) % 1f) * 0.78f
                        drawLine(onPrimaryContainer.copy(alpha = 0.16f * desktopAlpha), Offset(sx1, sBarY), Offset(sx2, sBarY), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(primary.copy(alpha = desktopAlpha), Offset(sx1, sBarY), Offset(sx1 + (sx2 - sx1) * sProg, sBarY), 2.dp.toPx(), StrokeCap.Round)
                    }

                    // 手机竖屏态内容（信息流快刷 + 底部导航栏）
                    val mobileAlpha = ((morph - 0.30f) / 0.70f).coerceIn(0f, 1f) * stageAlpha
                    if (mobileAlpha > 0.01f) {
                        val mInset = 5.dp.toPx()
                        val navH = 16.dp.toPx()
                        val feedRect = Rect(
                            frameRect.left + mInset,
                            frameRect.top + 8.dp.toPx(),
                            frameRect.right - mInset,
                            frameRect.bottom - navH - 4.dp.toPx(),
                        )
                        feedClipPath.reset()
                        feedClipPath.addRoundRect(RoundRect(feedRect, CornerRadius(9.dp.toPx())))
                        val slotPalette = arrayOf(secondaryContainer, tertiaryContainer, primaryContainer)
                        val slotOnPalette = arrayOf(onSecondaryContainer, onTertiaryContainer, onPrimaryContainer)
                        val mT = if (animate) livePhase else 0.12f
                        // 3 段等分无缝闭环（0 -> 1 -> 2 -> 0）：每段前 68% 播放，后 32% 平滑上滑至下一条
                        val scaled = mT * 3f
                        val idx = scaled.toInt() % 3
                        val nextIdx = (idx + 1) % 3
                        val segT = scaled - scaled.toInt()
                        val playEnd = 0.68f
                        val slide = if (segT > playEnd) smoothStep((segT - playEnd) / (1f - playEnd)) else 0f
                        val fProg = if (segT <= playEnd) (segT / playEnd) * 0.48f else 0.48f
                        val barAlpha = if (segT > playEnd) (1f - slide).coerceIn(0f, 1f) else 1f

                        clipPath(feedClipPath) {
                            val dy = -slide * feedRect.height
                            for (slot in 0..1) {
                                val slotIdx = if (slot == 0) idx else nextIdx
                                val slotColor = slotPalette[slotIdx]
                                val onSlot = slotOnPalette[slotIdx]
                                val topY = feedRect.top + dy + slot * feedRect.height
                                drawRect(slotColor.copy(alpha = 0.90f * mobileAlpha), Offset(feedRect.left, topY), feedRect.size)
                                val bc = Offset(feedRect.center.x - 3.dp.toPx(), topY + feedRect.height * 0.48f)
                                val tri = 4.8.dp.toPx()
                                tokenDetailPath.reset()
                                tokenDetailPath.moveTo(bc.x - tri * 0.4f, bc.y - tri * 0.75f)
                                tokenDetailPath.lineTo(bc.x + tri * 0.85f, bc.y)
                                tokenDetailPath.lineTo(bc.x - tri * 0.4f, bc.y + tri * 0.75f)
                                tokenDetailPath.close()
                                drawPath(tokenDetailPath, onSlot.copy(alpha = 0.86f * mobileAlpha))
                            }
                            val curOn = androidx.compose.ui.graphics.lerp(slotOnPalette[idx], slotOnPalette[nextIdx], slide)
                            val dotX = feedRect.right - 7.dp.toPx()
                            for (i in 0..2) {
                                drawCircle(
                                    curOn.copy(alpha = (0.42f + i * 0.16f) * mobileAlpha),
                                    2.1.dp.toPx(),
                                    Offset(dotX, feedRect.bottom - 26.dp.toPx() + i * 7.5.dp.toPx()),
                                )
                            }
                            val fBarY = feedRect.bottom - 5.dp.toPx()
                            val fx1 = feedRect.left + 6.dp.toPx()
                            val fx2 = feedRect.right - 14.dp.toPx()
                            drawLine(curOn.copy(alpha = 0.22f * mobileAlpha * barAlpha), Offset(fx1, fBarY), Offset(fx2, fBarY), 2.dp.toPx(), StrokeCap.Round)
                            drawLine(curOn.copy(alpha = 0.88f * mobileAlpha * barAlpha), Offset(fx1, fBarY), Offset(fx1 + (fx2 - fx1) * fProg, fBarY), 2.dp.toPx(), StrokeCap.Round)
                        }

                        val navY = frameRect.bottom - navH * 0.55f
                        for (i in -1..1) {
                            val active = i == 0
                            drawRoundRect(
                                color = if (active) primary.copy(alpha = 0.85f * mobileAlpha) else outline.copy(alpha = 0.65f * mobileAlpha),
                                topLeft = Offset(frameRect.center.x + i * 18.dp.toPx() - if (active) 7.dp.toPx() else 3.dp.toPx(), navY - 3.dp.toPx()),
                                size = Size(if (active) 14.dp.toPx() else 6.dp.toPx(), 6.dp.toPx()),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                            )
                        }
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
    sizeMultiplier: Float,
    bodyPath: Path,
    detailPath: Path,
) {
    val t = progress.coerceIn(0f, 1f)
    if (t <= 0.01f || t >= 0.99f) return
    val alpha = when {
        t < 0.14f -> t / 0.14f
        t > 0.74f -> ((1f - t) / 0.26f).coerceIn(0f, 1f)
        else -> 1f
    }
    val baseScale = if (t > 0.74f) 1f - ((t - 0.74f) / 0.26f) * 0.55f else 0.88f + 0.12f * (t / 0.74f).coerceAtMost(1f)
    val scale = baseScale * sizeMultiplier
    val ctrl = Offset((start.x + end.x) * 0.5f, start.y + (end.y - start.y) * 0.24f)
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
        when (kind) {
            4 -> {
                val r = 10.5.dp.toPx()
                drawCircle(fill.copy(alpha = alpha), r, Offset.Zero)
                drawCircle(ink, r, Offset.Zero, style = strokeStyle)
                detailPath.reset()
                detailPath.moveTo(-4.dp.toPx(), -3.5.dp.toPx())
                detailPath.lineTo(-4.dp.toPx(), 1.dp.toPx())
                detailPath.arcTo(Rect(-4.dp.toPx(), -3.dp.toPx(), 4.dp.toPx(), 5.dp.toPx()), 180f, -180f, false)
                detailPath.lineTo(4.dp.toPx(), -3.5.dp.toPx())
                drawPath(detailPath, ink, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
            3 -> {
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
            }
            else -> {
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

                detailPath.reset()
                detailPath.moveTo(r - fold, top)
                detailPath.lineTo(r - fold, top + fold)
                detailPath.lineTo(r, top + fold)
                drawPath(detailPath, color = ink.copy(alpha = 0.72f * alpha), style = strokeStyle)

                when (kind) {
                    0 -> {
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
                        drawCircle(color = ink, radius = 1.4.dp.toPx(), center = Offset(-2.6.dp.toPx(), 0f))
                        detailPath.reset()
                        detailPath.moveTo(l + 3.2.dp.toPx(), b - 3.6.dp.toPx())
                        detailPath.lineTo(-1.2.dp.toPx(), 2.2.dp.toPx())
                        detailPath.lineTo(1.2.dp.toPx(), 4.8.dp.toPx())
                        detailPath.lineTo(3.2.dp.toPx(), 2.8.dp.toPx())
                        detailPath.lineTo(r - 3.2.dp.toPx(), b - 3.6.dp.toPx())
                        drawPath(detailPath, color = ink, style = strokeStyle)
                    }
                    5 -> {
                        for (z in 0..3) {
                            val zy = -3.dp.toPx() + z * 3.dp.toPx()
                            val zx = if (z % 2 == 0) -1.5.dp.toPx() else 0.5.dp.toPx()
                            drawLine(ink, Offset(zx, zy), Offset(zx + 2.dp.toPx(), zy), 1.4.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    else -> {
                        drawLine(ink, Offset(l + 3.5.dp.toPx(), 0f), Offset(r - 3.5.dp.toPx(), 0f), 1.3.dp.toPx(), StrokeCap.Round)
                        drawLine(ink, Offset(l + 3.5.dp.toPx(), 3.6.dp.toPx()), Offset(r - 3.5.dp.toPx(), 3.6.dp.toPx()), 1.3.dp.toPx(), StrokeCap.Round)
                        drawLine(ink, Offset(l + 3.5.dp.toPx(), 7.2.dp.toPx()), Offset(1.5.dp.toPx(), 7.2.dp.toPx()), 1.3.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
    }
}

private fun smoothStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
