package dev.piko.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.ui.theme.LocalFixedColors
import io.github.nihildigit.pikpak.FileStat

/** M3 列表的前导图像尺寸（ItemLeadingImage 56x56dp）。 */
val ListLeadingSize = 56.dp

/** 前导图标尺寸（ItemLeadingIconSize）。 */
val ListLeadingIconSize = 24.dp

// 前导 56dp 加上下各 8dp 即 72dp，正是 M3 两行列表项的高度。原先按扫读密度降到 64dp，
// 前提是 48dp 的前导；前导改用规范的 56dp 后，64dp 放不下它加上下内边距。
private val RowMinHeight = 72.dp

// 外侧留 4dp，让按压与选中时变圆的容器不贴屏幕边缘；内侧起始 12dp，
// 两者相加使内容仍落在紧凑窗口 16dp 的页边距上。末端为 0，让尾部 48dp 图标按钮
// 自带的 12dp 内边距把图标对齐到同一条页边距。
private val RowOuterPadding = 4.dp
private val RowContentPadding = PaddingValues(start = 12.dp, end = 0.dp, top = 8.dp, bottom = 8.dp)

/** 禁用态内容的不透明度，用于弱化显示的行。 */
private const val DIMMED_ALPHA = 0.38f

/**
 * 网盘列表与传输列表共用的一行。
 *
 * 基于 M3 Expressive 的交互式 ListItem：按压与选中的形状变化、点击与长按的语义都由
 * 组件提供。多选时换用 checked 重载，整行即一个复选项，读屏会报告勾选状态；尾部的
 * 复选框只作指示，不单独响应点击，符合「每项只保留一种选择交互」的规范。复选框与
 * 更多按钮同宽，进出多选时内容不会横移。
 *
 * 标题最多两行，完整内容在详情面板顶部可见。
 *
 * 行高须始终落在 72dp 或 88dp 两档内，任何状态都不能把行撑高：副文本只放一行，
 * 长内容（如完整的错误信息）放进详情面板。副文本下还要放进度条的行，把
 * [headlineMaxLines] 设为 1，两行标题、一行副文本再加进度条会超出 88dp。
 *
 * [onLongClick] 为 null 时不提供长按（传输列表没有多选）。[dimmed] 把行内各槽位降到
 * 禁用态的不透明度，行本身仍可点击。
 */
@Composable
fun FileListItem(
    headline: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    headlineFontWeight: FontWeight? = null,
    headlineMaxLines: Int = 2,
    badge: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = { ListMoreButton(onClick = onMoreClick) },
    onLongClick: (() -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: (Boolean) -> Unit = {},
    isHighlighted: Boolean = false,
    dimmed: Boolean = false,
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
    // 弱化加在各槽位上而不是整行：整行降透明度会连按压的状态层一起变淡
    val slotModifier = if (dimmed) Modifier.alpha(DIMMED_ALPHA) else Modifier

    val leadingSlot: @Composable () -> Unit = { Box(slotModifier) { leading() } }
    val supportingSlot: (@Composable () -> Unit)? = supporting?.let { content ->
        { Box(slotModifier) { content() } }
    }
    val headlineSlot: @Composable () -> Unit = {
        Row(modifier = slotModifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = headline,
                fontWeight = headlineFontWeight,
                maxLines = headlineMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (badge != null) {
                Spacer(modifier = Modifier.width(6.dp))
                badge()
            }
        }
    }

    if (isSelectionMode) {
        ListItem(
            checked = isSelected,
            onCheckedChange = onSelectToggle,
            modifier = itemModifier,
            // 行高过 88dp 时 ListItem 默认把前导元素顶部对齐（M3 三行列表项的规则），
            // 带两行标题、元信息与进度条的行会超过它，前导图像贴在左上，与右侧文字块错开
            verticalAlignment = Alignment.CenterVertically,
            leadingContent = leadingSlot,
            trailingContent = {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
            },
            supportingContent = supportingSlot,
            colors = colors,
            contentPadding = RowContentPadding,
            content = headlineSlot,
        )
    } else {
        ListItem(
            onClick = onClick,
            modifier = itemModifier,
            verticalAlignment = Alignment.CenterVertically,
            leadingContent = leadingSlot,
            trailingContent = { Box(slotModifier) { trailing() } },
            supportingContent = supportingSlot,
            onLongClick = onLongClick?.let { longClick ->
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    longClick()
                }
            },
            onLongClickLabel = if (onLongClick != null) "多选" else null,
            colors = colors,
            contentPadding = RowContentPadding,
            content = headlineSlot,
        )
    }
}

/** 行尾的更多按钮，打开该项的详情面板。 */
@Composable
fun ListMoreButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = "更多操作")
    }
}

/**
 * 网盘文件的一行。名字最多两行，扩展名移到副标题单列，所以截断发生时丢掉的是名字中段
 * 而不是类型。
 *
 * 给出 [title] 时是解析后的短标题（「01」、罗马音作品名），只占一行，[tags] 整行排在其下，
 * 放不下的从尾部丢弃；文件在标签下仍有类型与大小，文件夹的类型已由图标表明，不再写。
 */
@Composable
fun FileListItem(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    highlightBadgeText: String = "刚存入",
    isSpoilerBlurred: Boolean = false,
    /** 全盘搜索结果所在的目录路径。仅搜索结果需要，平时为 null。 */
    locationLabel: String? = null,
    title: String? = null,
    tags: List<String> = emptyList(),
    /** 番号芯片，排在标签行最前。 */
    code: String? = null,
) {
    FileListItem(
        headline = title ?: file.displayTitle(),
        headlineMaxLines = if (title != null) 1 else 2,
        headlineFontWeight = if (file.isFolder) FontWeight.Medium else null,
        leading = { FileLeadingVisual(file = file, isSpoilerBlurred = isSpoilerBlurred) },
        onClick = onClick,
        onMoreClick = onMoreClick,
        modifier = modifier,
        badge = if (isHighlighted) ({ HighlightBadge(text = highlightBadgeText) }) else null,
        supporting = {
            Column {
                if (tags.isNotEmpty() || code != null) MediaTagRow(tags = tags, lead = code, modifier = Modifier.padding(vertical = 2.dp))
                if (tags.isEmpty() || !file.isFolder) MetaRow(parts = file.metaParts())
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
        },
        onLongClick = onLongClick,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        onSelectToggle = onSelectToggle,
        isHighlighted = isHighlighted,
    )
}

/**
 * 列表行的前导图像：有缩略图时显示缩略图（可带防窥模糊），否则显示 [fallback]。
 *
 * [thumbnail] 是交给 Coil 的模型：网盘缩略图的 URL，或本地文件。防窥模糊只作用于 URL，
 * 本地副本是用户自己下载的，不再遮蔽。[showPlayOverlay] 在缩略图上压一层播放标记，
 * 提示点按即播放；没有缩略图时类型图标已表明是媒体，不再叠加。
 */
@Composable
fun ListLeadingMedia(
    thumbnail: Any?,
    fallback: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    isSpoilerBlurred: Boolean = false,
    showPlayOverlay: Boolean = false,
    size: Dp = ListLeadingSize,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        // 本地文件与 SAF 地址也走 SpoilerThumbnail：传输列表的缩略图可能来自已下载的文件，
        // 只对 URL 做遮蔽的话，下载下来的东西反而绕过了防窥
        if (thumbnail == null) {
            fallback()
        } else {
            SpoilerThumbnail(
                model = thumbnail,
                isBlurred = isSpoilerBlurred,
                blur = ListSpoilerBlur,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 遮蔽时 SpoilerThumbnail 已画了遮蔽图标，再叠播放键就是两个图标摞在一起
        if (showPlayOverlay && thumbnail != null && !isSpoilerBlurred) {
            val fixed = LocalFixedColors.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(fixed.ScrimOnMedia.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = fixed.OnMedia,
                    modifier = Modifier.size(ListLeadingIconSize),
                )
            }
        }
    }
}

/** 没有缩略图时的类型图标块，外观与网盘文件（非文件夹）的图标块一致。 */
@Composable
fun ListLeadingIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ListLeadingIconSize),
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
