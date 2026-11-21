package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.shared.state.TransferItem
import dev.piko.ui.WorkMeter
import dev.piko.ui.components.CanonicalListItemShell
import dev.piko.ui.components.FileListLeadingVisual
import dev.piko.ui.components.FolderListLeadingVisual
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListLeadingSize
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.WorkMeterBar
import dev.piko.ui.components.ownsClicks
import dev.piko.ui.theme.tabular
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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

/** 兼容同包详情面板复用的薄转发器，直接委托至单一权威 [FileListLeadingVisual]。 */
@Composable
internal fun TaskFileTypeIcon(name: String, fallbackIcon: ImageVector? = null) {
    FileListLeadingVisual(fileName = name, fallbackIcon = fallbackIcon)
}

/** 文件夹批次前导块：与网盘列表文件夹一致的前导视觉组件。 */
@Composable
fun TaskFolderVisual(name: String, size: Dp = ListLeadingSize) {
    FolderListLeadingVisual(name = name, size = size)
}

@Composable
internal fun TaskRow(
    model: TaskRowModel,
    selection: RowSelection,
    layout: TaskRowLayout,
    index: Int,
    count: Int,
    item: TransferItem,
    completed: Boolean,
    attention: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    quickAction: SheetAction?,
    extraAction: SheetAction? = null,
    onLeadingClick: (() -> Unit)? = null,
    leadingVisual: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val pointers = LocalPointerSource.current
    val timestamp = remember(item.createdAtMs, completed) {
        if (completed && item.createdAtMs > 0) {
            val time = Instant.fromEpochMilliseconds(item.createdAtMs).toLocalDateTime(TimeZone.currentSystemDefault())
            "${time.monthNumber}/${time.dayOfMonth} ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
        } else null
    }
    val completedMetaParts = remember(completed, model, timestamp) {
        if (!completed) {
            emptyList()
        } else {
            val doneLabel = tr("已完成", "Completed", "完了", "완료", "已完成")
            val detailPart = model.detail?.takeIf { it.isNotBlank() && it != doneLabel }
                ?: model.status.takeIf { it.isNotBlank() && it != doneLabel }
            listOfNotNull(
                model.kind,
                detailPart,
                model.destination?.takeIf { it.isNotBlank() },
                timestamp,
            ).distinct()
        }
    }
    val meterColor = when {
        attention -> colors.error
        item is TransferItem.Local || item is TransferItem.LocalBatch -> colors.primary
        item is TransferItem.Upload || item is TransferItem.UploadBatch -> colors.secondary
        else -> colors.tertiary
    }
    val hasTrailing = quickAction != null || extraAction != null

    CanonicalListItemShell(
        selectionLabel = tr(
            "选择任务：${model.title}",
            "Select task: ${model.title}",
            "タスクを選択：${model.title}",
            "작업 선택: ${model.title}",
            "選取任務：${model.title}",
        ),
        leading = {
            if (leadingVisual != null) {
                leadingVisual()
            } else {
                ListLeadingIcon(model.icon)
            }
        },
        headline = {
            Text(
                text = model.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = { if (pointers.isTouchLike) onOpen() },
        headlineStyle = MaterialTheme.typography.titleSmall,
        supportingStyle = MaterialTheme.typography.bodySmall,
        supporting = if (completed && completedMetaParts.isNotEmpty()) {
            {
                MetaRow(
                    parts = completedMetaParts,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else null,
        progressSlot = if (!completed) {
            {
                TaskProgressCell(
                    model = model,
                    attention = attention,
                    meterColor = meterColor,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    showDetail = true,
                )
            }
        } else null,
        trailing = if (hasTrailing) {
            {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.ownsClicks(),
                ) {
                    quickAction?.let { TooltipIconButton(it.icon, it.label, it.onClick) }
                    extraAction?.let { TooltipIconButton(it.icon, it.label, it.onClick) }
                }
            }
        } else null,
        leadingSize = ListLeadingSize,
        leadingGap = 16.dp,
        trailingGap = if (hasTrailing) 12.dp else 0.dp,
        rowMinHeight = if (completed) 72.dp else 80.dp,
        outerPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        segmentedIndex = index,
        segmentedCount = count,
        restingContainerColor = colors.surfaceContainerLow,
        onLeadingClick = onLeadingClick,
        leadingClickLabel = tr("播放", "Play", "再生", "재생", "播放"),
        onLongClick = selection.onLongClick,
        onStartSelection = onToggle,
        isSelectionMode = selection.active,
        isSelected = selection.selected,
        onSelectToggle = { onToggle() },
        keepTrailingInSelectionMode = true,
    )
}

@Composable
private fun TaskProgressCell(
    model: TaskRowModel,
    attention: Boolean,
    meterColor: Color,
    modifier: Modifier,
    showDetail: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            model.status,
            style = MaterialTheme.typography.bodySmall.tabular,
            color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (attention) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (model.meter != WorkMeter.None) {
            WorkMeterBar(
                meter = model.meter,
                label = tr(
                    "任务进度：${model.title}",
                    "Task progress: ${model.title}",
                    "タスクの進捗：${model.title}",
                    "작업 진행 상황: ${model.title}",
                    "任務進度：${model.title}",
                ),
                modifier = Modifier.fillMaxWidth(),
                color = meterColor,
            )
        }
        if (showDetail) model.detail?.takeIf { it.isNotBlank() && it != model.status }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.tabular,
                color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (attention) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
