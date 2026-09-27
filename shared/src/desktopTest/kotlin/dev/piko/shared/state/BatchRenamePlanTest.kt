package dev.piko.shared.state

import kotlin.test.Test
import kotlin.test.assertEquals

class BatchRenamePlanTest {

    private fun files(vararg names: String) = names.mapIndexed { index, name -> RenameSource("f$index", "p", name, isFolder = false) }
    private fun folders(vararg names: String) = names.mapIndexed { index, name -> RenameSource("d$index", "p", name, isFolder = true) }

    @Test
    fun `prefix that stops mid-word falls back to the previous separator`() {
        assertEquals(CommonAffixes("[xxx.com]", ""), commonAffixes(files("[xxx.com]Alpha.mkv", "[xxx.com]Alps.mkv")))
    }

    @Test
    fun `prefix never ends inside a bracket`() {
        // 逐字符比到「[Group]Show[0」，切在「[」之后会剩下半个括号
        assertEquals("[Group]Show", commonAffixes(folders("[Group]Show[01]", "[Group]Show[02]")).prefix)
    }

    @Test
    fun `names that differ only in extension have nothing to strip`() {
        assertEquals(CommonAffixes("", ""), commonAffixes(files("Movie.mkv", "Movie.mp4")))
    }

    @Test
    fun `a name that is itself the common prefix is not emptied`() {
        assertEquals(CommonAffixes("", ""), commonAffixes(files("Movie.mkv", "Movie 2.mkv")))
    }

    @Test
    fun `full-width brackets and chinese suffix are cut on boundaries only`() {
        val items = files("【高清电影】电影甲-高清中字.mp4", "【高清电影】电影乙-高清中字.mkv")
        val affixes = commonAffixes(items)
        // 「电影」是共同的，但中文词之间没有分隔符，不剥
        assertEquals(CommonAffixes("【高清电影】", "-高清中字"), affixes)
        val plan = planRenames(items, RenameRules(prefix = affixes.prefix, suffix = affixes.suffix), emptyMap())
        assertEquals(listOf("电影甲.mp4", "电影乙.mkv"), plan.rows.map { it.newName })
    }

    @Test
    fun `stripping everything is an error rather than a bare extension`() {
        val plan = planRenames(files("abc.mkv", "abcd.mkv"), RenameRules(find = "abc"), emptyMap())
        assertEquals(listOf(RenameProblem.EMPTY, null), plan.rows.map { it.problem })
    }

    @Test
    fun `a blocked rename keeps its old name occupied for the others`() {
        // a 要改成 aa，aa 要改成 aaaa；aaaa 被未选中的项占着，aa 改不了，于是 a 也改不了
        val items = folders("a", "aa")
        val plan = planRenames(items, RenameRules(find = "a", replacement = "aa"), mapOf("p" to setOf("aaaa")))
        assertEquals(listOf(RenameProblem.TAKEN, RenameProblem.TAKEN), plan.rows.map { it.problem })

        // 没有占用时两项都能改，但必须先把 aa 改走
        val free = planRenames(items, RenameRules(find = "a", replacement = "aa"), emptyMap())
        assertEquals(listOf("aa", "a"), free.order.map { it.source.name })
    }

    @Test
    fun `same new name in different folders is not a conflict`() {
        val items = listOf(
            RenameSource("1", "p1", "[x] A.mkv", isFolder = false),
            RenameSource("2", "p2", "[x] A.mkv", isFolder = false),
            RenameSource("3", "p2", "[y] A.mkv", isFolder = false),
        )
        // 前两项都改成「A.mkv」，但在不同目录
        val plan = planRenames(items, RenameRules(find = "[x] "), emptyMap())
        assertEquals(listOf(null, null, null), plan.rows.map { it.problem })
        // 所选里不改名的「[y] A.mkv」占着自己的名称，同目录的另一项不能改成它
        val clash = planRenames(items, RenameRules(find = "[x] ", replacement = "[y] "), emptyMap())
        assertEquals(listOf(null, RenameProblem.TAKEN, null), clash.rows.map { it.problem })
    }
}
