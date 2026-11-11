package dev.piko.ui.components

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Layout
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isDark
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*

internal enum class FileArtworkFormat { Icon, Leading, Poster }

@Composable
internal fun FileArtwork(
    file: FileVisual, format: FileArtworkFormat,
    isSpoilerBlurred: Boolean = false, modifier: Modifier = Modifier,
) {
    when (format) {
        FileArtworkFormat.Icon -> Icon(file.typeIcon(), null, modifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        FileArtworkFormat.Leading -> ListLeadingMedia(
            thumbnail = file.thumbnail.takeUnless { file.isFolder },
            fallback = { FileTypeIcon(file, ListLeadingIconSize, Modifier.fillMaxSize()) },
            isSpoilerBlurred = isSpoilerBlurred, modifier = modifier,
        )
        FileArtworkFormat.Poster -> Box(modifier) {
            var coverFailed by remember(file.thumbnail) { mutableStateOf(false) }
            val cover = file.thumbnail?.takeUnless { coverFailed }
            when {
                file.isFolder -> StackedSheets(Modifier.fillMaxSize(), empty = file.isEmptyFolder,
                    cover = cover?.let { { SpoilerThumbnail(it, isSpoilerBlurred, PosterSpoilerBlur, Modifier.fillMaxSize(), onError = { coverFailed = true }) } })
                cover != null -> SpoilerThumbnail(cover, isSpoilerBlurred, PosterSpoilerBlur, Modifier.fillMaxSize(), showBlurIcon = !file.isVideo, onError = { coverFailed = true })
                else -> TypePlaceholder(file, Modifier.fillMaxSize())
            }
            if (file.isVideo && cover != null) VideoPlayMark(Modifier.align(Alignment.Center))
        }
    }
}

private val CoverCardShape = RoundedCornerShape(6.dp)

@Composable
private fun StackedSheets(
    modifier: Modifier = Modifier,
    empty: Boolean = false,
    cover: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalAppearance.current.isDark()
    val backPlate = colors.secondary.copy(alpha = if (dark) 0.36f else 0.38f).compositeOver(colors.secondaryContainer)
    val frontTopColor = Color.White.copy(alpha = if (dark) 0.08f else 0.34f).compositeOver(colors.secondaryContainer)
    val frontBottomColor = colors.secondary.copy(alpha = if (dark) 0.14f else 0.12f).compositeOver(colors.secondaryContainer)
    val cardBorder = Color.Black.copy(alpha = if (dark) 0.24f else 0.08f)
    val sheetColor = colors.surfaceContainerLowest
    val topHighlight = Color.White.copy(alpha = if (dark) 0.22f else 0.55f)
    val topSeam = Color.Black.copy(alpha = if (dark) 0.28f else 0.08f)
    val bottomRimColor = backPlate.copy(alpha = if (dark) 0.65f else 0.5f)
    val hasCover = cover != null
    val yLeftFraction = if (hasCover) FRONT_OPEN_LEFT_TOP else FRONT_CLOSED_LEFT_TOP
    val yRightFraction = if (hasCover) FRONT_OPEN_RIGHT_TOP else FRONT_CLOSED_RIGHT_TOP

    Layout(
        content = {
            if (cover != null) {
                Box(
                    modifier = Modifier
                        .clip(CoverCardShape)
                        .background(sheetColor)
                        .border(1.dp, cardBorder, CoverCardShape),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    cover()
                }
            }
        },
        modifier = modifier.drawWithCache {
            val backPath = buildFolderBackPath(size, this)
            val contourPath = buildFolderFrontTopContourPath(size, this, yLeftFraction, yRightFraction)
            val frontPath = buildFolderFrontPath(size, this, yLeftFraction, yRightFraction)
            val seamStroke = Stroke(1.dp.toPx())
            val highlightStroke = Stroke(1.25.dp.toPx())
            val rimHeight = 2.dp.toPx()
            val frontBrush = Brush.verticalGradient(
                listOf(frontTopColor, frontBottomColor),
                startY = size.height * yRightFraction,
            )
            val sheetPath = if (!hasCover && !empty) {
                val sheetW = size.width * 0.43f
                val sheetH = size.height * 0.18f
                val isLtr = layoutDirection == LayoutDirection.Ltr
                val sheetLeft = if (isLtr) size.width * 0.035f else size.width - size.width * 0.035f - sheetW
                val sheetTop = size.height * FRONT_CLOSED_RIGHT_TOP
                val topStartR = 6.dp.toPx()
                val topEndR = 5.dp.toPx()
                val topLeftR = if (isLtr) topStartR else topEndR
                val topRightR = if (isLtr) topEndR else topStartR
                Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = sheetLeft,
                            top = sheetTop,
                            right = sheetLeft + sheetW,
                            bottom = sheetTop + sheetH,
                            topLeftCornerRadius = CornerRadius(topLeftR),
                            topRightCornerRadius = CornerRadius(topRightR),
                        ),
                    )
                }
            } else {
                null
            }
            val sheetBorderStroke = if (sheetPath != null) Stroke(2.dp.toPx()) else null

            onDrawWithContent {
                drawPath(backPath, backPlate)
                if (sheetPath != null && sheetBorderStroke != null) {
                    drawPath(sheetPath, sheetColor)
                    clipPath(sheetPath) {
                        drawPath(sheetPath, cardBorder, style = sheetBorderStroke)
                    }
                }
                drawContent()
                drawPath(contourPath, topSeam, style = seamStroke)
                clipPath(frontPath) {
                    drawRect(brush = frontBrush)
                    drawRect(
                        color = bottomRimColor,
                        topLeft = Offset(0f, size.height - rimHeight),
                        size = Size(size.width, rimHeight),
                    )
                    drawPath(contourPath, topHighlight, style = highlightStroke)
                }
            }
        },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val coverPlaceable = measurables.firstOrNull()?.let { measurable ->
            val cardW = (width * COVER_CARD_WIDTH).roundToInt().coerceAtLeast(0)
            val cardH = (cardW * 9f / 16f).roundToInt().coerceAtLeast(0)
            measurable.measure(Constraints.fixed(cardW, cardH))
        }
        layout(width, height) {
            if (coverPlaceable != null) {
                coverPlaceable.place(
                    ((width - coverPlaceable.width) / 2f).roundToInt(),
                    (height * COVER_CARD_TOP).roundToInt(),
                )
            }
        }
    }
}

/** 视频封面正中的播放键，半透明黑底，在任何画面上都看得清。 */
@Composable
private fun VideoPlayMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = tr("视频", "Video", "動画", "동영상", "影片"), tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/**
 * 没有缩略图的文件：按大类上底色，类型图标下面写出扩展名，照样占满 16:9。
 * 扩展名是这一格最有用的信息：同是文档，TXT 与 PDF 打开的方式完全不同，只看图标分不出来。
 */
@Composable
private fun TypePlaceholder(file: FileVisual, modifier: Modifier = Modifier) {
    val (container, content) = file.placeholderColors()
    Box(modifier.background(container), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = file.watermarkIcon(),
                contentDescription = null,
                tint = content.copy(alpha = PLACEHOLDER_ICON_ALPHA),
                modifier = Modifier.size(40.dp),
            )
            file.extensionLabel()?.let { extension ->
                Text(
                    text = extension,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = content,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private const val PLACEHOLDER_ICON_ALPHA = 0.6f
private const val COVER_CARD_TOP = 0.145f
private const val COVER_CARD_WIDTH = 0.76f
private const val FRONT_OPEN_LEFT_TOP = 0.74f
private const val FRONT_OPEN_RIGHT_TOP = 0.68f
private const val FRONT_CLOSED_LEFT_TOP = 0.20f
private const val FRONT_CLOSED_RIGHT_TOP = 0.12f

private fun buildFolderBackPath(size: Size, density: Density): Path {
    val w = size.width
    val h = size.height
    val r = with(density) { 10.dp.toPx() }.coerceAtMost(minOf(w, h) / 2)
    val tabHeight = h * 0.065f
    val tabEnd = w * 0.33f
    val shoulderStart = w * 0.43f
    val mid = (tabEnd + shoulderStart) * 0.5f
    return Path().apply {
        moveTo(0f, r)
        quadraticTo(0f, 0f, r, 0f)
        lineTo(tabEnd, 0f)
        cubicTo(mid, 0f, mid, tabHeight, shoulderStart, tabHeight)
        lineTo(w - r, tabHeight)
        quadraticTo(w, tabHeight, w, tabHeight + r)
        lineTo(w, h - r)
        quadraticTo(w, h, w - r, h)
        lineTo(r, h)
        quadraticTo(0f, h, 0f, h - r)
        close()
    }
}

/** Shared open contour for the flap silhouette, its highlight and its cast shadow. */
internal fun buildFolderFrontTopContourPath(
    size: Size,
    density: Density,
    yLeftFraction: Float = FRONT_OPEN_LEFT_TOP,
    yRightFraction: Float = FRONT_OPEN_RIGHT_TOP,
): Path {
    val w = size.width
    val h = size.height
    val yLeft = h * yLeftFraction
    val yRight = h * yRightFraction
    val stepStart = w * 0.31f
    val stepEnd = w * 0.42f
    val r = minOf(with(density) { 8.dp.toPx() }, w * 0.12f, h * 0.08f)
    return Path().apply {
        moveTo(0f, yLeft + r)
        quadraticTo(0f, yLeft, r, yLeft)
        lineTo(stepStart, yLeft)
        val mid = (stepStart + stepEnd) * 0.5f
        cubicTo(mid, yLeft, mid, yRight, stepEnd, yRight)
        lineTo(w - r, yRight)
        quadraticTo(w, yRight, w, yRight + r)
    }
}

internal fun buildFolderFrontPath(
    size: Size,
    density: Density,
    yLeftFraction: Float = FRONT_OPEN_LEFT_TOP,
    yRightFraction: Float = FRONT_OPEN_RIGHT_TOP,
): Path =
    buildFolderFrontTopContourPath(size, density, yLeftFraction, yRightFraction).apply {
        val w = size.width
        val h = size.height
        val r = minOf(with(density) { 12.dp.toPx() }, w * 0.14f, h * 0.14f)
        lineTo(w, h - r)
        quadraticTo(w, h, w - r, h)
        lineTo(r, h)
        quadraticTo(0f, h, 0f, h - r)
        close()
    }
