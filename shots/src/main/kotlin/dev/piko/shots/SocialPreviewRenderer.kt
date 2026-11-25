@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)

package dev.piko.shots

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
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

            // 0. 满铺纯色底板（无外圆角描边，适配社交卡片任意裁切）
            drawRect(color = surfaceBg)

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
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (platform in PlatformBadgeIcon.entries) {
                    PlatformIconChip(
                        tint = colors.onSecondaryContainer,
                        containerColor = colors.secondaryContainer.copy(alpha = 0.62f),
                        borderColor = colors.outlineVariant.copy(alpha = 0.55f),
                    ) {
                        PlatformVectorIcon(
                            icon = platform,
                            tint = colors.onSecondaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .width(1.dp)
                        .height(18.dp)
                        .background(colors.outlineVariant.copy(alpha = 0.72f)),
                )
                for (device in DeviceBadgeIcon.entries) {
                    PlatformIconChip(
                        tint = colors.onSecondaryContainer,
                        containerColor = colors.secondaryContainer.copy(alpha = 0.62f),
                        borderColor = colors.outlineVariant.copy(alpha = 0.55f),
                    ) {
                        DeviceVectorIcon(
                            icon = device,
                            tint = colors.onSecondaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

private enum class PlatformBadgeIcon {
    ANDROID,
    WINDOWS,
    APPLE,
    LINUX,
}

private enum class DeviceBadgeIcon {
    DESKTOP,
    TABLET,
    PHONE,
}

@Composable
private fun PlatformIconChip(
    tint: Color,
    containerColor: Color,
    borderColor: Color,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = tint,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.size(32.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun DeviceVectorIcon(
    icon: DeviceBadgeIcon,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    // Google Material Icons Round (24x24 viewBox): desktop_windows, tablet, smartphone
    val path = remember(icon) {
        when (icon) {
            DeviceBadgeIcon.DESKTOP -> PathParser().parsePathString(
                "M21 2H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h7v2H9c-.55 0-1 .45-1 1s.45 1 1 1h6" +
                    "c.55 0 1-.45 1-1s-.45-1-1-1h-1v-2h7c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z" +
                    "m-1 14H4c-.55 0-1-.45-1-1V5c0-.55.45-1 1-1h16c.55 0 1 .45 1 1v10c0 .55-.45 1-1 1z",
            ).toPath()

            DeviceBadgeIcon.TABLET -> PathParser().parsePathString(
                "M21 4H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h18c1.1 0 1.99-.9 1.99-2L23 6" +
                    "c0-1.1-.9-2-2-2zm-2 14H5V6h14v12z",
            ).toPath()

            DeviceBadgeIcon.PHONE -> PathParser().parsePathString(
                "M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3" +
                    "c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z",
            ).toPath()
        }
    }
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        withTransform({
            scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero)
        }) {
            drawPath(path = path, color = tint)
        }
    }
}

@Composable
private fun PlatformVectorIcon(
    icon: PlatformBadgeIcon,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val path = remember(icon) {
        when (icon) {
            PlatformBadgeIcon.ANDROID -> PathParser().parsePathString(
                "M18.4395 5.5586c-.675 1.1664-1.352 2.3318-2.0274 3.498-.0366-.0155-.0742-.0286-.1113-.043" +
                    "-1.8249-.6957-3.484-.8-4.42-.787-1.8551.0185-3.3544.4643-4.2597.8203" +
                    "-.084-.1494-1.7526-3.021-2.0215-3.4864a1.1451 1.1451 0 0 0-.1406-.1914" +
                    "c-.3312-.364-.9054-.4859-1.379-.203-.475.282-.7136.9361-.3886 1.5019" +
                    " 1.9466 3.3696-.0966-.2158 1.9473 3.3593.0172.031-.4946.2642-1.3926 1.0177" +
                    "C2.8987 12.176.452 14.772 0 18.9902h24c-.119-1.1108-.3686-2.099-.7461-3.0683" +
                    "-.7438-1.9118-1.8435-3.2928-2.7402-4.1836a12.1048 12.1048 0 0 0-2.1309-1.6875" +
                    "c.6594-1.122 1.312-2.2559 1.9649-3.3848.2077-.3615.1886-.7956-.0079-1.1191" +
                    "a1.1001 1.1001 0 0 0-.8515-.5332c-.5225-.0536-.9392.3128-1.0488.5449z" +
                    "m-.0391 8.461c.3944.5926.324 1.3306-.1563 1.6503-.4799.3197-1.188.0985-1.582-.4941" +
                    "-.3944-.5927-.324-1.3307.1563-1.6504.4727-.315 1.1812-.1086 1.582.4941z" +
                    "M7.207 13.5273c.4803.3197.5506 1.0577.1563 1.6504-.394.5926-1.1038.8138-1.584.4941" +
                    "-.48-.3197-.5503-1.0577-.1563-1.6504.4008-.6021 1.1087-.8106 1.584-.4941z",
            ).toPath()

            PlatformBadgeIcon.APPLE -> PathParser().parsePathString(
                "M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014" +
                    "-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039" +
                    " 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948" +
                    " 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415" +
                    "-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559" +
                    "-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09z" +
                    "M15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.818" +
                    "-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701",
            ).toPath()

            PlatformBadgeIcon.LINUX -> PathParser().parsePathString(
                "M12.504 0c-.155 0-.315.008-.48.021-4.226.333-3.105 4.807-3.17 6.298-.076 1.092-.3 1.953-1.05 3.02" +
                    "-.885 1.051-2.127 2.75-2.716 4.521-.278.832-.41 1.684-.287 2.489a.424.424 0 00-.11.135" +
                    "c-.26.268-.45.6-.663.839-.199.199-.485.267-.797.4-.313.136-.658.269-.864.68" +
                    "-.09.189-.136.394-.132.602 0 .199.027.4.055.536.058.399.116.728.04.97" +
                    "-.249.68-.28 1.145-.106 1.484.174.334.535.47.94.601.81.2 1.91.135 2.774.6" +
                    ".926.466 1.866.67 2.616.47.526-.116.97-.464 1.208-.946.587-.003 1.23-.269 2.26-.334" +
                    ".699-.058 1.574.267 2.577.2.025.134.063.198.114.333l.003.003c.391.778 1.113 1.132 1.884 1.071" +
                    ".771-.06 1.592-.536 2.257-1.306.631-.765 1.683-1.084 2.378-1.503.348-.199.629-.469.649-.853" +
                    ".023-.4-.2-.811-.714-1.376v-.097l-.003-.003c-.17-.2-.25-.535-.338-.926" +
                    "-.085-.401-.182-.786-.492-1.046h-.003c-.059-.054-.123-.067-.188-.135a.357.357 0 00-.19-.064" +
                    "c.431-1.278.264-2.55-.173-3.694-.533-1.41-1.465-2.638-2.175-3.483-.796-1.005-1.576-1.957-1.56-3.368" +
                    ".026-2.152.236-6.133-3.544-6.139zm.529 3.405h.013c.213 0 .396.062.584.198.19.135.33.332.438.533" +
                    ".105.259.158.459.166.724 0-.02.006-.04.006-.06v.105a.086.086 0 01-.004-.021l-.004-.024" +
                    "a1.807 1.807 0 01-.15.706.953.953 0 01-.213.335.71.71 0 00-.088-.042" +
                    "c-.104-.045-.198-.064-.284-.133a1.312 1.312 0 00-.22-.066c.05-.06.146-.133.183-.198" +
                    ".053-.128.082-.264.088-.402v-.02a1.21 1.21 0 00-.061-.4c-.045-.134-.101-.2-.183-.333" +
                    "-.084-.066-.167-.132-.267-.132h-.016c-.093 0-.176.03-.262.132a.8.8 0 00-.205.334" +
                    " 1.18 1.18 0 00-.09.4v.019c.002.089.008.179.02.267-.193-.067-.438-.135-.607-.202" +
                    "a1.635 1.635 0 01-.018-.2v-.02a1.772 1.772 0 01.15-.768c.082-.22.232-.406.43-.533" +
                    "a.985.985 0 01.594-.2zm-2.962.059h.036c.142 0 .27.048.399.135.146.129.264.288.344.465" +
                    ".09.199.14.4.153.667v.004c.007.134.006.2-.002.266v.08c-.03.007-.056.018-.083.024" +
                    "-.152.055-.274.135-.393.2.012-.09.013-.18.003-.267v-.015c-.012-.133-.04-.2-.082-.333" +
                    "a.613.613 0 00-.166-.267.248.248 0 00-.183-.064h-.021c-.071.006-.13.04-.186.132" +
                    "a.552.552 0 00-.12.27.944.944 0 00-.023.33v.015c.012.135.037.2.08.334.046.134.098.2.166.268" +
                    ".01.009.02.018.034.024-.07.057-.117.07-.176.136a.304.304 0 01-.131.068" +
                    " 2.62 2.62 0 01-.275-.402 1.772 1.772 0 01-.155-.667 1.759 1.759 0 01.08-.668" +
                    " 1.43 1.43 0 01.283-.535c.128-.133.26-.2.418-.2zm1.37 1.706c.332 0 .733.065 1.216.399" +
                    ".293.2.523.269 1.052.468h.003c.255.136.405.266.478.399v-.131a.571.571 0 01.016.47" +
                    "c-.123.31-.516.643-1.063.842v.002c-.268.135-.501.333-.775.465-.276.135-.588.292-1.012.267" +
                    "a1.139 1.139 0 01-.448-.067 3.566 3.566 0 01-.322-.198c-.195-.135-.363-.332-.612-.465" +
                    "v-.005h-.005c-.4-.246-.616-.512-.686-.71-.07-.268-.005-.47.193-.6.224-.135.38-.271.483-.336" +
                    ".104-.074.143-.102.176-.131h.002v-.003c.169-.202.436-.47.839-.601.139-.036.294-.065.466-.065z" +
                    "m2.8 2.142c.358 1.417 1.196 3.475 1.735 4.473.286.534.855 1.659 1.102 3.024" +
                    ".156-.005.33.018.513.064.646-1.671-.546-3.467-1.089-3.966-.22-.2-.232-.335-.123-.335" +
                    ".59.534 1.365 1.572 1.646 2.757.13.535.16 1.104.021 1.67.067.028.135.06.205.067" +
                    " 1.032.534 1.413.938 1.23 1.537v-.043c-.06-.003-.12 0-.18 0h-.016" +
                    "c.151-.467-.182-.825-1.065-1.224-.915-.4-1.646-.336-1.77.465-.008.043-.013.066-.018.135" +
                    "-.068.023-.139.053-.209.064-.43.268-.662.669-.793 1.187-.13.533-.17 1.156-.205 1.869v.003" +
                    "c-.02.334-.17.838-.319 1.35-1.5 1.072-3.58 1.538-5.348.334a2.645 2.645 0 00-.402-.533" +
                    "a1.45 1.45 0 00-.275-.333c.182 0 .338-.03.465-.067a.615.615 0 00.314-.334" +
                    "c.108-.267 0-.697-.345-1.163-.345-.467-.931-.995-1.788-1.521-.63-.4-.986-.87-1.15-1.396" +
                    "-.165-.534-.143-1.085-.015-1.645.245-1.07.873-2.11 1.274-2.763.107-.065.037.135-.408.974" +
                    "-.396.751-1.14 2.497-.122 3.854a8.123 8.123 0 01.647-2.876c.564-1.278 1.743-3.504 1.836-5.268" +
                    ".048.036.217.135.289.202.218.133.38.333.59.465.21.201.477.335.876.335.039.003.075.006.11.006" +
                    ".412 0 .73-.134.997-.268.29-.134.52-.334.74-.4h.005c.467-.135.835-.402 1.044-.7z" +
                    "m2.185 8.958c.037.6.343 1.245.882 1.377.588.134 1.434-.333 1.791-.765l.211-.01" +
                    "c.315-.007.577.01.847.268l.003.003c.208.199.305.53.391.876.085.4.154.78.409 1.066" +
                    ".486.527.645.906.636 1.14l.003-.007v.018l-.003-.012c-.015.262-.185.396-.498.595" +
                    "-.63.401-1.746.712-2.457 1.57-.618.737-1.37 1.14-2.036 1.191-.664.053-1.237-.2-1.574-.898" +
                    "l-.005-.003c-.21-.4-.12-1.025.056-1.69.176-.668.428-1.344.463-1.897.037-.714.076-1.335.195-1.814" +
                    ".12-.465.308-.797.641-.984l.045-.022zm-10.814.049h.01c.053 0 .105.005.157.014" +
                    ".376.055.706.333 1.023.752l.91 1.664.003.003c.243.533.754 1.064 1.189 1.637" +
                    ".434.598.77 1.131.729 1.57v.006c-.057.744-.48 1.148-1.125 1.294-.645.135-1.52.002-2.395-.464" +
                    "-.968-.536-2.118-.469-2.857-.602-.369-.066-.61-.2-.723-.4-.11-.2-.113-.602.123-1.23" +
                    "v-.004l.002-.003c.117-.334.03-.752-.027-1.118-.055-.401-.083-.71.043-.94.16-.334.396-.4.69-.533" +
                    ".294-.135.64-.202.915-.47h.002v-.002c.256-.268.445-.601.668-.838.19-.201.38-.336.663-.336z" +
                    "m7.159-9.074c-.435.201-.945.535-1.488.535-.542 0-.97-.267-1.28-.466-.154-.134-.28-.268-.373-.335" +
                    "-.164-.134-.144-.333-.074-.333.109.016.129.134.199.2.096.066.215.2.36.333.292.2.68.467 1.167.467" +
                    ".485 0 1.053-.267 1.398-.466.195-.135.445-.334.648-.467.156-.136.149-.267.279-.267" +
                    ".128.016.034.134-.147.332a8.097 8.097 0 01-.69.468zm-1.082-1.583V5.64" +
                    "c-.006-.02.013-.042.029-.05.074-.043.18-.027.26.004.063 0 .16.067.15.135" +
                    "-.006.049-.085.066-.135.066-.055 0-.092-.043-.141-.068-.052-.018-.146-.008-.163-.065z" +
                    "m-.551 0c-.02.058-.113.049-.166.066-.047.025-.086.068-.14.068-.05 0-.13-.02-.136-.068" +
                    "-.01-.066.088-.133.15-.133.08-.031.184-.047.259-.005.019.009.036.03.03.05v.02h.003z",
            ).toPath()

            PlatformBadgeIcon.WINDOWS -> null
        }
    }

    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        if (icon == PlatformBadgeIcon.WINDOWS) {
            val gap = 1.8f
            val pad = 3.0f
            val cell = (24f - pad * 2f - gap) / 2f
            val r = CornerRadius(1.2f * sx, 1.2f * sy)
            val x1 = pad * sx
            val x2 = (pad + cell + gap) * sx
            val y1 = pad * sy
            val y2 = (pad + cell + gap) * sy
            val cellSize = Size(cell * sx, cell * sy)
            drawRoundRect(tint, Offset(x1, y1), cellSize, r)
            drawRoundRect(tint, Offset(x2, y1), cellSize, r)
            drawRoundRect(tint, Offset(x1, y2), cellSize, r)
            drawRoundRect(tint, Offset(x2, y2), cellSize, r)
        } else if (path != null) {
            withTransform({
                scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero)
            }) {
                drawPath(path = path, color = tint)
            }
        }
    }
}

private fun loadSocialPreviewFontFamily(): FontFamily {
    val fontDirs = buildList {
        System.getenv("LOCALAPPDATA")?.let { add(File(it, "Microsoft/Windows/Fonts")) }
        add(File("C:/Windows/Fonts"))
    }

    for (dir in fontDirs) {
        val hReg = File(dir, "HarmonyOS_Sans_SC_Regular.ttf")
        val hMed = File(dir, "HarmonyOS_Sans_SC_Medium.ttf")
        val hBold = File(dir, "HarmonyOS_Sans_SC_Bold.ttf")
        if (hReg.isFile && hMed.isFile && hBold.isFile) {
            return FontFamily(
                Font(hReg, weight = FontWeight.Normal),
                Font(hMed, weight = FontWeight.Medium),
                Font(hMed, weight = FontWeight.SemiBold),
                Font(hBold, weight = FontWeight.Bold),
            )
        }
        val mReg = File(dir, "MiSans-Regular.ttf")
        val mSemi = File(dir, "MiSans-Semibold.ttf")
        val mBold = File(dir, "MiSans-Bold.ttf")
        if (mReg.isFile && mSemi.isFile && mBold.isFile) {
            return FontFamily(
                Font(mReg, weight = FontWeight.Normal),
                Font(mSemi, weight = FontWeight.Medium),
                Font(mSemi, weight = FontWeight.SemiBold),
                Font(mBold, weight = FontWeight.Bold),
            )
        }
    }

    val noto = File("C:/Windows/Fonts/NotoSansSC-VF.ttf")
    if (noto.isFile) {
        return FontFamily(
            Font(noto, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
            Font(noto, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
            Font(noto, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
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
