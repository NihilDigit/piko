package dev.piko.ui.screens.drive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.HighlightBadge
import dev.piko.ui.components.MediaTag
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.WaterfallSpoilerBlur
import dev.piko.ui.components.displayTitle
import dev.piko.ui.components.watermarkIcon
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.FileStat

/**
 * 卡片整体的点击语义。多选时整张卡是一个复选项；平时单击打开、长按进入多选。
 * 与列表行的 ListItem 重载分工一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.cardInteraction(
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
): Modifier {
    val haptic = LocalHapticFeedback.current
    return if (isSelectionMode) {
        toggleable(value = isSelected, role = Role.Checkbox, onValueChange = onSelectToggle)
    } else {
        combinedClickable(
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
 * 只是看上去像瀑布流。标题固定占两行高，同一排卡片底边对齐；日期、大小不上卡片，在详情面板里看。
 *
 * 叠在封面上的：左上角「刚存入」，右上角至多两个标签（调用方已按优先级排好，无码、中字在前），
 * 左下角番号芯片，有封面的文件夹在它前面加文件夹标记。没有封面的文件夹画成叠起的纸张，
 * 其余没有缩略图的画类型图标，封面区照样占 16:9，不另起一种图块。
 *
 * 更多按钮在桌面端悬停时才显示：鼠标有右键菜单，常驻的按钮只是让每张卡多一个图标。位置始终留着，
 * 悬停时标题不跳动。触屏上长按是多选，更多按钮是进详情的唯一入口，所以常驻。
 */
@Composable
internal fun WaterfallCard(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isSpoilerBlurred: Boolean,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlightBadgeText: String = "刚存入",
    title: String? = null,
    tags: List<String> = emptyList(),
    /** 番号芯片，放在封面左下角。 */
    code: String? = null,
) {
    val coverShape = MaterialTheme.shapes.medium
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val showMore = isSelectionMode || !LocalPikoPlatform.current.revealsActionsOnHover || hovered
    Column(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(hover)
            .clip(coverShape)
            .cardInteraction(isSelectionMode, isSelected, onClick, onLongClick, onSelectToggle),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT)
                .clip(coverShape)
                .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, coverShape) else Modifier),
        ) {
            val hasCover = file.thumbnailLink.isNotEmpty()
            when {
                hasCover -> SpoilerThumbnail(
                    model = file.thumbnailLink,
                    isBlurred = isSpoilerBlurred,
                    blur = WaterfallSpoilerBlur,
                    modifier = Modifier.fillMaxSize(),
                )
                file.isFolder -> StackedSheets(Modifier.fillMaxSize())
                else -> TypePlaceholder(file, Modifier.fillMaxSize())
            }
            if (isHighlighted) {
                HighlightBadge(text = highlightBadgeText, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            if (tags.isNotEmpty()) {
                // 与左上角的「刚存入」各占一半宽，放不下的整个丢掉
                MediaTagRow(
                    tags = tags.take(COVER_CORNER_TAGS),
                    onMedia = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .fillMaxWidth(if (isHighlighted) 0.5f else 1f)
                        .wrapContentSize(Alignment.TopEnd),
                )
            }
            val folderMark = file.isFolder && hasCover
            if (folderMark || code != null) {
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (folderMark) FolderCoverMark()
                    if (code != null) MediaTag(code, onMedia = true, emphasized = true)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = title ?: file.displayTitle(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                minLines = TITLE_LINES,
                maxLines = TITLE_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 触控区保持 48dp，只把图标挪到标题首行右端、与封面右缘对齐：图标在按钮里四周各留 12dp
            CardTrailing(
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                onMoreClick = onMoreClick,
                modifier = Modifier.offset(x = 12.dp, y = (-12).dp).alpha(if (showMore) 1f else 0f),
            )
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
        Checkbox(checked = isSelected, onCheckedChange = null, modifier = modifier.minimumInteractiveComponentSize())
    } else {
        IconButton(onClick = onMoreClick, modifier = modifier) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "更多操作",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 有封面的文件夹靠这个标记与视频区分。 */
@Composable
private fun FolderCoverMark(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(3.dp).size(16.dp),
        )
    }
}

/**
 * 没有封面的文件夹：两张纸叠在后面，从顶上露出两道边。画在 16:9 的封面区里面，
 * 卡片高度与有封面的一样，网格照样对齐。
 */
@Composable
private fun StackedSheets(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    // 后两张纸用比最上面那张深的色调，边要看得出来：surfaceContainer 一族彼此太接近，叠起来像一整块
    Box(modifier.background(colors.surfaceContainerLow)) {
        Box(
            Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 6.dp)
                .clip(shape).background(colors.outlineVariant),
        )
        Box(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 12.dp)
                .clip(shape).background(colors.secondary.copy(alpha = 0.35f)),
        )
        Box(
            Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp, top = 18.dp)
                .clip(shape).background(colors.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = colors.onSecondaryContainer.copy(alpha = PLACEHOLDER_ICON_ALPHA),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/** 没有缩略图的文件：底色加类型图标，照样占满 16:9。 */
@Composable
private fun TypePlaceholder(file: FileStat, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = file.watermarkIcon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = PLACEHOLDER_ICON_ALPHA),
            modifier = Modifier.size(36.dp),
        )
    }
}

private const val COVER_ASPECT = 16f / 9f
private const val TITLE_LINES = 2
private const val COVER_CORNER_TAGS = 2
private const val PLACEHOLDER_ICON_ALPHA = 0.6f
