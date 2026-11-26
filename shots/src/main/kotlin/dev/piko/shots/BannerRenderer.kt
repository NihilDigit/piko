@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)

package dev.piko.shots

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.ThemeMode
import dev.piko.ui.theme.colorScheme
import java.io.File
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat

private data class TunnelToken(
    val phaseOffset: Float,
    val laneY: Float, // -1f..1f 垂直轨道位置
    val kind: Int,    // 0=视频, 1=图片, 2=文档, 3=文件夹, 4=磁力, 5=压缩包
    val spinDir: Float,
)

/**
 * 渲染 README 顶部动态 Banner 帧序列（浅色与深色双主题，1200×320，6 秒无缝循环）。
 *
 * - 左侧：虫洞 / 格列佛透视隧道，持续吸入视频、图片、文档、文件夹、磁力与压缩包；
 * - 中央：无蓝底 Piko 描边猫脸与 `Piko` 字标；
 * - 右侧：抽象化 Piko 核心界面在「桌面宽窗 ↔ 手机竖屏 + 伴随视图」间响应式弹簧变形并持续活跃输出。
 */
internal fun renderBannerFrames(outDir: File, fps: Int = 30, durationSec: Int = 6) {
    val totalFrames = fps * durationSec
    val widthPx = 1200
    val heightPx = 320
    val density = Density(2f)

    ShotEnv("banner", localDownloads = false).use { env ->
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            val tag = if (mode == ThemeMode.DARK) "dark" else "light"
            val framesDir = File(outDir, "banner-$tag-frames").apply {
                deleteRecursively()
                mkdirs()
            }
            val phaseState = mutableFloatStateOf(0f)
            val appearance = Appearance(mode = mode)
            val isDark = mode == ThemeMode.DARK
            val scene = edt {
                ImageComposeScene(
                    width = widthPx,
                    height = heightPx,
                    density = density,
                    coroutineContext = Dispatchers.Main,
                ) {
                    CompositionLocalProvider(LocalPikoPlatform provides env.platform) {
                        MaterialTheme(colorScheme = appearance.colorScheme(isDark)) {
                            PikoBannerCanvas(
                                phase = phaseState.floatValue,
                                fontFamily = env.platform.fontFamily,
                            )
                        }
                    }
                }
            }
            try {
                for (frame in 0 until totalFrames) {
                    val bytes = edt {
                        phaseState.floatValue = frame.toFloat() / totalFrames.toFloat()
                        val image = scene.render(frame.toLong() * (1_000_000_000L / fps))
                        val data = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
                        image.close()
                        data
                    }
                    File(framesDir, "frame_%04d.png".format(frame)).writeBytes(bytes)
                }
                val posterDir = File(outDir, "banner-previews").apply { mkdirs() }
                File(framesDir, "frame_0000.png").copyTo(File(posterDir, "banner-$tag-desktop.png"), overwrite = true)
                File(framesDir, "frame_%04d.png".format((totalFrames * 0.34f).toInt()))
                    .copyTo(File(posterDir, "banner-$tag-mobile.png"), overwrite = true)
            } finally {
                edt { scene.close() }
            }
            println("Rendered $totalFrames frames for banner-$tag -> ${framesDir.absolutePath}")
        }
    }
}

@Composable
private fun PikoBannerCanvas(phase: Float, fontFamily: FontFamily) {
    val colors = MaterialTheme.colorScheme

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

    val tunnelTokens = remember {
        listOf(
            TunnelToken(0.00f, -0.78f, 0, -1f),
            TunnelToken(0.10f, 0.62f, 4, 1f),
            TunnelToken(0.21f, -0.18f, 3, -0.6f),
            TunnelToken(0.32f, 0.82f, 1, 1.1f),
            TunnelToken(0.43f, -0.56f, 5, -0.9f),
            TunnelToken(0.54f, 0.22f, 2, 0.7f),
            TunnelToken(0.65f, -0.84f, 4, -1.1f),
            TunnelToken(0.76f, 0.50f, 0, 0.9f),
            TunnelToken(0.87f, -0.28f, 1, -0.8f),
            TunnelToken(0.94f, 0.10f, 3, 0.5f),
        )
    }

    val bodyPath = remember { Path() }
    val detailPath = remember { Path() }
    val clipRoundPath = remember { Path() }
    val ribbonPath = remember { Path() }

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

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            val w = size.width
            val h = size.height
            val cy = h * 0.5f
            val centerX = w * 0.485f

            // =============================================================
            // 1. 左侧：虫洞 / 格列佛透视隧道（持续吸入，无外框透明底）
            // =============================================================
            val vp = Offset(centerX - 42.dp.toPx(), cy)
            val tunnelLeft = 4.dp.toPx()
            val tunnelSpan = vp.x - tunnelLeft

            // 1a. 7 层透视收缩同心椭圆环（垂直完整容纳于画布内，避免上下裁切）
            val ringCount = 7
            for (r in 0 until ringCount) {
                val raw = (phase * 4f + r.toFloat() / ringCount) % 1f
                // 非线性深度映射：远处缓进，临近虫洞口加速收缩
                val depth = raw.pow(1.35f)
                val rx = (48f - 38f * depth).dp.toPx()
                val ry = (74f - 58f * depth).dp.toPx()
                val ringX = tunnelLeft + tunnelSpan * (0.04f + 0.96f * depth)
                val ringAlpha = when {
                    raw < 0.15f -> (raw / 0.15f) * 0.22f
                    raw > 0.80f -> ((1f - raw) / 0.20f) * 0.22f
                    else -> 0.22f
                }
                drawOval(
                    color = primary.copy(alpha = ringAlpha),
                    topLeft = Offset(ringX - rx, cy - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = Stroke(width = (1.5f - 0.7f * depth).dp.toPx()),
                )
            }

            // 1c. 10 枚多源图标持续沿透视曲线被吸入虫洞（每 6 秒 2 圈无缝循环）
            val fills = arrayOf(secondaryContainer, primaryContainer, tertiaryContainer)
            for (tok in tunnelTokens) {
                val raw = (phase * 2f + tok.phaseOffset) % 1f
                val t = raw.pow(1.25f)
                val startX = 2.dp.toPx()
                val startY = cy + tok.laneY * (h * 0.40f)
                val ctrlX = startX + (vp.x - startX) * 0.52f
                val ctrlY = startY * 0.78f + vp.y * 0.22f
                val inv = 1f - t
                val x = inv * inv * startX + 2f * inv * t * ctrlX + t * t * vp.x
                val y = inv * inv * startY + 2f * inv * t * ctrlY + t * t * vp.y
                val scale = (1.12f - 0.88f * t).coerceAtLeast(0.18f)
                val alpha = when {
                    raw < 0.10f -> raw / 0.10f
                    raw > 0.78f -> ((1f - raw) / 0.22f).coerceIn(0f, 1f)
                    else -> 1f
                }
                val rot = tok.spinDir * (14f - 28f * t)
                drawBannerIntakeToken(
                    center = Offset(x, y),
                    scale = scale,
                    alpha = alpha,
                    rotDeg = rot,
                    kind = tok.kind,
                    fill = fills[tok.kind % fills.size],
                    stroke = primary,
                    bodyPath = bodyPath,
                    detailPath = detailPath,
                )
            }

            // 左侧边缘平滑渐隐遮罩，消除任何硬切边，融入页面背景
            val leftFadeW = 56.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = leftFadeW,
                ),
                topLeft = Offset.Zero,
                size = Size(leftFadeW, h),
                blendMode = BlendMode.DstIn,
            )

            // =============================================================
            // 2. 中央 -> 右侧 轻盈输出能量流（衔接品牌与响应式视图）
            // =============================================================
                val outStart = Offset(centerX + 40.dp.toPx(), cy)
                val outEndX = w * 0.655f
                val ribbonOffsets = floatArrayOf(-26f, 0f, 26f)
                for (i in ribbonOffsets.indices) {
                    val endY = cy + ribbonOffsets[i].dp.toPx()
                    ribbonPath.reset()
                    ribbonPath.moveTo(outStart.x, outStart.y)
                    ribbonPath.cubicTo(
                        outStart.x + 28.dp.toPx(),
                        outStart.y,
                        outEndX - 24.dp.toPx(),
                        endY,
                        outEndX,
                        endY,
                    )
                    drawPath(
                        path = ribbonPath,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                primary.copy(alpha = 0.05f),
                                primary.copy(alpha = 0.22f),
                                primary.copy(alpha = 0.04f),
                            ),
                            startX = outStart.x,
                            endX = outEndX,
                        ),
                        style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
                    )
                    // 流动光珠（每 6 秒走 5 圈，与左侧吸入节拍呼应）
                    val pt = (phase * 5f + i * 0.33f) % 1f
                    val inv = 1f - pt
                    val c1x = outStart.x + 28.dp.toPx()
                    val c2x = outEndX - 24.dp.toPx()
                    val bx = inv * inv * inv * outStart.x +
                        3 * inv * inv * pt * c1x +
                        3 * inv * pt * pt * c2x +
                        pt * pt * pt * outEndX
                    val by = inv * inv * inv * outStart.y +
                        3 * inv * inv * pt * outStart.y +
                        3 * inv * pt * pt * endY +
                        pt * pt * pt * endY
                    val bAlpha = sin(pt * PI).toFloat() * 0.72f
                    drawCircle(color = primary.copy(alpha = bAlpha), radius = 2.6.dp.toPx(), center = Offset(bx, by))
                }

                // =============================================================
                // 3. 中央：Piko 猫脸锚点（固定尺寸 + 每轮平滑自然眨眼一次）
                // =============================================================
                val pikoCenter = Offset(centerX, h * 0.39f)
                val pikoSize = 54.dp.toPx()
                val pikoScale = pikoSize / 48f
                val blink = if (phase in 0.433f..0.500f) {
                    val u = (phase - 0.433f) / 0.067f
                    // 轻快闭眼（42%）+ 柔和睁眼（58%），起止加速度为 0，避免 30fps 下生硬跳变
                    if (u < 0.42f) smoothStep(u / 0.42f) else 1f - smoothStep((u - 0.42f) / 0.58f)
                } else 0f

                withTransform({
                    translate(left = pikoCenter.x, top = pikoCenter.y)
                    scale(scaleX = pikoScale, scaleY = pikoScale, pivot = Offset.Zero)
                    translate(left = -24f, top = -25f)
                }) {
                    drawPath(pikoEarLeft, color = primary)
                    drawPath(pikoEarRight, color = primary)
                    drawPath(
                        path = pikoFacePath,
                        color = primary,
                        style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    val eyeH = (6.2f * (1f - blink * 0.78f)).coerceAtLeast(1.4f)
                    val eyeCy = 25.6f
                    drawRoundRect(
                        color = primary,
                        topLeft = Offset(15.8f, eyeCy - eyeH / 2f),
                        size = Size(3.4f, eyeH),
                        cornerRadius = CornerRadius(1.7f, 1.7f),
                    )
                    drawRoundRect(
                        color = primary,
                        topLeft = Offset(28.8f, eyeCy - eyeH / 2f),
                        size = Size(3.4f, eyeH),
                        cornerRadius = CornerRadius(1.7f, 1.7f),
                    )
                    drawPath(
                        path = pikoSmile,
                        color = primary,
                        style = Stroke(width = 2.2f, cap = StrokeCap.Round),
                    )
                }

                // =============================================================
                // 4. 右侧：抽象关键界面 + 响应式布局变形（桌面宽窗 ↔ 手机竖屏 + 伴随卡片）
                // =============================================================
                // 每 6 秒完成 2 轮「桌面 ↔ 手机」干脆弹性切换（单轮 3 秒，变形耗时 ~0.36 秒）
                val morphPhase = (phase * 2f) % 1f
                val morph = when {
                    morphPhase < 0.36f -> 0f
                    morphPhase < 0.48f -> smoothStep((morphPhase - 0.36f) / 0.12f)
                    morphPhase < 0.86f -> 1f
                    morphPhase < 0.98f -> 1f - smoothStep((morphPhase - 0.86f) / 0.12f)
                    else -> 0f
                }
                val rightAreaCenterX = w * 0.805f

                // 主设备框在「桌面 188×118」与「手机 74×126」间平滑插值
                val frameW = lerp(188f, 74f, morph).dp.toPx()
                val frameH = lerp(118f, 126f, morph).dp.toPx()
                val frameCx = lerp(rightAreaCenterX, rightAreaCenterX - 48.dp.toPx(), morph)
                val frameRadius = lerp(12f, 16f, morph).dp.toPx()
                val frameRect = Rect(
                    left = frameCx - frameW / 2f,
                    top = cy - frameH / 2f,
                    right = frameCx + frameW / 2f,
                    bottom = cy + frameH / 2f,
                )

                // 4a. 手机态伴随双卡（播放器 + 空间分布图）从主窗右侧优雅展开
                if (morph > 0.02f) {
                    val compAlpha = ((morph - 0.08f) / 0.92f).coerceIn(0f, 1f)
                    val compX = lerp(rightAreaCenterX + 18.dp.toPx(), rightAreaCenterX + 44.dp.toPx(), morph)
                    val compW = 86.dp.toPx()

                    // 上卡：硬解播放器微缩窗
                    val playerRect = Rect(compX - compW / 2f, cy - 60.dp.toPx(), compX + compW / 2f, cy - 4.dp.toPx())
                    drawRoundRect(surfaceLow.copy(alpha = compAlpha), playerRect.topLeft, playerRect.size, CornerRadius(11.dp.toPx()))
                    drawRoundRect(outline.copy(alpha = 0.65f * compAlpha), playerRect.topLeft, playerRect.size, CornerRadius(11.dp.toPx()), style = Stroke(1.dp.toPx()))
                    val pScreen = Rect(playerRect.left + 5.dp.toPx(), playerRect.top + 5.dp.toPx(), playerRect.right - 5.dp.toPx(), playerRect.bottom - 13.dp.toPx())
                    drawRoundRect(primaryContainer.copy(alpha = 0.72f * compAlpha), pScreen.topLeft, pScreen.size, CornerRadius(6.dp.toPx()))
                    drawCircle(primary.copy(alpha = compAlpha), 7.dp.toPx(), pScreen.center)
                    detailPath.reset()
                    val tri = 3.dp.toPx()
                    detailPath.moveTo(pScreen.center.x - tri * 0.4f, pScreen.center.y - tri * 0.8f)
                    detailPath.lineTo(pScreen.center.x + tri * 0.85f, pScreen.center.y)
                    detailPath.lineTo(pScreen.center.x - tri * 0.4f, pScreen.center.y + tri * 0.8f)
                    detailPath.close()
                    drawPath(detailPath, onPrimary.copy(alpha = compAlpha))
                    val barY = playerRect.bottom - 6.5.dp.toPx()
                    val bx1 = playerRect.left + 8.dp.toPx()
                    val bx2 = playerRect.right - 8.dp.toPx()
                    val playProg = 0.12f + ((phase * 5f) % 1f) * 0.80f
                    val curX = bx1 + (bx2 - bx1) * playProg
                    drawLine(onPrimaryContainer.copy(alpha = 0.18f * compAlpha), Offset(bx1, barY), Offset(bx2, barY), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(primary.copy(alpha = compAlpha), Offset(bx1, barY), Offset(curX, barY), 2.dp.toPx(), StrokeCap.Round)
                    drawCircle(primary.copy(alpha = compAlpha), 2.4.dp.toPx(), Offset(curX, barY))

                    // 下卡：空间分布图 Treemap
                    val mapRect = Rect(compX - compW / 2f, cy + 4.dp.toPx(), compX + compW / 2f, cy + 60.dp.toPx())
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

                // 4b. 主响应式窗口外框
                drawRoundRect(surfaceLow, frameRect.topLeft, frameRect.size, CornerRadius(frameRadius))
                drawRoundRect(outline.copy(alpha = 0.72f), frameRect.topLeft, frameRect.size, CornerRadius(frameRadius), style = Stroke(1.2.dp.toPx()))

                clipRoundPath.reset()
                clipRoundPath.addRoundRect(RoundRect(frameRect, CornerRadius(frameRadius)))
                clipPath(clipRoundPath) {
                    // --- 桌面态内容（morph < 0.75 时渐隐）---
                    val desktopAlpha = (1f - morph * 1.45f).coerceIn(0f, 1f)
                    if (desktopAlpha > 0.01f) {
                        val topBarH = 16.dp.toPx()
                        // 极简顶栏 + 两个标签胶囊 + 右上三点窗口按钮
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

                        // 左侧导航轨
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

                        // 中间瀑布流两列持续无缝滚动（每 6 秒滚 5 个周期，与左侧节拍同步）
                        val gridLeft = frameRect.left + railW + 4.dp.toPx()
                        val gridW = 94.dp.toPx()
                        val gridRect = Rect(gridLeft, bodyTop, gridLeft + gridW, bodyBottom)
                        val gridClip = Path().apply { addRoundRect(RoundRect(gridRect, CornerRadius(6.dp.toPx()))) }
                        val scroll = (phase * 5f) % 1f
                        val gap = 4.dp.toPx()
                        val colW = (gridW - gap) / 2f
                        clipPath(gridClip) {
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

                        // 右侧停靠信息流/播放侧栏
                        val sideLeft = gridRect.right + 5.dp.toPx()
                        val sideRect = Rect(sideLeft, bodyTop, frameRect.right - 6.dp.toPx(), bodyBottom)
                        drawRoundRect(secondaryContainer.copy(alpha = 0.72f * desktopAlpha), sideRect.topLeft, sideRect.size, CornerRadius(7.dp.toPx()))
                        drawCircle(primary.copy(alpha = desktopAlpha), 8.dp.toPx(), Offset(sideRect.center.x, sideRect.center.y - 6.dp.toPx()))
                        detailPath.reset()
                        val tri = 3.2.dp.toPx()
                        val pc = Offset(sideRect.center.x, sideRect.center.y - 6.dp.toPx())
                        detailPath.moveTo(pc.x - tri * 0.4f, pc.y - tri * 0.8f)
                        detailPath.lineTo(pc.x + tri * 0.85f, pc.y)
                        detailPath.lineTo(pc.x - tri * 0.4f, pc.y + tri * 0.8f)
                        detailPath.close()
                        drawPath(detailPath, onPrimary.copy(alpha = desktopAlpha))
                        val sBarY = sideRect.bottom - 9.dp.toPx()
                        val sx1 = sideRect.left + 7.dp.toPx()
                        val sx2 = sideRect.right - 7.dp.toPx()
                        val sProg = 0.14f + ((phase * 6f) % 1f) * 0.78f
                        drawLine(onPrimaryContainer.copy(alpha = 0.16f * desktopAlpha), Offset(sx1, sBarY), Offset(sx2, sBarY), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(primary.copy(alpha = desktopAlpha), Offset(sx1, sBarY), Offset(sx1 + (sx2 - sx1) * sProg, sBarY), 2.dp.toPx(), StrokeCap.Round)
                    }

                    // --- 手机竖屏态内容（morph > 0.30 时淡入，信息流快刷 + 底部导航栏）---
                    val mobileAlpha = ((morph - 0.30f) / 0.70f).coerceIn(0f, 1f)
                    if (mobileAlpha > 0.01f) {
                        val mInset = 5.dp.toPx()
                        val navH = 16.dp.toPx()
                        val feedRect = Rect(
                            frameRect.left + mInset,
                            frameRect.top + 8.dp.toPx(),
                            frameRect.right - mInset,
                            frameRect.bottom - navH - 4.dp.toPx(),
                        )
                        val feedClip = Path().apply { addRoundRect(RoundRect(feedRect, CornerRadius(9.dp.toPx()))) }
                        // 手机态停留期稳定完成 2 次完整上滑切换（视频 1 -> 视频 2 -> 视频 3）
                        val mT = ((morphPhase - 0.46f) / 0.40f).coerceIn(0f, 1f)
                        val swipe1 = smoothStep((mT - 0.22f) / 0.20f)
                        val swipe2 = smoothStep((mT - 0.64f) / 0.20f)
                        val clipPos = swipe1 + swipe2 // 0f..2f
                        val idx = clipPos.toInt().coerceAtMost(1)
                        val nextIdx = idx + 1
                        val slide = clipPos - idx
                        val local = when {
                            mT < 0.22f -> mT / 0.22f
                            mT < 0.64f -> ((mT - 0.42f) / 0.22f).coerceIn(0f, 1f)
                            else -> ((mT - 0.84f) / 0.16f).coerceIn(0f, 1f)
                        }
                        val slotPalette = arrayOf(secondaryContainer, tertiaryContainer, primaryContainer)
                        val slotOnPalette = arrayOf(onSecondaryContainer, onTertiaryContainer, onPrimaryContainer)

                        clipPath(feedClip) {
                            val dy = -slide * feedRect.height
                            for (slot in 0..1) {
                                val slotIdx = if (slot == 0) idx else nextIdx
                                val c = slotPalette[slotIdx]
                                val onC = slotOnPalette[slotIdx]
                                val topY = feedRect.top + dy + slot * feedRect.height
                                drawRect(c.copy(alpha = 0.90f * mobileAlpha), Offset(feedRect.left, topY), feedRect.size)
                                val bc = Offset(feedRect.center.x - 3.dp.toPx(), topY + feedRect.height * 0.48f)
                                val tri = 4.8.dp.toPx()
                                detailPath.reset()
                                detailPath.moveTo(bc.x - tri * 0.4f, bc.y - tri * 0.75f)
                                detailPath.lineTo(bc.x + tri * 0.85f, bc.y)
                                detailPath.lineTo(bc.x - tri * 0.4f, bc.y + tri * 0.75f)
                                detailPath.close()
                                drawPath(detailPath, onC.copy(alpha = 0.86f * mobileAlpha))
                            }
                            val curOn = slotOnPalette[idx]
                            // 右侧点赞/收藏/更多三圆点
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
                            val fProg = (local / 0.68f).coerceIn(0f, 1f) * 0.45f
                            drawLine(curOn.copy(alpha = 0.22f * mobileAlpha), Offset(fx1, fBarY), Offset(fx2, fBarY), 2.dp.toPx(), StrokeCap.Round)
                            drawLine(curOn.copy(alpha = 0.88f * mobileAlpha), Offset(fx1, fBarY), Offset(fx1 + (fx2 - fx1) * fProg, fBarY), 2.dp.toPx(), StrokeCap.Round)
                        }

                        // 底部手机导航胶囊
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

        // 中央 Piko 品牌字标（唯一文字，全球五语通用）
        Text(
            text = "Piko",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = fontFamily,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            ),
            color = colors.onSurface,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (-9).dp, y = 34.dp),
        )
    }
}

internal fun DrawScope.drawBannerIntakeToken(
    center: Offset,
    scale: Float,
    alpha: Float,
    rotDeg: Float,
    kind: Int,
    fill: Color,
    stroke: Color,
    bodyPath: Path,
    detailPath: Path,
) {
    if (alpha <= 0.01f) return
    val strokeStyle = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val ink = stroke.copy(alpha = 0.88f * alpha)

    withTransform({
        translate(left = center.x, top = center.y)
        rotate(degrees = rotDeg, pivot = Offset.Zero)
        scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
    }) {
        when (kind) {
            3 -> {
                // 文件夹
                val fw = 22.dp.toPx()
                val fh = 17.dp.toPx()
                val l = -fw / 2f
                val r = fw / 2f
                val t = -fh / 2f
                val b = fh / 2f
                val tabH = 3.5.dp.toPx()
                val tabEnd = l + fw * 0.45f
                bodyPath.reset()
                bodyPath.moveTo(l, t + tabH)
                bodyPath.lineTo(l, t + 1.5.dp.toPx())
                bodyPath.quadraticTo(l, t, l + 1.8.dp.toPx(), t)
                bodyPath.lineTo(tabEnd - 1.5.dp.toPx(), t)
                bodyPath.lineTo(tabEnd + 1.5.dp.toPx(), t + tabH)
                bodyPath.lineTo(r - 2.dp.toPx(), t + tabH)
                bodyPath.quadraticTo(r, t + tabH, r, t + tabH + 2.dp.toPx())
                bodyPath.lineTo(r, b - 2.dp.toPx())
                bodyPath.quadraticTo(r, b, r - 2.dp.toPx(), b)
                bodyPath.lineTo(l + 2.dp.toPx(), b)
                bodyPath.quadraticTo(l, b, l, b - 2.dp.toPx())
                bodyPath.close()
                drawPath(bodyPath, fill.copy(alpha = alpha))
                drawPath(bodyPath, ink, style = strokeStyle)
            }
            4 -> {
                // 磁力链接（圆角徽章 + U 形磁铁）
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
            else -> {
                // 经典竖版折角文件（0=视频, 1=图片, 2=文档, 5=压缩包）
                val fw = 18.dp.toPx()
                val fh = 22.dp.toPx()
                val l = -fw / 2f
                val r = fw / 2f
                val t = -fh / 2f
                val b = fh / 2f
                val fold = 5.dp.toPx()
                bodyPath.reset()
                bodyPath.moveTo(l + 2.dp.toPx(), t)
                bodyPath.lineTo(r - fold, t)
                bodyPath.lineTo(r, t + fold)
                bodyPath.lineTo(r, b - 2.dp.toPx())
                bodyPath.quadraticTo(r, b, r - 2.dp.toPx(), b)
                bodyPath.lineTo(l + 2.dp.toPx(), b)
                bodyPath.quadraticTo(l, b, l, b - 2.dp.toPx())
                bodyPath.lineTo(l, t + 2.dp.toPx())
                bodyPath.quadraticTo(l, t, l + 2.dp.toPx(), t)
                bodyPath.close()
                drawPath(bodyPath, fill.copy(alpha = alpha))
                drawPath(bodyPath, ink, style = strokeStyle)
                detailPath.reset()
                detailPath.moveTo(r - fold, t)
                detailPath.lineTo(r - fold, t + fold)
                detailPath.lineTo(r, t + fold)
                drawPath(detailPath, ink.copy(alpha = 0.72f * alpha), style = strokeStyle)

                when (kind) {
                    0 -> {
                        val tri = 3.dp.toPx()
                        val cy = 2.dp.toPx()
                        detailPath.reset()
                        detailPath.moveTo(-tri * 0.45f, cy - tri * 0.85f)
                        detailPath.lineTo(tri * 0.95f, cy)
                        detailPath.lineTo(-tri * 0.45f, cy + tri * 0.85f)
                        detailPath.close()
                        drawPath(detailPath, ink)
                    }
                    1 -> {
                        drawCircle(ink, 1.3.dp.toPx(), Offset(-2.4.dp.toPx(), 0f))
                        detailPath.reset()
                        detailPath.moveTo(l + 3.dp.toPx(), b - 3.5.dp.toPx())
                        detailPath.lineTo(-1.dp.toPx(), 2.dp.toPx())
                        detailPath.lineTo(1.2.dp.toPx(), 4.5.dp.toPx())
                        detailPath.lineTo(3.dp.toPx(), 2.6.dp.toPx())
                        detailPath.lineTo(r - 3.dp.toPx(), b - 3.5.dp.toPx())
                        drawPath(detailPath, ink, style = strokeStyle)
                    }
                    5 -> {
                        // 压缩包拉链齿
                        for (z in 0..3) {
                            val zy = -3.dp.toPx() + z * 3.dp.toPx()
                            val zx = if (z % 2 == 0) -1.5.dp.toPx() else 0.5.dp.toPx()
                            drawLine(ink, Offset(zx, zy), Offset(zx + 2.dp.toPx(), zy), 1.4.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    else -> {
                        drawLine(ink, Offset(l + 3.5.dp.toPx(), 0f), Offset(r - 3.5.dp.toPx(), 0f), 1.3.dp.toPx(), StrokeCap.Round)
                        drawLine(ink, Offset(l + 3.5.dp.toPx(), 3.6.dp.toPx()), Offset(r - 3.5.dp.toPx(), 3.6.dp.toPx()), 1.3.dp.toPx(), StrokeCap.Round)
                        drawLine(ink, Offset(l + 3.5.dp.toPx(), 7.dp.toPx()), Offset(1.5.dp.toPx(), 7.dp.toPx()), 1.3.dp.toPx(), StrokeCap.Round)
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
