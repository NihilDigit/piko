package dev.piko.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.ui.theme.LocalFixedColors
import io.github.nihildigit.pikpak.FileStat

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "ape")
private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "xz", "bz2")

/**
 * 扩展名的展示形式（大写，不带点），拿不到可信扩展名时返回 null。
 *
 * 长度卡在 6 以内：「Show.S01E05.1080p」这类名字里的点不是扩展名分隔符，
 * 截出来的「1080P」会被当成文件类型展示。
 */
fun FileStat.extensionLabel(): String? {
    if (isFolder) return null
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return null
    val ext = name.substring(dot + 1)
    if (ext.length > 6 || !ext.all(Char::isLetterOrDigit) || ext.all(Char::isDigit)) return null
    return ext.uppercase()
}

/** 标题行显示的名字。扩展名已在副标题里单列，标题去掉它，把宽度留给能区分文件的部分。 */
fun FileStat.displayTitle(): String =
    if (extensionLabel() != null) name.substringBeforeLast('.') else name

/** 副标题：文件为「类型 · 大小 · 日期」，文件夹为「文件夹 · 日期」。 */
fun FileStat.metaLine(includeDate: Boolean = true): String = buildString {
    if (isFolder) {
        append("文件夹")
    } else {
        extensionLabel()?.let { append(it).append(" · ") }
        append(sizeBytes.toReadableSize())
    }
    if (includeDate && modifiedTime.isNotEmpty()) {
        append(" · ")
        append(modifiedTime.take(10))
    }
}

fun FileStat.typeIcon(): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        isFolder -> Icons.Filled.Folder
        isPlayableVideo() -> Icons.Outlined.Movie
        ext in AUDIO_EXTENSIONS -> Icons.Outlined.AudioFile
        isPreviewableImage() -> Icons.Outlined.Image
        ext in ARCHIVE_EXTENSIONS -> Icons.Outlined.FolderZip
        else -> Icons.Outlined.Description
    }
}

/**
 * 列表行与网格卡片共用的前导图形：文件夹、无缩略图的文件、有缩略图的文件三种外观。
 *
 * 文件夹与文件的区分不只靠颜色：文件夹用实心图标压在 secondaryContainer 上，文件用
 * 描边图标压在 surfaceContainerHighest 上。动态取色下这两种容器色可能很接近，
 * 实心与描边的形状差异在任何配色下都成立。
 */
@Composable
fun FileLeadingVisual(
    file: FileStat,
    isSpoilerBlurred: Boolean,
    onToggleSpoiler: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (!file.isFolder && file.thumbnailLink.isNotEmpty()) {
            SpoilerThumbnail(
                url = file.thumbnailLink,
                isBlurred = isSpoilerBlurred,
                onReveal = onToggleSpoiler,
                blurredSamplePx = LIST_BLUR_SAMPLE_PX,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            FileTypeIcon(file = file, iconSize = size * 0.5f, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 无缩略图时的类型图标块，网格卡片的封面区也用它。 */
@Composable
fun FileTypeIcon(
    file: FileStat,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val container = if (file.isFolder) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val tint = if (file.isFolder) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier.background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = file.typeIcon(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

// 模糊态解码的目标宽度（像素）。列表缩略图约 144px 宽，缩到 12px 后每个源像素被放大
// 约 12 倍；网格封面约 500px 宽，取 24px。数值越小越糊，也越难辨认内容。
internal const val LIST_BLUR_SAMPLE_PX = 12
internal const val GRID_BLUR_SAMPLE_PX = 24

/**
 * 防窥缩略图。
 *
 * 模糊不用 Modifier.blur：它依赖 RenderEffect，API 31 以下直接不生效，minSdk 26 上
 * 等于把原图原样露出来；API 31 以上每帧都要在 GPU 上跑一次高斯核，滚动时成本随可见
 * 项数叠加。这里改为让 Coil 把缩略图解码成十几像素宽的小图，再由绘制时的双线性
 * 插值放大，得到的是柔和的色块。效果在所有 API 级别一致，没有逐帧成本，解码出的位图
 * 也只有几百字节。
 *
 * 模糊态单独给一个内存缓存键，避免与清晰图互相命中；磁盘缓存按 URL 共用，揭示时
 * 不会重新下载。
 */
@Composable
fun SpoilerThumbnail(
    url: String,
    isBlurred: Boolean,
    onReveal: () -> Unit,
    blurredSamplePx: Int,
    modifier: Modifier = Modifier,
    revealOnClick: Boolean = true,
) {
    if (!isBlurred) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        return
    }

    val context = LocalPlatformContext.current
    val request = remember(url, blurredSamplePx) {
        ImageRequest.Builder(context)
            .data(url)
            .size(blurredSamplePx)
            .precision(Precision.EXACT)
            .memoryCacheKey("spoiler:$blurredSamplePx:$url")
            .build()
    }
    val fixedColors = LocalFixedColors.current
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                if (revealOnClick) {
                    Modifier.clickable(onClickLabel = "显示预览", onClick = onReveal)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 遮罩压低色块对比度，并给图标一个在任何底色上都成立的衬底
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f)),
        )
        Icon(
            imageVector = Icons.Outlined.VisibilityOff,
            contentDescription = "预览已遮蔽",
            tint = fixedColors.OnMedia,
            modifier = Modifier.size(20.dp),
        )
    }
}
