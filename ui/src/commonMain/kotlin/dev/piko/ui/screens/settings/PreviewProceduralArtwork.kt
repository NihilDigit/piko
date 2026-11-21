package dev.piko.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.ThumbnailScale
import dev.piko.ui.screens.drive.LocalDriveAppearancePrefs
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk

internal const val PREVIEW_COVER_VIDEO = "piko-preview://video-night-sky"
internal const val PREVIEW_COVER_FOLDER = "piko-preview://folder-sunset-dusk"

internal enum class PreviewArtworkVariant {
    VideoNightSky,
    FolderSunsetDusk,
}

/** 100% 原创矢量程序封面（无网络依赖、无版权海报），经 `LocalPreviewArtworkRenderer` 注入真实组件渲染。 */
@Composable
internal fun PreviewProceduralArtwork(
    variant: PreviewArtworkVariant,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val accentGlow = colors.primary
    val tertiaryGlow = colors.tertiary
    val scale = LocalDriveAppearancePrefs.current.thumbnailScale
    val intrinsicAspect = when (variant) {
        PreviewArtworkVariant.VideoNightSky -> 16f / 9f
        PreviewArtworkVariant.FolderSunsetDusk -> 4f / 3f
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .then(
                if (scale == ThumbnailScale.FIT) {
                    Modifier.background(if (isEInk) Color.White else colors.surfaceContainerHighest)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 严格按真实 AsyncImage 的 ContentScale.Fit / ContentScale.Crop 数学公式计算目标矩形
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cw = size.width
            val ch = size.height
            if (cw <= 0f || ch <= 0f) return@Canvas
            val containerAspect = cw / ch
            val drawW: Float
            val drawH: Float
            if (scale == ThumbnailScale.FIT) {
                if (containerAspect > intrinsicAspect) {
                    drawH = ch
                    drawW = ch * intrinsicAspect
                } else {
                    drawW = cw
                    drawH = cw / intrinsicAspect
                }
            } else {
                if (containerAspect > intrinsicAspect) {
                    drawW = cw
                    drawH = cw / intrinsicAspect
                } else {
                    drawH = ch
                    drawW = ch * intrinsicAspect
                }
            }
            val left = (cw - drawW) / 2f
            val top = (ch - drawH) / 2f
            val minDim = minOf(drawW, drawH)

            clipRect(
                left = left.coerceAtLeast(0f),
                top = top.coerceAtLeast(0f),
                right = (left + drawW).coerceAtMost(cw),
                bottom = (top + drawH).coerceAtMost(ch),
            ) {
                translate(left = left, top = top) {
                    val w = drawW
                    val h = drawH
                    if (isEInk) {
                        drawRect(color = Color.White, size = Size(w, h))
                        if (scale == ThumbnailScale.FIT) {
                            drawRect(
                                color = Color.Black,
                                size = Size(w, h),
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                        val stroke = 1.5.dp.toPx()
                        when (variant) {
                            PreviewArtworkVariant.VideoNightSky -> {
                                drawCircle(
                                    color = Color.Black,
                                    radius = minDim * 0.13f,
                                    center = Offset(w * 0.72f, h * 0.30f),
                                    style = Stroke(width = stroke),
                                )
                                val ridge = Path().apply {
                                    moveTo(0f, h * 0.66f)
                                    lineTo(w * 0.22f, h * 0.66f)
                                    lineTo(w * 0.22f, h * 0.56f)
                                    lineTo(w * 0.44f, h * 0.56f)
                                    lineTo(w * 0.54f, h * 0.70f)
                                    lineTo(w * 0.80f, h * 0.70f)
                                    lineTo(w * 0.80f, h * 0.60f)
                                    lineTo(w, h * 0.60f)
                                }
                                drawPath(ridge, color = Color.Black, style = Stroke(width = stroke))
                            }
                            PreviewArtworkVariant.FolderSunsetDusk -> {
                                drawCircle(
                                    color = Color.Black,
                                    radius = minDim * 0.14f,
                                    center = Offset(w * 0.34f, h * 0.44f),
                                    style = Stroke(width = stroke),
                                )
                                val peak = Path().apply {
                                    moveTo(0f, h * 0.68f)
                                    lineTo(w * 0.28f, h * 0.52f)
                                    lineTo(w * 0.58f, h * 0.66f)
                                    lineTo(w * 0.82f, h * 0.48f)
                                    lineTo(w, h * 0.62f)
                                }
                                drawPath(peak, color = Color.Black, style = Stroke(width = stroke))
                            }
                        }
                        return@translate
                    }
                    when (variant) {
                        PreviewArtworkVariant.VideoNightSky -> {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0xFF15192E), Color(0xFF28204B), Color(0xFF5A335C)),
                                    startY = 0f,
                                    endY = h,
                                ),
                                size = Size(w, h),
                            )
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(accentGlow.copy(alpha = 0.48f), Color.Transparent),
                                    center = Offset(w * 0.72f, h * 0.30f),
                                    radius = minDim * 0.62f,
                                ),
                                center = Offset(w * 0.72f, h * 0.30f),
                                radius = minDim * 0.62f,
                            )
                            drawCircle(
                                color = Color(0xFFF6E7D8).copy(alpha = 0.88f),
                                radius = minDim * 0.13f,
                                center = Offset(w * 0.72f, h * 0.30f),
                            )
                            drawLine(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color.Transparent, tertiaryGlow.copy(alpha = 0.45f), Color.Transparent),
                                    start = Offset(0f, h * 0.78f),
                                    end = Offset(w, h * 0.22f),
                                ),
                                start = Offset(0f, h * 0.78f),
                                end = Offset(w, h * 0.22f),
                                strokeWidth = (minDim * 0.045f).coerceAtLeast(2f),
                                cap = StrokeCap.Round,
                            )
                            val ridge = Path().apply {
                                moveTo(0f, h)
                                lineTo(0f, h * 0.66f)
                                lineTo(w * 0.22f, h * 0.66f)
                                lineTo(w * 0.22f, h * 0.56f)
                                lineTo(w * 0.44f, h * 0.56f)
                                lineTo(w * 0.54f, h * 0.70f)
                                lineTo(w * 0.80f, h * 0.70f)
                                lineTo(w * 0.80f, h * 0.60f)
                                lineTo(w, h * 0.60f)
                                lineTo(w, h)
                                close()
                            }
                            drawPath(ridge, color = Color(0xFF0E1122).copy(alpha = 0.78f))
                        }
                        PreviewArtworkVariant.FolderSunsetDusk -> {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0xFF2B1C3D), Color(0xFF8C3B4A), Color(0xFFE07A47)),
                                    startY = 0f,
                                    endY = h,
                                ),
                                size = Size(w, h),
                            )
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color(0xFFFFD88A).copy(alpha = 0.75f), Color.Transparent),
                                    center = Offset(w * 0.34f, h * 0.44f),
                                    radius = minDim * 0.58f,
                                ),
                                center = Offset(w * 0.34f, h * 0.44f),
                                radius = minDim * 0.58f,
                            )
                            drawCircle(
                                color = Color(0xFFFFF1C6).copy(alpha = 0.92f),
                                radius = minDim * 0.14f,
                                center = Offset(w * 0.34f, h * 0.44f),
                            )
                            val farPeak = Path().apply {
                                moveTo(0f, h)
                                lineTo(0f, h * 0.68f)
                                lineTo(w * 0.28f, h * 0.52f)
                                lineTo(w * 0.58f, h * 0.66f)
                                lineTo(w * 0.82f, h * 0.48f)
                                lineTo(w, h * 0.62f)
                                lineTo(w, h)
                                close()
                            }
                            drawPath(farPeak, color = Color(0xFF47213E).copy(alpha = 0.72f))
                            val nearPeak = Path().apply {
                                moveTo(0f, h)
                                lineTo(0f, h * 0.80f)
                                lineTo(w * 0.36f, h * 0.68f)
                                lineTo(w * 0.70f, h * 0.78f)
                                lineTo(w, h * 0.64f)
                                lineTo(w, h)
                                close()
                            }
                            drawPath(nearPeak, color = Color(0xFF20132B).copy(alpha = 0.84f))
                        }
                    }
                }
            }
        }
    }
}
