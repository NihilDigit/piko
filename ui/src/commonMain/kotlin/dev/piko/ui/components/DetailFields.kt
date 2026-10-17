package dev.piko.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * 标签与值两列。标签列取最长的那个标签的宽度，各行的值左沿对齐；值放不下就换行，位置、哈希都要看全。
 */
@Composable
internal fun DetailFields(rows: List<Pair<String, String>>) {
    val colors = MaterialTheme.colorScheme
    Layout(
        content = {
            for ((label, value) in rows) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                SelectionContainer {
                    Text(text = value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
    ) { measurables, constraints ->
        val labels = measurables.filterIndexed { index, _ -> index % 2 == 0 }.map { it.measure(Constraints()) }
        val labelWidth = labels.maxOfOrNull { it.width } ?: 0
        val gap = PropertyLabelGap.roundToPx()
        val valueMax = if (constraints.hasBoundedWidth) (constraints.maxWidth - labelWidth - gap).coerceAtLeast(0) else Constraints.Infinity
        val values = measurables.filterIndexed { index, _ -> index % 2 == 1 }.map { it.measure(Constraints(maxWidth = valueMax)) }
        val rowGap = PropertyRowGap.roundToPx()
        val rowHeights = labels.indices.map { maxOf(labels[it].height, values[it].height) }
        val width = (labelWidth + gap + (values.maxOfOrNull { it.width } ?: 0)).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = rowHeights.sum() + rowGap * (rowHeights.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            labels.indices.forEach { row ->
                labels[row].place(0, y)
                values[row].place(labelWidth + gap, y)
                y += rowHeights[row] + rowGap
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


private val PropertyLabelGap = 16.dp
private val PropertyRowGap = 8.dp
