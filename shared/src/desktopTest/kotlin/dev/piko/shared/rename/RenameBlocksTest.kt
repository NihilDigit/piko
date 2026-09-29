package dev.piko.shared.rename

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenameBlocksTest {

    private val allFindBlocks = listOf(
        listOf(FindBlock.Start, FindBlock.Bracketed(BracketKind.SQUARE), FindBlock.AnyText(), FindBlock.Text(" - "), FindBlock.Digits(capture = true), FindBlock.End),
        listOf(FindBlock.Digits(2, 2), FindBlock.Digits(2, 4, capture = true), FindBlock.Digits(3, null), FindBlock.Letters(capture = true)),
        listOf(FindBlock.Bracketed(null), FindBlock.Bracketed(null, optional = true, capture = true), FindBlock.Bracketed(BracketKind.LENTICULAR, optional = true)),
        listOf(FindBlock.Bracketed(BracketKind.ROUND, capture = true), FindBlock.Bracketed(BracketKind.FULLWIDTH_ROUND)),
        listOf(FindBlock.AnyText(']'), FindBlock.AnyText('\\', capture = true), FindBlock.AnyText('^'), FindBlock.AnyText('-'), FindBlock.AnyText(' ')),
        listOf(FindBlock.OneOf(listOf("mkv", "mp4", "a.b|c")), FindBlock.OneOf(listOf("x", "(y)"), capture = true)),
        listOf(FindBlock.Text("[a](b).*+?\\$^|{}"), FindBlock.Text("取出的文字", capture = true), FindBlock.Text("第𝟐集")),
    )

    @Test
    fun `find blocks survive the round trip through regex`() {
        for (blocks in allFindBlocks) {
            val regex = findBlocksToRegex(blocks)
            assertEquals(blocks, regexToFindBlocks(regex), regex)
        }
    }

    @Test
    fun `text blocks are escaped so they match only themselves`() {
        val text = "[a](b).*+?\\$^|{}-"
        val regex = Regex(findBlocksToRegex(listOf(FindBlock.Start, FindBlock.Text(text), FindBlock.End)))
        assertTrue(regex.matches(text))
        assertTrue(!regex.containsMatchIn("[a](b)x*+?\\$^|{}-"))
        val inClass = Regex(findBlocksToRegex(listOf(FindBlock.AnyText(']'))))
        assertEquals("ab", inClass.find("ab]c")?.value)
    }

    @Test
    fun `regex outside the block vocabulary is not turned into blocks`() {
        for (regex in listOf("""\d+""", "a*", "(?i)x", "[abc]", "(a|b", """\1""", "[0-9]{1,}", "x$$")) {
            assertNull(regexToFindBlocks(regex), regex)
        }
    }

    @Test
    fun `digit blocks match ascii digits only and never crash on unicode digits`() {
        // 积木的「数字」就是阿拉伯数字：第２集、第𝟐集、第٢集原样留着，与手写 \d 的 Unicode 语义不同，是有意的
        val find = listOf(FindBlock.Text("第"), FindBlock.Digits(capture = true), FindBlock.Text("集"))
        val replace = listOf(ReplaceBlock.Text("E"), ReplaceBlock.Piece(1), ReplaceBlock.Counter(start = 1, padding = 2))
        val options = FindReplaceOptions(search = findBlocksToRegex(find), replacement = replaceBlocksToTemplate(replace), useRegex = true)
        val items = listOf("第𝟐集", "第２集", "第٢集", "第12集").mapIndexed { index, name -> RenameSource("$index", "p", name, isFolder = true) }
        val plan = planRenames(items, applyRules(items, listOf(FindReplaceRule(options, 0))), emptyMap())
        assertEquals(listOf("第𝟐集", "第２集", "第٢集", "E1201"), plan.rows.map { it.newName })
    }

    @Test
    fun `replace blocks survive the round trip through the template`() {
        val cases = listOf(
            listOf(ReplaceBlock.Text("第"), ReplaceBlock.Piece(1), ReplaceBlock.Text("2集"), ReplaceBlock.Piece(0)),
            listOf(ReplaceBlock.Counter(), ReplaceBlock.Counter(start = -3, increment = 5, padding = 4), ReplaceBlock.Text("$"), ReplaceBlock.Counter()),
            listOf(ReplaceBlock.Date("YYYY"), ReplaceBlock.Text("-"), ReplaceBlock.Date("MM"), ReplaceBlock.Text("\$YYYY 与 $1 与 $&")),
            listOf(ReplaceBlock.RandomText(RandomKind.ALNUM, 8), ReplaceBlock.RandomText(RandomKind.DIGIT, 3), ReplaceBlock.Uuid),
        )
        for (blocks in cases) {
            val template = replaceBlocksToTemplate(blocks)
            assertEquals(blocks, templateToReplaceBlocks(template), template)
        }
        assertNull(templateToReplaceBlocks("\${rstringalnum=3,rstringdigit=2}"))
        assertNull(templateToReplaceBlocks("$`"))
    }

    @Test
    fun `blocks rename like the equivalent handwritten regex`() {
        val find = listOf(FindBlock.Start, FindBlock.Bracketed(BracketKind.SQUARE), FindBlock.AnyText(), FindBlock.Text(" - "), FindBlock.Digits(capture = true))
        val replace = listOf(ReplaceBlock.Text("第"), ReplaceBlock.Piece(1), ReplaceBlock.Text("集 $1"))
        val options = FindReplaceOptions(search = findBlocksToRegex(find), replacement = replaceBlocksToTemplate(replace), useRegex = true)
        val item = RenameSource("1", "p", "[Sub] Show - 09 [1080p].mkv", isFolder = false)
        assertEquals("第09集 $1 [1080p].mkv", FindReplaceRule(options, 0).apply(listOf(item), listOf(item.name)).single())
    }

    @Test
    fun `highlights map each block to its own span of the name`() {
        // 取出的与不取出的积木混在一起，各块仍对到自己的那段；只改主名时扩展名不参与
        val find = listOf(FindBlock.Start, FindBlock.Bracketed(BracketKind.SQUARE), FindBlock.Text(" "), FindBlock.Digits(capture = true), FindBlock.AnyText())
        val options = FindReplaceOptions(search = findBlocksToRegex(find), useRegex = true, scope = RenameScope.NAME)
        val item = RenameSource("1", "p", "[Sub] 09.mkv", isFolder = false)
        val spans = MatchHighlighter(options, find).highlights(item).map { item.name.substring(it.range) to it.block }
        assertEquals(listOf("[Sub]" to 1, " " to 2, "09" to 3), spans)
        // 文本模式整个匹配一种颜色，全部替换时每处都标
        val text = MatchHighlighter(FindReplaceOptions(search = "[0-9]", useRegex = true, scope = RenameScope.FULL), null).highlights(RenameSource("2", "p", "a1b2.mp4", isFolder = false))
        assertEquals(listOf("1", "2", "4"), text.map { "a1b2.mp4".substring(it.range) })
    }

    @Test
    fun `description folds anchors into the neighbouring block`() {
        val blocks = listOf(FindBlock.Start, FindBlock.Bracketed(BracketKind.SQUARE), FindBlock.AnyText(), FindBlock.Digits(2, 2, capture = true), FindBlock.End)
        assertEquals("开头的方括号内容、任意字符、结尾的 2 位数字①", describeFind(blocks))
    }
}
