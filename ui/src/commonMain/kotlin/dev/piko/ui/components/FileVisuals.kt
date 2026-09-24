package dev.piko.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.LocalFixedColors
import io.github.nihildigit.pikpak.FileStat


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

/** 副标题的各段：文件为类型、大小、日期，文件夹为「文件夹」、日期。由 [MetaRow] 排成一行。 */
fun FileStat.metaParts(includeDate: Boolean = true): List<String> = buildList {
    if (isFolder) {
        add("文件夹")
    } else {
        extensionLabel()?.let { add(it) }
        add(sizeBytes.toReadableSize())
    }
    if (includeDate && modifiedTime.isNotEmpty()) add(modifiedTime.take(10))
}

// 元数据各段之间的间距，取 M3 列表项内部元素间距 12dp
private val MetaPartSpacing = 12.dp

/**
 * 把几段元数据排成一行，段与段之间只靠间距分开，不插分隔符。
 *
 * 宽度不够时（窄屏或大字号）只让最后一段省略，前面的类型与大小总是完整的。
 * 文字样式与颜色默认继承所在槽位，放进 ListItem 的 supportingContent 时自动是副文本样式。
 */
@Composable
fun MetaRow(
    parts: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MetaPartSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { index, part ->
            Text(
                text = part,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = if (index == parts.lastIndex) Modifier.weight(1f, fill = false) else Modifier,
            )
        }
    }
}

private enum class FileKind { FOLDER, VIDEO, AUDIO, IMAGE, ARCHIVE, SUBTITLE, DOCUMENT }

/** 只看扩展名。磁力解析出的文件没有 mime 与元数据，只有名字。 */
private fun fileNameKind(name: String): FileKind = name.fileCategory().toKind()

private fun FileCategory.toKind(): FileKind = when (this) {
    FileCategory.VIDEO -> FileKind.VIDEO
    FileCategory.AUDIO -> FileKind.AUDIO
    FileCategory.IMAGE -> FileKind.IMAGE
    FileCategory.ARCHIVE -> FileKind.ARCHIVE
    FileCategory.SUBTITLE -> FileKind.SUBTITLE
    FileCategory.DOCUMENT -> FileKind.DOCUMENT
}

/** 文件大类的图标，秒传面板的按类勾选也用它。 */
fun FileCategory.icon(): ImageVector = toKind().icon()

private fun FileStat.kind(): FileKind = if (isFolder) FileKind.FOLDER else fileCategory().toKind()

private fun FileKind.icon(): ImageVector = when (this) {
    FileKind.FOLDER -> Icons.Filled.Folder
    FileKind.VIDEO -> Icons.Outlined.Movie
    FileKind.AUDIO -> Icons.Outlined.AudioFile
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.ARCHIVE -> Icons.Outlined.FolderZip
    FileKind.SUBTITLE -> Icons.Outlined.Subtitles
    FileKind.DOCUMENT -> Icons.Outlined.Description
}

fun FileStat.typeIcon(): ImageVector = kind().icon()

/** 只有文件名时的类型图标，如磁力解析结果。 */
fun fileNameTypeIcon(name: String): ImageVector = fileNameKind(name).icon()

/** 海报墙里没有缩略图的文件，封面区画的类型图标，见 WatermarkIcons。 */
fun FileStat.watermarkIcon(): ImageVector = when (kind()) {
    FileKind.FOLDER -> WatermarkIcons.Folder
    FileKind.VIDEO -> WatermarkIcons.Movie
    FileKind.AUDIO -> WatermarkIcons.AudioFile
    FileKind.IMAGE -> WatermarkIcons.Image
    FileKind.ARCHIVE -> WatermarkIcons.FolderZip
    FileKind.SUBTITLE -> WatermarkIcons.Subtitles
    FileKind.DOCUMENT -> WatermarkIcons.Description
}

/**
 * 网盘文件的前导图形：文件夹、无缩略图的文件、有缩略图的文件三种外观。
 *
 * 文件夹与文件的区分不只靠颜色：文件夹用实心图标压在 secondaryContainer 上，文件用
 * 描边图标压在 surfaceContainerHighest 上。动态取色下这两种容器色可能很接近，
 * 实心与描边的形状差异在任何配色下都成立。
 */
@Composable
fun FileLeadingVisual(
    file: FileStat,
    isSpoilerBlurred: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = ListLeadingSize,
) {
    ListLeadingMedia(
        thumbnail = file.thumbnailLink.takeIf { !file.isFolder && it.isNotEmpty() },
        fallback = { FileTypeIcon(file = file, iconSize = ListLeadingIconSize, modifier = Modifier.fillMaxSize()) },
        isSpoilerBlurred = isSpoilerBlurred,
        size = size,
        modifier = modifier,
    )
}

/** 无缩略图时的类型图标块。 */
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
 * 列表 56dp 取 9dp，约为显示宽度的六分之一，这个比例下人脸与文字已不成形，只剩大块色调。
 * 海报封面 150 到约 330dp 取 24dp，宽的一端比例低到十二分之一，实测照样认不出人脸：
 * 64px 的解码尺寸已先把细节抹掉。解码尺寸取到 48 与 64px，
 * 比清晰图小一个数量级，高频细节在解码时就已丢掉，模糊只需要抹平放大后的块状边。
 */
@Immutable
class SpoilerBlur internal constructor(val decodePx: Int, val radius: Dp)

val ListSpoilerBlur = SpoilerBlur(decodePx = 48, radius = 9.dp)
val PosterSpoilerBlur = SpoilerBlur(decodePx = 64, radius = 24.dp)

/**
 * 防窥缩略图。
 *
 * 平台支持模糊时（Android 12 起与桌面端）：Coil 按 decodePx 解码小图，再用 Modifier.blur 做 RenderEffect 高斯模糊。
 * 模糊态单独给内存缓存键，避免与清晰图互相命中；磁盘缓存按 URL 共用，揭示时不重新下载。
 *
 * Android 12 以下没有 RenderEffect，Modifier.blur 在那里什么都不做。防窥是隐私功能，
 * 降级不能变成露出原图，所以这一侧不请求缩略图，只画不透明的占位。
 *
 * 模糊的图层里只放图片。遮罩与图标是它的兄弟节点，按压、选中这些父级重绘不会让
 * 模糊图层失效。
 *
 * 本身不接收点击，单击归所在的行或卡片。原先点缩略图只揭示、点别处才打开，同一张
 * 卡片上两块区域看不出分界，文件夹还要多点一次才能进入。逐项揭示改由操作菜单提供。
 */
@Composable
fun SpoilerThumbnail(
    /** Coil 能解析的任何来源：缩略图 URL、本地 File、SAF 的 content: URI。 */
    model: Any,
    isBlurred: Boolean,
    blur: SpoilerBlur,
    modifier: Modifier = Modifier,
) {
    if (!isBlurred) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        return
    }

    val fixedColors = LocalFixedColors.current
    val supportsRenderEffect = LocalPikoPlatform.current.supportsBlur
    Box(
        modifier = modifier
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (supportsRenderEffect) {
            BlurredThumbnail(model = model, blur = blur, modifier = Modifier.fillMaxSize())
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
@Composable
private fun BlurredThumbnail(model: Any, blur: SpoilerBlur, modifier: Modifier) {
    val context = LocalPlatformContext.current
    val request = remember(model, blur.decodePx) {
        ImageRequest.Builder(context)
            .data(model)
            .size(blur.decodePx)
            .precision(Precision.EXACT)
            .memoryCacheKey("spoiler:${blur.decodePx}:$model")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.blur(blur.radius, edgeTreatment = BlurredEdgeTreatment.Rectangle),
    )
}
