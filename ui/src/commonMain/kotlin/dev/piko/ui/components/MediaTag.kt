package dev.piko.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 解析出的元信息标签：发布组、集数范围、清晰度、中字、无码等。
 *
 * 纯展示，不用 Chip：M3 的 Chip 是可点击的筛选或操作，拿来展示会让人以为能点。
 * [onMedia] 为 true 时叠在封面上用：深色半透明底加浅色字，任何画面上都保持 3:1 以上的对比度。
 * 宽度设上限：联合发布组的名字（「Airota&Nekomoe kissaten&VCB-Studio」）能占满整张卡片，把其余标签全挤掉。
 */
@Composable
fun MediaTag(
    text: String,
    modifier: Modifier = Modifier,
    onMedia: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(MediaTagCorner),
        color = if (onMedia) Color.Black.copy(alpha = ON_MEDIA_ALPHA) else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (onMedia) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.widthIn(max = MediaTagMaxWidth),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 一行标签，放不下的整个丢掉，不换行也不截成半个：列表行与卡片的高度要稳定。
 * 调用方按优先级排好顺序，排在后面的先被挤掉。
 */
@Composable
fun MediaTagRow(
    tags: List<String>,
    modifier: Modifier = Modifier,
    onMedia: Boolean = false,
) {
    if (tags.isEmpty()) return
    Layout(
        content = { tags.forEach { MediaTag(it, onMedia = onMedia) } },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = MediaTagGap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = mutableListOf<Placeable>()
        var width = 0
        for (measurable in measurables) {
            val placeable = measurable.measure(loose)
            val next = if (placeables.isEmpty()) placeable.width else width + gap + placeable.width
            if (next > constraints.maxWidth) break
            placeables += placeable
            width = next
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gap
            }
        }
    }
}

private val MediaTagCorner = 4.dp
private val MediaTagGap = 4.dp
private val MediaTagMaxWidth = 120.dp
private const val ON_MEDIA_ALPHA = 0.6f
