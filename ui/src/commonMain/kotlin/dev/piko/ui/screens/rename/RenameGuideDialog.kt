package dev.piko.ui.screens.rename

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.PikoTopBar
import dev.piko.shared.rename.BlockGuide
import dev.piko.shared.rename.BlockGuideEntry
import dev.piko.shared.rename.BlockGuideExample
import dev.piko.shared.rename.FindReplaceOptions
import dev.piko.shared.rename.MatchHighlight
import dev.piko.shared.rename.MatchHighlighter
import dev.piko.shared.rename.RenameScope
import dev.piko.shared.rename.RenameSource
import dev.piko.shared.rename.findBlocksToRegex
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.piko.shared.rename.ReplaceBlock
import dev.piko.shared.rename.captureNumbers
import dev.piko.shared.rename.circled
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass

/**
 * 批量重命名的使用说明，由底栏左下角的问号打开，比批量重命名小一圈，盖在它上面。
 *
 * 原先排在规则区里、默认收起的一段，没有人展开读；它还占着规则区的高度，左栏要翻页。
 * 宽窗口两栏两行，一屏排完；窄窗口一栏，放不下才滚动。
 */
@Composable
internal fun RenameGuideDialog(onDismiss: () -> Unit) {
    val wide = currentWidthClass() == WidthClass.Expanded
    val columns = if (wide) 2 else 1
    RenameDialogSurface(onDismiss, maxWidth = if (wide) 840 else 560, heightFraction = 0.72f) {
        Column(modifier = Modifier.fillMaxSize()) {
            PikoTopBar(title = "使用说明", colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (row in BlockGuide.chunked(columns)) {
                    // 同一行的卡片等高：例子长短不一，高低错落时一行看着像没对齐
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                        for (entry in row) GuideCard(entry, Modifier.weight(1f).fillMaxHeight())
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun GuideCard(entry: BlockGuideEntry, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = LocalRenameRowColor.current, modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(entry.body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            entry.example?.let { GuideExample(it) }
        }
    }
}

/** 例子里的积木照积木条的样子画：同样的颜色、同样的字，读者对照着就能在积木条上拼出来。 */
@Composable
private fun GuideExample(example: BlockGuideExample) {
    val label = MaterialTheme.typography.bodySmall
    val colors = MaterialTheme.colorScheme
    val numbers = captureNumbers(example.find)
    Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GuideBlocks("查找") {
            example.find.forEachIndexed { index, block ->
                MiniChip(chipLabel(block) + (numbers[index]?.let(::circled) ?: ""), blockColor(index), blockContentColor(index))
            }
        }
        GuideBlocks("替换") {
            if (example.replace.isEmpty()) Text("（留空）", style = label, color = colors.onSurfaceVariant)
            example.replace.forEach { block ->
                val source = (block as? ReplaceBlock.Piece)?.number?.let { number -> numbers.indexOf(number).takeIf { it >= 0 } }
                if (source != null) {
                    MiniChip(chipLabel(block), blockColor(source), blockContentColor(source))
                } else {
                    MiniChip(chipLabel(block), colors.surfaceContainer, colors.onSurface)
                }
            }
        }
        // 改前名里匹配到的部分按块的颜色铺底，与预览同一套颜色：读者看见的是「找到了哪段」，不必在脑中跑一遍匹配
        val highlighted = remember(example) { highlightExample(example) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                buildAnnotatedString {
                    append(example.input)
                    for ((range, block) in highlighted) {
                        addStyle(SpanStyle(background = blockColor(block), color = blockContentColor(block)), range.first, range.last + 1)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "改为", tint = colors.primary, modifier = Modifier.size(16.dp))
            Text(example.result, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun highlightExample(example: BlockGuideExample): List<MatchHighlight> {
    val options = FindReplaceOptions(search = findBlocksToRegex(example.find), useRegex = true, scope = RenameScope.FULL)
    return MatchHighlighter(options, example.find).highlights(RenameSource("guide", "", example.input, isFolder = true))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideBlocks(name: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun MiniChip(text: String, color: Color, contentColor: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color,
        contentColor = contentColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
