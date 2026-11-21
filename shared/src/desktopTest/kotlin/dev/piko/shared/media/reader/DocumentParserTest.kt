package dev.piko.shared.media.reader

import dev.piko.data.repository.isHtmlDocument
import dev.piko.data.repository.isMarkdownDocument
import dev.piko.data.repository.isPlayableAudio
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.data.repository.isReadableTextDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocumentParserTest {
    @Test
    fun chunksLargePlainTextAndSplitsUltraLongLines() {
        val lines = (1..75).joinToString("\n") { "Line $it content" }
        val chunks = chunkPlainText(lines, maxChunkLines = 20, maxChunkChars = 500)
        assertTrue(chunks.size >= 4)
        assertEquals(1, chunks.first().startLine)
        assertEquals(lines, chunks.joinToString("\n") { it.text })

        val hugeSingleLine = "a".repeat(4500)
        val split = chunkPlainText(hugeSingleLine, maxSingleLineChars = 1000)
        assertEquals(5, split.size)
        assertTrue(split.all { it.startLine == 1 && it.text.length <= 1000 })
        assertEquals(hugeSingleLine, split.joinToString("") { it.text })
    }

    @Test
    fun parsesMarkdownHeadingsListsCodeTablesLinksAndQuotes() {
        val md = """
            # Main Title

            A paragraph with **bold**, *italic*, ~~strike~~, `inlineCode`, and [Piko](https://example.com/piko) plus [ref][r1].

            > Quoted line inside blockquote.

            - First bullet
              - Nested bullet
            - [x] Done task
            - [ ] Pending task

            1. Ordered one
            2. Ordered two

            ```kotlin
            fun greet() = println("hi")
            ```

            ---

            | Name | Score | Align |
            | :--- | :---: | ---: |
            | Alpha | 100 | right |
            | Beta | 200 | end |

            ![Cover Alt](https://example.com/cover.png)

            [r1]: https://example.com/ref
        """.trimIndent()

        val blocks = parseMarkdown(md)
        val heading = assertIs<DocumentBlock.Heading>(blocks[0])
        assertEquals(1, heading.level)
        assertEquals("Main Title", heading.plainText)

        val para = assertIs<DocumentBlock.Paragraph>(blocks[1])
        assertTrue(para.spans.any { it is InlineSpan.Bold })
        assertTrue(para.spans.any { it is InlineSpan.Italic })
        assertTrue(para.spans.any { it is InlineSpan.Strikethrough })
        assertTrue(para.spans.any { it is InlineSpan.Code && it.code == "inlineCode" })
        assertTrue(para.spans.any { it is InlineSpan.Link && it.destination == "https://example.com/piko" })
        assertTrue(para.spans.any { it is InlineSpan.Link && it.destination == "https://example.com/ref" })

        val quote = assertIs<DocumentBlock.BlockQuote>(blocks[2])
        assertEquals("Quoted line inside blockquote.", quote.plainText)

        val listItems = blocks.filterIsInstance<DocumentBlock.ListItemBlock>()
        assertEquals(6, listItems.size)
        assertEquals(0, listItems[0].depth)
        assertEquals(1, listItems[1].depth)
        assertEquals(true, listItems[2].checked)
        assertEquals(false, listItems[3].checked)
        assertTrue(listItems[4].ordered)
        assertEquals(1, listItems[4].number)
        assertEquals(2, listItems[5].number)

        val code = blocks.filterIsInstance<DocumentBlock.CodeBlock>().single()
        assertEquals("kotlin", code.language)
        assertEquals("fun greet() = println(\"hi\")", code.code)

        assertTrue(blocks.any { it is DocumentBlock.ThematicBreak })

        val table = blocks.filterIsInstance<DocumentBlock.TableBlock>().single()
        assertEquals(3, table.headers.size)
        assertEquals(listOf(TableAlignment.Start, TableAlignment.Center, TableAlignment.End), table.alignments)
        assertEquals(2, table.rows.size)
        assertEquals("Alpha", table.rows[0][0].flattenPlainText())

        val image = blocks.filterIsInstance<DocumentBlock.ImageBlock>().single()
        assertEquals("https://example.com/cover.png", image.url)
        assertEquals("Cover Alt", image.alt)
    }

    @Test
    fun searchesAcrossBlocksCaseInsensitively() {
        val doc = parseDocument(
            fileName = "notes.md",
            decoded = DecodedDocumentText("# Piko Guide\n\nPiko supports piko reader.\n\nOther paragraph.", "UTF-8"),
            totalBytes = 64,
            loadedBytes = 64,
            truncated = false,
        )
        val result = searchDocumentBlocks(doc.blocks, "piko")
        assertEquals(2, result.matches.size)
        assertEquals(3, result.totalOccurrences)
        assertEquals(listOf(0, 1), result.blockIndices)
    }

    @Test
    fun fileTypePredicatesRouteDocumentsWithoutOverlappingMedia() {
        assertTrue("readme.md".isMarkdownDocument())
        assertTrue("notes.markdown".isReadableTextDocument())
        assertTrue("app.log".isReadableTextDocument())
        assertTrue("config.yaml".isReadableTextDocument())
        assertTrue("movie.srt".isReadableTextDocument())
        assertTrue("index.html".isHtmlDocument())
        assertTrue("archive.XHTML".isHtmlDocument())

        assertFalse("index.html".isReadableTextDocument())
        assertFalse("readme.md".isHtmlDocument())
        assertFalse("movie.mp4".isReadableTextDocument())
        assertFalse("movie.mp4".isHtmlDocument())
        assertFalse("song.flac".isReadableTextDocument())
        assertFalse("photo.png".isReadableTextDocument())
        assertTrue("movie.mp4".isPlayableVideo())
        assertTrue("song.flac".isPlayableAudio())
        assertTrue("photo.png".isPreviewableImage())
    }

    @Test
    fun capsDeepBlockQuotesAndNestedInlinesWithoutStackOverflow() {
        val deepQuote = ">".repeat(5_000) + " Deep quote survivor"
        val deepInline = "*".repeat(1_500) + "inline-survivor" + "*".repeat(1_500)
        val fencedArrows = "```txt\n" + ">".repeat(40) + " literal\n```"
        val blocks = parseMarkdown("$deepQuote\n\n$deepInline\n\n$fencedArrows")

        val outerQuote = assertIs<DocumentBlock.BlockQuote>(blocks[0])
        assertEquals("Deep quote survivor", outerQuote.plainText)
        var depth = 0
        var cursor: DocumentBlock = outerQuote
        while (cursor is DocumentBlock.BlockQuote) {
            depth++
            cursor = cursor.children.first()
        }
        assertTrue(depth in 1..MAX_MARKDOWN_NESTING_DEPTH, "BlockQuote nesting depth ($depth) must not exceed $MAX_MARKDOWN_NESTING_DEPTH")

        val inlinePara = assertIs<DocumentBlock.Paragraph>(blocks[1])
        assertTrue(inlinePara.plainText.contains("inline-survivor"))

        val codeBlock = assertIs<DocumentBlock.CodeBlock>(blocks[2])
        assertEquals(">".repeat(40) + " literal", codeBlock.code)
    }
}
