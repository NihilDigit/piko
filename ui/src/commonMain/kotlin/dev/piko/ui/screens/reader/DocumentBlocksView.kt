package dev.piko.ui.screens.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
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
import androidx.compose.ui.unit.Dp
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

private const val CODE_SLICE_MAX_LINES = 120
private const val CODE_SLICE_MAX_CHARS = 6_144
private const val CODE_SINGLE_LINE_HARD_LIMIT = 2_048
private const val TABLE_SLICE_MAX_ROWS = 40
private const val MAX_TABLE_COLUMNS = 64
private const val MAX_NESTED_TABLE_ROWS = 80
private const val MAX_NESTED_CODE_SLICES = 4
private const val MAX_BLOCKQUOTE_DEPTH = 6
private const val MAX_FLATTENED_QUOTE_CHILDREN = 256
private const val MAX_INLINE_SPAN_DEPTH = 16

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

internal sealed interface ReaderRenderItem {
    val key: String
    val sourceBlockIndex: Int
    val isLastInBlock: Boolean
    val contentType: Any
    fun containsQuery(trimmedQuery: String): Boolean

    data class SingleBlock(
        override val key: String,
        override val sourceBlockIndex: Int,
        val block: DocumentBlock,
    ) : ReaderRenderItem {
        override val isLastInBlock: Boolean get() = true
        override val contentType: Any get() = block::class
        override fun containsQuery(trimmedQuery: String): Boolean = true
    }

    data class CodeSlice(
        override val key: String,
        override val sourceBlockIndex: Int,
        val language: String?,
        val fullCode: String,
        val sliceText: String,
        val isFirstInBlock: Boolean,
        override val isLastInBlock: Boolean,
    ) : ReaderRenderItem {
        override val contentType: Any get() = CodeSlice::class
        override fun containsQuery(trimmedQuery: String): Boolean =
            sliceText.contains(trimmedQuery, ignoreCase = true)
    }

    data class TableSlice(
        override val key: String,
        override val sourceBlockIndex: Int,
        val headers: List<List<InlineSpan>>,
        val alignments: List<TableAlignment>,
        val rows: List<List<List<InlineSpan>>>,
        val rowOffset: Int,
        val totalRows: Int,
        val isFirstInBlock: Boolean,
        override val isLastInBlock: Boolean,
    ) : ReaderRenderItem {
        override val contentType: Any get() = TableSlice::class
        override fun containsQuery(trimmedQuery: String): Boolean =
            (isFirstInBlock && headers.any { cell -> spansContainQuery(cell, trimmedQuery) }) ||
                rows.any { row -> row.any { cell -> spansContainQuery(cell, trimmedQuery) } }
    }
}

internal fun expandDocumentBlocksForViewport(
    blocks: List<DocumentBlock>,
    keyPrefix: String,
): List<ReaderRenderItem> {
    if (blocks.isEmpty()) return emptyList()
    val result = ArrayList<ReaderRenderItem>(blocks.size + 8)
    blocks.forEachIndexed { index, block ->
        val baseKey = "${keyPrefix}_${block.id}"
        when (block) {
            is DocumentBlock.CodeBlock -> {
                val slices = splitCodeIntoSlices(block.code)
                if (slices.size <= 1) {
                    result += ReaderRenderItem.SingleBlock(
                        key = baseKey,
                        sourceBlockIndex = index,
                        block = block,
                    )
                } else {
                    slices.forEachIndexed { sliceIdx, text ->
                        result += ReaderRenderItem.CodeSlice(
                            key = "${baseKey}_cs_$sliceIdx",
                            sourceBlockIndex = index,
                            language = block.language,
                            fullCode = block.code,
                            sliceText = text,
                            isFirstInBlock = sliceIdx == 0,
                            isLastInBlock = sliceIdx == slices.lastIndex,
                        )
                    }
                }
            }

            is DocumentBlock.TableBlock -> {
                if (block.rows.size <= TABLE_SLICE_MAX_ROWS) {
                    result += ReaderRenderItem.SingleBlock(
                        key = baseKey,
                        sourceBlockIndex = index,
                        block = block,
                    )
                } else {
                    val total = block.rows.size
                    var offset = 0
                    var sliceIdx = 0
                    while (offset < total) {
                        val end = (offset + TABLE_SLICE_MAX_ROWS).coerceAtMost(total)
                        result += ReaderRenderItem.TableSlice(
                            key = "${baseKey}_ts_$sliceIdx",
                            sourceBlockIndex = index,
                            headers = block.headers,
                            alignments = block.alignments,
                            rows = block.rows.subList(offset, end),
                            rowOffset = offset,
                            totalRows = total,
                            isFirstInBlock = offset == 0,
                            isLastInBlock = end == total,
                        )
                        offset = end
                        sliceIdx++
                    }
                }
            }

            else -> {
                result += ReaderRenderItem.SingleBlock(
                    key = baseKey,
                    sourceBlockIndex = index,
                    block = block,
                )
            }
        }
    }
    return result
}

internal fun splitCodeIntoSlices(code: String): List<String> {
    if (code.length <= CODE_SLICE_MAX_CHARS) {
        var newlines = 0
        for (ch in code) {
            if (ch == '\n' && ++newlines > CODE_SLICE_MAX_LINES) break
        }
        if (newlines <= CODE_SLICE_MAX_LINES) return listOf(code)
    }
    val slices = ArrayList<String>()
    val current = StringBuilder(minOf(code.length, CODE_SLICE_MAX_CHARS))
    var lineCount = 0
    var cursor = 0
    while (cursor < code.length) {
        val newlineIdx = code.indexOf('\n', cursor)
        val lineEnd = if (newlineIdx < 0) code.length else newlineIdx
        var segStart = cursor
        while (segStart < lineEnd) {
            val segEnd = minOf(lineEnd, segStart + CODE_SINGLE_LINE_HARD_LIMIT)
            val segLen = segEnd - segStart
            if (current.isNotEmpty() && (current.length + segLen > CODE_SLICE_MAX_CHARS || lineCount >= CODE_SLICE_MAX_LINES)) {
                slices += current.toString()
                current.setLength(0)
                lineCount = 0
            }
            if (current.isNotEmpty()) {
                current.append('\n')
            }
            current.append(code, segStart, segEnd)
            lineCount++
            segStart = segEnd
        }
        if (cursor == lineEnd) {
            if (lineCount >= CODE_SLICE_MAX_LINES || current.length + 1 > CODE_SLICE_MAX_CHARS) {
                slices += current.toString()
                current.setLength(0)
                lineCount = 0
            } else if (current.isNotEmpty()) {
                current.append('\n')
                lineCount++
            } else {
                current.append('\n')
                lineCount = 1
            }
        }
        cursor = if (newlineIdx < 0) code.length else newlineIdx + 1
    }
    if (current.isNotEmpty() || slices.isEmpty()) {
        slices += current.toString()
    }
    return slices
}

private fun spansContainQuery(spans: List<InlineSpan>, query: String): Boolean {
    val stack = ArrayDeque<InlineSpan>(spans.size)
    spans.forEach(stack::addLast)
    while (stack.isNotEmpty()) {
        when (val span = stack.removeFirst()) {
            is InlineSpan.Text -> if (span.text.contains(query, ignoreCase = true)) return true
            is InlineSpan.Code -> if (span.code.contains(query, ignoreCase = true)) return true
            is InlineSpan.Image -> if (span.alt.contains(query, ignoreCase = true)) return true
            is InlineSpan.Bold -> span.children.forEach(stack::addLast)
            is InlineSpan.Italic -> span.children.forEach(stack::addLast)
            is InlineSpan.Strikethrough -> span.children.forEach(stack::addLast)
            is InlineSpan.Link -> span.children.forEach(stack::addLast)
            InlineSpan.LineBreak -> Unit
        }
    }
    return false
}

@Composable
internal fun ReaderRenderItemView(
    item: ReaderRenderItem,
    style: ReaderTypographyStyle,
    searchQuery: String,
    isActiveMatchBlock: Boolean,
    onOpenUrl: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    when (item) {
        is ReaderRenderItem.SingleBlock -> DocumentBlockView(
            block = item.block,
            style = style,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            onOpenUrl = onOpenUrl,
            onCopyCode = onCopyCode,
            modifier = modifier,
        )

        is ReaderRenderItem.CodeSlice -> CodeSliceView(
            language = item.language,
            fullCode = item.fullCode,
            sliceText = item.sliceText,
            isFirst = item.isFirstInBlock,
            isLast = item.isLastInBlock,
            style = style,
            colors = colors,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            onCopyCode = onCopyCode,
            modifier = modifier,
        )

        is ReaderRenderItem.TableSlice -> TableSliceView(
            headers = item.headers,
            alignments = item.alignments,
            rows = item.rows,
            rowOffset = item.rowOffset,
            totalRows = item.totalRows,
            isFirst = item.isFirstInBlock,
            isLast = item.isLastInBlock,
            style = style,
            colors = colors,
            searchQuery = searchQuery,
            isActiveMatchBlock = isActiveMatchBlock,
            onOpenUrl = onOpenUrl,
            modifier = modifier,
        )
    }
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
    quoteDepth: Int = 0,
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
            quoteDepth = quoteDepth,
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
    val slices = remember(block.text) { splitCodeIntoSlices(block.text) }
    Column(modifier = modifier.fillMaxWidth()) {
        for (slice in slices) {
            val annotated = remember(slice, colors, style.isEInk, searchQuery, isActiveMatchBlock) {
                highlightPlainText(
                    text = slice,
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
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
    val slices = remember(block.code) {
        val rawSlices = splitCodeIntoSlices(block.code)
        if (rawSlices.size <= MAX_NESTED_CODE_SLICES) rawSlices else rawSlices.take(MAX_NESTED_CODE_SLICES)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        slices.forEachIndexed { idx, sliceText ->
            CodeSliceView(
                language = block.language,
                fullCode = block.code,
                sliceText = sliceText,
                isFirst = idx == 0,
                isLast = idx == slices.lastIndex,
                style = style,
                colors = colors,
                searchQuery = searchQuery,
                isActiveMatchBlock = isActiveMatchBlock,
                onCopyCode = onCopyCode,
            )
        }
    }
}

@Composable
private fun CodeSliceView(
    language: String?,
    fullCode: String,
    sliceText: String,
    isFirst: Boolean,
    isLast: Boolean,
    style: ReaderTypographyStyle,
    colors: ColorScheme,
    searchQuery: String,
    isActiveMatchBlock: Boolean,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (style.isEInk) colors.surface else colors.surfaceContainerLow
    val borderColor = if (style.isEInk) colors.outline else colors.outlineVariant.copy(alpha = 0.65f)
    val shape = sliceCornerShape(isFirst = isFirst, isLast = isLast, radius = 12.dp)
    val annotated = remember(sliceText, colors, style.isEInk, searchQuery, isActiveMatchBlock) {
        highlightPlainText(
            text = sliceText,
            query = searchQuery,
            colors = colors,
            isEInk = style.isEInk,
            isActiveMatchBlock = isActiveMatchBlock,
        )
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .sliceSideAndEndBorders(borderColor, isFirst = isFirst, isLast = isLast, shape = shape),
        shape = shape,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (isFirst) {
                DisableSelection {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = language.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (style.isEInk) colors.onSurface else colors.onSurfaceVariant,
                        )
                        TooltipIconButton(
                            icon = Icons.Outlined.ContentCopy,
                            label = stringResource(Res.string.reader_action_copy_code),
                            onClick = { onCopyCode(fullCode) },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        start = 14.dp,
                        end = 14.dp,
                        top = if (isFirst) 0.dp else 4.dp,
                        bottom = if (isLast) 12.dp else 4.dp,
                    ),
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
    quoteDepth: Int,
    modifier: Modifier = Modifier,
) {
    val accent = if (style.isEInk) colors.outline else colors.primary.copy(alpha = 0.72f)
    val container = if (style.isEInk) colors.surface else colors.surfaceContainerLow.copy(alpha = 0.65f)
    val effectiveChildren = remember(block.children, quoteDepth) {
        if (quoteDepth < MAX_BLOCKQUOTE_DEPTH) {
            block.children.take(MAX_FLATTENED_QUOTE_CHILDREN)
        } else {
            flattenDeepQuoteChildren(block.children)
        }
    }
    // 使用 drawBehind 绘制左侧引用竖条，避免嵌套 IntrinsicSize.Min 引发 O(2^depth) 布局测量爆炸
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .drawBehind {
                val barW = 3.dp.toPx()
                drawRect(
                    color = accent,
                    topLeft = Offset.Zero,
                    size = Size(barW, size.height),
                )
            }
            .padding(start = 17.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (child in effectiveChildren) {
            DocumentBlockView(
                block = child,
                style = style,
                searchQuery = searchQuery,
                isActiveMatchBlock = isActiveMatchBlock,
                onOpenUrl = onOpenUrl,
                onCopyCode = onCopyCode,
                quoteDepth = quoteDepth + 1,
            )
        }
    }
}

private fun flattenDeepQuoteChildren(initialChildren: List<DocumentBlock>): List<DocumentBlock> {
    val flattened = ArrayList<DocumentBlock>(minOf(initialChildren.size, MAX_FLATTENED_QUOTE_CHILDREN))
    val queue = ArrayDeque<DocumentBlock>(initialChildren.size)
    initialChildren.forEach(queue::addLast)
    while (queue.isNotEmpty() && flattened.size < MAX_FLATTENED_QUOTE_CHILDREN) {
        val current = queue.removeFirst()
        if (current is DocumentBlock.BlockQuote) {
            for (i in current.children.lastIndex downTo 0) {
                queue.addFirst(current.children[i])
            }
        } else {
            flattened += current
        }
    }
    return flattened
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
    val boundedRows = remember(block.rows) {
        if (block.rows.size <= MAX_NESTED_TABLE_ROWS) block.rows else block.rows.take(MAX_NESTED_TABLE_ROWS)
    }
    TableSliceView(
        headers = block.headers,
        alignments = block.alignments,
        rows = boundedRows,
        rowOffset = 0,
        totalRows = boundedRows.size,
        isFirst = true,
        isLast = true,
        style = style,
        colors = colors,
        searchQuery = searchQuery,
        isActiveMatchBlock = isActiveMatchBlock,
        onOpenUrl = onOpenUrl,
        modifier = modifier,
    )
}

@Composable
private fun TableSliceView(
    headers: List<List<InlineSpan>>,
    alignments: List<TableAlignment>,
    rows: List<List<List<InlineSpan>>>,
    rowOffset: Int,
    totalRows: Int,
    isFirst: Boolean,
    isLast: Boolean,
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
    val shape = sliceCornerShape(isFirst = isFirst, isLast = isLast, radius = 12.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .sliceSideAndEndBorders(borderColor, isFirst = isFirst, isLast = isLast, shape = shape),
        shape = shape,
        color = stripeBg,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            if (isFirst && headers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .background(headerBg)
                        .padding(vertical = 8.dp),
                ) {
                    val boundedHeaders = if (headers.size <= MAX_TABLE_COLUMNS) headers else headers.take(MAX_TABLE_COLUMNS)
                    boundedHeaders.forEachIndexed { colIndex, cellSpans ->
                        val alignment = alignments.getOrElse(colIndex) { TableAlignment.Start }
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
                if (rows.isNotEmpty()) {
                    HorizontalDivider(thickness = 1.dp, color = borderColor)
                }
            }
            rows.forEachIndexed { localIndex, row ->
                val globalRowIndex = rowOffset + localIndex
                Row(
                    modifier = Modifier
                        .background(if (globalRowIndex % 2 == 1) altBg else stripeBg)
                        .padding(vertical = 8.dp),
                ) {
                    val boundedRow = if (row.size <= MAX_TABLE_COLUMNS) row else row.take(MAX_TABLE_COLUMNS)
                    boundedRow.forEachIndexed { colIndex, cellSpans ->
                        val alignment = alignments.getOrElse(colIndex) { TableAlignment.Start }
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
                if (globalRowIndex < totalRows - 1) {
                    HorizontalDivider(thickness = 1.dp, color = borderColor.copy(alpha = if (style.isEInk) 1f else 0.55f))
                }
            }
        }
    }
}

private fun sliceCornerShape(isFirst: Boolean, isLast: Boolean, radius: Dp): Shape = when {
    isFirst && isLast -> RoundedCornerShape(radius)
    isFirst -> RoundedCornerShape(topStart = radius, topEnd = radius, bottomStart = 0.dp, bottomEnd = 0.dp)
    isLast -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = radius, bottomEnd = radius)
    else -> RectangleShape
}

private fun Modifier.sliceSideAndEndBorders(
    borderColor: Color,
    isFirst: Boolean,
    isLast: Boolean,
    shape: Shape,
): Modifier = if (isFirst && isLast) {
    this.border(BorderStroke(1.dp, borderColor), shape)
} else {
    this
        .clip(shape)
        .drawBehind {
            val stroke = 1.dp.toPx()
            val half = stroke / 2f
            drawLine(borderColor, Offset(half, 0f), Offset(half, size.height), strokeWidth = stroke)
            drawLine(borderColor, Offset(size.width - half, 0f), Offset(size.width - half, size.height), strokeWidth = stroke)
            if (isFirst) {
                drawLine(borderColor, Offset(0f, half), Offset(size.width, half), strokeWidth = stroke)
            }
            if (isLast) {
                drawLine(borderColor, Offset(0f, size.height - half), Offset(size.width, size.height - half), strokeWidth = stroke)
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

private fun appendPlainSpansIteratively(builder: AnnotatedString.Builder, rootSpans: List<InlineSpan>) {
    val stack = ArrayDeque<InlineSpan>(rootSpans.size)
    for (i in rootSpans.lastIndex downTo 0) stack.addFirst(rootSpans[i])
    while (stack.isNotEmpty()) {
        when (val span = stack.removeFirst()) {
            is InlineSpan.Text -> builder.append(span.text)
            is InlineSpan.Code -> builder.append(span.code)
            is InlineSpan.Image -> builder.append(span.alt.ifBlank { span.url })
            InlineSpan.LineBreak -> builder.append('\n')
            is InlineSpan.Bold -> for (i in span.children.lastIndex downTo 0) stack.addFirst(span.children[i])
            is InlineSpan.Italic -> for (i in span.children.lastIndex downTo 0) stack.addFirst(span.children[i])
            is InlineSpan.Strikethrough -> for (i in span.children.lastIndex downTo 0) stack.addFirst(span.children[i])
            is InlineSpan.Link -> for (i in span.children.lastIndex downTo 0) stack.addFirst(span.children[i])
        }
    }
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
        fun appendSpan(span: InlineSpan, depth: Int) {
            if (depth >= MAX_INLINE_SPAN_DEPTH) {
                appendPlainSpansIteratively(this, listOf(span))
                return
            }
            when (span) {
                is InlineSpan.Text -> append(span.text)
                is InlineSpan.Bold -> {
                    val start = length
                    span.children.forEach { appendSpan(it, depth + 1) }
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                }
                is InlineSpan.Italic -> {
                    val start = length
                    span.children.forEach { appendSpan(it, depth + 1) }
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                }
                is InlineSpan.Strikethrough -> {
                    val start = length
                    span.children.forEach { appendSpan(it, depth + 1) }
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
                        span.children.forEach { appendSpan(it, depth + 1) }
                        pop()
                    } else {
                        span.children.forEach { appendSpan(it, depth + 1) }
                    }
                }
                is InlineSpan.Image -> {
                    append(span.alt.ifBlank { span.url })
                }
                InlineSpan.LineBreak -> append('\n')
            }
        }
        spans.forEach { appendSpan(it, 0) }
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
