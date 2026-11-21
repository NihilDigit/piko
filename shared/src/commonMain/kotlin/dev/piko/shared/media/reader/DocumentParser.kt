package dev.piko.shared.media.reader

import dev.piko.data.repository.isMarkdownDocument
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

private const val MAX_CHUNK_LINES = 24
private const val MAX_CHUNK_CHARS = 2400
private const val MAX_SINGLE_LINE_CHARS = 2000
internal const val MAX_MARKDOWN_NESTING_DEPTH = 16

/**
 * 将解码后的纯文本或 Markdown 正文解析为面向 `LazyColumn` 虚拟化的分块文档模型。
 */
fun parseDocument(
    fileName: String,
    decoded: DecodedDocumentText,
    totalBytes: Long,
    loadedBytes: Long,
    truncated: Boolean,
): ParsedDocument {
    val kind = if (fileName.isMarkdownDocument()) DocumentKind.Markdown else DocumentKind.PlainText
    val text = decoded.text
    val lineCount = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
    val rawBlocks = chunkPlainText(text)
    val blocks = when (kind) {
        DocumentKind.PlainText -> rawBlocks
        DocumentKind.Markdown -> parseMarkdown(text)
    }
    return ParsedDocument(
        kind = kind,
        rawText = text,
        blocks = blocks,
        rawBlocks = rawBlocks,
        encodingName = decoded.encodingName,
        totalBytes = totalBytes,
        loadedBytes = loadedBytes,
        truncated = truncated,
        lineCount = lineCount,
        looksBinary = decoded.looksBinary,
    )
}

/**
 * 将纯文本按段落边界与行数/字符数上限切分为 [DocumentBlock.PlainTextChunk] 列表，
 * 同时将超长单行（如压缩 JSON）安全折分，保证大文本在 `LazyColumn` 中平滑虚拟化不卡顿。
 */
fun chunkPlainText(
    text: String,
    maxChunkLines: Int = MAX_CHUNK_LINES,
    maxChunkChars: Int = MAX_CHUNK_CHARS,
    maxSingleLineChars: Int = MAX_SINGLE_LINE_CHARS,
): List<DocumentBlock.PlainTextChunk> {
    if (text.isEmpty()) return emptyList()
    val chunks = ArrayList<DocumentBlock.PlainTextChunk>()
    val buf = StringBuilder()
    var nextId = 0
    var currentLine = 1
    var chunkStartLine = 1
    var linesInChunk = 0

    fun flush() {
        if (buf.isEmpty()) return
        chunks += DocumentBlock.PlainTextChunk(
            id = nextId++,
            startLine = chunkStartLine,
            text = buf.toString(),
        )
        buf.clear()
        linesInChunk = 0
    }

    var pos = 0
    val n = text.length
    while (pos <= n) {
        val nl = text.indexOf('\n', pos)
        val lineEnd = if (nl < 0) n else nl
        val line = text.substring(pos, lineEnd)

        if (line.length > maxSingleLineChars) {
            flush()
            var offset = 0
            while (offset < line.length) {
                val end = minOf(offset + maxSingleLineChars, line.length)
                chunks += DocumentBlock.PlainTextChunk(
                    id = nextId++,
                    startLine = currentLine,
                    text = line.substring(offset, end),
                )
                offset = end
            }
        } else {
            val isBlank = line.isBlank()
            val projectedChars = buf.length + (if (buf.isNotEmpty()) 1 else 0) + line.length
            if (buf.isNotEmpty() && (linesInChunk >= maxChunkLines || projectedChars > maxChunkChars || (isBlank && linesInChunk >= 4))) {
                flush()
            }
            if (buf.isEmpty()) {
                chunkStartLine = currentLine
            } else {
                buf.append('\n')
            }
            buf.append(line)
            linesInChunk++
            if (isBlank && linesInChunk >= 4) {
                flush()
            }
        }

        currentLine++
        if (nl < 0) break
        pos = nl + 1
    }
    flush()
    return chunks
}

/**
 * 基于 `org.jetbrains:markdown` 的 GFM AST 解析器，将 Markdown 文档转换为扁平可虚拟化的 [DocumentBlock] 列表。
 */
fun parseMarkdown(markdown: String): List<DocumentBlock> {
    if (markdown.isBlank()) return emptyList()
    val safeMarkdown = clampExcessBlockQuoteMarkers(markdown, MAX_MARKDOWN_NESTING_DEPTH)
    val flavour = GFMFlavourDescriptor()
    val root = MarkdownParser(flavour, cancellationToken = org.intellij.markdown.parser.CancellationToken.NonCancellable)
        .buildMarkdownTreeFromString(safeMarkdown as CharSequence)
    val linkDefinitions = collectLinkDefinitions(root, safeMarkdown)
    val walker = MarkdownAstWalker(safeMarkdown, linkDefinitions)
    return walker.walkDocument(root)
}

private fun clampExcessBlockQuoteMarkers(markdown: String, maxDepth: Int): String {
    if (!hasExcessBlockQuoteLine(markdown, maxDepth)) return markdown
    val sb = StringBuilder(markdown.length)
    var fenceChar = '\u0000'
    var fenceLen = 0
    var pos = 0
    val n = markdown.length
    while (pos <= n) {
        val nl = markdown.indexOf('\n', pos)
        val lineEnd = if (nl < 0) n else nl
        val hasCr = lineEnd > pos && markdown[lineEnd - 1] == '\r'
        val contentEnd = if (hasCr) lineEnd - 1 else lineEnd

        var i = pos
        var quoteCount = 0
        while (i < contentEnd) {
            var spaces = 0
            while (i + spaces < contentEnd && markdown[i + spaces] == ' ' && spaces < 3) spaces++
            if (i + spaces < contentEnd && markdown[i + spaces] == '>') {
                quoteCount++
                i += spaces + 1
                if (i < contentEnd && markdown[i] == ' ') i++
            } else {
                break
            }
        }

        // Track fenced code blocks so literal `>>>>...` lines inside code fences are preserved verbatim
        var fPos = i
        var leadingSpaces = 0
        while (fPos < contentEnd && markdown[fPos] == ' ' && leadingSpaces < 3) {
            fPos++
            leadingSpaces++
        }
        if (fPos < contentEnd && (markdown[fPos] == '`' || markdown[fPos] == '~')) {
            val ch = markdown[fPos]
            var run = 0
            while (fPos + run < contentEnd && markdown[fPos + run] == ch) run++
            if (run >= 3) {
                val restStart = fPos + run
                if (fenceChar == '\u0000') {
                    val validOpen = ch != '`' || (restStart until contentEnd).none { markdown[it] == '`' }
                    if (validOpen) {
                        fenceChar = ch
                        fenceLen = run
                    }
                } else if (ch == fenceChar && run >= fenceLen) {
                    val onlyWhitespaceAfter = (restStart until contentEnd).all { markdown[it] == ' ' || markdown[it] == '\t' }
                    if (onlyWhitespaceAfter) {
                        fenceChar = '\u0000'
                        fenceLen = 0
                    }
                }
            }
        }

        if (fenceChar == '\u0000' && quoteCount > maxDepth) {
            repeat(maxDepth) { sb.append('>') }
            if (i < contentEnd) {
                sb.append(' ')
                sb.append(markdown, i, contentEnd)
            }
        } else {
            sb.append(markdown, pos, contentEnd)
        }
        if (hasCr) sb.append('\r')
        if (nl < 0) break
        sb.append('\n')
        pos = nl + 1
    }
    return sb.toString()
}

private fun hasExcessBlockQuoteLine(markdown: String, maxDepth: Int): Boolean {
    var pos = 0
    val n = markdown.length
    while (pos < n) {
        var i = pos
        var quotes = 0
        while (i < n && markdown[i] != '\n' && markdown[i] != '\r') {
            var spaces = 0
            while (i + spaces < n && markdown[i + spaces] == ' ' && spaces < 3) spaces++
            if (i + spaces < n && markdown[i + spaces] == '>') {
                quotes++
                if (quotes > maxDepth) return true
                i += spaces + 1
                if (i < n && markdown[i] == ' ') i++
            } else {
                break
            }
        }
        val nl = markdown.indexOf('\n', pos)
        if (nl < 0) break
        pos = nl + 1
    }
    return false
}

private fun collectLinkDefinitions(root: ASTNode, source: String): Map<String, String> {
    val map = HashMap<String, String>()
    val stack = ArrayDeque<ASTNode>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
            val label = node.findChild(MarkdownElementTypes.LINK_LABEL)
                ?.let { normalizeLinkLabel(it.getTextInNode(source).toString()) }
            val dest = node.findChild(MarkdownElementTypes.LINK_DESTINATION)
                ?.let { cleanDestination(it.getTextInNode(source).toString()) }
            if (!label.isNullOrEmpty() && !dest.isNullOrEmpty() && label !in map) {
                map[label] = dest
            }
            continue
        }
        val children = node.children
        for (idx in children.lastIndex downTo 0) {
            stack.addLast(children[idx])
        }
    }
    return map
}

private class MarkdownAstWalker(
    private val source: String,
    private val linkDefinitions: Map<String, String>,
) {
    private var nextId = 0

    fun walkDocument(root: ASTNode): List<DocumentBlock> {
        val out = ArrayList<DocumentBlock>()
        walkBlockChildren(root.children, out, blockDepth = 0)
        return out
    }

    private fun walkBlockChildren(
        children: List<ASTNode>,
        out: MutableList<DocumentBlock>,
        blockDepth: Int,
    ) {
        if (blockDepth >= MAX_MARKDOWN_NESTING_DEPTH) {
            walkBlockChildrenFlatIterative(children, out, baseListDepth = MAX_MARKDOWN_NESTING_DEPTH)
            return
        }
        for (node in children) {
            when (node.type) {
                MarkdownTokenTypes.EOL,
                MarkdownTokenTypes.WHITE_SPACE,
                MarkdownTokenTypes.BLOCK_QUOTE,
                MarkdownElementTypes.LINK_DEFINITION,
                -> continue

                MarkdownElementTypes.ATX_1 -> addHeading(node, 1, out)
                MarkdownElementTypes.ATX_2 -> addHeading(node, 2, out)
                MarkdownElementTypes.ATX_3 -> addHeading(node, 3, out)
                MarkdownElementTypes.ATX_4 -> addHeading(node, 4, out)
                MarkdownElementTypes.ATX_5 -> addHeading(node, 5, out)
                MarkdownElementTypes.ATX_6 -> addHeading(node, 6, out)
                MarkdownElementTypes.SETEXT_1 -> addHeading(node, 1, out)
                MarkdownElementTypes.SETEXT_2 -> addHeading(node, 2, out)

                MarkdownElementTypes.PARAGRAPH -> addParagraphOrImage(node, out)
                MarkdownElementTypes.CODE_FENCE -> addCodeFence(node, out)
                MarkdownElementTypes.CODE_BLOCK -> addIndentedCodeBlock(node, out)
                MarkdownElementTypes.BLOCK_QUOTE -> addBlockQuote(node, out, blockDepth)
                MarkdownElementTypes.UNORDERED_LIST -> walkList(node, ordered = false, depth = 0, blockDepth = blockDepth + 1, out = out)
                MarkdownElementTypes.ORDERED_LIST -> walkList(node, ordered = true, depth = 0, blockDepth = blockDepth + 1, out = out)
                MarkdownTokenTypes.HORIZONTAL_RULE -> out += DocumentBlock.ThematicBreak(nextId++)
                GFMElementTypes.TABLE -> addTable(node, out)
                MarkdownElementTypes.HTML_BLOCK -> {
                    val raw = node.getTextInNode(source).toString().trimEnd('\n')
                    if (raw.isNotBlank()) {
                        out += DocumentBlock.CodeBlock(nextId++, language = "html", code = raw)
                    }
                }
                else -> {
                    if (node.children.isNotEmpty()) {
                        walkBlockChildren(node.children, out, blockDepth + 1)
                    }
                }
            }
        }
    }

    /**
     * 达到最大嵌套层数后改用显式栈扁平展开后续块节点，彻底杜绝深层嵌套引用/列表引发 `StackOverflowError`。
     */
    private fun walkBlockChildrenFlatIterative(
        initialChildren: List<ASTNode>,
        out: MutableList<DocumentBlock>,
        baseListDepth: Int,
    ) {
        val stack = ArrayDeque<ASTNode>(initialChildren.size)
        for (idx in initialChildren.lastIndex downTo 0) {
            stack.addLast(initialChildren[idx])
        }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            when (node.type) {
                MarkdownTokenTypes.EOL,
                MarkdownTokenTypes.WHITE_SPACE,
                MarkdownTokenTypes.BLOCK_QUOTE,
                MarkdownTokenTypes.LIST_BULLET,
                MarkdownTokenTypes.LIST_NUMBER,
                GFMTokenTypes.CHECK_BOX,
                MarkdownElementTypes.LINK_DEFINITION,
                -> continue

                MarkdownElementTypes.ATX_1 -> addHeading(node, 1, out)
                MarkdownElementTypes.ATX_2 -> addHeading(node, 2, out)
                MarkdownElementTypes.ATX_3 -> addHeading(node, 3, out)
                MarkdownElementTypes.ATX_4 -> addHeading(node, 4, out)
                MarkdownElementTypes.ATX_5 -> addHeading(node, 5, out)
                MarkdownElementTypes.ATX_6 -> addHeading(node, 6, out)
                MarkdownElementTypes.SETEXT_1 -> addHeading(node, 1, out)
                MarkdownElementTypes.SETEXT_2 -> addHeading(node, 2, out)

                MarkdownElementTypes.PARAGRAPH -> addParagraphOrImage(node, out)
                MarkdownElementTypes.CODE_FENCE -> addCodeFence(node, out)
                MarkdownElementTypes.CODE_BLOCK -> addIndentedCodeBlock(node, out)
                MarkdownTokenTypes.HORIZONTAL_RULE -> out += DocumentBlock.ThematicBreak(nextId++)
                GFMElementTypes.TABLE -> addTable(node, out)
                MarkdownElementTypes.HTML_BLOCK -> {
                    val raw = node.getTextInNode(source).toString().trimEnd('\n')
                    if (raw.isNotBlank()) {
                        out += DocumentBlock.CodeBlock(nextId++, language = "html", code = raw)
                    }
                }
                MarkdownElementTypes.LIST_ITEM -> {
                    val checkboxNode = node.findChild(GFMTokenTypes.CHECK_BOX)
                    val checked: Boolean? = checkboxNode?.getTextInNode(source)?.toString()?.let { raw ->
                        raw.contains('x', ignoreCase = true)
                    }
                    val inlineSpans = ArrayList<InlineSpan>()
                    val trailing = ArrayList<ASTNode>()
                    for (itemChild in node.children) {
                        when (itemChild.type) {
                            MarkdownTokenTypes.LIST_BULLET,
                            MarkdownTokenTypes.LIST_NUMBER,
                            GFMTokenTypes.CHECK_BOX,
                            MarkdownTokenTypes.EOL,
                            MarkdownTokenTypes.WHITE_SPACE,
                            -> continue
                            MarkdownElementTypes.PARAGRAPH -> {
                                if (inlineSpans.isNotEmpty()) inlineSpans += InlineSpan.LineBreak
                                inlineSpans += trimEdgeWhitespace(extractInlines(itemChild))
                            }
                            else -> trailing += itemChild
                        }
                    }
                    val normalizedSpans = trimEdgeWhitespace(inlineSpans)
                    if (normalizedSpans.isNotEmpty() || checked != null) {
                        out += DocumentBlock.ListItemBlock(
                            id = nextId++,
                            ordered = false,
                            number = null,
                            depth = baseListDepth.coerceAtMost(MAX_MARKDOWN_NESTING_DEPTH),
                            checked = checked,
                            spans = normalizedSpans,
                            plainText = normalizedSpans.flattenPlainText(),
                        )
                    }
                    for (idx in trailing.lastIndex downTo 0) {
                        stack.addLast(trailing[idx])
                    }
                }
                else -> {
                    val ch = node.children
                    for (idx in ch.lastIndex downTo 0) {
                        stack.addLast(ch[idx])
                    }
                }
            }
        }
    }

    private fun addHeading(node: ASTNode, level: Int, out: MutableList<DocumentBlock>) {
        val contentNode = node.findChild(MarkdownTokenTypes.ATX_CONTENT)
            ?: node.findChild(MarkdownTokenTypes.SETEXT_CONTENT)
            ?: node
        val spans = trimEdgeWhitespace(extractInlines(contentNode))
        val plain = spans.flattenPlainText()
        if (plain.isNotEmpty()) {
            out += DocumentBlock.Heading(nextId++, level.coerceIn(1, 6), spans, plain)
        }
    }

    private fun addParagraphOrImage(node: ASTNode, out: MutableList<DocumentBlock>) {
        val spans = trimEdgeWhitespace(extractInlines(node))
        if (spans.isEmpty()) return
        val singleImage = spans.singleOrNull() as? InlineSpan.Image
        if (singleImage != null) {
            out += DocumentBlock.ImageBlock(nextId++, singleImage.url, singleImage.alt)
            return
        }
        val plain = spans.flattenPlainText()
        if (plain.isNotEmpty()) {
            out += DocumentBlock.Paragraph(nextId++, spans, plain)
        }
    }

    private fun addCodeFence(node: ASTNode, out: MutableList<DocumentBlock>) {
        val lang = node.findChild(MarkdownTokenTypes.FENCE_LANG)
            ?.getTextInNode(source)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var started = false
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> {
                    lines += child.getTextInNode(source).toString()
                    started = true
                }
                MarkdownTokenTypes.EOL -> {
                    if (started) {
                        lines += "\n"
                    } else {
                        started = true
                    }
                }
                MarkdownTokenTypes.CODE_FENCE_END -> break
            }
        }
        val code = lines.joinToString("").trimEnd('\n')
        out += DocumentBlock.CodeBlock(nextId++, lang, code)
    }

    private fun addIndentedCodeBlock(node: ASTNode, out: MutableList<DocumentBlock>) {
        val raw = buildString {
            for (child in node.children) {
                when (child.type) {
                    MarkdownTokenTypes.CODE_LINE -> {
                        val line = child.getTextInNode(source).toString()
                        append(stripCodeIndent(line))
                    }
                    MarkdownTokenTypes.EOL -> append('\n')
                }
            }
        }.trimEnd('\n')
        if (raw.isNotEmpty()) {
            out += DocumentBlock.CodeBlock(nextId++, language = null, code = raw)
        }
    }

    private fun addBlockQuote(node: ASTNode, out: MutableList<DocumentBlock>, blockDepth: Int) {
        val filtered = node.children.filter { it.type != MarkdownTokenTypes.BLOCK_QUOTE }
        if (blockDepth >= MAX_MARKDOWN_NESTING_DEPTH) {
            walkBlockChildrenFlatIterative(filtered, out, baseListDepth = MAX_MARKDOWN_NESTING_DEPTH)
            return
        }
        val inner = ArrayList<DocumentBlock>()
        walkBlockChildren(filtered, inner, blockDepth + 1)
        if (inner.isNotEmpty()) {
            val plain = inner.joinToString("\n") { it.plainText }
            out += DocumentBlock.BlockQuote(nextId++, inner, plain)
        }
    }

    private fun walkList(
        listNode: ASTNode,
        ordered: Boolean,
        depth: Int,
        blockDepth: Int,
        out: MutableList<DocumentBlock>,
    ) {
        if (blockDepth >= MAX_MARKDOWN_NESTING_DEPTH || depth >= MAX_MARKDOWN_NESTING_DEPTH) {
            walkBlockChildrenFlatIterative(listNode.children, out, baseListDepth = depth.coerceAtMost(MAX_MARKDOWN_NESTING_DEPTH))
            return
        }
        var autoNumber = 1
        for (child in listNode.children) {
            if (child.type != MarkdownElementTypes.LIST_ITEM) continue
            val parsedNumber = if (ordered) {
                child.findChild(MarkdownTokenTypes.LIST_NUMBER)
                    ?.getTextInNode(source)?.toString()?.trim()
                    ?.takeWhile { it.isDigit() }?.toIntOrNull()
                    ?.also { autoNumber = it }
                    ?: autoNumber
            } else {
                null
            }
            if (ordered) autoNumber++

            val checkboxNode = child.findChild(GFMTokenTypes.CHECK_BOX)
            val checked: Boolean? = checkboxNode?.getTextInNode(source)?.toString()?.let { raw ->
                raw.contains('x', ignoreCase = true)
            }

            val inlineSpans = ArrayList<InlineSpan>()
            val nestedLists = ArrayList<Pair<ASTNode, Boolean>>()
            val extraBlocks = ArrayList<DocumentBlock>()

            for (itemChild in child.children) {
                when (itemChild.type) {
                    MarkdownTokenTypes.LIST_BULLET,
                    MarkdownTokenTypes.LIST_NUMBER,
                    GFMTokenTypes.CHECK_BOX,
                    MarkdownTokenTypes.EOL,
                    MarkdownTokenTypes.WHITE_SPACE,
                    -> continue

                    MarkdownElementTypes.PARAGRAPH -> {
                        if (inlineSpans.isNotEmpty()) inlineSpans += InlineSpan.LineBreak
                        inlineSpans += trimEdgeWhitespace(extractInlines(itemChild))
                    }
                    MarkdownElementTypes.UNORDERED_LIST -> nestedLists += itemChild to false
                    MarkdownElementTypes.ORDERED_LIST -> nestedLists += itemChild to true
                    else -> walkBlockChildren(listOf(itemChild), extraBlocks, blockDepth + 1)
                }
            }

            val normalizedSpans = trimEdgeWhitespace(inlineSpans)
            if (normalizedSpans.isNotEmpty() || checked != null) {
                out += DocumentBlock.ListItemBlock(
                    id = nextId++,
                    ordered = ordered,
                    number = parsedNumber,
                    depth = depth,
                    checked = checked,
                    spans = normalizedSpans,
                    plainText = normalizedSpans.flattenPlainText(),
                )
            }
            out.addAll(extraBlocks)
            for ((nested, nestedOrdered) in nestedLists) {
                walkList(nested, ordered = nestedOrdered, depth = depth + 1, blockDepth = blockDepth + 1, out = out)
            }
        }
    }

    private fun addTable(node: ASTNode, out: MutableList<DocumentBlock>) {
        val headerNode = node.findChild(GFMElementTypes.HEADER) ?: return
        val headers = headerNode.children
            .filter { it.type == GFMTokenTypes.CELL }
            .map { trimEdgeWhitespace(extractInlines(it)) }
        if (headers.isEmpty()) return

        val separatorText = node.findChild(GFMTokenTypes.TABLE_SEPARATOR)
            ?.getTextInNode(source)?.toString().orEmpty()
        val alignments = parseTableAlignments(separatorText, headers.size)

        val rows = node.children
            .filter { it.type == GFMElementTypes.ROW }
            .map { rowNode ->
                val cells = rowNode.children
                    .filter { it.type == GFMTokenTypes.CELL }
                    .map { trimEdgeWhitespace(extractInlines(it)) }
                if (cells.size >= headers.size) {
                    cells.take(headers.size)
                } else {
                    cells + List(headers.size - cells.size) { emptyList() }
                }
            }

        val plain = buildString {
            append(headers.joinToString(" ") { it.flattenPlainText() })
            for (row in rows) {
                append('\n')
                append(row.joinToString(" ") { it.flattenPlainText() })
            }
        }
        out += DocumentBlock.TableBlock(
            id = nextId++,
            headers = headers,
            alignments = alignments,
            rows = rows,
            plainText = plain,
        )
    }

    private fun parseTableAlignments(separatorLine: String, columnCount: Int): List<TableAlignment> {
        val segments = separatorLine.trim().trim('|').split('|').map { it.trim() }
        return List(columnCount) { idx ->
            val seg = segments.getOrNull(idx).orEmpty()
            val left = seg.startsWith(':')
            val right = seg.endsWith(':')
            when {
                left && right -> TableAlignment.Center
                right -> TableAlignment.End
                else -> TableAlignment.Start
            }
        }
    }

    private fun extractInlines(node: ASTNode): List<InlineSpan> {
        val out = ArrayList<InlineSpan>()
        appendInlines(node.children, out, inlineDepth = 0)
        return mergeAdjacentText(out)
    }

    private fun appendInlines(
        children: List<ASTNode>,
        out: MutableList<InlineSpan>,
        inlineDepth: Int,
    ) {
        if (inlineDepth >= MAX_MARKDOWN_NESTING_DEPTH) {
            appendInlinesFlatIterative(children, out)
            return
        }
        for (child in children) {
            when (child.type) {
                MarkdownElementTypes.STRONG -> {
                    val inner = extractDelimitedInlines(child, MarkdownTokenTypes.EMPH, inlineDepth + 1)
                    if (inner.isNotEmpty()) out += InlineSpan.Bold(inner)
                }
                MarkdownElementTypes.EMPH -> {
                    val inner = extractDelimitedInlines(child, MarkdownTokenTypes.EMPH, inlineDepth + 1)
                    if (inner.isNotEmpty()) out += InlineSpan.Italic(inner)
                }
                GFMElementTypes.STRIKETHROUGH -> {
                    val inner = extractDelimitedInlines(child, GFMTokenTypes.TILDE, inlineDepth + 1)
                    if (inner.isNotEmpty()) out += InlineSpan.Strikethrough(inner)
                }
                MarkdownElementTypes.CODE_SPAN -> {
                    val raw = child.getTextInNode(source).toString()
                    out += InlineSpan.Code(stripCodeSpanTicks(raw))
                }
                MarkdownElementTypes.INLINE_LINK -> {
                    val dest = child.findChild(MarkdownElementTypes.LINK_DESTINATION)
                        ?.let { cleanDestination(it.getTextInNode(source).toString()) }.orEmpty()
                    val textNode = child.findChild(MarkdownElementTypes.LINK_TEXT)
                    val labelSpans = textNode?.let { extractBracketedInlines(it, inlineDepth + 1) }.orEmpty()
                    val finalSpans = labelSpans.ifEmpty { listOf(InlineSpan.Text(dest)) }
                    if (dest.isNotEmpty()) {
                        out += InlineSpan.Link(dest, finalSpans)
                    } else {
                        out += finalSpans
                    }
                }
                MarkdownElementTypes.FULL_REFERENCE_LINK,
                MarkdownElementTypes.SHORT_REFERENCE_LINK,
                -> {
                    val labelNode = child.findChild(MarkdownElementTypes.LINK_LABEL)
                    val textNode = child.findChild(MarkdownElementTypes.LINK_TEXT) ?: labelNode
                    val lookupKey = labelNode?.let { normalizeLinkLabel(it.getTextInNode(source).toString()) }
                    val dest = lookupKey?.let { linkDefinitions[it] }
                    val labelSpans = textNode?.let { extractBracketedInlines(it, inlineDepth + 1) }.orEmpty()
                    if (!dest.isNullOrEmpty()) {
                        out += InlineSpan.Link(dest, labelSpans.ifEmpty { listOf(InlineSpan.Text(dest)) })
                    } else {
                        out += InlineSpan.Text(child.getTextInNode(source).toString())
                    }
                }
                MarkdownElementTypes.AUTOLINK -> {
                    val raw = child.getTextInNode(source).toString().removePrefix("<").removeSuffix(">")
                    if (raw.isNotEmpty()) {
                        val dest = if ('@' in raw && !raw.contains("://") && !raw.startsWith("mailto:", ignoreCase = true)) {
                            "mailto:$raw"
                        } else {
                            raw
                        }
                        out += InlineSpan.Link(dest, listOf(InlineSpan.Text(raw)))
                    }
                }
                GFMTokenTypes.GFM_AUTOLINK -> {
                    val raw = child.getTextInNode(source).toString()
                    if (raw.isNotEmpty()) {
                        val dest = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
                        out += InlineSpan.Link(dest, listOf(InlineSpan.Text(raw)))
                    }
                }
                MarkdownTokenTypes.EMAIL_AUTOLINK -> {
                    val raw = child.getTextInNode(source).toString()
                    if (raw.isNotEmpty()) {
                        out += InlineSpan.Link("mailto:$raw", listOf(InlineSpan.Text(raw)))
                    }
                }
                MarkdownElementTypes.IMAGE -> {
                    val linkNode = child.findChild(MarkdownElementTypes.INLINE_LINK)
                        ?: child.findChild(MarkdownElementTypes.FULL_REFERENCE_LINK)
                        ?: child.findChild(MarkdownElementTypes.SHORT_REFERENCE_LINK)
                    val dest = linkNode?.findChild(MarkdownElementTypes.LINK_DESTINATION)
                        ?.let { cleanDestination(it.getTextInNode(source).toString()) }
                        ?: linkNode?.findChild(MarkdownElementTypes.LINK_LABEL)
                            ?.let { linkDefinitions[normalizeLinkLabel(it.getTextInNode(source).toString())] }
                        ?: ""
                    val alt = linkNode?.findChild(MarkdownElementTypes.LINK_TEXT)
                        ?.let { extractBracketedInlines(it, inlineDepth + 1).flattenPlainText() }
                        .orEmpty()
                    if (dest.isNotEmpty() || alt.isNotEmpty()) {
                        out += InlineSpan.Image(dest, alt)
                    }
                }
                MarkdownTokenTypes.HARD_LINE_BREAK -> out += InlineSpan.LineBreak
                MarkdownTokenTypes.EOL -> out += InlineSpan.Text(" ")
                MarkdownTokenTypes.WHITE_SPACE -> out += InlineSpan.Text(" ")
                MarkdownTokenTypes.HTML_TAG -> {
                    val tag = child.getTextInNode(source).toString()
                    if (tag.equals("<br>", ignoreCase = true) || tag.equals("<br/>", ignoreCase = true) || tag.equals("<br />", ignoreCase = true)) {
                        out += InlineSpan.LineBreak
                    } else {
                        out += InlineSpan.Text(tag)
                    }
                }
                else -> {
                    if (child.children.isEmpty()) {
                        val raw = child.getTextInNode(source).toString()
                        out += InlineSpan.Text(unescapeMarkdownText(raw))
                    } else {
                        appendInlines(child.children, out, inlineDepth + 1)
                    }
                }
            }
        }
    }

    private fun appendInlinesFlatIterative(
        initialChildren: List<ASTNode>,
        out: MutableList<InlineSpan>,
    ) {
        val stack = ArrayDeque<ASTNode>(initialChildren.size)
        for (idx in initialChildren.lastIndex downTo 0) {
            stack.addLast(initialChildren[idx])
        }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            when (node.type) {
                MarkdownElementTypes.CODE_SPAN -> {
                    val raw = node.getTextInNode(source).toString()
                    out += InlineSpan.Code(stripCodeSpanTicks(raw))
                }
                MarkdownElementTypes.LINK_DESTINATION,
                MarkdownElementTypes.LINK_TITLE,
                MarkdownTokenTypes.EMPH,
                GFMTokenTypes.TILDE,
                MarkdownTokenTypes.LBRACKET,
                MarkdownTokenTypes.RBRACKET,
                MarkdownTokenTypes.LPAREN,
                MarkdownTokenTypes.RPAREN,
                -> continue
                MarkdownTokenTypes.HARD_LINE_BREAK -> out += InlineSpan.LineBreak
                MarkdownTokenTypes.EOL,
                MarkdownTokenTypes.WHITE_SPACE,
                -> out += InlineSpan.Text(" ")
                else -> {
                    val ch = node.children
                    if (ch.isEmpty()) {
                        val raw = node.getTextInNode(source).toString()
                        if (raw.isNotEmpty()) {
                            out += InlineSpan.Text(unescapeMarkdownText(raw))
                        }
                    } else {
                        for (idx in ch.lastIndex downTo 0) {
                            stack.addLast(ch[idx])
                        }
                    }
                }
            }
        }
    }

    private fun extractDelimitedInlines(
        node: ASTNode,
        delimiterType: org.intellij.markdown.IElementType,
        inlineDepth: Int,
    ): List<InlineSpan> {
        val children = node.children
        var start = 0
        var end = children.size
        while (start < end && children[start].type == delimiterType) start++
        while (end > start && children[end - 1].type == delimiterType) end--
        if (start >= end) return emptyList()
        val out = ArrayList<InlineSpan>()
        appendInlines(children.subList(start, end), out, inlineDepth)
        return mergeAdjacentText(out)
    }

    private fun extractBracketedInlines(node: ASTNode, inlineDepth: Int): List<InlineSpan> {
        val children = node.children
        if (children.isEmpty()) {
            val raw = node.getTextInNode(source).toString().removePrefix("[").removeSuffix("]")
            return if (raw.isEmpty()) emptyList() else listOf(InlineSpan.Text(unescapeMarkdownText(raw)))
        }
        var start = 0
        var end = children.size
        if (start < end && children[start].type == MarkdownTokenTypes.LBRACKET) start++
        if (end > start && children[end - 1].type == MarkdownTokenTypes.RBRACKET) end--
        if (start >= end) return emptyList()
        val out = ArrayList<InlineSpan>()
        appendInlines(children.subList(start, end), out, inlineDepth)
        return mergeAdjacentText(out)
    }
}

private fun ASTNode.findChild(type: org.intellij.markdown.IElementType): ASTNode? =
    children.firstOrNull { it.type == type }

private fun stripCodeIndent(line: String): String = when {
    line.startsWith("    ") -> line.substring(4)
    line.startsWith("\t") -> line.substring(1)
    else -> line
}

private fun stripCodeSpanTicks(raw: String): String {
    var tickCount = 0
    while (tickCount < raw.length && raw[tickCount] == '`') tickCount++
    if (tickCount == 0 || raw.length < tickCount * 2) return raw
    val inner = raw.substring(tickCount, raw.length - tickCount).replace('\n', ' ')
    return if (inner.length >= 2 && inner.startsWith(' ') && inner.endsWith(' ') && inner.any { it != ' ' }) {
        inner.substring(1, inner.length - 1)
    } else {
        inner
    }
}

private fun cleanDestination(raw: String): String =
    unescapeMarkdownText(raw.trim().removePrefix("<").removeSuffix(">"))

private fun normalizeLinkLabel(raw: String): String =
    raw.trim().removePrefix("[").removeSuffix("]").trim().replace(Regex("\\s+"), " ").lowercase()

private fun mergeAdjacentText(spans: List<InlineSpan>): List<InlineSpan> {
    if (spans.size <= 1) return spans
    val out = ArrayList<InlineSpan>(spans.size)
    val pending = StringBuilder()
    fun flush() {
        if (pending.isNotEmpty()) {
            out += InlineSpan.Text(pending.toString())
            pending.clear()
        }
    }
    for (span in spans) {
        if (span is InlineSpan.Text) {
            pending.append(span.text)
        } else {
            flush()
            out += span
        }
    }
    flush()
    return out
}

private fun trimEdgeWhitespace(spans: List<InlineSpan>): List<InlineSpan> {
    if (spans.isEmpty()) return spans
    val list = spans.toMutableList()
    while (list.isNotEmpty()) {
        val first = list.first()
        if (first is InlineSpan.Text) {
            val trimmed = first.text.trimStart()
            if (trimmed.isEmpty()) list.removeAt(0) else {
                list[0] = InlineSpan.Text(trimmed)
                break
            }
        } else break
    }
    while (list.isNotEmpty()) {
        val last = list.last()
        if (last is InlineSpan.Text) {
            val trimmed = last.text.trimEnd()
            if (trimmed.isEmpty()) list.removeAt(list.lastIndex) else {
                list[list.lastIndex] = InlineSpan.Text(trimmed)
                break
            }
        } else break
    }
    return list
}

private const val ESCAPABLE_PUNCTUATION = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

internal fun unescapeMarkdownText(raw: String): String {
    if ('\\' !in raw && '&' !in raw) return raw
    val sb = StringBuilder(raw.length)
    var i = 0
    val n = raw.length
    while (i < n) {
        val c = raw[i]
        if (c == '\\' && i + 1 < n && raw[i + 1] in ESCAPABLE_PUNCTUATION) {
            sb.append(raw[i + 1])
            i += 2
            continue
        }
        if (c == '&') {
            val semi = raw.indexOf(';', i + 1)
            if (semi in (i + 2)..(i + 10)) {
                val decoded = decodeHtmlEntity(raw.substring(i + 1, semi))
                if (decoded != null) {
                    sb.append(decoded)
                    i = semi + 1
                    continue
                }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

private fun decodeHtmlEntity(entity: String): String? = when {
    entity == "amp" -> "&"
    entity == "lt" -> "<"
    entity == "gt" -> ">"
    entity == "quot" -> "\""
    entity == "apos" -> "'"
    entity == "nbsp" -> "\u00A0"
    entity.startsWith("#x", ignoreCase = true) -> {
        val cp = entity.substring(2).toIntOrNull(16)
        cp?.takeIf { it in 1..0x10FFFF }?.let(::codePointToString)
    }
    entity.startsWith("#") -> {
        val cp = entity.substring(1).toIntOrNull(10)
        cp?.takeIf { it in 1..0x10FFFF }?.let(::codePointToString)
    }
    else -> null
}

private fun codePointToString(cp: Int): String = if (cp <= 0xFFFF) {
    cp.toChar().toString()
} else {
    val adjusted = cp - 0x10000
    val high = (0xD800 + (adjusted ushr 10)).toChar()
    val low = (0xDC00 + (adjusted and 0x3FF)).toChar()
    charArrayOf(high, low).concatToString()
}
