package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TransferItem
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import dev.piko.ui.WorkMeter
import dev.piko.ui.components.*

internal data class TaskRowLayout(
    val wide: Boolean,
    val amount: Boolean,
    val rate: Boolean,
    val destination: Boolean,
    val progressWidth: Dp,
) {
    companion object {
        fun forWidth(width: Dp) = TaskRowLayout(
            wide = width >= 600.dp,
            amount = false,
            rate = false,
            destination = false,
            progressWidth = 200.dp,
        )
    }
}

/** 无缩略图的文件前导块：按文件大类配容器底色，标准 56.dp 圆角块。 */
@Composable
internal fun TaskFileTypeIcon(name: String, fallbackIcon: ImageVector? = null) {
    val visual = remember(name) { FileVisual(name) }
    val hasExt = remember(name) { visual.extensionLabel() != null }
    val (container, content) = if (hasExt) {
        visual.placeholderColors()
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(ListLeadingSize)
            .clip(MaterialTheme.shapes.small)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (hasExt) visual.typeIcon() else (fallbackIcon ?: visual.typeIcon()),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(ListLeadingIconSize),
        )
    }
}

/** 文件夹批次前导块：与网盘列表文件夹一致的 56.dp 图标。 */
@Composable
internal fun TaskFolderVisual(name: String) {
    val visual = remember(name) { FileVisual(name, isFolder = true) }
    FileArtwork(visual, FileArtworkFormat.Leading)
}

@Composable
internal fun TaskRow(
    model: TaskRowModel, selection: RowSelection, layout: TaskRowLayout,
    index: Int, count: Int, item: TransferItem, completed: Boolean, attention: Boolean,
    onOpen: () -> Unit, onToggle: () -> Unit,
    quickAction: SheetAction?, extraAction: SheetAction? = null,
    onLeadingClick: (() -> Unit)? = null,
    leadingVisual: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val byteStream = item is TransferItem.Local || item is TransferItem.LocalBatch ||
        item is TransferItem.Upload || item is TransferItem.UploadBatch ||
        item is TransferItem.Cloud || item is TransferItem.Pack
    val timestamp = remember(item.createdAtMs, completed) {
        if (completed && item.createdAtMs > 0) {
            val time = Instant.fromEpochMilliseconds(item.createdAtMs).toLocalDateTime(TimeZone.currentSystemDefault())
            "${time.monthNumber}/${time.dayOfMonth} ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
        } else null
    }
    val pointers = LocalPointerSource.current
    val shownExtraAction = extraAction
    val rowShape = MaterialTheme.shapes.medium
    SegmentedListItem(
        onClick = { if (selection.active) onToggle() else if (pointers.isTouchLike) onOpen() },
        // 按触发长按时的输入来源判断，避免首个触摸事件切换回调后取消正在进行的手势。
        onLongClick = { if (pointers.isTouchLike) selection.onLongClick?.invoke() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).heightIn(min = if (completed) 72.dp else 80.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        shapes = ListItemDefaults.shapes(
            shape = rowShape,
            selectedShape = rowShape,
            pressedShape = rowShape,
            focusedShape = rowShape,
            hoveredShape = rowShape,
            draggedShape = rowShape,
        ),
        colors = ListItemDefaults.segmentedColors(
            containerColor = if (selection.selected) colors.secondaryContainer else colors.surfaceContainerLow,
            contentColor = if (selection.selected) colors.onSecondaryContainer else colors.onSurface,
        ),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(ListLeadingSize)
                    .then(
                        if (!selection.active && onLeadingClick != null) {
                            Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = tr("播放", "Play", "再生", "재생"), onClick = onLeadingClick)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selection.active) {
                    Checkbox(selection.selected, null, Modifier.semantics { contentDescription = tr("选择任务：${model.title}", "Select task: ${model.title}", "タスクを選択：${model.title}", "작업 선택: ${model.title}") })
                } else if (leadingVisual != null) {
                    leadingVisual()
                } else {
                    ListLeadingIcon(model.icon)
                }
            }
        },
        content = {
            if (completed) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        model.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val detailPart = model.detail?.takeIf { it.isNotBlank() && it != tr("已完成", "Completed", "完了", "완료") }
                        ?: model.status.takeIf { it.isNotBlank() && it != tr("已完成", "Completed", "完了", "완료") }
                    val metaParts = listOfNotNull(
                        model.kind,
                        detailPart,
                        model.destination?.takeIf { it.isNotBlank() },
                        timestamp,
                    ).distinct()
                    if (metaParts.isNotEmpty()) {
                        MetaRow(
                            parts = metaParts,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        model.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TaskProgressCell(model, attention, Modifier.fillMaxWidth(), showDetail = true)
                }
            }
        },
        trailingContent = if (quickAction != null || shownExtraAction != null) ({
            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                quickAction?.let { TooltipIconButton(it.icon, it.label, it.onClick) }
                shownExtraAction?.let { TooltipIconButton(it.icon, it.label, it.onClick) }
            }
        }) else null,
    )
}

@Composable
private fun TaskMetric(value: String?, modifier: Modifier = Modifier) {
    Text(value?.takeIf { it.isNotBlank() } ?: "—", modifier, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TaskProgressCell(model: TaskRowModel, attention: Boolean, modifier: Modifier, showDetail: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(model.status, style = MaterialTheme.typography.bodySmall, color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (attention) 2 else 1, overflow = TextOverflow.Ellipsis)
        if (model.meter != WorkMeter.None) WorkMeterBar(model.meter, tr("任务进度：${model.title}", "Task progress: ${model.title}", "タスクの進捗：${model.title}", "작업 진행 상황: ${model.title}"), Modifier.fillMaxWidth())
        if (showDetail) model.detail?.takeIf { it.isNotBlank() && it != model.status }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (attention) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
