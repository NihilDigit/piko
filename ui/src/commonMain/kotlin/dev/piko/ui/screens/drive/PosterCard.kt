package dev.piko.ui.screens.drive

import dev.piko.ui.components.itemMarks
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import dev.piko.ui.components.PikoBrandIcons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.piko.data.repository.isPlayableVideo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import dev.piko.ui.components.longPressSelects
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.focusIndication
import dev.piko.ui.components.ownsClicks
import dev.piko.ui.components.locateHighlight
import dev.piko.ui.components.MediaTag
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.PosterSpoilerBlur
import dev.piko.ui.components.SkeletonBlock
import dev.piko.ui.components.SkeletonTextLine
import dev.piko.ui.components.displayTitle
import dev.piko.ui.components.watermarkIcon
import dev.piko.ui.components.extensionLabel
import dev.piko.ui.components.placeholderColors
import io.github.nihildigit.pikpak.FileStat

/**
 * 卡片整体的点击语义。多选时整张卡是一个复选项；平时单击打开，移动端长按进入多选。
 * 与列表行的 ListItem 重载分工一致。图库方格共用。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.cardInteraction(
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
): Modifier {
    val haptic = LocalHapticFeedback.current
    return when {
        isSelectionMode -> toggleable(value = isSelected, role = Role.Checkbox, onValueChange = onSelectToggle)
        // 桌面上按住等于右键，由外面的 ContextMenuArea 弹菜单
        !longPressSelects() -> clickable(onClick = onClick)
        else -> combinedClickable(
            onLongClickLabel = "多选",
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
            onClick = onClick,
        )
    }
}

/**
 * 海报墙里的一张卡：16:9 封面加两行标题，文件与文件夹共用。
 *
 * 多数视频就是 16:9，封面统一这个比例，整面墙排成齐整的网格；原先按位置轮换的高矮两档并不反映画面，
 * 只是看上去像瀑布流。标题固定占两行高，同一排卡片底边对齐；日期、大小不上卡片，在属性里看。
 *
 * 被定位时封面外圈加一道描边（[locateHighlight]）。叠在封面上的：右上角至多两个标签（调用方已按优先级排好，无码、中字在前），
 * 左下角番号芯片，有封面的文件夹在它前面加文件夹标记，右下角清晰度。清晰度单独放一角，
 * 不和其余标签抢右上角的两个位置。没有封面的文件夹画成叠起的纸张，
 * 其余没有缩略图的画类型图标，封面区照样占 16:9，不另起一种图块。
 */
@Composable
internal fun PosterCard(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isSpoilerBlurred: Boolean,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
    onMoreClick: () -> Unit,
    /** 画不画打开操作面板的更多按钮。宽窗口不画，那里有右键菜单。 */
    moreButton: Boolean,
    /** 列过、确实是空的文件夹：封面里不画纸。不知道空不空时为 false，照常画。 */
    isEmptyFolder: Boolean = false,
    modifier: Modifier = Modifier,
    title: String? = null,
    tags: List<String> = emptyList(),
    /** 番号芯片，放在封面左下角。 */
    code: String? = null,
    /** 清晰度，放在封面右下角；[tags] 里的同一项不再重复显示。 */
    resolution: String? = null,
    /** 文件夹里直接放着归档条目，见 itemMarks。 */
    folderHasVault: Boolean = false,
) {
    val coverShape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .fillMaxWidth()
            .focusIndication(coverShape)
            .clip(coverShape)
            .cardInteraction(isSelectionMode, isSelected, onClick, onLongClick, onSelectToggle),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT)
                .clip(coverShape)
                .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, coverShape) else Modifier)
                .locateHighlight(isHighlighted, coverShape),
        ) {
            val hasCover = file.thumbnailLink.isNotEmpty()
            val isVideo = !file.isFolder && file.isPlayableVideo()
            when {
                // 有封面的文件夹照样画成文件夹，封面当作前板：原先封面铺满、左下角挂一个文件夹小标，
                // 与视频只差那一个小标，一眼分不出
                file.isFolder -> StackedSheets(
                    modifier = Modifier.fillMaxSize(),
                    empty = isEmptyFolder,
                    cover = if (hasCover) {
                        {
                            SpoilerThumbnail(
                                model = file.thumbnailLink,
                                isBlurred = isSpoilerBlurred,
                                blur = PosterSpoilerBlur,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        null
                    },
                )
                hasCover -> SpoilerThumbnail(
                    model = file.thumbnailLink,
                    isBlurred = isSpoilerBlurred,
                    blur = PosterSpoilerBlur,
                    modifier = Modifier.fillMaxSize(),
                    showBlurIcon = !isVideo,
                )
                else -> TypePlaceholder(file, Modifier.fillMaxSize())
            }
            // 视频正中一个播放键：封面是一张截图，不标出来的话与图片、文件夹的封面看不出分别
            if (isVideo && hasCover) VideoPlayMark(Modifier.align(Alignment.Center))
            val cornerTags = tags.filter { it != resolution }.take(COVER_CORNER_TAGS)
            if (cornerTags.isNotEmpty()) {
                // 放不下的整个丢掉，不截半个标签
                MediaTagRow(
                    tags = cornerTags,
                    onMedia = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.TopEnd),
                )
            }
            if (code != null || resolution != null) {
                // 左右两角放在同一行：窄卡片上长番号会碰到清晰度，同一行里番号先让出位置
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (code != null) MediaTag(code, onMedia = true, emphasized = true)
                    }
                    if (resolution != null) MediaTag(resolution, onMedia = true)
                }
            }
        }

        // 右端的勾选框或更多按钮浮在标题上，不另占一栏：原来的三点按钮占着 48dp 的一栏，
        // 又往右上偏移出卡片，被卡片的圆角裁掉一半，偏出去的部分也点不到。显示时给标题让出位置
        val trailingShown = isSelectionMode || moreButton
        // 下面留 4dp：叠在一行标题上的 28dp 按钮上下各探出 4dp，卡片裁了圆角，不留就被切掉
        Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = if (trailingShown) CARD_TRAILING_SIZE else 0.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // 星标与归档标记放在标题前而不上封面，封面的角已经给了标签与番号；对齐首行（bodyMedium 行高 20，图标 16）
                itemMarks(file, folderHasVault)?.let { marks ->
                    Box(Modifier.padding(top = 2.dp, end = 4.dp)) { marks() }
                }
                Text(
                    text = title ?: file.displayTitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    // 不再固定占两行：短名字下面空着一行，一屏卡片的下半截全是空白。同一行里有长名字的，
                    // 按行对齐的网格照最高的那张排，其余行照样紧凑
                    maxLines = TITLE_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (trailingShown) {
                // matchParentSize 不参与量高度：标题只有一行时这一行 20dp，按钮一出现就把它撑高的话，悬停时卡片会跳
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                    CardTrailing(
                        isSelectionMode = isSelectionMode,
                        isSelected = isSelected,
                        onMoreClick = onMoreClick,
                    )
                }
            }
        }
    }
}

/**
 * [PosterCard] 的骨架：同样的 16:9 封面，标题区同高，即两行字的 40dp。右端让出更多按钮的宽度，
 * 与按钮一直显示时（触屏）文字止于同一处。
 */
@Composable
internal fun PosterCardSkeleton(titleFraction: Float, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT), MaterialTheme.shapes.medium)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(TITLE_HEIGHT)) {
            // bodyMedium 行高 20dp、字形约 12dp，上留 4dp、行间 8dp，两条正好落在两行字的位置
            Column(
                modifier = Modifier.weight(1f).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonTextLine(0.9f)
                SkeletonTextLine(titleFraction)
            }
            Spacer(modifier = Modifier.width(CARD_TRAILING_SIZE))
        }
    }
}

@Composable
private fun CardTrailing(
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isSelectionMode) {
        Checkbox(checked = isSelected, onCheckedChange = null, modifier = modifier)
    } else {
        // 盖在标题上时要有底色，否则图标压在字上读不清
        FilledTonalIconButton(onClick = onMoreClick, modifier = modifier.size(28.dp).ownsClicks()) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作", modifier = Modifier.size(18.dp))
        }
    }
}

/** 有封面的文件夹靠这个标记与视频区分。图库方格共用。 */
@Composable
internal fun FolderCoverMark(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(4.dp).size(20.dp),
        )
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
private fun TypePlaceholder(file: FileStat, modifier: Modifier = Modifier) {
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

private const val COVER_ASPECT = 16f / 9f
private const val TITLE_LINES = 2
private const val COVER_CORNER_TAGS = 2
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
private val CARD_TRAILING_SIZE = 32.dp

// 两行 bodyMedium，行高各 20dp
private val TITLE_HEIGHT = 40.dp
