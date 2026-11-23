@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)

package dev.piko.shots

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTheme
import dev.piko.ui.theme.ThemeMode
import java.io.File
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat

private data class SocialTunnelToken(
    val depthRaw: Float, // 0f (far left) .. 1f (vanishing point)
    val laneY: Float,    // -1f..1f vertical lane
    val kind: Int,       // 0=video, 1=image, 2=doc, 3=folder, 4=magnet, 5=archive
    val spinDir: Float,
)

/**
 * 渲染 GitHub Social Preview（1280×640，浅色与深色双版预览）。
 *
 * 视觉语言延续 README 动态 Banner：
 * - 上半区：左虫洞吸入多源图标 + 中央无底描边 Piko 猫脸与字标 + 右侧抽象桌面宽窗与叠层手机竖屏；
 * - 下半区：中英双语副标题与五平台圆角胶囊（Android / Windows / macOS / Linux / iOS）。
 */
internal fun renderSocialPreview(outDir: File) {
    val widthPx = 1280
    val heightPx = 640
    val density = Density(2f)
    outDir.mkdirs()

    ShotEnv("social-preview", localDownloads = false).use { env ->
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            val fileName = if (mode == ThemeMode.DARK) "social-preview-dark.png" else "social-preview.png"
            val scene = edt {
                ImageComposeScene(
                    width = widthPx,
                    height = heightPx,
                    density = density,
                    coroutineContext = Dispatchers.Main,
                ) {
                    CompositionLocalProvider(LocalPikoPlatform provides env.platform) {
                        PikoTheme(appearance = Appearance(mode = mode)) {
                            PikoSocialPreviewCanvas()
                        }
                    }
                }
            }
            try {
                val bytes = edt {
                    val image = scene.render(0L)
                    val data = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
                    image.close()
                    data
                }
                val outFile = File(outDir, fileName)
                outFile.writeBytes(bytes)
                println("Rendered $fileName -> ${outFile.absolutePath} (${bytes.size / 1024} KB)")
            } finally {
                edt { scene.close() }
            }
        }
    }
}

@Composable
private fun PikoSocialPreviewCanvas() {
    val colors = MaterialTheme.colorScheme
    val displayFont = remember { loadSocialPreviewFontFamily() }

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

    // 静态构图精心排布 9 枚多源图标，避免重叠与边缘裁切
    val tunnelTokens = remember {
        listOf(
            SocialTunnelToken(0.14f, -0.78f, 3, -0.8f), // 文件夹
            SocialTunnelToken(0.18f, 0.72f, 1, 0.9f),   // 图片
            SocialTunnelToken(0.32f, -0.18f, 5, -0.7f), // 压缩包
            SocialTunnelToken(0.42f, -0.84f, 2, 0.7f),  // 文档
            SocialTunnelToken(0.48f, 0.42f, 4, 1.0f),   // 磁力
            SocialTunnelToken(0.60f, -0.46f, 0, -0.9f), // 视频
            SocialTunnelToken(0.70f, 0.66f, 2, 0.6f),   // 文档
            SocialTunnelToken(0.76f, -0.10f, 4, -0.8f), // 磁力
            SocialTunnelToken(0.86f, 0.24f, 3, 0.5f),   // 文件夹（近洞口微缩）
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
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // 适配 GitHub 卡片上下各 ~40dp (80px) 的 cover 裁切安全区，将核心视觉收束在 y ∈ [48, 268] dp 内
            val heroCy = 108.dp.toPx()
            val centerX = w * 0.485f

            // 0. 满铺底板 + 柔和主题径向光晕（无外圆角描边，适配社交卡片任意裁切）
            drawRect(color = surfaceBg)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryContainer.copy(alpha = 0.30f),
                        primaryContainer.copy(alpha = 0.08f),
                        Color.Transparent,
                    ),
                    center = Offset(centerX, h * 0.46f),
                    radius = w * 0.44f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        secondaryContainer.copy(alpha = 0.20f),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.80f, heroCy + 14.dp.toPx()),
                    radius = w * 0.32f,
                ),
            )

            // =============================================================
            // 1. 左侧：虫洞 / 格列佛透视隧道
            // =============================================================
            val vp = Offset(centerX - 42.dp.toPx(), heroCy)
            val tunnelLeft = -18.dp.toPx()
            val tunnelSpan = vp.x - tunnelLeft

            val ringCount = 7
            for (r in 0 until ringCount) {
                val raw = (r.toFloat() + 0.35f) / ringCount
                val depth = raw.pow(1.32f)
                val rx = (50f - 40f * depth).dp.toPx()
                val ry = (76f - 62f * depth).dp.toPx()
                val ringX = tunnelLeft + tunnelSpan * (0.04f + 0.96f * depth)
                val ringAlpha = when {
                    raw < 0.14f -> (raw / 0.14f) * 0.22f
                    raw > 0.82f -> ((1f - raw) / 0.18f) * 0.22f
                    else -> 0.22f
                }
                drawOval(
                    color = primary.copy(alpha = ringAlpha),
                    topLeft = Offset(ringX - rx, heroCy - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = Stroke(width = (1.45f - 0.65f * depth).dp.toPx()),
                )
            }

            val fills = arrayOf(secondaryContainer, primaryContainer, tertiaryContainer)
            for (tok in tunnelTokens) {
                val raw = tok.depthRaw
                val t = raw.pow(1.22f)
                val startX = 4.dp.toPx()
                val startY = heroCy + tok.laneY * (56.dp.toPx())
                val ctrlX = startX + (vp.x - startX) * 0.52f
                val ctrlY = startY * 0.78f + vp.y * 0.22f
                val inv = 1f - t
                val x = inv * inv * startX + 2f * inv * t * ctrlX + t * t * vp.x
                val y = inv * inv * startY + 2f * inv * t * ctrlY + t * t * vp.y
                val scale = (1.04f - 0.78f * t).coerceAtLeast(0.24f)
                val alpha = when {
                    raw > 0.76f -> ((1f - raw) / 0.24f).coerceIn(0f, 1f)
                    else -> 1f
                }
                val rot = tok.spinDir * (14f - 26f * t)
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

            // =============================================================
            // 2. 中央 -> 右侧 轻盈输出能量流
            // =============================================================
            val outStart = Offset(centerX + 38.dp.toPx(), heroCy)
            val outEndX = w * 0.642f
            val ribbonOffsets = floatArrayOf(-24f, 0f, 24f)
            val beadProgress = floatArrayOf(0.32f, 0.66f, 0.48f)
            for (i in ribbonOffsets.indices) {
                val endY = heroCy + ribbonOffsets[i].dp.toPx()
                val c1x = outStart.x + 24.dp.toPx()
                val c2x = outEndX - 20.dp.toPx()
                ribbonPath.reset()
                ribbonPath.moveTo(outStart.x, outStart.y)
                ribbonPath.cubicTo(c1x, outStart.y, c2x, endY, outEndX, endY)
                drawPath(
                    path = ribbonPath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            primary.copy(alpha = 0.05f),
                            primary.copy(alpha = 0.24f),
                            primary.copy(alpha = 0.05f),
                        ),
                        startX = outStart.x,
                        endX = outEndX,
                    ),
                    style = Stroke(width = 1.35.dp.toPx(), cap = StrokeCap.Round),
                )
                val pt = beadProgress[i]
                val inv = 1f - pt
                val bx = inv * inv * inv * outStart.x +
                    3 * inv * inv * pt * c1x +
                    3 * inv * pt * pt * c2x +
                    pt * pt * pt * outEndX
                val by = inv * inv * inv * outStart.y +
                    3 * inv * inv * pt * outStart.y +
                    3 * inv * pt * pt * endY +
                    pt * pt * pt * endY
                val bAlpha = sin(pt * PI).toFloat() * 0.78f
                drawCircle(color = primary.copy(alpha = bAlpha), radius = 2.5.dp.toPx(), center = Offset(bx, by))
            }

            // =============================================================
            // 3. 中央：Piko 猫脸锚点
            // =============================================================
            val pikoCenter = Offset(centerX, heroCy - 18.dp.toPx())
            val pikoSize = 56.dp.toPx()
            val pikoScale = pikoSize / 48f

            // 中心岛柔光，隔开最末透视环
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(surfaceBg.copy(alpha = 0.96f), surfaceBg.copy(alpha = 0f)),
                    center = Offset(centerX, heroCy),
                    radius = 66.dp.toPx(),
                ),
                radius = 66.dp.toPx(),
                center = Offset(centerX, heroCy),
            )

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
                val eyeH = 6.2f
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
            // 4. 右侧：抽象桌面宽窗 + 前景右下叠层手机竖屏（静态展现双端自适应）
            // =============================================================
            val deskW = 166.dp.toPx()
            val deskH = 104.dp.toPx()
            val deskLeft = w * 0.642f
            val deskTop = heroCy - deskH * 0.52f
            val deskRect = Rect(deskLeft, deskTop, deskLeft + deskW, deskTop + deskH)
            val deskRadius = 12.dp.toPx()

            // 桌面窗柔和阴影垫底
            drawRoundRect(
                color = primary.copy(alpha = 0.06f),
                topLeft = Offset(deskRect.left, deskRect.top + 4.dp.toPx()),
                size = deskRect.size,
                cornerRadius = CornerRadius(deskRadius),
            )
            drawRoundRect(surfaceLow, deskRect.topLeft, deskRect.size, CornerRadius(deskRadius))
            drawRoundRect(
                outline.copy(alpha = 0.72f),
                deskRect.topLeft,
                deskRect.size,
                CornerRadius(deskRadius),
                style = Stroke(1.2.dp.toPx()),
            )

            clipRoundPath.reset()
            clipRoundPath.addRoundRect(RoundRect(deskRect, CornerRadius(deskRadius)))
            clipPath(clipRoundPath) {
                val topBarH = 17.dp.toPx()
                drawRect(surfaceHigh.copy(alpha = 0.55f), deskRect.topLeft, Size(deskRect.width, topBarH))
                drawRoundRect(
                    primaryContainer.copy(alpha = 0.88f),
                    Offset(deskRect.left + 8.dp.toPx(), deskRect.top + 4.5.dp.toPx()),
                    Size(32.dp.toPx(), 8.dp.toPx()),
                    CornerRadius(4.dp.toPx()),
                )
                drawRoundRect(
                    surfaceBg.copy(alpha = 0.75f),
                    Offset(deskRect.left + 44.dp.toPx(), deskRect.top + 4.5.dp.toPx()),
                    Size(24.dp.toPx(), 8.dp.toPx()),
                    CornerRadius(4.dp.toPx()),
                )
                for (d in 0..2) {
                    drawCircle(
                        outline.copy(alpha = 0.9f),
                        2.dp.toPx(),
                        Offset(deskRect.right - 22.dp.toPx() + d * 7.dp.toPx(), deskRect.top + topBarH * 0.5f),
                    )
                }

                // 左侧导航轨
                val railW = 18.dp.toPx()
                val bodyTop = deskRect.top + topBarH + 4.dp.toPx()
                val bodyBottom = deskRect.bottom - 5.dp.toPx()
                for (n in 0..3) {
                    val active = n == 0
                    drawRoundRect(
                        color = if (active) primary.copy(alpha = 0.84f) else outline.copy(alpha = 0.52f),
                        topLeft = Offset(deskRect.left + 4.5.dp.toPx(), bodyTop + 2.5.dp.toPx() + n * 15.dp.toPx()),
                        size = Size(9.dp.toPx(), 7.dp.toPx()),
                        cornerRadius = CornerRadius(3.5.dp.toPx()),
                    )
                }

                // 中间两列错落瀑布流
                val gridLeft = deskRect.left + railW + 3.5.dp.toPx()
                val gridW = 86.dp.toPx()
                val gridRect = Rect(gridLeft, bodyTop, gridLeft + gridW, bodyBottom)
                val gridClip = Path().apply { addRoundRect(RoundRect(gridRect, CornerRadius(5.5.dp.toPx()))) }
                val gap = 4.dp.toPx()
                val colW = (gridW - gap) / 2f
                clipPath(gridClip) {
                    val col1H = floatArrayOf(31.dp.toPx(), 21.dp.toPx(), 26.dp.toPx())
                    val col1C = arrayOf(primaryContainer, tertiaryContainer, surfaceHigh)
                    var y1 = gridRect.top
                    for (i in col1H.indices) {
                        drawRoundRect(col1C[i].copy(alpha = 0.90f), Offset(gridLeft, y1), Size(colW, col1H[i]), CornerRadius(4.5.dp.toPx()))
                        y1 += col1H[i] + gap
                    }
                    val col2H = floatArrayOf(22.dp.toPx(), 33.dp.toPx(), 23.dp.toPx())
                    val col2C = arrayOf(secondaryContainer, primaryContainer, tertiaryContainer)
                    var y2 = gridRect.top
                    for (i in col2H.indices) {
                        drawRoundRect(col2C[i].copy(alpha = 0.88f), Offset(gridLeft + colW + gap, y2), Size(colW, col2H[i]), CornerRadius(4.5.dp.toPx()))
                        y2 += col2H[i] + gap
                    }
                }

                // 右侧停靠信息流/播放侧栏
                val sideLeft = gridRect.right + 4.5.dp.toPx()
                val sideRect = Rect(sideLeft, bodyTop, deskRect.right - 5.5.dp.toPx(), bodyBottom)
                drawRoundRect(secondaryContainer.copy(alpha = 0.72f), sideRect.topLeft, sideRect.size, CornerRadius(6.dp.toPx()))
                val pc = Offset(sideRect.center.x, sideRect.center.y - 8.dp.toPx())
                drawCircle(primary, 7.dp.toPx(), pc)
                drawPlayTriangle(pc, 2.8.dp.toPx(), onPrimary, detailPath)
            }

            // 前景右下角叠层手机竖屏（展现信息流与移动自适应）
            val phoneW = 54.dp.toPx()
            val phoneH = 92.dp.toPx()
            val phoneLeft = deskRect.right - phoneW + 18.dp.toPx()
            val phoneTop = deskRect.bottom - phoneH + 14.dp.toPx()
            val phoneRect = Rect(phoneLeft, phoneTop, phoneLeft + phoneW, phoneTop + phoneH)
            val phoneRadius = 12.dp.toPx()

            // 手机外圈柔和底色护边 + 微阴影，与桌面窗清晰分层
            drawRoundRect(
                color = surfaceBg.copy(alpha = 0.92f),
                topLeft = Offset(phoneRect.left - 3.dp.toPx(), phoneRect.top - 3.dp.toPx()),
                size = Size(phoneRect.width + 6.dp.toPx(), phoneRect.height + 6.dp.toPx()),
                cornerRadius = CornerRadius(phoneRadius + 2.5.dp.toPx()),
            )
            drawRoundRect(
                color = primary.copy(alpha = 0.08f),
                topLeft = Offset(phoneRect.left, phoneRect.top + 3.dp.toPx()),
                size = phoneRect.size,
                cornerRadius = CornerRadius(phoneRadius),
            )
            drawRoundRect(surfaceLow, phoneRect.topLeft, phoneRect.size, CornerRadius(phoneRadius))
            drawRoundRect(
                outline.copy(alpha = 0.85f),
                phoneRect.topLeft,
                phoneRect.size,
                CornerRadius(phoneRadius),
                style = Stroke(1.3.dp.toPx()),
            )

            clipRoundPath.reset()
            clipRoundPath.addRoundRect(RoundRect(phoneRect, CornerRadius(phoneRadius)))
            clipPath(clipRoundPath) {
                val mInset = 4.5.dp.toPx()
                val navH = 15.dp.toPx()
                val feedRect = Rect(
                    phoneRect.left + mInset,
                    phoneRect.top + 7.dp.toPx(),
                    phoneRect.right - mInset,
                    phoneRect.bottom - navH - 3.5.dp.toPx(),
                )
                val feedClip = Path().apply { addRoundRect(RoundRect(feedRect, CornerRadius(8.5.dp.toPx()))) }
                clipPath(feedClip) {
                    drawRect(tertiaryContainer.copy(alpha = 0.90f), feedRect.topLeft, feedRect.size)
                    val bc = Offset(feedRect.center.x - 2.dp.toPx(), feedRect.center.y - 2.dp.toPx())
                    drawPlayTriangle(bc, 5.dp.toPx(), onTertiaryContainer.copy(alpha = 0.88f), detailPath)

                    val dotX = feedRect.right - 6.5.dp.toPx()
                    for (i in 0..2) {
                        drawCircle(
                            onTertiaryContainer.copy(alpha = 0.42f + i * 0.16f),
                            1.9.dp.toPx(),
                            Offset(dotX, feedRect.bottom - 24.dp.toPx() + i * 6.8.dp.toPx()),
                        )
                    }
                    val fBarY = feedRect.bottom - 5.dp.toPx()
                    val fx1 = feedRect.left + 5.5.dp.toPx()
                    val fx2 = feedRect.right - 13.dp.toPx()
                    drawLine(onTertiaryContainer.copy(alpha = 0.22f), Offset(fx1, fBarY), Offset(fx2, fBarY), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(onTertiaryContainer.copy(alpha = 0.88f), Offset(fx1, fBarY), Offset(fx1 + (fx2 - fx1) * 0.52f, fBarY), 2.dp.toPx(), StrokeCap.Round)
                }

                // 手机底部三枚导航胶囊
                val navY = phoneRect.bottom - navH * 0.55f
                for (i in -1..1) {
                    val active = i == 0
                    drawRoundRect(
                        color = if (active) primary.copy(alpha = 0.86f) else outline.copy(alpha = 0.65f),
                        topLeft = Offset(phoneRect.center.x + i * 16.dp.toPx() - if (active) 6.5.dp.toPx() else 2.8.dp.toPx(), navY - 2.6.dp.toPx()),
                        size = Size(if (active) 13.dp.toPx() else 5.6.dp.toPx(), 5.2.dp.toPx()),
                        cornerRadius = CornerRadius(2.6.dp.toPx()),
                    )
                }
            }
        }

        // 中央 Piko 品牌字标（对齐中心猫脸 centerX = 640 * 0.485 = 310.4dp，相对 320dp 偏移 -9.6dp）
        Text(
            text = "Piko",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = displayFont,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
            ),
            color = colors.onSurface,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(x = (-9.6).dp, y = 120.dp),
        )

        // 下半区：双语副标题与五平台圆角胶囊（底部保留 54dp = 108px 安全区，兼容 GitHub 卡片上下裁切）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 54.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "优雅、易用、高性能的 PikPak 客户端",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = displayFont,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.2.sp,
                ),
                color = colors.onSurface,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "An elegant, easy-to-use, high-performance PikPak client",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = displayFont,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.sp,
                ),
                color = colors.onSurface.copy(alpha = 0.80f),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (platform in listOf("Android", "Windows", "macOS", "Linux", "iOS")) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = colors.secondaryContainer.copy(alpha = 0.62f),
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
                    ) {
                        Text(
                            text = platform,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = displayFont,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = colors.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun loadSocialPreviewFontFamily(): FontFamily {
    val userFonts = System.getenv("LOCALAPPDATA")?.let { File(it, "Microsoft/Windows/Fonts") }
    val winFonts = File("C:/Windows/Fonts")

    if (userFonts != null) {
        val hReg = File(userFonts, "HarmonyOS_Sans_SC_Regular.ttf")
        val hMed = File(userFonts, "HarmonyOS_Sans_SC_Medium.ttf")
        val hBold = File(userFonts, "HarmonyOS_Sans_SC_Bold.ttf")
        if (hReg.isFile && hMed.isFile && hBold.isFile) {
            return FontFamily(
                Font(hReg, weight = FontWeight.Normal),
                Font(hMed, weight = FontWeight.Medium),
                Font(hMed, weight = FontWeight.SemiBold),
                Font(hBold, weight = FontWeight.Bold),
            )
        }
        val mReg = File(userFonts, "MiSans-Regular.ttf")
        val mSemi = File(userFonts, "MiSans-Semibold.ttf")
        val mBold = File(userFonts, "MiSans-Bold.ttf")
        if (mReg.isFile && mSemi.isFile && mBold.isFile) {
            return FontFamily(
                Font(mReg, weight = FontWeight.Normal),
                Font(mSemi, weight = FontWeight.Medium),
                Font(mSemi, weight = FontWeight.SemiBold),
                Font(mBold, weight = FontWeight.Bold),
            )
        }
    }

    val noto = File(winFonts, "NotoSansSC-VF.ttf")
    if (noto.isFile) {
        return FontFamily(
            Font(noto, weight = FontWeight.Normal),
            Font(noto, weight = FontWeight.Medium),
            Font(noto, weight = FontWeight.Bold),
        )
    }
    return FontFamily.SansSerif
}

private fun DrawScope.drawPlayTriangle(
    center: Offset,
    tri: Float,
    color: Color,
    path: Path,
) {
    path.reset()
    path.moveTo(center.x - tri * 0.4f, center.y - tri * 0.8f)
    path.lineTo(center.x + tri * 0.85f, center.y)
    path.lineTo(center.x - tri * 0.4f, center.y + tri * 0.8f)
    path.close()
    drawPath(path, color)
}
