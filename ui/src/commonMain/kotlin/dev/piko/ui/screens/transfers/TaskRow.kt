package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
            wide = width >= 760.dp,
            amount = width >= 920.dp,
            rate = width >= 1200.dp,
            destination = width >= 1440.dp,
            progressWidth = if (width >= 1200.dp) 240.dp else 200.dp,
        )
    }
}

@Composable
internal fun TaskRow(
    model: TaskRowModel, selection: RowSelection, layout: TaskRowLayout,
    index: Int, count: Int, item: TransferItem, completed: Boolean, attention: Boolean,
    onOpen: () -> Unit, onToggle: () -> Unit,
    quickAction: SheetAction?, extraAction: SheetAction? = null,
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
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pointers = LocalPointerSource.current
    val showCheckbox = selection.active || (hovered && !pointers.isTouchLike)
    val shownExtraAction = extraAction.takeIf { layout.wide }
    SegmentedListItem(
        onClick = { if (selection.active) onToggle() else if (pointers.isTouchLike) onOpen() },
        // 按触发长按时的输入来源判断，避免首个触摸事件切换回调后取消正在进行的手势。
        onLongClick = { if (pointers.isTouchLike) selection.onLongClick?.invoke() },
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).heightIn(min = if (layout.wide || completed) 64.dp else 88.dp)
            .hoverable(interactions),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        shapes = ListItemDefaults.segmentedShapes(if (selection.selected) 0 else index, if (selection.selected) 1 else count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = if (selection.selected) colors.primaryContainer else colors.surfaceContainerLow,
            contentColor = if (selection.selected) colors.onPrimaryContainer else colors.onSurface,
        ),
        leadingContent = {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (showCheckbox) {
                    Checkbox(selection.selected, { onToggle() }, Modifier.semantics { contentDescription = "选择任务：${model.title}" })
                } else Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    if (leadingVisual != null) leadingVisual() else Icon(model.icon, null, Modifier.size(22.dp))
                }
            }
        },
        content = {
            if (completed) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(model.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = if (layout.wide) 1 else 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(model.status, model.detail?.takeIf { it.isNotBlank() }).distinct().joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(model.kind, model.destination?.takeIf { it.isNotBlank() }, timestamp).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (layout.wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(model.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(model.kind, model.destination.takeUnless { layout.destination }).joinToString("，"), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TaskProgressCell(model, attention, Modifier.width(layout.progressWidth), showDetail = true)
                if (byteStream && layout.amount) Column(Modifier.width(130.dp)) {
                    TaskMetric(model.amount)
                    if (!layout.rate) TaskMetric(model.rate)
                }
                if (byteStream && layout.rate) TaskMetric(model.rate, Modifier.width(92.dp))
                if (layout.destination) TaskMetric(model.destination, Modifier.width(160.dp))
            } else Column(Modifier.fillMaxWidth()) {
                Text(model.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(model.kind, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                TaskProgressCell(model, attention, Modifier.fillMaxWidth(), showDetail = true)
            }
        },
        trailingContent = if (quickAction != null || shownExtraAction != null) ({
            Row(Modifier.width(if (layout.wide) 96.dp else 48.dp), horizontalArrangement = Arrangement.End) {
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
        if (model.meter != WorkMeter.None) WorkMeterBar(model.meter, "任务进度：${model.title}", Modifier.fillMaxWidth())
        if (showDetail) model.detail?.takeIf { it.isNotBlank() && it != model.status }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (attention) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
