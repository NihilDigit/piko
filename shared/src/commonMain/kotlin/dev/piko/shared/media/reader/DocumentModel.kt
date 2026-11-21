package dev.piko.shared.media.reader

enum class DocumentKind {
    PlainText,
    Markdown,
}

enum class TableAlignment {
    Start,
    Center,
    End,
}

/**
 * 富文本行内节点，不依赖 Compose UI 类型以便在 `shared` 跨平台解析与单元测试。
 */
sealed interface InlineSpan {
    data class Text(val text: String) : InlineSpan
    data class Bold(val children: List<InlineSpan>) : InlineSpan
    data class Italic(val children: List<InlineSpan>) : InlineSpan
    data class Strikethrough(val children: List<InlineSpan>) : InlineSpan
    data class Code(val code: String) : InlineSpan
    data class Link(val destination: String, val children: List<InlineSpan>) : InlineSpan
    data class Image(val url: String, val alt: String) : InlineSpan
    data object LineBreak : InlineSpan
}

/**
 * 面向 `LazyColumn` 虚拟化的分块文档模型：
 * 每个顶级块携带稳定整数 [id] 与供页内搜索使用的纯文本 [plainText]。
 */
sealed interface DocumentBlock {
    val id: Int
    val plainText: String

    data class Heading(
        override val id: Int,
        val level: Int,
        val spans: List<InlineSpan>,
        override val plainText: String,
    ) : DocumentBlock

    data class Paragraph(
        override val id: Int,
        val spans: List<InlineSpan>,
        override val plainText: String,
    ) : DocumentBlock

    data class PlainTextChunk(
        override val id: Int,
        val startLine: Int,
        val text: String,
    ) : DocumentBlock {
        override val plainText: String get() = text
    }

    data class CodeBlock(
        override val id: Int,
        val language: String?,
        val code: String,
    ) : DocumentBlock {
        override val plainText: String get() = code
    }

    data class BlockQuote(
        override val id: Int,
        val children: List<DocumentBlock>,
        override val plainText: String,
    ) : DocumentBlock

    data class ListItemBlock(
        override val id: Int,
        val ordered: Boolean,
        val number: Int?,
        val depth: Int,
        val checked: Boolean?,
        val spans: List<InlineSpan>,
        override val plainText: String,
    ) : DocumentBlock

    data class ThematicBreak(
        override val id: Int,
    ) : DocumentBlock {
        override val plainText: String get() = ""
    }

    data class TableBlock(
        override val id: Int,
        val headers: List<List<InlineSpan>>,
        val alignments: List<TableAlignment>,
        val rows: List<List<List<InlineSpan>>>,
        override val plainText: String,
    ) : DocumentBlock

    data class ImageBlock(
        override val id: Int,
        val url: String,
        val alt: String,
    ) : DocumentBlock {
        override val plainText: String get() = alt.ifBlank { url }
    }
}

/**
 * 解析完成的文档快照。Markdown 同时保留渲染块 [blocks] 与可虚拟化的源码分块 [rawBlocks]。
 */
data class ParsedDocument(
    val kind: DocumentKind,
    val rawText: String,
    val blocks: List<DocumentBlock>,
    val rawBlocks: List<DocumentBlock.PlainTextChunk>,
    val encodingName: String,
    val totalBytes: Long,
    val loadedBytes: Long,
    val truncated: Boolean,
    val lineCount: Int,
    val looksBinary: Boolean = false,
)

/**
 * 页内检索命中记录：定位到具体块索引与块内命中次数。
 */
data class DocumentSearchMatch(
    val blockIndex: Int,
    val matchCountInBlock: Int,
)

data class DocumentSearchResult(
    val query: String,
    val matches: List<DocumentSearchMatch>,
    val totalOccurrences: Int,
) {
    val blockIndices: List<Int> get() = matches.map { it.blockIndex }
}

fun searchDocumentBlocks(blocks: List<DocumentBlock>, query: String): DocumentSearchResult {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return DocumentSearchResult("", emptyList(), 0)
    val matches = ArrayList<DocumentSearchMatch>()
    var total = 0
    for (index in blocks.indices) {
        val text = blocks[index].plainText
        if (text.isEmpty()) continue
        var count = 0
        var from = 0
        while (from < text.length) {
            val found = text.indexOf(trimmed, startIndex = from, ignoreCase = true)
            if (found < 0) break
            count++
            from = found + trimmed.length
        }
        if (count > 0) {
            matches += DocumentSearchMatch(index, count)
            total += count
        }
    }
    return DocumentSearchResult(trimmed, matches, total)
}

fun InlineSpan.flattenPlainText(): String = buildString {
    appendInlinePlainText(this@flattenPlainText)
}

fun List<InlineSpan>.flattenPlainText(): String = buildString {
    for (span in this@flattenPlainText) {
        appendInlinePlainText(span)
    }
}

private fun StringBuilder.appendInlinePlainText(span: InlineSpan) {
    when (span) {
        is InlineSpan.Text -> append(span.text)
        is InlineSpan.Bold -> span.children.forEach { appendInlinePlainText(it) }
        is InlineSpan.Italic -> span.children.forEach { appendInlinePlainText(it) }
        is InlineSpan.Strikethrough -> span.children.forEach { appendInlinePlainText(it) }
        is InlineSpan.Code -> append(span.code)
        is InlineSpan.Link -> span.children.forEach { appendInlinePlainText(it) }
        is InlineSpan.Image -> append(span.alt.ifBlank { span.url })
        InlineSpan.LineBreak -> append('\n')
    }
}
