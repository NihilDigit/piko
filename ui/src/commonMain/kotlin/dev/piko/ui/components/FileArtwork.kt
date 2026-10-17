package dev.piko.ui.components

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

/**
 * 文件夹的封面，照实物文件夹画：后板与左上角的页签，前板压低到下半截，里面装着的东西从上半截露出来，
 * 与 PikPak 官方客户端同一个意思。整个仍是 16:9 的高度，网格照样对齐。
 *
 * 露出来的是封面图（[cover]）的上半张；没有封面时是一张带 Piko 图标的占位纸；空文件夹（[empty]，列过才知道）
 * 什么也不露。原先封面铺满整块、只在左下角挂一个文件夹小标，与视频只差那一个小标，分不出来。
 */
@Composable
private fun StackedSheets(
    modifier: Modifier = Modifier,
    empty: Boolean = false,
    cover: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val sheetShape = MaterialTheme.shapes.small
    // 后板比前板深一档，看得出前后
    val backPlate = colors.secondary.copy(alpha = 0.3f).compositeOver(colors.secondaryContainer)
    // 光从上方来：前板上沿亮、下沿暗，前板在纸上投一道影。没有这两样，几块平涂的色块叠在一起看不出前后
    val frontLit = colors.secondaryContainer
    val frontShade = colors.secondary.copy(alpha = 0.16f).compositeOver(colors.secondaryContainer)
    BoxWithConstraints(modifier) {
        // 从斜上方俯视：前板向外翻开，上沿离眼睛近，比下沿宽；后板在更远处，两侧各收进一截。
        // 正对着画时前板、后板一样宽，看着像一块立着的牌子，里面那张纸就没有地方放
        val backInset = maxWidth * FOLDER_PERSPECTIVE_INSET
        val backWidth = maxWidth - backInset * 2
        // 装在里面的卡片收窄到后板的一部分，按封面本来的 16:9 定高，前板盖住它的下面三成：
        // 封面露出七成，认得出画的是什么，前板也还有足够的高度像个文件夹
        val cardTop = FOLDER_TAB_HEIGHT + 8.dp
        val cardWidth = backWidth * FOLDER_CARD_WIDTH
        val cardHeight = cardWidth * 9f / 16f
        val visibleHeight = cardHeight * FOLDER_CARD_VISIBLE
        val frontTop = cardTop + visibleHeight
        // 后板与左上角的页签
        Box(
            Modifier
                .padding(start = backInset)
                .width(backWidth * FOLDER_TAB_WIDTH)
                .height(FOLDER_TAB_HEIGHT * 2)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(backPlate),
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = backInset, end = backInset, top = FOLDER_TAB_HEIGHT)
                .clip(shape)
                .background(backPlate),
        )
        if (!empty) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = cardTop)
                    .size(cardWidth, cardHeight)
                    .clip(sheetShape)
                    .background(colors.surfaceContainerLowest),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (cover != null) {
                    cover()
                } else {
                    // 没有封面时照官方客户端露一张占位纸，纸上是 Piko 的图标，居中于露出来的那一截
                    val iconSize = cardHeight * FOLDER_GLYPH_SIZE
                    Icon(
                        imageVector = PikoBrandIcons.Glyph,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(top = ((visibleHeight - iconSize) / 2).coerceAtLeast(0.dp)).size(iconSize),
                    )
                }
                // 前板落在纸上的影子，贴着前板上沿往上渐淡。卡片很窄时露出的一截比影子还矮，影子就只占那一截
                val shadowHeight = minOf(FOLDER_SHADOW_HEIGHT, visibleHeight)
                Box(
                    Modifier
                        .padding(top = visibleHeight - shadowHeight)
                        .fillMaxWidth()
                        .height(shadowHeight)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)))),
                )
            }
        }
        // 前板压到下面，上沿照实物文件夹的前板做一道斜上去的台阶，不是一条平线：平的看着像一块板子横在那儿，没有翻开的样子
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = (frontTop - FOLDER_FRONT_STEP).coerceAtLeast(0.dp))
                .clip(FolderFrontShape(FOLDER_FRONT_STEP, 12.dp, FOLDER_PERSPECTIVE_INSET))
                .background(Brush.verticalGradient(listOf(frontLit, frontShade))),
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
private val FOLDER_TAB_HEIGHT = 8.dp

// 装在里面的卡片占后板宽度的比例，与它露出前板的比例
private const val FOLDER_CARD_WIDTH = 0.76f
private const val FOLDER_CARD_VISIBLE = 0.7f

// 前板上沿台阶的高差
private val FOLDER_FRONT_STEP = 10.dp

// 俯视的透视量：后板与前板下沿各边收进的宽度比例
private const val FOLDER_PERSPECTIVE_INSET = 0.05f

// 占位纸上 Piko 图标的边长，按纸高算
private const val FOLDER_GLYPH_SIZE = 0.5f

// 前板投在纸上的影子高度
private val FOLDER_SHADOW_HEIGHT = 14.dp

/**
 * 文件夹前板：左边一段低 [step]，到三成宽处斜着升上去，右边一段高，四角圆 [corner]。
 * 照实物文件夹前板的样子，也与 PikPak 官方客户端的文件夹同一个轮廓。
 * 下沿两端各收进宽度的 [bottomInset]，成上宽下窄的梯形，是翻开的前板从上方看去的透视。
 */
private class FolderFrontShape(private val step: Dp, private val corner: Dp, private val bottomInset: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val s = with(density) { step.toPx() }
        val r = with(density) { corner.toPx() }.coerceAtMost(h / 2)
        val inset = w * bottomInset
        val slantStart = w * 0.28f
        val slantEnd = w * 0.34f
        // 斜边上离两端各 r 的点，圆角从这里起落。左边从台阶的低处起，右边从顶上起，斜率不同
        fun leftX(y: Float) = inset * (y - s) / (h - s)
        fun rightX(y: Float) = w - inset * y / h
        val path = Path().apply {
            moveTo(leftX(s + r), s + r)
            quadraticTo(0f, s, r, s)
            lineTo(slantStart, s)
            lineTo(slantEnd, 0f)
            lineTo(w - r, 0f)
            quadraticTo(w, 0f, rightX(r), r)
            lineTo(rightX(h - r), h - r)
            quadraticTo(w - inset, h, w - inset - r, h)
            lineTo(inset + r, h)
            quadraticTo(inset, h, leftX(h - r), h - r)
            close()
        }
        return Outline.Generic(path)
    }
}
private const val FOLDER_TAB_WIDTH = 0.34f
// 一直显示按钮时（多选、触屏）给标题让出的宽度：28dp 的按钮加一点间隙

// 两行 bodyMedium，行高各 20dp
