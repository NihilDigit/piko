package dev.piko.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nihildigit.pikpak.FileStat

fun Long.toReadableSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var digitGroups = (Math.log10(this.toDouble()) / Math.log10(1024.0)).toInt()
    digitGroups = digitGroups.coerceIn(0, units.lastIndex)
    return String.format("%.1f %s", this / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

// 行高按 M3 两行列表项 72dp 降两档密度（每档 4dp）取 64dp：文件列表以扫读为主，
// 属于规范里建议提高密度的场景。前导 48dp 加上下各 8dp 正好是 64dp。
private val RowMinHeight = 64.dp

// 外侧留 4dp，让按压与选中时变圆的容器不贴屏幕边缘；内侧起始 12dp，
// 两者相加使内容仍落在紧凑窗口 16dp 的页边距上。末端为 0，让尾部 48dp 图标按钮
// 自带的 12dp 内边距把图标对齐到同一条页边距。
private val RowOuterPadding = 4.dp
private val RowContentPadding = PaddingValues(start = 12.dp, end = 0.dp, top = 8.dp, bottom = 8.dp)

/**
 * 网盘列表的一行。
 *
 * 基于 M3 Expressive 的交互式 ListItem：按压与选中的形状变化、点击与长按的语义都由
 * 组件提供。多选时换用 checked 重载，整行即一个复选项，读屏会报告勾选状态；尾部的
 * 复选框只作指示，不单独响应点击，符合「每项只保留一种选择交互」的规范。复选框与
 * 更多按钮同宽，进出多选时内容不会横移。
 *
 * 名字最多两行。扩展名移到副标题单列，所以截断发生时丢掉的是名字中段而不是类型；
 * 完整名字在操作面板顶部可见。
 */
@Composable
fun FileItemRow(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    highlightBadgeText: String = "刚秒传",
    isSpoilerBlurred: Boolean = false,
    /** 全盘搜索结果所在的目录路径。仅搜索结果需要，平时为 null。 */
    locationLabel: String? = null,
    onToggleSpoiler: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val colors = if (isHighlighted) {
        ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        )
    } else {
        ListItemDefaults.colors()
    }
    val itemModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = RowOuterPadding)
        .heightIn(min = RowMinHeight)

    val leading: @Composable () -> Unit = {
        FileLeadingVisual(
            file = file,
            isSpoilerBlurred = isSpoilerBlurred,
            onToggleSpoiler = onToggleSpoiler,
        )
    }
    val supporting: @Composable () -> Unit = {
        Column {
            Text(
                text = file.metaLine(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!locationLabel.isNullOrEmpty()) {
                // 路径从头截断：离命中项最近的几级目录最有辨识度
                Text(
                    text = locationLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
        }
    }
    val headline: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = file.displayTitle(),
                fontWeight = if (file.isFolder) FontWeight.Medium else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isHighlighted) {
                Spacer(modifier = Modifier.width(6.dp))
                HighlightBadge(text = highlightBadgeText)
            }
        }
    }

    if (isSelectionMode) {
        ListItem(
            checked = isSelected,
            onCheckedChange = onSelectToggle,
            modifier = itemModifier,
            leadingContent = leading,
            trailingContent = {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
            },
            supportingContent = supporting,
            colors = colors,
            contentPadding = RowContentPadding,
            content = headline,
        )
    } else {
        ListItem(
            onClick = onClick,
            modifier = itemModifier,
            leadingContent = leading,
            trailingContent = {
                IconButton(onClick = onMoreClick) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "更多操作",
                    )
                }
            },
            supportingContent = supporting,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
            onLongClickLabel = "多选",
            colors = colors,
            contentPadding = RowContentPadding,
            content = headline,
        )
    }
}

/** 刚秒传进来的条目角标。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HighlightBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
