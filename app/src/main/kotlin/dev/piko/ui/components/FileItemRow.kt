package dev.piko.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.draw.blur
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nihildigit.pikpak.FileStat

fun Long.toReadableSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var digitGroups = (Math.log10(this.toDouble()) / Math.log10(1024.0)).toInt()
    digitGroups = digitGroups.coerceIn(0, units.lastIndex)
    return String.format("%.1f %s", this / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItemRow(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isHighlighted: Boolean = false,
    highlightBadgeText: String = "刚秒传",
    isSpoilerBlurred: Boolean = false,
    onToggleSpoiler: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onDownloadSegment: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onSelectToggle(!isSelected)
                    } else {
                        onClick()
                    }
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
        color = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            isHighlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 多选勾选框动画
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelectToggle(it) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            // 图标或缩略图
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                if (file.thumbnailLink.isNotEmpty()) {
                    AsyncImage(
                        model = file.thumbnailLink,
                        contentDescription = null,
                        modifier = Modifier
                            .size(44.dp)
                            .then(if (isSpoilerBlurred) Modifier.blur(16.dp) else Modifier),
                        contentScale = ContentScale.Crop,
                    )
                    if (isSpoilerBlurred) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f))
                                .clickable { onToggleSpoiler() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VisibilityOff,
                                contentDescription = "点击查看",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = MaterialTheme.shapes.small,
                        color = if (file.isFolder) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val icon = when {
                                file.isFolder -> Icons.Outlined.Folder
                                file.name.endsWith(".mp4", ignoreCase = true) ||
                                    file.name.endsWith(".mkv", ignoreCase = true) ||
                                    file.name.endsWith(".avi", ignoreCase = true) ||
                                    file.name.endsWith(".mov", ignoreCase = true) -> Icons.Outlined.Movie
                                file.name.endsWith(".mp3", ignoreCase = true) ||
                                    file.name.endsWith(".flac", ignoreCase = true) ||
                                    file.name.endsWith(".wav", ignoreCase = true) -> Icons.Outlined.AudioFile
                                file.name.endsWith(".jpg", ignoreCase = true) ||
                                    file.name.endsWith(".png", ignoreCase = true) ||
                                    file.name.endsWith(".webp", ignoreCase = true) -> Icons.Outlined.Image
                                file.name.endsWith(".zip", ignoreCase = true) ||
                                    file.name.endsWith(".rar", ignoreCase = true) ||
                                    file.name.endsWith(".7z", ignoreCase = true) -> Icons.Outlined.FolderZip
                                else -> Icons.Outlined.Description
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (file.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 文件名称与元信息
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isHighlighted) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                text = highlightBadgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                val subtitle = buildString {
                    if (!file.isFolder) {
                        append(file.sizeBytes.toReadableSize())
                    } else {
                        append("文件夹")
                    }
                    if (file.modifiedTime.isNotEmpty()) {
                        append(" · ")
                        append(file.modifiedTime.take(10))
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            // 更多操作按钮
            if (!isSelectionMode) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        if (!file.isFolder) {
                            DropdownMenuItem(
                                text = { Text("下载到本地") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Download, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    onDownload()
                                },
                            )
                            val isVideo = file.name.endsWith(".mp4", ignoreCase = true) ||
                                file.name.endsWith(".mkv", ignoreCase = true) ||
                                file.name.endsWith(".mov", ignoreCase = true) ||
                                file.name.endsWith(".avi", ignoreCase = true) ||
                                file.name.endsWith(".webm", ignoreCase = true) ||
                                file.name.endsWith(".ts", ignoreCase = true)
                            if (isVideo) {
                                DropdownMenuItem(
                                    text = { Text("下载指定段落") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.ContentCut,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onDownloadSegment()
                                    },
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Edit, contentDescription = null)
                            },
                            onClick = {
                                showMenu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("移入回收站", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}
