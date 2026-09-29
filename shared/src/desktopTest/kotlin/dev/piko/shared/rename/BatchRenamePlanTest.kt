package dev.piko.shared.rename

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchRenamePlanTest {

    private fun files(vararg names: String) = names.mapIndexed { index, name -> RenameSource("f$index", "p", name, isFolder = false) }
    private fun folders(vararg names: String) = names.mapIndexed { index, name -> RenameSource("d$index", "p", name, isFolder = true) }

    private fun replaceAll(find: String, replacement: String) =
        FindReplaceRule(FindReplaceOptions(search = find, replacement = replacement), randomSeed = 0)

    private fun plan(items: List<RenameSource>, vararg rules: RenameRule, siblings: Map<String, Set<String>> = emptyMap()) =
        planRenames(items, applyRules(items, rules.toList()), siblings)

    /** 按步骤逐个改名，每一步都不能撞上目录里当时已有的名称，最后得到的就是预览里的新名称。 */
    private fun assertStepsReplay(items: List<RenameSource>, plan: RenamePlan, siblings: Set<String> = emptySet()) {
        val names = items.associate { it.id to it.name }.toMutableMap()
        for (step in plan.steps) {
            assertEquals(step.from, names[step.source.id])
            assertTrue(step.to !in names.values && step.to !in siblings, "第 ${plan.steps.indexOf(step)} 步撞上了 ${step.to}")
            names[step.source.id] = step.to
        }
        assertEquals(plan.rows.associate { it.source.id to it.newName }, names)
    }

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
    fun `a name that is itself the common prefix is not emptied`() {
        assertEquals(CommonAffixes("", ""), commonAffixes(files("Movie.mkv", "Movie 2.mkv")))
    }

    @Test
    fun `full-width brackets and chinese suffix are cut on boundaries only`() {
        val items = files("【高清电影】电影甲-高清中字.mp4", "【高清电影】电影乙-高清中字.mkv")
        val affixes = commonAffixes(items)
        // 「电影」是共同的，但中文词之间没有分隔符，不剥
        assertEquals(CommonAffixes("【高清电影】", "-高清中字"), affixes)
        assertEquals(listOf("电影甲.mp4", "电影乙.mkv"), plan(items, AffixStripRule(affixes.prefix, affixes.suffix)).rows.map { it.newName })
    }

    @Test
    fun `find and replace works on the original name before common parts are stripped`() {
        // 查找的是原名里共同开头中的一段；先去共同开头的话查找落空，「第」就没了
        val items = files("[SweetSub] Frieren - 02 [WebRip].mkv", "[SweetSub] Frieren - 03 [WebRip].mkv")
        val result = runBatchRenamePipeline(items, replaceAll("Frieren - ", "第"), stripPrefix = true, stripSuffix = true)
        assertEquals(CommonAffixes("[SweetSub] ", " [WebRip]"), result.affixes)
        assertEquals(listOf("第02.mkv", "第03.mkv"), result.names)
    }

    @Test
    fun `reopening with remembered options changes nothing`() {
        val items = files("[SweetSub] a.mkv", "[SweetSub] b.mkv")
        val remembered = FindReplaceOptions(search = "a", replacement = "b", useRegex = true, textCase = TextCase.UPPER, scope = RenameScope.FULL)
        val result = runBatchRenamePipeline(items, FindReplaceRule(remembered.withoutChanges(), 0), stripPrefix = false, stripSuffix = false)
        assertEquals(items.map { it.name }, result.names)
    }

    @Test
    fun `stripping everything is an error rather than a bare extension`() {
        val result = plan(files("abc.mkv", "abcd.mkv"), replaceAll("abc", ""))
        assertEquals(listOf(RenameProblem.EMPTY, null), result.rows.map { it.problem })
    }

    @Test
    fun `new names are trimmed and forbidden characters are flagged`() {
        val result = plan(folders("a", "b"), FindReplaceRule(FindReplaceOptions(search = "a", replacement = " x. "), 0), replaceAll("b", "b:c"))
        assertEquals(listOf("x", "b:c"), result.rows.map { it.newName })
        assertEquals(listOf(null, RenameProblem.INVALID_CHARS), result.rows.map { it.problem })
    }

    @Test
    fun `length limit counts utf-8 bytes`() {
        val fill = RenameRule { _, names -> names.map { if (it == "a") "a".repeat(1024) else "中".repeat(342) } }
        val result = plan(folders("a", "b"), fill)
        assertEquals(listOf(null, RenameProblem.TOO_LONG), result.rows.map { it.problem })
    }

    @Test
    fun `names with non-ascii digits go through the whole pipeline`() {
        // Android 上 \d 认这些数字（PR #12 的信息流崩溃就出在拿它们 toInt），桌面端加了 (?U) 后同样认
        val items = folders("第𝟐集", "第２集", "第٢集", "第2集", "𝟏𝟐")
        val rule = FindReplaceRule(
            FindReplaceOptions(search = """(\d+)""", replacement = "E\${start=1,padding=2}-$1", useRegex = true, textCase = TextCase.TITLE),
            randomSeed = 0,
        )
        val result = plan(items, rule, siblings = mapOf("p" to setOf("第e01-𝟐集")))
        // 汉字算字母，「第E01」是一个词，标题格式把词中的 E 改成小写
        assertEquals(listOf("第e01-𝟐集", "第e02-２集", "第e03-٢集", "第e04-2集", "E05-𝟏𝟐"), result.rows.map { it.newName })
        assertEquals(RenameProblem.TAKEN, result.rows.first().problem)
        assertStepsReplay(items.drop(1), planRenames(items.drop(1), applyRules(items.drop(1), listOf(rule)), emptyMap()))
    }

    @Test
    fun `a blocked rename keeps its old name occupied for the others`() {
        // a 要改成 aa，aa 要改成 aaaa；aaaa 被未选中的项占着，aa 改不了，于是 a 也改不了
        val items = folders("a", "aa")
        val blocked = plan(items, replaceAll("a", "aa"), siblings = mapOf("p" to setOf("aaaa")))
        assertEquals(listOf(RenameProblem.BLOCKED, RenameProblem.TAKEN), blocked.rows.map { it.problem })

        // 没有占用时两项都能改，但必须先把 aa 改走
        val free = plan(items, replaceAll("a", "aa"))
        assertEquals(listOf("aa", "a"), free.steps.map { it.source.name })
        assertStepsReplay(items, free)
    }

    @Test
    fun `renumbering part of a folder blames only the item that hits an unselected name`() {
        // 目录里有 01 到 08，只选了 02、03、04、06、07、08 重新编号成 01 到 06。
        // 02→01、07→05 撞上未选中的 01 与 05；03→02 这类目标是本批另一项的原名，那一项改不走才连带受阻，
        // 不是与现有名称重复
        val items = folders("02", "03", "04", "06", "07", "08")
        val renumber = RenameRule { _, names -> names.indices.map { "0${it + 1}" } }
        val result = plan(items, renumber, siblings = mapOf("p" to setOf("01", "05")))
        val taken = RenameProblem.TAKEN
        val blocked = RenameProblem.BLOCKED
        assertEquals(listOf(taken, blocked, blocked, blocked, taken, blocked), result.rows.map { it.problem })
        // 未选中的项不占这些名称时，同样的编号只是一条链，按顺序都能改
        val free = plan(items, renumber, siblings = mapOf("p" to setOf("09")))
        assertEquals(listOf(null), free.rows.map { it.problem }.distinct())
        assertStepsReplay(items, free, siblings = setOf("09"))
    }

    @Test
    fun `swapped names go through a temporary name`() {
        val items = folders("A", "B", "C")
        // A→B、B→C、C→A 成环，逐个改名每一步都撞上还没改走的那个
        val rotate = RenameRule { _, names -> names.map { mapOf("A" to "B", "B" to "C", "C" to "A").getValue(it) } }
        val result = plan(items, rotate, siblings = mapOf("p" to setOf("A.piko-swap1")))
        assertEquals(listOf(null, null, null), result.rows.map { it.problem })
        assertEquals(4, result.steps.size)
        assertStepsReplay(items, result, siblings = setOf("A.piko-swap1"))
    }

    @Test
    fun `same new name in different folders is not a conflict`() {
        val items = listOf(
            RenameSource("1", "p1", "[x] A.mkv", isFolder = false),
            RenameSource("2", "p2", "[x] A.mkv", isFolder = false),
            RenameSource("3", "p2", "[y] A.mkv", isFolder = false),
        )
        // 前两项都改成「A.mkv」，但在不同目录
        assertEquals(listOf(null, null, null), plan(items, replaceAll("[x] ", "")).rows.map { it.problem })
        // 所选里不改名的「[y] A.mkv」占着自己的名称，同目录的另一项不能改成它
        val clash = plan(items, replaceAll("[x] ", "[y] "))
        assertEquals(listOf(null, RenameProblem.TAKEN, null), clash.rows.map { it.problem })
    }
}
