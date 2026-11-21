package dev.piko.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.ui.screens.drive.LocalDriveAppearancePrefs

/**
 * 目录图与导入来源树共用的单一权威树形行（基于 [CanonicalListItemShell]）：
 * 自动继承 [LocalDriveAppearancePrefs.current.list] 的拟物/极简文件夹样式、紧凑密度、双名称、标签显隐、
 * 零协程鼠标悬停（`fileItemHover`）、键盘焦点环（`focusIndication`）与水墨屏 `1.dp` 描边。
 */
@Composable
fun WorkspaceTreeRow(
    name: String,
    depth: Int,
    icon: ImageVector,
    expanded: Boolean?,
    onExpand: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    highlighted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    supporting: String? = null,
    item: FilePresentation? = null,
    visual: FileVisual? = item?.visual,
    visualSize: Dp = 32.dp,
    actions: FileItemActions? = null,
    reserveExpandSpace: Boolean = true,
    startBasePadding: Dp = 4.dp,
    indentStep: Dp = 16.dp,
    expandButtonSize: Dp = 36.dp,
) {
    val listPrefs = LocalDriveAppearancePrefs.current.list
    val compact = listPrefs.compactDensity
    val effectiveVisualSize = if (compact && visualSize >= 28.dp) visualSize - 4.dp else visualSize
    val minRowHeight = if (compact) 40.dp else 48.dp
    val verticalPad = if (compact) 4.dp else 6.dp
    val resolvedVisual = visual ?: item?.visual
    val showItemSupporting = item != null && (
        (listPrefs.showSecondaryName && item.name.original != null) ||
            (listPrefs.showParsedTags && (item.tags.isNotEmpty() || item.code != null)) ||
            item.metadata.isNotEmpty() ||
            item.contextNote.isNotEmpty() ||
            !item.locationPath.isNullOrEmpty() ||
            item.status != null
        )
    val hasSupporting = showItemSupporting || !supporting.isNullOrEmpty()
    val hasTrailing = trailing != null || actions != null

    CanonicalListItemShell(
        selectionLabel = tr("选择 $name", "Select $name", "$name を選択", "$name 선택", "選取 $name"),
        leading = {
            if (resolvedVisual != null) {
                FileListLeadingVisual(
                    file = resolvedVisual,
                    size = effectiveVisualSize,
                    folderStyle = listPrefs.folderStyle,
                    watchProgress = item?.watchProgress,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        headline = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val resolvedName = item?.name ?: FileItemName(name)
                FileItemTitle(
                    name = resolvedName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if ((listPrefs.showSecondaryName && resolvedName.original != null) || compact) 1 else 2,
                    fontWeight = when {
                        emphasized -> FontWeight.SemiBold
                        resolvedVisual?.isFolder == true -> FontWeight.Medium
                        else -> null
                    },
                )
                if (item != null && item.marks.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    FileItemMarks(item.marks)
                }
            }
        },
        onClick = actions?.primary ?: onOpen,
        modifier = modifier.onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else {
                when {
                    event.key == Key.DirectionRight && expanded == false -> {
                        onExpand()
                        true
                    }
                    event.key == Key.DirectionLeft && expanded == true -> {
                        onExpand()
                        true
                    }
                    else -> false
                }
            }
        },
        headlineStyle = MaterialTheme.typography.bodyMedium,
        supportingStyle = MaterialTheme.typography.bodySmall,
        supporting = if (hasSupporting) {
            {
                if (showItemSupporting && item != null) {
                    FileItemInformation(
                        item = item,
                        showOriginal = listPrefs.showSecondaryName,
                        showTags = listPrefs.showParsedTags,
                    )
                } else if (!supporting.isNullOrEmpty()) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else null,
        trailing = if (hasTrailing) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (trailing != null) trailing()
                    if (actions != null) FileItemActionButtons(actions)
                }
            }
        } else null,
        treePrefix = {
            if (expanded != null) {
                IconButton(
                    onClick = onExpand,
                    modifier = Modifier.size(expandButtonSize).ownsClicks(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (expanded) {
                            tr("收起 $name", "Collapse $name", "$name を折りたたむ", "$name 접기", "收合 $name")
                        } else {
                            tr("展开 $name", "Expand $name", "$name を展開", "$name 펼치기", "展開 $name")
                        },
                        modifier = Modifier.size(18.dp).rotate(if (expanded) 90f else 0f),
                    )
                }
            } else if (reserveExpandSpace) {
                Spacer(Modifier.width(expandButtonSize))
            }
            if (leading != null) {
                leading()
            }
        },
        leadingSize = effectiveVisualSize,
        leadingGap = 8.dp,
        trailingGap = if (hasTrailing) 4.dp else 0.dp,
        rowMinHeight = minRowHeight,
        outerPadding = PaddingValues(0.dp),
        contentPadding = PaddingValues(
            start = startBasePadding + indentStep * depth,
            end = 8.dp,
            top = verticalPad,
            bottom = verticalPad,
        ),
        onLongClick = onLongClick,
        isHighlighted = highlighted,
        isEmphasized = emphasized,
        keepTrailingInSelectionMode = true,
    )
}
