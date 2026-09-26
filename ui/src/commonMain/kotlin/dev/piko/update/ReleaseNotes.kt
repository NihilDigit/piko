package dev.piko.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.piko.shared.update.ReleaseNoteBlock
import dev.piko.shared.update.releaseNoteBlocks

/** 更新说明：标题、列表与段落按排版档位排开，行内的粗体、代码与链接照样呈现，可选中复制。 */
@Composable
fun ReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { releaseNoteBlocks(markdown) }
    val colors = MaterialTheme.colorScheme
    val inlineStyles = remember(colors) {
        InlineStyles(
            code = SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerHighest),
            link = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)),
        )
    }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            blocks.forEachIndexed { index, block ->
                val text = remember(block, inlineStyles) { inlineMarkdown(block.text, inlineStyles) }
                when (block) {
                    is ReleaseNoteBlock.Heading -> Text(
                        text,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp),
                    )
                    is ReleaseNoteBlock.Bullet -> Row {
                        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 6.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                    is ReleaseNoteBlock.Paragraph -> Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private class InlineStyles(val code: SpanStyle, val link: TextLinkStyles)

/** 粗体 **…**、行内代码 `…` 与链接 […](…)；其余字符原样保留，没闭合的标记也原样显示。 */
private fun inlineMarkdown(text: String, styles: InlineStyles): AnnotatedString = buildAnnotatedString {
    var plainStart = 0
    for (match in INLINE_TOKEN.findAll(text)) {
        append(text, plainStart, match.range.first)
        val (bold, code, linkText, url) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            code.isNotEmpty() -> withStyle(styles.code) { append(code) }
            else -> withLink(LinkAnnotation.Url(url, styles.link)) { append(linkText) }
        }
        plainStart = match.range.last + 1
    }
    append(text, plainStart, text.length)
}

private val INLINE_TOKEN = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)""")
