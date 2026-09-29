package dev.piko.shared.rename

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/** 期望值多数取自 PowerRename 的单元测试（PowerRenameRegExTests、CommonRegExTests、PowerRenameManagerTests）。 */
class FindReplaceRuleTest {

    private fun file(name: String, id: String = name, created: String = "") = RenameSource(id, "p", name, isFolder = false, createdTime = created)
    private fun folder(name: String) = RenameSource(name, "p", name, isFolder = true)

    private fun rename(options: FindReplaceOptions, vararg items: RenameSource): List<String> =
        FindReplaceRule(options, randomSeed = 1, timeZone = TimeZone.UTC).apply(items.toList(), items.map { it.name })

    private fun regex(search: String, replacement: String, matchAll: Boolean = true) =
        FindReplaceOptions(search, replacement, useRegex = true, caseSensitive = true, matchAll = matchAll, scope = RenameScope.FULL)

    @Test
    fun `capture group references follow std regex rather than java matcher`() {
        val table = listOf(
            "$1_$002_$223_$001021_$00001" to "foo_\$002_bar23_\$001021_\$00001",
            "_$1$2_$123$040" to "_foobar_foo23\$040",
            "$$$1" to "\$foo",
            "$$1" to "\$1",
            "$12" to "foo2",
            "$10" to "foo0",
            "$01" to "\$01",
            "$$$11" to "\$foo1",
            "$$$$113a" to "\$\$113a",
        )
        for ((replacement, expected) in table) {
            assertEquals(expected, rename(regex("(foo)(bar)", replacement), folder("foobar")).single(), replacement)
        }
    }

    @Test
    fun `an empty match right after a match is not replaced again`() {
        // JVM 与 JS 都得到 FooFoo
        assertEquals("Foo", rename(regex(".*", "Foo"), folder("AAAAAA")).single())
    }

    @Test
    fun `plain search keeps dollar signs literally and ignores case by default`() {
        val options = FindReplaceOptions(search = "BAR", replacement = "$1x$$", scope = RenameScope.FULL)
        assertEquals("foo\$1x\$\$", rename(options, folder("foobar")).single())
    }

    @Test
    fun `counter only advances on items that matched`() {
        val options = FindReplaceOptions(search = "ep", replacement = "E${'$'}{start=1,padding=2}", scope = RenameScope.NAME)
        val names = rename(options, file("ep.mkv"), file("other.mkv"), file("ep.mp4"), folder("ep"))
        assertEquals(listOf("E01.mkv", "other.mkv", "E02.mp4", "E03"), names)

        // 被排除的文件夹不占号
        val filesOnly = rename(options.copy(includeFolders = false), folder("ep"), file("ep.mkv"))
        assertEquals(listOf("ep", "E01.mkv"), filesOnly)
    }

    @Test
    fun `counter options match powerrename`() {
        fun counter(placeholder: String, index: Int): String {
            val items = List(index + 1) { folder("bar") }
            return rename(regex("bar", "bar_$placeholder"), *items.toTypedArray()).last()
        }
        assertEquals("bar_01077", counter("\${increment=7,start=993,padding=5}", 12))
        assertEquals("bar_00204", counter("\${padding=5}", 204))
        // printf 的 %05d 把负号算进宽度
        assertEquals("bar_-0003", counter("\${start=-3,padding=5}", 0))
        // 写了随机串的花括号不是计数器
        assertTrue(Regex("bar_[0-9]{9}").matches(counter("\${rstringdigit=9}", 0)), counter("\${rstringdigit=9}", 0))
    }

    @Test
    fun `random uuid is version 4 in upper case`() {
        val name = rename(regex("bar", "\${ruuidv4}"), folder("bar")).single()
        assertTrue(Regex("[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}").matches(name), name)
    }

    @Test
    fun `date placeholders use the item time and respect dollar escapes`() {
        val item = file("foo.mkv", created = "2020-07-22T15:06:42.453Z")
        val options = FindReplaceOptions(search = "foo", replacement = "\$YYYY-\$MM-\$DD \$HH\$TT \$hh.\$mm.\$ss.\$fff \$\$YY \$MMMM \$DDDD")
        assertEquals("2020-07-22 03PM 15.06.42.453 \$\$YY 七月 星期三.mkv", rename(options, item).single())
    }

    @Test
    fun `extension scope skips folders and files without extension`() {
        val options = FindReplaceOptions(search = "mkv", replacement = "mp4", scope = RenameScope.EXTENSION)
        assertEquals(listOf("a.mp4", "mkv", "mkv"), rename(options, file("a.mkv"), file("mkv"), folder("mkv")))
    }

    @Test
    fun `title case keeps minor words and contractions lower`() {
        val options = FindReplaceOptions(search = "foo", replacement = "bar", textCase = TextCase.TITLE)
        assertEquals(
            "'The 'Bar' 'I'll' and I've You're Dogs' the 'I'd' It's I'm Don't to Y'all.txt",
            rename(options, file("'the 'foo' 'i'll' and i've you're dogs' the 'i'd' it's i'm don't to y'all.txt")).single(),
        )
        assertEquals("Bar and the To", rename(options, folder("foo And The To")).single())
        val capitalized = options.copy(textCase = TextCase.CAPITALIZED)
        assertEquals("Bar And The To", rename(capitalized, folder("foo and the to")).single())
    }

    @Test
    fun `case format applies even without a search term`() {
        val options = FindReplaceOptions(textCase = TextCase.UPPER, scope = RenameScope.NAME)
        assertEquals("MOVIE.mkv", rename(options, file("movie.mkv")).single())
    }

    @Test
    fun `broken pattern is reported instead of thrown as is`() {
        assertFailsWith<InvalidPatternException> { FindReplaceRule(regex("(unclosed", ""), randomSeed = 0) }
    }
}
