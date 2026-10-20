package dev.piko.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
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
            val cover = file.thumbnail
            when {
                file.isFolder -> StackedSheets(Modifier.fillMaxSize(), empty = file.isEmptyFolder,
                    cover = cover?.let { { SpoilerThumbnail(it, isSpoilerBlurred, PosterSpoilerBlur, Modifier.fillMaxSize()) } })
                cover != null -> SpoilerThumbnail(cover, isSpoilerBlurred, PosterSpoilerBlur, Modifier.fillMaxSize(), showBlurIcon = !file.isVideo)
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
    val backPlate = colors.secondary.copy(alpha = 0.3f).compositeOver(colors.secondaryContainer)
    val frontShade = colors.secondary.copy(alpha = 0.16f).compositeOver(colors.secondaryContainer)
    BoxWithConstraints(modifier) {
        val cardTop = maxHeight * 0.07f
        val cardWidth = maxWidth * 0.81f
        val cardHeight = cardWidth * 9f / 16f
        val frontTop = maxHeight * 0.74f
        val visibleHeight = (frontTop - cardTop).coerceAtMost(cardHeight)
        Box(Modifier.fillMaxSize().clip(FolderBackShape).background(backPlate))
        if (!empty) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = cardTop)
                    .size(cardWidth, cardHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainerLowest),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (cover != null) {
                    cover()
                } else {
                    val iconSize = cardHeight * FOLDER_GLYPH_SIZE
                    Icon(
                        imageVector = PikoBrandIcons.Glyph,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(top = ((visibleHeight - iconSize) / 2).coerceAtLeast(0.dp)).size(iconSize),
                    )
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val contour = buildFolderFrontTopContourPath(size, this)
            for (offset in 1..3) {
                translate(top = -offset.dp.toPx()) {
                    drawPath(contour, Color.Black.copy(alpha = 0.045f / offset), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Box(
            Modifier.fillMaxSize().clip(FolderFrontShape)
                .background(Brush.verticalGradient(
                    listOf(colors.secondaryContainer, frontShade),
                    startY = with(androidx.compose.ui.platform.LocalDensity.current) { frontTop.toPx() },
                )),
        )
        Canvas(Modifier.fillMaxSize()) {
            drawPath(buildFolderFrontTopContourPath(size, this),
                Color.White.copy(alpha = if (dark) 0.18f else 0.45f), style = Stroke(1.dp.toPx()))
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
        Icon(Icons.Filled.PlayArrow, contentDescription = "视频", tint = Color.White, modifier = Modifier.size(24.dp))
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
private const val FOLDER_GLYPH_SIZE = 0.5f

private object FolderBackShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val r = with(density) { 12.dp.toPx() }.coerceAtMost(minOf(w, h) / 2)
        val tabHeight = h * 0.065f
        val tabEnd = w * 0.34f
        val shoulderStart = w * 0.48f
        val path = Path().apply {
            moveTo(0f, r)
            quadraticTo(0f, 0f, r, 0f)
            lineTo(tabEnd, 0f)
            cubicTo(tabEnd + w * 0.07f, 0f, shoulderStart - w * 0.07f, tabHeight, shoulderStart, tabHeight)
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
internal fun buildFolderFrontTopContourPath(size: Size, density: Density): Path {
    val w = size.width
    val h = size.height
    val yLeft = h * 0.78f
    val yRight = h * 0.74f
    val stepStart = w * 0.28f
    val stepEnd = w * 0.40f
    val r = minOf(with(density) { 6.dp.toPx() }, w * 0.14f, h * 0.08f)
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

internal fun buildFolderFrontPath(size: Size, density: Density): Path =
    buildFolderFrontTopContourPath(size, density).apply {
        val w = size.width
        val h = size.height
        val r = minOf(with(density) { 12.dp.toPx() }, w * 0.14f, h * 0.14f)
        lineTo(w, h - r)
        quadraticTo(w, h, w - r, h)
        lineTo(r, h)
        quadraticTo(0f, h, 0f, h - r)
        close()
    }

private object FolderFrontShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(buildFolderFrontPath(size, density))
}
