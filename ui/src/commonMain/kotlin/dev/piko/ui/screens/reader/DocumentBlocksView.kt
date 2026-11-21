package dev.piko.ui.screens.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.piko.shared.media.reader.DocumentBlock
import dev.piko.shared.media.reader.InlineSpan
import dev.piko.shared.media.reader.TableAlignment
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.reader_action_copy_code
import dev.piko.ui.theme.tabular

@Immutable
internal data class ReaderTypographyStyle(
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float,
    val fontFamily: FontFamily,
    val isEInk: Boolean,
) {
    val bodyFontSize: TextUnit get() = fontSizeSp.sp
    val bodyLineHeight: TextUnit get() = (fontSizeSp * lineHeightMultiplier).sp
    val codeFontSize: TextUnit get() = (fontSizeSp * 0.88f).coerceAtLeast(11f).sp
    val codeLineHeight: TextUnit get() = (fontSizeSp * 0.88f * 1.48f).coerceAtLeast(16f).sp
}

@Composable
internal fun DocumentBlockView(
    block: DocumentBlock,
    style: ReaderTypographyStyle,
    searchQuery: String,
    isActiveMatchBlock: Boolean,
    onOpenUrl: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    when (block) {
        is DocumentBlock.Heading -> HeadingBlockView(
            block = block,
            style = style,
            colors = colors,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            onOpenUrl = onOpenUrl,
            modifier = modifier,
        )
        is DocumentBlock.Paragraph -> ParagraphBlockView(
            spans = block.spans,
            style = style,
            colors = colors,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            onOpenUrl = onOpenUrl,
            modifier = modifier,
        )
        is DocumentBlock.PlainTextChunk -> PlainTextChunkView(
            block = block,
            style = style,
            colors = colors,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            modifier = modifier,
        )
        is DocumentBlock.CodeBlock -> CodeBlockView(
            block = block,
            style = style,
            colors = colors,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            onCopyCode = onCopyCode,
            modifier = modifier,
        )
        is DocumentBlock.BlockQuote -> BlockQuoteView(
            block = block,
            style = style,
            colors = colors,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            onOpenUrl = onOpenUrl,
            onCopyCode = onCopyCode,
            modifier = modifier,
        )
        is DocumentBlock.ListItemBlock -> ListItemBlockView(
            block = block,
            style = style,
            colors = colors,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            onOpenUrl = onOpenUrl,
            modifier = modifier,
        )
        is DocumentBlock.ThematicBreak -> {
            HorizontalDivider(
                modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
                thickness = 1.dp,
                color = if (style.isEInk) colors.outline else colors.outlineVariant,
            )
        }
        is DocumentBlock.TableBlock -> TableBlockView(
            block = block,
            style = style,
            colors = colors,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            onOpenUrl = onOpenUrl,
            modifier = modifier,
        )
        is DocumentBlock.ImageBlock -> ImageBlockView(
            block = block,
            style = style,
            colors = colors,
            modifier = modifier,
        )
    }
}

@Composable
private fun HeadingBlockView(
    block: DocumentBlock.Heading,
    style: ReaderTypographyStyle,
    colors: ColorScheme,
    searchQuery: String,
    isActiveMatchBlock: Boolean,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale = when (block.level) {
        1 -> 1.65f
        2 -> 1.38f
        3 -> 1.20f
        4 -> 1.08f
        5 -> 1.00f
        else -> 0.94f
    }
    val headingSize = (style.fontSizeSp * scale).sp
    val headingLineHeight = (style.fontSizeSp * scale * 1.32f).sp
    val textColor = if (block.level >= 6 && !style.isEInk) colors.onSurfaceVariant else colors.onSurface
    val annotated = remember(block.spans, colors, style, searchQuery, isActiveMatchBlock) {
        buildInlineAnnotatedString(
            spans = block.spans,
            colors = colors,
            isEInk = style.isEInk,
            codeFontSize = (style.fontSizeSp * scale * 0.85f).sp,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            onOpenUrl = onOpenUrl,
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (block.level <= 2) 14.dp else 8.dp, bottom = 2.dp),
    ) {
        Text(
            text = annotated,
            style = TextStyle(
                fontFamily = style.fontFamily,
                fontSize = headingSize,
                lineHeight = headingLineHeight,
                fontWeight = if (block.level <= 3) FontWeight.Bold else FontWeight.SemiBold,
                color = textColor,
            ),
        )
        if (block.level <= 2) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(
                thickness = 1.dp,
                color = if (style.isEInk) colors.outline else colors.outlineVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ParagraphBlockView(
    spans: List<InlineSpan>,
    style: ReaderTypographyStyle,
    colors: ColorScheme,
    searchQuery: String,
    isActiveMatchBlock: Boolean,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(spans, colors, style, searchQuery, isActiveMatchBlock) {
        buildInlineAnnotatedString(
            spans = spans,
            colors = colors,
            isEInk = style.isEInk,
            codeFontSize = style.codeFontSize,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            onOpenUrl = onOpenUrl,
        )
    }
    Text(
        text = annotated,
        style = TextStyle(
            fontFamily = style.fontFamily,
            fontSize = style.bodyFontSize,
            lineHeight = style.bodyLineHeight,
            color = colors.onSurface,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlainTextChunkView(
    block: DocumentBlock.PlainTextChunk,
    style: ReaderTypographyStyle,
    colors: ColorScheme,
    searchQuery: String,
    isActiveMatchBlock: Boolean,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(block.text, colors, style.isEInk, searchQuery, isActiveMatchBlock) {
        highlightPlainText(
            text = block.text,
            query = searchQuery,
            colors = colors,
            isEInk = style.isEInk,
            isActiveMatchBlock = isActiveMatchBlock,
        )
    }
    Text(
        text = annotated,
        style = TextStyle(
            fontFamily = style.fontFamily,
            fontSize = style.bodyFontSize,
            lineHeight = style.bodyLineHeight,
            color = colors.onSurface,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun CodeBlockView(
    block: DocumentBlock.CodeBlock,
    style: ReaderTypographyStyle,
    colors: ColorScheme,
    searchQuery: String,
    isActiveMatchBlock: Boolean,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (style.isEInk) colors.surface else colors.surfaceContainerLow
    val borderColor = if (style.isEInk) colors.outline else colors.outlineVariant.copy(alpha = 0.65f)
    val shape = MaterialTheme.shapes.medium
    val annotated = remember(block.code, colors, style.isEInk, searchQuery, isActiveMatchBlock) {
        highlightPlainText(
            text = block.code,
            query = searchQuery,
            colors = colors,
            isEInk = style.isEInk,
            isActiveMatchBlock = isActiveMatchBlock,
        )
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = block.language.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (style.isEInk) colors.onSurface else colors.onSurfaceVariant,
                    )
                    TooltipIconButton(
                        icon = Icons.Outlined.ContentCopy,
                        label = stringResource(Res.string.reader_action_copy_code),
                        onClick = { onCopyCode(block.code) },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            ) {
                Text(
                    text = annotated,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = style.codeFontSize,
                        lineHeight = style.codeLineHeight,
                        color = colors.onSurface,
                    ),
                )
            }
        }
    }
}

@Composable
private fun BlockQuoteView(
    block: DocumentBlock.BlockQuote,
    style: ReaderTypographyStyle,
    colors: ColorScheme,
    searchQuery: String,
    isActiveMatchBlock: Boolean,
    onOpenUrl: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (style.isEInk) colors.outline else colors.primary.copy(alpha = 0.72f)
    val container = if (style.isEInk) colors.surface else colors.surfaceContainerLow.copy(alpha = 0.65f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.small)
            .background(container),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (child in block.children) {
                DocumentBlockView(
                    block = child,
                    style = style,
                    searchQuery = searchQuery,
                    isActiveMatchBlock = isActiveMatchBlock,
                    onOpenUrl = onOpenUrl,
                    onCopyCode = onCopyCode,
                )
            }
        }
    }
}

@Composable
private fun ListItemBlockView(
    block: DocumentBlock.ListItemBlock,
    style: ReaderTypographyStyle,
    colors: ColorScheme,
    searchQuery: String,
    isActiveMatchBlock: Boolean,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val indent = (block.depth.coerceAtMost(6) * 20).dp
    val annotated = remember(block.spans, colors, style, searchQuery, isActiveMatchBlock) {
        buildInlineAnnotatedString(
            spans = block.spans,
            colors = colors,
            isEInk = style.isEInk,
            codeFontSize = style.codeFontSize,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            onOpenUrl = onOpenUrl,
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indent),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DisableSelection {
            Box(
                modifier = Modifier
                    .widthIn(min = 20.dp)
                    .padding(top = ((style.fontSizeSp * style.lineHeightMultiplier - 16f) / 2f).coerceAtLeast(2f).dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                when {
                    block.checked != null -> {
                        Icon(
                            imageVector = if (block.checked == true) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (style.isEInk) colors.onSurface else colors.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    block.ordered -> {
                        Text(
                            text = "${block.number ?: 1}.",
                            style = TextStyle(
                                fontFamily = style.fontFamily,
                                fontSize = style.bodyFontSize,
                                fontWeight = FontWeight.Medium,
                                color = if (style.isEInk) colors.onSurface else colors.onSurfaceVariant,
                            ).tabular,
                        )
                    }
                    else -> {
                        val bulletModifier = if (block.depth % 2 == 0) {
                            Modifier
                                .padding(top = 5.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (style.isEInk) colors.onSurface else colors.onSurfaceVariant)
                        } else {
                            Modifier
                                .padding(top = 5.dp)
                                .size(6.dp)
                                .border(1.2.dp, if (style.isEInk) colors.onSurface else colors.onSurfaceVariant, CircleShape)
                        }
                        Box(bulletModifier)
                    }
                }
            }
        }
        Text(
            text = annotated,
            style = TextStyle(
                fontFamily = style.fontFamily,
                fontSize = style.bodyFontSize,
                lineHeight = style.bodyLineHeight,
                color = colors.onSurface,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TableBlockView(
    block: DocumentBlock.TableBlock,
    style: ReaderTypographyStyle,
    colors: ColorScheme,
    searchQuery: String,
    isActiveMatchBlock: Boolean,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (style.isEInk) colors.outline else colors.outlineVariant
    val headerBg = if (style.isEInk) colors.surfaceContainer else colors.surfaceContainerHigh
    val stripeBg = if (style.isEInk) colors.surface else colors.surfaceContainerLowest
    val altBg = if (style.isEInk) colors.surface else colors.surfaceContainerLow.copy(alpha = 0.45f)
    val cellWidth = 156.dp

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = stripeBg,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier
                    .background(headerBg)
                    .padding(vertical = 8.dp),
            ) {
                block.headers.forEachIndexed { colIndex, cellSpans ->
                    val alignment = block.alignments.getOrElse(colIndex) { TableAlignment.Start }
                    val annotated = remember(cellSpans, colors, style, searchQuery, isActiveMatchBlock) {
                        buildInlineAnnotatedString(
                            spans = cellSpans,
                            colors = colors,
                            isEInk = style.isEInk,
                            codeFontSize = style.codeFontSize,
                            searchQuery = searchQuery,
                            isActiveMatchBlock = isActiveMatchBlock,
                            onOpenUrl = onOpenUrl,
                        )
                    }
                    Text(
                        text = annotated,
                        style = TextStyle(
                            fontFamily = style.fontFamily,
                            fontSize = (style.fontSizeSp * 0.92f).sp,
                            lineHeight = (style.fontSizeSp * 0.92f * 1.4f).sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            textAlign = alignment.toTextAlign(),
                        ),
                        modifier = Modifier
                            .widthIn(min = cellWidth, max = 280.dp)
                            .padding(horizontal = 12.dp),
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = borderColor)
            block.rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .background(if (rowIndex % 2 == 1) altBg else stripeBg)
                        .padding(vertical = 8.dp),
                ) {
                    row.forEachIndexed { colIndex, cellSpans ->
                        val alignment = block.alignments.getOrElse(colIndex) { TableAlignment.Start }
                        val annotated = remember(cellSpans, colors, style, searchQuery, isActiveMatchBlock) {
                            buildInlineAnnotatedString(
                                spans = cellSpans,
                                colors = colors,
                                isEInk = style.isEInk,
                                codeFontSize = style.codeFontSize,
                                searchQuery = searchQuery,
                                isActiveMatchBlock = isActiveMatchBlock,
                                onOpenUrl = onOpenUrl,
                            )
                        }
                        Text(
                            text = annotated,
                            style = TextStyle(
                                fontFamily = style.fontFamily,
                                fontSize = (style.fontSizeSp * 0.92f).sp,
                                lineHeight = (style.fontSizeSp * 0.92f * 1.4f).sp,
                                color = colors.onSurface,
                                textAlign = alignment.toTextAlign(),
                            ),
                            modifier = Modifier
                                .widthIn(min = cellWidth, max = 280.dp)
                                .padding(horizontal = 12.dp),
                        )
                    }
                }
                if (rowIndex < block.rows.lastIndex) {
                    HorizontalDivider(thickness = 1.dp, color = borderColor.copy(alpha = if (style.isEInk) 1f else 0.55f))
                }
            }
        }
    }
}

@Composable
private fun ImageBlockView(
    block: DocumentBlock.ImageBlock,
    style: ReaderTypographyStyle,
    colors: ColorScheme,
    modifier: Modifier = Modifier,
) {
    val isRemoteHttp = block.url.startsWith("http://", ignoreCase = true) ||
        block.url.startsWith("https://", ignoreCase = true)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isRemoteHttp) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (style.isEInk) colors.surface else colors.surfaceContainerLow,
                border = BorderStroke(1.dp, if (style.isEInk) colors.outline else colors.outlineVariant.copy(alpha = 0.6f)),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                AsyncImage(
                    model = block.url,
                    contentDescription = block.alt.ifBlank { null },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                )
            }
        }
        val caption = block.alt.ifBlank { if (!isRemoteHttp) block.url else "" }
        if (caption.isNotBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = if (style.isEInk) colors.onSurface else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun TableAlignment.toTextAlign(): TextAlign = when (this) {
    TableAlignment.Start -> TextAlign.Start
    TableAlignment.Center -> TextAlign.Center
    TableAlignment.End -> TextAlign.End
}

internal fun isSafeExternalLink(url: String): Boolean {
    val trimmed = url.trim().lowercase()
    return trimmed.startsWith("https://") ||
        trimmed.startsWith("http://") ||
        trimmed.startsWith("mailto:")
}

private fun buildInlineAnnotatedString(
    spans: List<InlineSpan>,
    colors: ColorScheme,
    isEInk: Boolean,
    codeFontSize: TextUnit,
    searchQuery: String,
    isActiveMatchBlock: Boolean,
    onOpenUrl: (String) -> Unit,
): AnnotatedString {
    val codeBackground = if (isEInk) colors.surfaceContainer else colors.surfaceContainerHigh
    val linkColor = if (isEInk) colors.onSurface else colors.primary
    val base = buildAnnotatedString {
        fun appendSpan(span: InlineSpan) {
            when (span) {
                is InlineSpan.Text -> append(span.text)
                is InlineSpan.Bold -> {
                    val start = length
                    span.children.forEach(::appendSpan)
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                }
                is InlineSpan.Italic -> {
                    val start = length
                    span.children.forEach(::appendSpan)
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                }
                is InlineSpan.Strikethrough -> {
                    val start = length
                    span.children.forEach(::appendSpan)
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
                }
                is InlineSpan.Code -> {
                    val start = length
                    append(span.code)
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = codeFontSize,
                            background = codeBackground,
                        ),
                        start,
                        length,
                    )
                }
                is InlineSpan.Link -> {
                    val dest = span.destination
                    if (isSafeExternalLink(dest)) {
                        val link = LinkAnnotation.Url(
                            url = dest,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                            linkInteractionListener = LinkInteractionListener {
                                onOpenUrl(dest)
                            },
                        )
                        pushLink(link)
                        span.children.forEach(::appendSpan)
                        pop()
                    } else {
                        span.children.forEach(::appendSpan)
                    }
                }
                is InlineSpan.Image -> {
                    append(span.alt.ifBlank { span.url })
                }
                InlineSpan.LineBreak -> append('\n')
            }
        }
        spans.forEach(::appendSpan)
    }
    return applySearchHighlight(base, searchQuery, colors, isEInk, isActiveMatchBlock)
}

private fun highlightPlainText(
    text: String,
    query: String,
    colors: ColorScheme,
    isEInk: Boolean,
    isActiveMatchBlock: Boolean,
): AnnotatedString = applySearchHighlight(AnnotatedString(text), query, colors, isEInk, isActiveMatchBlock)

private fun applySearchHighlight(
    source: AnnotatedString,
    query: String,
    colors: ColorScheme,
    isEInk: Boolean,
    isActiveMatchBlock: Boolean,
): AnnotatedString {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || source.text.isEmpty()) return source
    val raw = source.text
    var pos = raw.indexOf(trimmed, ignoreCase = true)
    if (pos < 0) return source
    val highlightBg: Color
    val highlightFg: Color
    if (isEInk) {
        highlightBg = if (isActiveMatchBlock) Color.Black else colors.surfaceContainerHighest
        highlightFg = if (isActiveMatchBlock) Color.White else Color.Black
    } else {
        highlightBg = if (isActiveMatchBlock) colors.primaryContainer else colors.secondaryContainer
        highlightFg = if (isActiveMatchBlock) colors.onPrimaryContainer else colors.onSecondaryContainer
    }
    return buildAnnotatedString {
        append(source)
        while (pos >= 0 && pos < raw.length) {
            val end = (pos + trimmed.length).coerceAtMost(raw.length)
            addStyle(
                SpanStyle(
                    background = highlightBg,
                    color = highlightFg,
                    fontWeight = if (isActiveMatchBlock) FontWeight.SemiBold else null,
                ),
                pos,
                end,
            )
            pos = raw.indexOf(trimmed, startIndex = end, ignoreCase = true)
        }
    }
}
