package dev.piko.shared.rename

import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalAvRuleTest {

    private fun source(id: String, name: String, parent: String = "p") = RenameSource(id, parent, name, isFolder = false)

    @Test
    fun `canonical names go through the usual plan and find replace runs on top`() {
        val items = listOf(
            source("1", "abc00123hhb.mp4"),
            source("2", "ABC-123.mp4"),
            source("piko-vault:abc", "abc-456.mp4"),
            source("4", "readme.txt"),
        )
        val result = runBatchRenamePipeline(items, findReplace = null, stripPrefix = false, stripSuffix = false, base = CanonicalAvRule())
        assertEquals(listOf("ABC-123.mp4", "ABC-123.mp4", "abc-456.mp4", "readme.txt"), result.names)
        // 两项改成同一个名字：交给冲突检查，一项改、一项原样占着的算 TAKEN
        val plan = planRenames(items, result.names, emptyMap())
        assertEquals(RenameProblem.TAKEN, plan.rows[0].problem)

        val replaced = runBatchRenamePipeline(
            items.take(1), FindReplaceRule(FindReplaceOptions(search = "$", replacement = " 完", useRegex = true), 0),
            stripPrefix = false, stripSuffix = false, base = CanonicalAvRule(mapOf("ABC-123" to "片名")),
        )
        assertEquals(listOf("ABC-123 片名 完.mp4"), replaced.names)
    }
}
