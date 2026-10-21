package dev.piko.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * M3 分段式属性表：外层裁 14dp 大圆角，各行 4dp 内圆角、行间 2dp 间隙，短值左右对齐，路径/哈希等长值上下两行显示。
 */
@Composable
internal fun DetailFields(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((label, value) in rows) {
            val multiline = value.length > 26 || value.contains('›') || value.contains('/') || label == "哈希" || label == "位置"
            Surface(
                color = colors.surfaceContainerLow,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (multiline) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        SelectionContainer {
                            Text(text = value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        SelectionContainer {
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
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
