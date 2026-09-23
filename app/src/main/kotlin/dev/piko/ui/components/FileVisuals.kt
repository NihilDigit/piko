package dev.piko.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
                blur = ListSpoilerBlur,
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

/**
 * 防窥模糊的参数。decodePx 是 Coil 解码的目标边长，radius 是绘制时的高斯模糊半径。
 *
 * 半径按显示尺寸给，不按解码尺寸：RenderEffect 作用在已经放大到显示尺寸的图层上。
 * 两档都取显示宽度的约六分之一（列表 48dp 取 8dp，网格封面 130 到 200dp 取 24dp），
 * 这个比例下人脸与文字已不成形，只剩大块色调。解码尺寸取到 48 与 64px，
 * 比清晰图小一个数量级，高频细节在解码时就已丢掉，模糊只需要抹平放大后的块状边。
 */
@Immutable
class SpoilerBlur internal constructor(val decodePx: Int, val radius: Dp)

val ListSpoilerBlur = SpoilerBlur(decodePx = 48, radius = 8.dp)
val GridSpoilerBlur = SpoilerBlur(decodePx = 64, radius = 24.dp)

/**
 * 防窥缩略图。
 *
 * API 31 及以上：Coil 按 decodePx 解码小图，再用 Modifier.blur 做 RenderEffect 高斯模糊。
 * 模糊态单独给内存缓存键，避免与清晰图互相命中；磁盘缓存按 URL 共用，揭示时不重新下载。
 *
 * API 31 以下没有 RenderEffect，Modifier.blur 在那里什么都不做。防窥是隐私功能，
 * 降级不能变成露出原图，所以这一侧不请求缩略图，只画不透明的占位。
 *
 * 模糊的图层里只放图片。遮罩与图标是它的兄弟节点，按压、选中这些父级重绘不会让
 * 模糊图层失效。
 */
@Composable
fun SpoilerThumbnail(
    url: String,
    isBlurred: Boolean,
    onReveal: () -> Unit,
    blur: SpoilerBlur,
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

    val fixedColors = LocalFixedColors.current
    val supportsRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(
        modifier = modifier
            .clipToBounds()
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
        if (supportsRenderEffect) {
            BlurredThumbnail(url = url, blur = blur, modifier = Modifier.fillMaxSize())
            // 遮罩压低色块对比度，并给图标一个在任何底色上都成立的衬底
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f)),
            )
        }
        Icon(
            imageVector = Icons.Outlined.VisibilityOff,
            contentDescription = "预览已遮蔽",
            tint = if (supportsRenderEffect) fixedColors.OnMedia else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 用 Modifier.blur 而不是手写 graphicsLayer { renderEffect = BlurEffect(...) }：
 * 1.12 的 Modifier.blur 本身就是 graphicsLayer 的 lambda 形式，按 edgeTreatment 选
 * TileMode（Rectangle 对应 Clamp）并同时设 shape 与 clip = true。手写只是把这三件事
 * 再抄一遍。Clamp 把边缘像素向外延伸参与卷积，四周不会被透明像素拉出一圈暗边；
 * clip 把卷积溢出的部分裁在图层边界内。
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun BlurredThumbnail(url: String, blur: SpoilerBlur, modifier: Modifier) {
    val context = LocalPlatformContext.current
    val request = remember(url, blur.decodePx) {
        ImageRequest.Builder(context)
            .data(url)
            .size(blur.decodePx)
            .precision(Precision.EXACT)
            .memoryCacheKey("spoiler:${blur.decodePx}:$url")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.blur(blur.radius, edgeTreatment = BlurredEdgeTreatment.Rectangle),
    )
}
