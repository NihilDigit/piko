package dev.piko.ui.components

import dev.piko.shared.data.DriveAppearancePrefs
import dev.piko.shared.data.FolderStyle
import dev.piko.shared.i18n.tr
import dev.piko.ui.screens.drive.LocalDriveAppearancePrefs
import io.github.nihildigit.pikpak.FileStat

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
import dev.piko.ui.theme.isEInk
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import dev.piko.ui.theme.LocalFixedColors

const val FILE_LEADING_SKEUOMORPHIC_FOLDER_TAG = "file_leading_skeuomorphic_folder"
const val FILE_LEADING_MINIMAL_FOLDER_TAG = "file_leading_minimal_folder"
const val FILE_LEADING_MEDIA_TAG = "file_leading_media"
const val FILE_POSTER_SKEUOMORPHIC_FOLDER_TAG = "file_poster_skeuomorphic_folder"
const val FILE_POSTER_MINIMAL_FOLDER_TAG = "file_poster_minimal_folder"

fun resolveLeadingFolderStyle(prefs: DriveAppearancePrefs): FolderStyle = prefs.list.folderStyle

fun resolvePosterFolderStyle(prefs: DriveAppearancePrefs): FolderStyle = prefs.grid.folderStyle

/** 水墨屏模式下统一退化为高对比度极简矢量图标，不绘制多层灰度渐变拟物文件夹。 */
fun resolveEffectiveFolderStyle(preferred: FolderStyle, isEInk: Boolean): FolderStyle =
    if (isEInk) FolderStyle.MINIMAL_ICON else preferred

fun leadingIconSizeFor(containerSize: Dp): Dp = when {
    containerSize >= 52.dp -> ListLeadingIconSize
    containerSize >= 40.dp -> 22.dp
    containerSize >= 34.dp -> 20.dp
    containerSize >= 28.dp -> 18.dp
    else -> 16.dp
}

fun leadingCornerRadiusFor(containerSize: Dp): Dp = when {
    containerSize >= 40.dp -> 8.dp
    containerSize >= 28.dp -> 6.dp
    else -> 5.dp
}

fun leadingSkeuomorphicPaddingFor(containerSize: Dp): Dp = when {
    containerSize >= 52.dp -> 3.dp
    containerSize >= 40.dp -> 2.dp
    containerSize >= 28.dp -> 1.5.dp
    else -> 1.dp
}

/**
 * 全应用列表、目录树、文件夹选择器与面板共用的单权威文件/文件夹前导视觉组件。
 * 始终读取 [LocalDriveAppearancePrefs.current.list]（含 `folderStyle`、`thumbnailScale`、`thumbnailClarity`），
 * 确保网盘列表、链接解析、文件夹选择、传输列表、分享与空间分布保持统一外观。
 */
@Composable
fun FileListLeadingVisual(
    file: FileStat,
    modifier: Modifier = Modifier,
    size: Dp = ListLeadingSize,
    isSpoilerBlurred: Boolean = false,
    isEmptyFolder: Boolean = false,
    allowThumbnail: Boolean = true,
    showMinimalFolderBadge: Boolean = true,
    showPlayOverlay: Boolean = false,
) {
    val visual = remember(file, isEmptyFolder) { file.fileVisual(emptyFolder = isEmptyFolder) }
    FileListLeadingVisual(
        file = visual,
        modifier = modifier,
        size = size,
        isSpoilerBlurred = isSpoilerBlurred,
        allowThumbnail = allowThumbnail,
        showMinimalFolderBadge = showMinimalFolderBadge,
        showPlayOverlay = showPlayOverlay,
    )
}

/**
 * 仅有文件夹名称（尚未持有完整 [FileStat]）时的单权威列表前导视觉组件，
 * 同样严格遵循 [LocalDriveAppearancePrefs.current.list.folderStyle]。
 */
@Composable
fun FolderListLeadingVisual(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = ListLeadingSize,
    isEmptyFolder: Boolean = false,
    isLocalMount: Boolean = false,
    thumbnail: Any? = null,
    isSpoilerBlurred: Boolean = false,
    showMinimalFolderBadge: Boolean = true,
) {
    val visual = remember(name, isEmptyFolder, isLocalMount, thumbnail) {
        FileVisual(
            name = name,
            isFolder = true,
            thumbnail = thumbnail,
            isEmptyFolder = isEmptyFolder,
            isLocalMount = isLocalMount,
        )
    }
    FileListLeadingVisual(
        file = visual,
        modifier = modifier,
        size = size,
        isSpoilerBlurred = isSpoilerBlurred,
        showMinimalFolderBadge = showMinimalFolderBadge,
    )
}

/**
 * 仅有文件名（如传输任务、解压或云端保存记录）时的单权威无缩略图前导视觉块：
 * 有可信扩展名时按文件大类容器色与图标渲染，无扩展名时回退至 [fallbackIcon]（若提供），并在水墨屏下绘制 `1.dp` 描边。
 */
@Composable
fun FileListLeadingVisual(
    fileName: String,
    modifier: Modifier = Modifier,
    size: Dp = ListLeadingSize,
    fallbackIcon: ImageVector? = null,
) {
    val visual = remember(fileName) { FileVisual(fileName) }
    val hasExt = remember(fileName) { visual.extensionLabel() != null }
    val isEInk = LocalAppearance.current.isEInk
    val shape = RoundedCornerShape(leadingCornerRadiusFor(size))
    val iconSize = leadingIconSizeFor(size)
    val (container, content) = when {
        isEInk -> Color.White to Color.Black
        hasExt -> visual.placeholderColors()
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(size)
            .then(if (isEInk) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
            .clip(shape)
            .background(container)
            .testTag(FILE_LEADING_MEDIA_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (hasExt) visual.typeIcon() else (fallbackIcon ?: visual.typeIcon()),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun FileListLeadingVisual(
    file: FileVisual,
    modifier: Modifier = Modifier,
    size: Dp = ListLeadingSize,
    isSpoilerBlurred: Boolean = false,
    allowThumbnail: Boolean = true,
    showMinimalFolderBadge: Boolean = true,
    showPlayOverlay: Boolean = false,
    folderStyle: FolderStyle = resolveLeadingFolderStyle(LocalDriveAppearancePrefs.current),
    watchProgress: Float? = null,
) {
    val isEInk = LocalAppearance.current.isEInk
    val effectiveFolderStyle = resolveEffectiveFolderStyle(folderStyle, isEInk)
    val effectiveThumbnail = if (allowThumbnail) file.thumbnail else null
    val iconSize = leadingIconSizeFor(size)
    val shape = RoundedCornerShape(leadingCornerRadiusFor(size))
    when {
        file.isFolder && effectiveFolderStyle == FolderStyle.SKEUOMORPHIC -> {
            var coverFailed by remember(effectiveThumbnail) { mutableStateOf(false) }
            val cover = effectiveThumbnail?.takeUnless { coverFailed }
            Box(
                modifier = modifier
                    .size(size)
                    .padding(leadingSkeuomorphicPaddingFor(size))
                    .testTag(FILE_LEADING_SKEUOMORPHIC_FOLDER_TAG),
            ) {
                StackedSheets(
                    Modifier.fillMaxSize(),
                    empty = file.isEmptyFolder,
                    cover = cover?.let {
                        {
                            SpoilerThumbnail(
                                model = it,
                                isBlurred = isSpoilerBlurred,
                                blur = ListSpoilerBlur,
                                modifier = Modifier.fillMaxSize(),
                                onError = { coverFailed = true },
                                stableId = file.stableId,
                            )
                        }
                    },
                )
            }
        }
        file.isFolder && effectiveFolderStyle == FolderStyle.MINIMAL_ICON -> {
            var coverFailed by remember(effectiveThumbnail) { mutableStateOf(false) }
            val cover = effectiveThumbnail?.takeUnless { coverFailed }
            Box(
                modifier = modifier
                    .size(size)
                    .then(if (isEInk) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
                    .clip(shape)
                    .testTag(FILE_LEADING_MINIMAL_FOLDER_TAG),
                contentAlignment = Alignment.Center,
            ) {
                if (cover == null) {
                    FileTypeIcon(file, iconSize, Modifier.fillMaxSize())
                } else {
                    SpoilerThumbnail(
                        model = cover,
                        isBlurred = isSpoilerBlurred,
                        blur = ListSpoilerBlur,
                        modifier = Modifier.fillMaxSize(),
                        onError = { coverFailed = true },
                        stableId = file.stableId,
                    )
                    if (showMinimalFolderBadge) {
                        MinimalFolderBadge(
                            compact = true,
                            mini = size <= 36.dp,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
                        )
                    }
                }
            }
        }
        else -> Box(modifier = modifier.size(size)) {
            ListLeadingMedia(
                thumbnail = effectiveThumbnail,
                fallback = { FileTypeIcon(file, iconSize, Modifier.fillMaxSize()) },
                isSpoilerBlurred = isSpoilerBlurred,
                showPlayOverlay = showPlayOverlay,
                size = size,
                shape = shape,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isEInk) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
                    .testTag(FILE_LEADING_MEDIA_TAG),
                stableId = file.stableId,
            )
            if (watchProgress != null && watchProgress > 0f) {
                WatchProgressStrip(
                    progress = watchProgress,
                    modifier = Modifier.align(Alignment.BottomCenter).clip(shape),
                )
            }
        }
    }
}

enum class FileArtworkFormat { Icon, Leading, Poster }

@Composable
fun FileArtwork(
    file: FileVisual,
    format: FileArtworkFormat,
    isSpoilerBlurred: Boolean = false,
    modifier: Modifier = Modifier,
    folderStyle: FolderStyle = when (format) {
        FileArtworkFormat.Poster -> resolvePosterFolderStyle(LocalDriveAppearancePrefs.current)
        else -> resolveLeadingFolderStyle(LocalDriveAppearancePrefs.current)
    },
    /** 列表前导视觉的边长；仅 [FileArtworkFormat.Leading] 使用。 */
    leadingSize: Dp = ListLeadingSize,
    /** 极简图标模式下有封面文件夹是否在封面内部绘制轻巧文件夹角标；`MediaCardScaffold` 会在左下角徽标行自行并排绘制，避免重叠。 */
    showMinimalFolderBadge: Boolean = true,
    /** 海报封面中央是否叠加视频播放圆钮；瀑布流默认关闭以免满屏视频重复叠加。 */
    showVideoPlayMark: Boolean = true,
    onAspectRatio: ((Float) -> Unit)? = null,
    onCoverError: (() -> Unit)? = null,
) {
    val isEInk = LocalAppearance.current.isEInk
    val effectiveFolderStyle = resolveEffectiveFolderStyle(folderStyle, isEInk)
    when (format) {
        FileArtworkFormat.Icon -> Icon(file.typeIcon(), null, modifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        FileArtworkFormat.Leading -> FileListLeadingVisual(
            file = file,
            modifier = modifier,
            size = leadingSize,
            isSpoilerBlurred = isSpoilerBlurred,
            showMinimalFolderBadge = showMinimalFolderBadge,
            folderStyle = effectiveFolderStyle,
        )
        FileArtworkFormat.Poster -> Box(modifier) {
            var coverFailed by remember(file.thumbnail) { mutableStateOf(false) }
            val cover = file.thumbnail?.takeUnless { coverFailed }
            val handleError: () -> Unit = {
                coverFailed = true
                onCoverError?.invoke()
            }
            when {
                file.isFolder && effectiveFolderStyle == FolderStyle.SKEUOMORPHIC -> StackedSheets(
                    Modifier.fillMaxSize().testTag(FILE_POSTER_SKEUOMORPHIC_FOLDER_TAG),
                    empty = file.isEmptyFolder,
                    cover = cover?.let {
                        {
                            SpoilerThumbnail(
                                model = it,
                                isBlurred = isSpoilerBlurred,
                                blur = PosterSpoilerBlur,
                                modifier = Modifier.fillMaxSize(),
                                onAspectRatio = onAspectRatio,
                                onError = handleError,
                                stableId = file.stableId,
                            )
                        }
                    },
                )
                cover != null -> {
                    val coverMod = if (file.isFolder && effectiveFolderStyle == FolderStyle.MINIMAL_ICON) {
                        Modifier.fillMaxSize().testTag(FILE_POSTER_MINIMAL_FOLDER_TAG)
                    } else {
                        Modifier.fillMaxSize()
                    }
                    SpoilerThumbnail(
                        model = cover,
                        isBlurred = isSpoilerBlurred,
                        blur = PosterSpoilerBlur,
                        modifier = coverMod,
                        showBlurIcon = !(file.isVideo && showVideoPlayMark),
                        onAspectRatio = onAspectRatio,
                        onError = handleError,
                        stableId = file.stableId,
                    )
                    if (file.isFolder && effectiveFolderStyle == FolderStyle.MINIMAL_ICON && showMinimalFolderBadge) {
                        MinimalFolderBadge(
                            compact = false,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                        )
                    }
                }
                else -> TypePlaceholder(
                    file,
                    if (file.isFolder && effectiveFolderStyle == FolderStyle.MINIMAL_ICON) {
                        Modifier.fillMaxSize().testTag(FILE_POSTER_MINIMAL_FOLDER_TAG)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
            }
            if (showVideoPlayMark && file.isVideo && cover != null) VideoPlayMark(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
fun WatchProgressStrip(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val clamped = progress.coerceIn(0f, 1f)
    val isEInk = LocalAppearance.current.isEInk
    val trackColor = if (isEInk) Color.White else Color.Black.copy(alpha = 0.45f)
    val fillColor = if (isEInk) Color.Black else MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .fillMaxHeight()
                .background(fillColor),
        )
    }
}

/** 极简图标模式下有封面文件夹的轻巧角标：不遮挡画面主体，又能一眼与普通视频/图片区分。 */
@Composable
fun MinimalFolderBadge(
    compact: Boolean,
    modifier: Modifier = Modifier,
    mini: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val border = when {
        isEInk -> BorderStroke(1.dp, colors.outline)
        compact -> BorderStroke(1.dp, colors.surfaceContainerLow)
        else -> BorderStroke(0.75.dp, LocalFixedColors.current.HudSpecularBorder)
    }
    val iconSize = when {
        mini -> 9.dp
        compact -> 11.dp
        else -> 14.dp
    }
    val hPad = when {
        mini -> 2.dp
        compact -> 2.5.dp
        else -> 5.dp
    }
    val vPad = when {
        mini -> 1.5.dp
        compact -> 2.dp
        else -> 3.dp
    }
    Surface(
        shape = RoundedCornerShape(if (compact) 4.dp else 5.dp),
        color = if (isEInk) Color.White else colors.secondaryContainer.copy(alpha = 0.94f),
        contentColor = if (isEInk) Color.Black else colors.onSecondaryContainer,
        border = border,
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (isEInk) Icons.Outlined.Folder else Icons.Filled.Folder,
            contentDescription = tr("文件夹", "Folder", "フォルダー", "폴더", "資料夾"),
            modifier = Modifier
                .padding(horizontal = hPad, vertical = vPad)
                .size(iconSize),
        )
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
                val topStartR = 6.dp.toPx().coerceAtMost(sheetH * 0.5f)
                val topEndR = 5.dp.toPx().coerceAtMost(sheetH * 0.5f)
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

/** 视频封面正中的播放键，半透明黑底，在任何画面上都看得清；水墨屏下使用纯黑底白边高对比度。 */
@Composable
private fun VideoPlayMark(modifier: Modifier = Modifier) {
    val isEInk = LocalAppearance.current.isEInk
    Box(
        modifier = modifier
            .size(40.dp)
            .then(if (isEInk) Modifier.border(1.5.dp, Color.White, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(if (isEInk) Color.Black else Color.Black.copy(alpha = 0.45f)),
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
fun TypePlaceholder(file: FileVisual, modifier: Modifier = Modifier) {
    val isEInk = LocalAppearance.current.isEInk
    val (container, content) = file.placeholderColors()
    Box(modifier.background(container), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = file.watermarkIcon(),
                contentDescription = null,
                tint = if (isEInk) content else content.copy(alpha = PLACEHOLDER_ICON_ALPHA),
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
    val r = minOf(with(density) { 10.dp.toPx() }, minOf(w, h) * 0.16f)
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
fun buildFolderFrontTopContourPath(
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

fun buildFolderFrontPath(
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
