package dev.piko.ui.screens.drive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.WaterfallSpoilerBlur
import dev.piko.ui.components.HighlightBadge
import dev.piko.ui.components.MediaTag
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.displayTitle
import dev.piko.ui.components.metaParts
import dev.piko.ui.components.watermarkIcon
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

@Composable
private fun CardTrailing(
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isSelectionMode) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = null,
            modifier = modifier.minimumInteractiveComponentSize(),
        )
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

/**
 * 瀑布流视图里的卡片：封面加至多五行名字，文件与文件夹共用。
 *
 * 文件夹原先是一行高的横向图块，前提是文件夹没有缩略图。实测服务端给多数文件夹返回
 * 目录内容的截图，官方客户端也以此作封面；横向图块的图标与留白又把名字压到不足 90dp。
 * 带封面的文件夹靠左下角的文件夹标记与视频卡片区分。
 *
 * 底色只给封面，名字直接排在页面背景上，选中态因此改为封面描边。
 *
 * 名字至多五行：卡片排在瀑布流里，高度不必与邻卡对齐，五行已能读全绝大多数名字；
 * 不设上限时，带站点前缀与参数串的长名字会把一张卡拉到半屏高。全名在详情面板里读。
 *
 * [title] 与 [tags] 是解析结果，为空时照原样显示名字。有封面的文件夹把标签叠在封面上：
 * 清晰度 [resolution] 在右下角，其余至多两个在右上角，左下角留给文件夹标记、左上角留给
 * 「刚存入」角标。其余情况标签整行排在名字下面，放不下的丢掉，卡片因此高一些。
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
    /** 封面宽高比。瀑布流靠它排出高低交错，见 DriveFileGrid 的 coverAspectFor。 */
    coverAspectRatio: Float = 16f / 10f,
    title: String? = null,
    tags: List<String> = emptyList(),
    resolution: String? = null,
    /** 番号芯片，排在标签最前。 */
    code: String? = null,
) {
    if (file.thumbnailLink.isEmpty()) {
        CompactWaterfallTile(
            file = file,
            title = title,
            tags = tags,
            code = code,
            isSelectionMode = isSelectionMode,
            isSelected = isSelected,
            isHighlighted = isHighlighted,
            highlightBadgeText = highlightBadgeText,
            onClick = onClick,
            onLongClick = onLongClick,
            onSelectToggle = onSelectToggle,
            onMoreClick = onMoreClick,
            modifier = modifier,
        )
        return
    }
    val coverShape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(coverShape)
            .cardInteraction(isSelectionMode, isSelected, onClick, onLongClick, onSelectToggle),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(coverAspectRatio)
                .clip(coverShape)
                .then(
                    if (isSelected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, coverShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            SpoilerThumbnail(
                model = file.thumbnailLink,
                isBlurred = isSpoilerBlurred,
                blur = WaterfallSpoilerBlur,
                modifier = Modifier.fillMaxSize(),
            )
            if (file.isFolder) {
                FolderCoverMark(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                )
                val cornerTags = tags.filter { it != resolution }.take(COVER_CORNER_TAGS)
                if (cornerTags.isNotEmpty() || code != null) {
                    // 与左上角的「刚存入」角标各占一半宽，放不下的整个丢掉
                    MediaTagRow(
                        tags = cornerTags,
                        onMedia = true,
                        lead = code,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .fillMaxWidth(if (isHighlighted) 0.5f else 1f)
                            .wrapContentSize(Alignment.TopEnd),
                    )
                }
                if (resolution != null) {
                    MediaTag(
                        text = resolution,
                        onMedia = true,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                    )
                }
            }
            if (isHighlighted) {
                HighlightBadge(
                    text = highlightBadgeText,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title ?: file.displayTitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = TITLE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!file.isFolder && (tags.isNotEmpty() || code != null)) {
                    MediaTagRow(tags = tags, lead = code, modifier = Modifier.padding(vertical = 2.dp))
                }
                MetaRow(
                    parts = file.waterfallMetaParts(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 触控区保持 48dp，只把图标挪到名字首行右端、与封面右缘对齐：
            // 图标在按钮里四周各留 12dp，按原位摆会让名字右侧空出一块，也比首行低半行。
            CardTrailing(
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                onMoreClick = onMoreClick,
                modifier = Modifier.offset(x = 12.dp, y = (-12).dp),
            )
        }
    }
}

@Composable
// 28dp：封面约 200x120dp，原先 22dp 的标记在上面一扫而过，认不出是文件夹
private fun FolderCoverMark(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .padding(4.dp)
                .size(20.dp),
        )
    }
}

/** 文件夹的类型已由封面上的标记或图标表明，副标题改放修改日期；文件仍是类型与大小。 */
private fun FileStat.waterfallMetaParts(): List<String> =
    if (isFolder) listOfNotNull(modifiedTime.take(10).ifEmpty { null }) else metaParts(includeDate = false)

/**
 * 没有缩略图的条目：不留封面区，整张卡是一块带底色的图块。
 *
 * 封面区里没有画面可放，只放一个类型图标的话，嵌套目录一屏全是空底；换成表现性形状或自绘
 * 文件夹的装饰也只是填空。图块按文字定高，不跟封面节奏取高低两档：试过，矮块被硬拉高后
 * 只多出空底。
 */
@Composable
private fun CompactWaterfallTile(
    file: FileStat,
    title: String?,
    tags: List<String>,
    code: String?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isHighlighted: Boolean,
    highlightBadgeText: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.medium
    // 类型图标放大、压到约一成透明度，贴右下角作底纹，被圆角裁去一截。不再单占一层，
    // 图标仍在，文件夹与各类文件照样一眼可分。名字从顶端起排，占满宽度
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .cardInteraction(isSelectionMode, isSelected, onClick, onLongClick, onSelectToggle),
    ) {
        // matchParentSize 不参与测量：底纹只画不占位，图块高度仍由文字决定，否则 88dp 的
        // 图标会把每块都撑到同样高
        Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.BottomEnd) {
            Icon(
                imageVector = file.watermarkIcon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = WATERMARK_ALPHA),
                modifier = Modifier
                    .wrapContentSize(Alignment.BottomEnd, unbounded = true)
                    .offset(x = 16.dp, y = 24.dp)
                    .requiredSize(88.dp),
            )
        }
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (isHighlighted) HighlightBadge(text = highlightBadgeText, modifier = Modifier.padding(bottom = 4.dp))
                Text(
                    text = title ?: file.displayTitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = TITLE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tags.isNotEmpty() || code != null) MediaTagRow(tags = tags, lead = code, modifier = Modifier.padding(vertical = 2.dp))
                MetaRow(
                    parts = file.waterfallMetaParts(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 触控区 48dp，上提让图标对齐名字首行
            CardTrailing(
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                onMoreClick = onMoreClick,
                modifier = Modifier.offset(y = (-12).dp),
            )
        }
    }
}

private const val TITLE_MAX_LINES = 5

private const val COVER_CORNER_TAGS = 2

private const val WATERMARK_ALPHA = 0.1f
