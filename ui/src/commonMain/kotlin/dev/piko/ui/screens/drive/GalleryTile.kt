package dev.piko.ui.screens.drive

import dev.piko.ui.theme.LocalFixedColors
import androidx.compose.foundation.shape.CircleShape
import dev.piko.shared.data.isVaulted
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.isPlayableVideo
import dev.piko.ui.components.focusIndication
import dev.piko.ui.components.locateHighlight
import dev.piko.ui.components.PosterSpoilerBlur
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.watermarkIcon
import io.github.nihildigit.pikpak.FileStat

/**
 * 图库视图里的一格：正方形缩略图，不带名字，一屏能看到的图比海报墙多得多，适合整夹照片。
 *
 * 有缩略图的只画图，视频在右下角加播放标记，文件夹在左下角加文件夹标记，与图片区分。
 * 没有缩略图的文件不进图库（DriveScreenState.thumbnailsOnly），落到这里的只有没封面的文件夹：
 * 画文件夹图标并在底部压一行名字，它没有画面可认，不写名字就分不出彼此。
 *
 * 格子上不放「更多」按钮：一百来 dp 的方格上它会盖住画面。桌面用右键菜单；触屏长按进入多选，
 * 批量操作走顶栏，单项的其余操作要切回列表或海报墙。这是为密度让出的便利。
 */
@Composable
internal fun GalleryTile(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isSpoilerBlurred: Boolean,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** 文件夹里直接放着归档条目，见 itemMarks。 */
    folderHasVault: Boolean = false,
) {
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .focusIndication(shape)
            .clip(shape)
            .cardInteraction(isSelectionMode, isSelected, onClick, onLongClick, onSelectToggle)
            .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .locateHighlight(isHighlighted, shape),
    ) {
        val hasThumbnail = file.thumbnailLink.isNotEmpty()
        if (hasThumbnail) {
            SpoilerThumbnail(
                model = file.thumbnailLink,
                isBlurred = isSpoilerBlurred,
                blur = PosterSpoilerBlur,
                modifier = Modifier.fillMaxSize(),
            )
            if (file.isFolder) {
                FolderCoverMark(Modifier.align(Alignment.BottomStart).padding(4.dp))
            } else if (file.isPlayableVideo()) {
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = "视频",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(20.dp),
                )
            }
        } else {
            val container = if (file.isFolder) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
            val content = if (file.isFolder) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            Box(Modifier.fillMaxSize().background(container), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = file.watermarkIcon(),
                    contentDescription = null,
                    tint = content.copy(alpha = 0.6f),
                    modifier = Modifier.size(36.dp).padding(bottom = 8.dp),
                )
            }
            TileName(file.name, Modifier.align(Alignment.BottomStart))
        }
        // 方格上没有标题行，标记压在左上角；垫一层圆底，压在任何画面上都看得清
        if (file.isVaulted || (file.isFolder && folderHasVault)) {
            val fixed = LocalFixedColors.current
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(fixed.ScrimOnMedia.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Inventory2,
                    contentDescription = if (file.isFolder) "含归档条目" else "已归档",
                    tint = fixed.OnMedia,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                modifier = Modifier.align(Alignment.TopEnd).minimumInteractiveComponentSize(),
            )
        }
    }
}

/** 压在格子底部的名字，底下垫一层渐变，浅色图标块与深色主题里都读得清。 */
@Composable
private fun TileName(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
