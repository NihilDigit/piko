package dev.piko.ui.components

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Precision
import kotlinx.coroutines.CancellationException
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.DetailField
import dev.piko.shared.data.ThumbnailClarity
import dev.piko.shared.data.ThumbnailScale
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.data.isLocalMountRoot
import dev.piko.shared.data.isVaulted
import dev.piko.shared.i18n.enCount
import dev.piko.shared.upload.isUploading
import dev.piko.ui.format.formatDurationMillis
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.resolveCoilModel
import dev.piko.ui.screens.drive.LocalDriveAppearancePrefs
import dev.piko.ui.theme.LocalFixedColors
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.imageThumbnailUrlOf


/**
 * 扩展名的展示形式（大写，不带点），拿不到可信扩展名时返回 null。
 *
 * 长度卡在 6 以内：「Show.S01E05.1080p」这类名字里的点不是扩展名分隔符，
 * 截出来的「1080P」会被当成文件类型展示。
 */
fun FileStat.extensionLabel(): String? {
    return fileVisual().extensionLabel()
}

fun FileVisual.extensionLabel(): String? {
    if (isFolder) return null
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return null
    val ext = name.substring(dot + 1)
    if (ext.length > 6 || !ext.all(Char::isLetterOrDigit) || ext.all(Char::isDigit)) return null
    return ext.uppercase()
}

/**
 * 文件名是否带扩展名显示，来自偏好 showExtensionsFlow，由 PikoMainScaffold 提供。
 * 默认值两端不同，见那里的说明。
 */
val LocalShowExtensions = compositionLocalOf { false }

/**
 * 标题行显示的名字。不显示扩展名时去掉它，把宽度留给能区分文件的部分，类型已在副标题里单列。
 */
@Composable
@ReadOnlyComposable
fun FileStat.displayTitle(): String =
    if (!LocalShowExtensions.current && extensionLabel() != null) name.substringBeforeLast('.') else name

/** 图片在列表、画廊与查看器垫底用的缩略图地址：优先自带直链，其次按 GCID 走免鉴权图片缩略图。 */
fun FileStat.previewImageCoverUrl(): String =
    thumbnailLink.ifBlank { if (!isFolder && hash.isNotBlank()) imageThumbnailUrlOf(hash) else "" }

/** 从 FileStat 的远端/树元数据中提取有效播放时长（毫秒），无有效时长时返回 null。 */
fun FileStat.fileDurationMillis(): Long? =
    params["duration"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000.0).toLong() }?.takeIf { it > 0L }

/**
 * 按用户配置的 [fields] 顺序拼接列表行或卡片底部详情行各段；保留「上传中」「待保存」「本机挂载」等
 * 瞬态状态，其余元信息严格遵循 [fields] 顺序。经 [MetaRow] 渲染时统一应用等宽数字（`.tabular`）。
 */
fun FileStat.detailLineParts(
    fields: List<DetailField>,
    tags: List<String> = emptyList(),
    folderItemCount: Int? = null,
    folderDurationMillis: Long? = null,
    folderSizeBytes: Long? = null,
    folderHasVault: Boolean = false,
    includeParsedTags: Boolean = true,
): List<String> = buildList {
    if (isUploading) add(tr("上传中", "Uploading", "アップロード中", "업로드 중", "上傳中"))
    if (isImportPreview) add(tr("待保存", "Unsaved", "未保存", "저장 대기", "待儲存"))
    if (isLocalMountRoot) add(tr("本机挂载", "Local mount", "ローカルマウント", "로컬 마운트", "本機掛載"))
    for (field in fields) {
        when (field) {
            DetailField.SIZE -> {
                val bytes = if (isFolder) folderSizeBytes?.takeIf { it > 0L } else sizeBytes.takeIf { it >= 0L }
                if (bytes != null) add(bytes.toReadableSize())
            }
            DetailField.DURATION -> {
                val ms = if (isFolder) folderDurationMillis?.takeIf { it > 0L } else fileDurationMillis()
                if (ms != null) add(formatDurationMillis(ms))
            }
            DetailField.ADDED_TIME -> {
                val date = createdTime.ifEmpty { modifiedTime }.take(10)
                if (date.isNotEmpty()) add(date)
            }
            DetailField.PARSED_TAGS -> {
                if (includeParsedTags && tags.isNotEmpty()) {
                    addAll(tags.take(2))
                }
            }
            DetailField.ITEM_COUNT -> {
                if (isFolder && folderItemCount != null && folderItemCount >= 0) {
                    add(
                        tr(
                            "$folderItemCount 项",
                            enCount(folderItemCount, "item", "items"),
                            "${folderItemCount}項目",
                            "${folderItemCount}개 항목",
                            "$folderItemCount 項",
                        ),
                    )
                }
            }
            DetailField.EXTENSION -> {
                if (!isFolder) extensionLabel()?.let { add(it) }
            }
            DetailField.VAULT_STATUS -> {
                if (isVaulted) {
                    add(tr("已归档", "Archived", "アーカイブ済み", "보관됨", "已歸檔"))
                } else if (isFolder && folderHasVault) {
                    add(tr("含归档", "Has archived", "アーカイブ含む", "보관 포함", "含歸檔"))
                }
            }
        }
    }
}

/** 副标题的各段：文件为类型、大小、日期，文件夹为「文件夹」、日期。由 [MetaRow] 排成一行。 */
fun FileStat.metaParts(includeDate: Boolean = true): List<String> = buildList {
    if (isUploading) add(tr("上传中", "Uploading", "アップロード中", "업로드 중", "上傳中"))
    if (isImportPreview) add(tr("待保存", "Unsaved", "未保存", "저장 대기", "待儲存"))
    // 网盘里没有它的文件，打开时才临时造出来；与「上传中」同处，一眼能分出哪些不占空间
    if (isVaulted) add(tr("已归档", "Archived", "アーカイブ済み", "보관됨", "已歸檔"))
    if (isLocalMountRoot) add(tr("本机挂载", "Local mount", "ローカルマウント", "로컬 마운트", "本機掛載"))
    if (isFolder) {
        add(tr("文件夹", "Folder", "フォルダー", "폴더", "資料夾"))
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
    trailing: (@Composable () -> Unit)? = null,
) {
    val tabularStyle = style.tabular
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MetaPartSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { index, part ->
            Text(
                text = part,
                style = tabularStyle,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = if (index == parts.lastIndex) Modifier.weight(1f, fill = false) else Modifier,
            )
        }
        if (trailing != null) {
            trailing()
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

private fun FileVisual.kind(): FileKind = if (isFolder) FileKind.FOLDER else category.toKind()

private fun FileKind.icon(): ImageVector = when (this) {
    FileKind.FOLDER -> Icons.Filled.Folder
    FileKind.VIDEO -> Icons.Outlined.Movie
    FileKind.AUDIO -> Icons.Outlined.AudioFile
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.ARCHIVE -> Icons.Outlined.FolderZip
    FileKind.SUBTITLE -> Icons.Outlined.Subtitles
    FileKind.DOCUMENT -> Icons.Outlined.Description
}

fun FileStat.typeIcon(): ImageVector = fileVisual().typeIcon()

fun FileVisual.typeIcon(): ImageVector =
    if (isFolder && isLocalMount) Icons.Filled.Storage else kind().icon()

/** 只有文件名时的类型图标，如磁力解析结果。 */
fun fileNameTypeIcon(name: String): ImageVector = fileNameKind(name).icon()

/**
 * 没有缩略图的文件在海报墙里的底色与前景色，按大类分开。一律灰底时一排压缩包、文档、字幕连成一片，
 * 只能靠中间的小图标分辨；换成几种容器色，扫一眼就知道哪一格是哪一类。都取配色方案里的容器色，
 * 跟着动态取色与深浅主题走。
 */
@Composable
@ReadOnlyComposable
fun FileStat.placeholderColors(): Pair<Color, Color> {
    return fileVisual().placeholderColors()
}

@Composable
@ReadOnlyComposable
fun FileVisual.placeholderColors(): Pair<Color, Color> {
    if (LocalAppearance.current.isEInk) return Color.White to Color.Black
    val colors = MaterialTheme.colorScheme
    return when (kind()) {
        FileKind.VIDEO, FileKind.AUDIO -> colors.tertiaryContainer to colors.onTertiaryContainer
        FileKind.IMAGE -> colors.primaryContainer to colors.onPrimaryContainer
        FileKind.ARCHIVE, FileKind.FOLDER -> colors.secondaryContainer to colors.onSecondaryContainer
        FileKind.SUBTITLE, FileKind.DOCUMENT -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
}

/** 海报墙里没有缩略图的文件，封面区画的类型图标，见 WatermarkIcons。 */
fun FileStat.watermarkIcon(): ImageVector = fileVisual().watermarkIcon()

fun FileVisual.watermarkIcon(): ImageVector = when (kind()) {
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
    FileListLeadingVisual(
        file = file,
        modifier = modifier,
        size = size,
        isSpoilerBlurred = isSpoilerBlurred,
    )
}

/** 无缩略图时的类型图标块。 */
@Composable
fun FileTypeIcon(
    file: FileStat,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    FileTypeIcon(file.fileVisual(), iconSize, modifier)
}

@Composable
fun FileTypeIcon(file: FileVisual, iconSize: Dp, modifier: Modifier = Modifier) {
    val isEInk = LocalAppearance.current.isEInk
    val container = when {
        isEInk -> Color.White
        file.isFolder -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val tint = when {
        isEInk -> Color.Black
        file.isFolder -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val vector = when {
        isEInk && file.isFolder && file.isLocalMount -> Icons.Outlined.Storage
        isEInk && file.isFolder -> Icons.Outlined.Folder
        else -> file.typeIcon()
    }
    Box(
        modifier = modifier.background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = vector,
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
class SpoilerBlur internal constructor(val decodePx: Int, val radius: Dp, val preblurRadiusPx: Int)

val ListSpoilerBlur = SpoilerBlur(decodePx = 48, radius = 9.dp, preblurRadiusPx = 6)
val PosterSpoilerBlur = SpoilerBlur(decodePx = 64, radius = 24.dp, preblurRadiusPx = 7)

/**
 * 可选的缩略图绘制覆写钩子：仅由设置页实时预览提供，按合成 `model` 键渲染无网络依赖的原创矢量封面，
 * 使预览直接挂载真实的 `FileListItem` / `PosterCard` / `GalleryCanvasTile` 而不触发 Coil 网络请求。
 * 正常业务树中默认为 `null`，零额外开销。
 */
val LocalPreviewArtworkRenderer =
    staticCompositionLocalOf<(@Composable (model: Any, modifier: Modifier) -> Boolean)?> { null }

/**
 * 防窥缩略图。
 *
 * Coil 按 decodePx（48/64px）解码小图后，在后台解码线程经 [SpoilerBlurTransformation]（复用
 * 3 趟可分离盒式模糊）一次性预模糊并写入 Coil MemoryCache；绘制时直接渲染预模糊位图，
 * 避免列表/海报墙滚动期间每屏挂起 20~60 个实时 GPU `Modifier.blur` 离屏 RenderEffect，
 * 同时覆盖 Android 12 以下设备。
 *
 * 模糊态单独给内存缓存键，避免与清晰图互相命中；磁盘缓存按 URL 共用，揭示时不重新下载。
 * 若变换失败（不返回未模糊原图），保持不透明占位。
 */
@Composable
fun SpoilerThumbnail(
    /** Coil 能解析的任何来源：缩略图 URL、本地 File、SAF 的 content: URI。 */
    model: Any,
    isBlurred: Boolean,
    blur: SpoilerBlur,
    modifier: Modifier = Modifier,
    /**
     * 模糊时正中画一只闭着的眼睛。海报墙的视频传 false：那里正中是播放键，两个图标叠在一处；
     * 模糊本身已经说明预览遮住了。
     */
    showBlurIcon: Boolean = true,
    /** Decoded cover dimensions; shares the existing image request and cache. */
    onAspectRatio: ((Float) -> Unit)? = null,
    onError: (() -> Unit)? = null,
    /** 稳定内容标识（如 GCID / 文件 ID），用于含易变签名参数的外链生成确定性磁盘缓存键。 */
    stableId: String? = null,
    /** 封面缩放方式（裁剪填满或完整不裁剪），默认跟随 [LocalDriveAppearancePrefs]。 */
    thumbnailScale: ThumbnailScale = LocalDriveAppearancePrefs.current.thumbnailScale,
    /** 缩略图清晰度档位，默认跟随 [LocalDriveAppearancePrefs]。 */
    thumbnailClarity: ThumbnailClarity = LocalDriveAppearancePrefs.current.thumbnailClarity,
) {
    val previewRenderer = LocalPreviewArtworkRenderer.current
    if (previewRenderer != null && !isBlurred && previewRenderer(model, modifier)) {
        return
    }
    val context = LocalPlatformContext.current
    val platform = LocalPikoPlatform.current
    val contentScale = if (thumbnailScale == ThumbnailScale.FIT) ContentScale.Fit else ContentScale.Crop
    val fitBackdrop = thumbnailScale == ThumbnailScale.FIT

    if (!isBlurred) {
        val canUpgradeHighRes = remember(model, thumbnailClarity) {
            isUpgradedThumbnailClarityUrl(model, thumbnailClarity)
        }
        var highResFailed by remember(model, thumbnailClarity) { mutableStateOf(false) }
        val allowHighRes = canUpgradeHighRes && !highResFailed
        val request = remember(context, platform, model, thumbnailClarity, allowHighRes, stableId) {
            buildThumbnailImageRequest(
                context = context,
                platform = platform,
                rawModel = model,
                clarity = thumbnailClarity,
                allowHighResUpgrade = allowHighRes,
                stableId = stableId,
            )
        }
        val imageModifier = if (fitBackdrop) {
            modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
        } else {
            modifier
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = contentScale,
            modifier = imageModifier,
            onSuccess = { state ->
                val image = state.result.image
                if (image.width > 0 && image.height > 0) onAspectRatio?.invoke(image.width.toFloat() / image.height)
            },
            onError = { state ->
                if (!state.isCancellation()) {
                    if (allowHighRes) {
                        highResFailed = true
                    } else {
                        onError?.invoke()
                    }
                }
            },
        )
        return
    }

    val resolvedModel = remember(platform, model) { platform.resolveCoilModel(model) }
    val fixedColors = LocalFixedColors.current
    Box(
        modifier = modifier
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        BlurredThumbnail(
            model = resolvedModel,
            rawModel = model,
            stableId = stableId,
            blur = blur,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onAspectRatio = onAspectRatio,
            onError = onError,
        )
        // 遮罩压低色块对比度，并给图标一个在任何底色上都成立的衬底
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f)),
        )
        if (showBlurIcon) {
            Icon(
                imageVector = Icons.Outlined.VisibilityOff,
                contentDescription = tr("预览已遮蔽", "Preview hidden", "プレビューを非表示にしています", "미리 보기가 가려졌습니다", "預覽已遮蔽"),
                tint = fixedColors.OnMedia,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 在 Coil 后台解码流水线中对小缩略图一次性完成预模糊并缓存，不在主渲染线程挂实时 `Modifier.blur`。
 */
@Composable
private fun BlurredThumbnail(
    model: Any,
    rawModel: Any,
    stableId: String?,
    blur: SpoilerBlur,
    contentScale: ContentScale,
    modifier: Modifier,
    onAspectRatio: ((Float) -> Unit)? = null,
    onError: (() -> Unit)? = null,
) {
    val context = LocalPlatformContext.current
    val platform = LocalPikoPlatform.current
    val request = remember(context, platform, model, rawModel, stableId, blur) {
        val diskKey = thumbnailDiskCacheKey(rawModel, ThumbnailClarity.STANDARD, allowUpgrade = false, stableId = stableId)
        val memoryKey = thumbnailMemoryCacheKey(rawModel, ThumbnailClarity.STANDARD, blurredDecodePx = blur.decodePx, stableId = stableId)
        ImageRequest.Builder(context)
            .data(model)
            .size(blur.decodePx)
            .precision(Precision.EXACT)
            .transformations(SpoilerBlurTransformation(platform, blur.preblurRadiusPx))
            .memoryCacheKey(memoryKey)
            .apply {
                if (diskKey != null) diskCacheKey(diskKey)
            }
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
        onSuccess = { state ->
            val image = state.result.image
            if (image.width > 0 && image.height > 0) onAspectRatio?.invoke(image.width.toFloat() / image.height)
        },
        onError = { state -> if (!state.isCancellation()) onError?.invoke() },
    )
}

private fun AsyncImagePainter.State.Error.isCancellation(): Boolean =
    result.throwable is CancellationException || result.throwable.cause is CancellationException
