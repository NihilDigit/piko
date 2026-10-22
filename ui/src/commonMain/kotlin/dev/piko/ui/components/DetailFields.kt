package dev.piko.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.piko.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay

internal data class DetailAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val copyFeedback: Boolean = false,
)

internal data class DetailField(
    val label: String,
    val value: String,
    val supportingText: String? = null,
    val monospace: Boolean = false,
    val maxLines: Int = Int.MAX_VALUE,
    val actions: List<DetailAction> = emptyList(),
)

internal sealed interface DetailRow {
    data class Single(val field: DetailField) : DetailRow
    data class PairRow(val start: DetailField, val end: DetailField) : DetailRow
}

/**
 * M3 Expressive 分段式属性组：外侧由组容器裁出 16dp 大圆角，组内各格 4dp 内圆角、2dp 间隙，
 * 底色取高两级的 surfaceContainerHigh，在 surfaceContainerLow 的面板或对话框上边界清晰。
 */
@Composable
internal fun DetailSection(rows: List<DetailRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (row in rows) {
            when (row) {
                is DetailRow.Single -> DetailFieldCell(row.field, Modifier.fillMaxWidth())
                is DetailRow.PairRow -> Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    DetailFieldCell(row.start, Modifier.weight(1f).fillMaxHeight())
                    DetailFieldCell(row.end, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
internal fun DetailFields(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    DetailSection(rows.map { (label, value) -> DetailRow.Single(DetailField(label = label, value = value)) })
}

@Composable
private fun DetailFieldCell(field: DetailField, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = if (field.actions.isEmpty()) 16.dp else 6.dp,
                    top = 10.dp,
                    bottom = 10.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = field.value,
                            style = if (field.monospace) {
                                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = colors.onSurface,
                            maxLines = field.maxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                        field.supportingText?.let { sub ->
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (field.actions.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    field.actions.forEach { action ->
                        DetailActionIconButton(action)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DetailActionIconButton(action: DetailAction, modifier: Modifier = Modifier) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val label = if (copied) "已复制" else action.label
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(
            onClick = {
                action.onClick()
                if (action.copyFeedback) copied = true
            },
            modifier = Modifier.size(40.dp),
        ) {
            if (action.copyFeedback) {
                Crossfade(targetState = copied, label = "detail-copy") { done ->
                    Icon(
                        imageVector = if (done) Icons.Outlined.Check else action.icon,
                        contentDescription = label,
                        tint = if (done) LocalStatusColors.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Icon(
                    imageVector = action.icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 竖排，宽度由文字一类的子项定，标了 [DetailFollowsWidth] 的子项（预览图、标签行）再按这个宽度量：它们自己会填满给多少占多少，
 * 先量的话卡片总被撑到上限。不用 IntrinsicSize：缩略图与选中容器的固有尺寸靠不住。
 */
@Composable
internal fun FitDetailWidthColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        measurables.forEachIndexed { index, measurable ->
            if (measurable.layoutId != DetailFollowsWidth) placeables[index] = measurable.measure(loose)
        }
        val width = maxOf(constraints.minWidth, placeables.maxOf { it?.width ?: 0 })
        measurables.forEachIndexed { index, measurable ->
            if (measurable.layoutId == DetailFollowsWidth) {
                placeables[index] = measurable.measure(Constraints(minWidth = width, maxWidth = width))
            }
        }
        val laid = placeables.map { it!! }
        layout(width, laid.sumOf { it.height }) {
            var y = 0
            laid.forEach {
                it.place(0, y)
                y += it.height
            }
        }
    }
}

internal const val DetailFollowsWidth = "followsWidth"
