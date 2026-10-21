package dev.piko.ui.screens.drive

import dev.piko.ui.components.itemMarks
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.getValue
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.focusIndication
import dev.piko.ui.components.ownsClicks
import dev.piko.ui.components.locateHighlight
import dev.piko.ui.components.MediaTag
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.SkeletonBlock
import dev.piko.ui.components.SkeletonTextLine
import io.github.nihildigit.pikpak.FileStat
import dev.piko.ui.components.*

/**
 * 卡片整体的点击语义。多选时整张卡是一个复选项；单击打开，长按及无障碍长按打开操作面板。
 * 与列表行的 ListItem 重载分工一致。图库方格共用。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.cardInteraction(
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onSelectToggle: (Boolean) -> Unit,
): Modifier {
    val longPress = fileItemLongPress(onLongClick)
    return semantics {
        if (isSelectionMode) {
            role = Role.Checkbox
            toggleableState = if (isSelected) ToggleableState.On else ToggleableState.Off
        }
    }.combinedClickable(
        onLongClickLabel = "操作",
        onLongClick = longPress,
        onClick = { if (isSelectionMode) onSelectToggle(!isSelected) else onClick() },
    )
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
    onLongClick: (() -> Unit)?,
    onStartSelection: (() -> Unit)? = null,
    onSelectToggle: (Boolean) -> Unit,
    onMoreClick: () -> Unit,
    /** 画不画打开操作面板的更多按钮。宽窗口不画，那里有右键菜单。 */
    moreButton: Boolean,
    /** 列过、确实是空的文件夹：封面里不画纸。不知道空不空时为 false，照常画。 */
    isEmptyFolder: Boolean = false,
    modifier: Modifier = Modifier,
    title: String? = null,
    /** 真实名称，[title] 与它不同时在标题下面写一行小字，见 DriveItemName。 */
    original: String? = null,
    tags: List<String> = emptyList(),
    /** 番号芯片，放在封面左下角。 */
    code: String? = null,
    /** 清晰度，放在封面右下角；[tags] 里的同一项不再重复显示。 */
    resolution: String? = null,
    /** 文件夹里直接放着归档条目，见 itemMarks。 */
    folderHasVault: Boolean = false,
) {
    val item = file.filePresentation(title, original, tags, code, resolution, folderHasVault, isEmptyFolder)
    val hover = fileItemHover()
    val coverShape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .then(hover.modifier)
            .fillMaxWidth()
            .focusIndication(coverShape, showPointerFocus = !isSelectionMode)
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
            FileArtwork(item.visual, FileArtworkFormat.Poster, isSpoilerBlurred, Modifier.fillMaxSize())
            if (isSelectionMode || (hover.mouseHovered && onStartSelection != null)) {
                FileOverlaySelectionControl(FileItemSelection(
                    if (isSelected) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                    { if (isSelectionMode) onSelectToggle(!isSelected) else onStartSelection?.invoke() }, label = "选择 ${item.name.fullName}"),
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).then(if (!isSelectionMode) Modifier.ownsClicks() else Modifier), interactive = !isSelectionMode)
            }
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
        val trailingShown = moreButton
        // 下面留 4dp：叠在一行标题上的 28dp 按钮上下各探出 4dp，卡片裁了圆角，不留就被切掉
        Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = if (trailingShown) CARD_TRAILING_SIZE else 0.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // 星标与归档标记放在标题前而不上封面，封面的角已经给了标签与番号；对齐首行（bodyMedium 行高 20，图标 16）
                if (item.marks.isNotEmpty()) Box(Modifier.padding(top = 2.dp, end = 4.dp)) { FileItemMarks(item.marks) }
                Column(Modifier.weight(1f)) {
                    FileItemTitle(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = TITLE_LINES)
                    FileItemOriginal(item.name)
                }
            }
            if (trailingShown && !isSelectionMode) {
                // matchParentSize 不参与量高度：标题只有一行时这一行 20dp，按钮一出现就把它撑高的话，悬停时卡片会跳
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                    CardTrailing(onMoreClick = onMoreClick)
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
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
        // 盖在标题上时要有底色，否则图标压在字上读不清
        FilledTonalIconButton(onClick = onMoreClick, modifier = modifier.size(28.dp).ownsClicks()) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作", modifier = Modifier.size(18.dp))
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


private const val COVER_ASPECT = 16f / 9f
private const val TITLE_LINES = 2
private const val COVER_CORNER_TAGS = 2
private val CARD_TRAILING_SIZE = 32.dp
private val TITLE_HEIGHT = 40.dp
