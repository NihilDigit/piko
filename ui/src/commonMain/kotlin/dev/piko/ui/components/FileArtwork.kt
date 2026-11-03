package dev.piko.ui.components

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isDark
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
    val hasCover = cover != null
    val yLeftFraction = if (hasCover) FRONT_OPEN_LEFT_TOP else FRONT_CLOSED_LEFT_TOP
    val yRightFraction = if (hasCover) FRONT_OPEN_RIGHT_TOP else FRONT_CLOSED_RIGHT_TOP
    val frontShape = remember(hasCover) { FolderFrontShape(yLeftFraction, yRightFraction) }
    BoxWithConstraints(modifier) {
        Box(Modifier.fillMaxSize().clip(FolderBackShape).background(backPlate))
        if (hasCover) {
            val cardTop = maxHeight * COVER_CARD_TOP
            val cardWidth = maxWidth * COVER_CARD_WIDTH
            val cardHeight = cardWidth * 9f / 16f
            val innerShape = RoundedCornerShape(6.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = cardTop)
                    .size(cardWidth, cardHeight)
                    .clip(innerShape)
                    .background(colors.surfaceContainerLowest)
                    .border(1.dp, cardBorder, innerShape),
                contentAlignment = Alignment.TopCenter,
            ) {
                cover()
            }
        } else if (!empty) {
            val sheetShape = RoundedCornerShape(topStart = 6.dp, topEnd = 5.dp)
            Box(
                modifier = Modifier
                    .padding(start = maxWidth * 0.035f, top = maxHeight * FRONT_CLOSED_RIGHT_TOP)
                    .size(maxWidth * 0.43f, maxHeight * 0.18f)
                    .clip(sheetShape)
                    .background(colors.surfaceContainerLowest)
                    .border(1.dp, cardBorder, sheetShape),
            )
        }
        val frontStartPx = with(androidx.compose.ui.platform.LocalDensity.current) { (maxHeight * yRightFraction).toPx() }
        val topHighlight = Color.White.copy(alpha = if (dark) 0.22f else 0.55f)
        val topSeam = Color.Black.copy(alpha = if (dark) 0.28f else 0.08f)
        Canvas(Modifier.fillMaxSize()) {
            drawPath(buildFolderFrontTopContourPath(size, this, yLeftFraction, yRightFraction), topSeam, style = Stroke(1.dp.toPx()))
        }
        Box(
            Modifier.fillMaxSize()
                .clip(frontShape)
                .background(Brush.verticalGradient(listOf(frontTopColor, frontBottomColor), startY = frontStartPx))
                .drawWithContent {
                    drawContent()
                    val rimHeight = 2.dp.toPx()
                    drawRect(
                        color = backPlate.copy(alpha = if (dark) 0.65f else 0.5f),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - rimHeight),
                        size = Size(size.width, rimHeight),
                    )
                    drawPath(
                        buildFolderFrontTopContourPath(size, this, yLeftFraction, yRightFraction),
                        topHighlight,
                        style = Stroke(1.25.dp.toPx()),
                    )
                },
        )
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

private object FolderBackShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val r = with(density) { 10.dp.toPx() }.coerceAtMost(minOf(w, h) / 2)
        val tabHeight = h * 0.065f
        val tabEnd = w * 0.33f
        val shoulderStart = w * 0.43f
        val mid = (tabEnd + shoulderStart) * 0.5f
        val path = Path().apply {
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
        return Outline.Generic(path)
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

private class FolderFrontShape(
    private val yLeftFraction: Float,
    private val yRightFraction: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(buildFolderFrontPath(size, density, yLeftFraction, yRightFraction))
}
