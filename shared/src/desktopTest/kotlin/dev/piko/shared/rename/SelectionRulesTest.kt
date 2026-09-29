package dev.piko.shared.rename

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionRulesTest {

    private fun files(vararg names: String) = names.mapIndexed { index, name -> RenameSource("f$index", "p", name, isFolder = false) }

    private val options = FindReplaceOptions(useRegex = true, scope = RenameScope.NAME)

    /** 用选区生成的规则跑一遍整批，得到新名称。 */
    private fun renameWith(items: List<RenameSource>, proposal: SelectionProposal): List<String> {
        val applied = options.copy(search = findBlocksToRegex(proposal.find), replacement = replaceBlocksToTemplate(proposal.replace))
        return FindReplaceRule(applied, 0).apply(items, items.map { it.name })
    }

    private fun rangeOf(name: String, part: String): IntRange = name.indexOf(part).let { it until it + part.length }

    @Test
    fun `episode numbers that differ are matched by position and renumbered`() {
        val items = files("[Sub] Show - 02 [1080p].mkv", "[Sub] Show - 03 [1080p].mkv", "[Sub] Show - 12 [1080p].mkv")
        val target = items.first()
        val proposal = proposeSelectionRule(items, options, target, rangeOf(target.name, "02"), SelectionEdit.NUMBER)!!
        assertTrue(proposal.generalized)
        assertEquals(3, proposal.matched)
        assertEquals(listOf("[Sub] Show - 01 [1080p].mkv", "[Sub] Show - 02 [1080p].mkv", "[Sub] Show - 03 [1080p].mkv"), renameWith(items, proposal))
    }

    @Test
    fun `a digit run that also appears earlier is located by the letters next to it`() {
        // 「02」在 S01E02 里，E 之前还有 01；按文字找会先撞上别处，按位置要落在 E 后面
        val items = files("Show S01E02.mkv", "Show S01E05.mkv")
        val target = items.first()
        val proposal = proposeSelectionRule(items, options, target, rangeOf(target.name, "02"), SelectionEdit.NUMBER)!!
        assertEquals(listOf("Show S01E01.mkv", "Show S01E02.mkv"), renameWith(items, proposal))
    }

    @Test
    fun `text shared by every name is matched literally`() {
        val items = files("[Sub] a.mkv", "[Sub] b.mkv")
        val target = items.first()
        val proposal = proposeSelectionRule(items, options, target, rangeOf(target.name, "[Sub] "), SelectionEdit.DELETE)!!
        assertEquals(false, proposal.generalized)
        assertEquals(listOf("a.mkv", "b.mkv"), renameWith(items, proposal))
    }

    @Test
    fun `selection outside the searched part yields nothing`() {
        val items = files("a.mkv", "b.mkv")
        assertNull(proposeSelectionRule(items, options, items.first(), 2..4, SelectionEdit.DELETE))
    }

    @Test
    fun `unicode digits neither crash nor get treated as numbers`() {
        val items = files("第𝟐集 - 01.mkv", "第２集 - 02.mkv", "第٢集 - 03.mkv")
        val target = items.first()
        // 选词不把 𝟐 的两个代理项拆开
        val token = tokenRangeAt(target.name, 1)
        assertEquals("第𝟐集", target.name.substring(token))
        val proposal = proposeSelectionRule(items, options, target, rangeOf(target.name, "01"), SelectionEdit.REPLACE, "E")!!
        assertEquals(listOf("第𝟐集 - E.mkv", "第２集 - E.mkv", "第٢集 - E.mkv"), renameWith(items, proposal))
    }
}
