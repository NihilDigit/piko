package dev.piko.ui.screens.drive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.FileLeadingVisual
import dev.piko.ui.components.FileTypeIcon
import dev.piko.ui.components.GridSpoilerBlur
import dev.piko.ui.components.HighlightBadge
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.displayTitle
import dev.piko.ui.components.metaLine
import io.github.nihildigit.pikpak.FileStat

@Composable
private fun cardContainerColor(isSelected: Boolean, isHighlighted: Boolean): Color = when {
    isSelected -> MaterialTheme.colorScheme.secondaryContainer
    isHighlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    else -> MaterialTheme.colorScheme.surfaceContainer
}

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
) {
    if (isSelectionMode) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = null,
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
    } else {
        IconButton(onClick = onMoreClick) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "更多操作",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 网格视图里的文件夹。
 *
 * 不给文件夹 16:10 的封面区：文件夹没有缩略图，封面区里只会是一个放大的图标，
 * 占掉一张卡片三分之二的高度却不提供信息。改成一行高的横向图块，与带封面的文件卡
 * 在形状上就不同，一眼可分。
 */
@Composable
internal fun FolderGridTile(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(cardContainerColor(isSelected, isHighlighted))
            .cardInteraction(isSelectionMode, isSelected, onClick, onLongClick, onSelectToggle)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileLeadingVisual(
            file = file,
            isSpoilerBlurred = false,
            onToggleSpoiler = {},
            size = 40.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CardTrailing(isSelectionMode, isSelected, onMoreClick)
    }
}

/**
 * 网格视图里的文件卡片：16:10 封面加两行名字。
 *
 * 名字固定占两行（minLines = 2），同一行的卡片高度一致，网格不会参差。
 * 防窥遮蔽下首次点击封面只揭示，再点才打开，避免误触让遮蔽形同虚设。
 */
@Composable
internal fun FileGridCard(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isSpoilerBlurred: Boolean,
    isHighlighted: Boolean,
    onToggleSpoiler: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlightBadgeText: String = "刚秒传",
) {
    val coverIsSpoiler = isSpoilerBlurred && file.thumbnailLink.isNotEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(cardContainerColor(isSelected, isHighlighted))
            .cardInteraction(isSelectionMode, isSelected, onClick, onLongClick, onSelectToggle),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .then(
                    if (coverIsSpoiler && !isSelectionMode) {
                        Modifier.clickable(onClickLabel = "显示预览", onClick = onToggleSpoiler)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (file.thumbnailLink.isNotEmpty()) {
                SpoilerThumbnail(
                    url = file.thumbnailLink,
                    isBlurred = isSpoilerBlurred,
                    onReveal = onToggleSpoiler,
                    blur = GridSpoilerBlur,
                    revealOnClick = false,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                FileTypeIcon(file = file, iconSize = 36.dp, modifier = Modifier.fillMaxSize())
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
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayTitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = file.metaLine(includeDate = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CardTrailing(isSelectionMode, isSelected, onMoreClick)
        }
    }
}
