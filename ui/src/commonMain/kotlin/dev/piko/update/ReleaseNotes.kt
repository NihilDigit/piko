package dev.piko.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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

/**
 * 更新说明：支持多级标题（`##` / `###`）、嵌套列表与段落排版，行内粗体、代码与链接照样呈现，可选中复制。
 * 外层容器建议放在 `surfaceContainerLow` 上，使 `surfaceContainerHighest` 的行内代码背景形成清晰对比。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { releaseNoteBlocks(markdown) }
    val colors = MaterialTheme.colorScheme
    val inlineStyles = remember(colors) {
        InlineStyles(
            code = SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = colors.surfaceContainerHighest,
                color = colors.onSurface,
            ),
            link = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)),
        )
    }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            blocks.forEachIndexed { index, block ->
                val text = remember(block, inlineStyles) { inlineMarkdown(block.text, inlineStyles) }
                when (block) {
                    is ReleaseNoteBlock.Heading -> {
                        val isSubheading = block.level >= 3
                        Text(
                            text = text,
                            style = if (isSubheading) {
                                MaterialTheme.typography.labelLargeEmphasized
                            } else {
                                MaterialTheme.typography.titleSmallEmphasized
                            },
                            color = if (isSubheading) colors.onSurfaceVariant else colors.onSurface,
                            modifier = Modifier.padding(
                                top = when {
                                    index == 0 -> 0.dp
                                    isSubheading -> 6.dp
                                    else -> 10.dp
                                },
                            ),
                        )
                    }
                    is ReleaseNoteBlock.Bullet -> {
                        val marker = when (block.depth) {
                            0 -> "•"
                            1 -> "◦"
                            else -> "▪"
                        }
                        Row(modifier = Modifier.padding(start = (14 * block.depth).dp)) {
                            Text(
                                text = marker,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface,
                            )
                        }
                    }
                    is ReleaseNoteBlock.Paragraph -> Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                    )
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
