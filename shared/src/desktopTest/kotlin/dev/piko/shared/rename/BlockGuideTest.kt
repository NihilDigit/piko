package dev.piko.shared.rename

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockGuideTest {
    @Test
    fun `every example in the block guide does what it says`() {
        for (entry in BlockGuide) {
            val example = entry.example ?: continue
            assertExample(example, entry.title)
        }
    }

    @Test
    fun `every rename preset does what its example says`() {
        for (preset in RenamePresets) {
            assertExample(preset.example, preset.label)
            // 例子只在替换条末尾补了文字，换上的积木本身要与例子一致，否则例子测的不是界面上的那组
            assertEquals(preset.find, preset.example.find, preset.label)
        }
    }

    private fun assertExample(example: BlockGuideExample, title: String) {
        val options = FindReplaceOptions(
            search = findBlocksToRegex(example.find),
            replacement = replaceBlocksToTemplate(example.replace),
            useRegex = true,
            scope = RenameScope.FULL,
        )
        val item = RenameSource("1", "p", example.input, isFolder = true)
        assertEquals(example.result, FindReplaceRule(options, 0).apply(listOf(item), listOf(item.name)).single(), title)
    }

    @Test
    fun `numbering preset counts each item once`() {
        val preset = RenamePresets.single { it.label == "改为编号" }
        val options = FindReplaceOptions(
            search = findBlocksToRegex(preset.find),
            replacement = replaceBlocksToTemplate(preset.replace),
            useRegex = true,
        )
        val items = listOf("甲.mkv", "乙.mkv", "丙.mkv").mapIndexed { index, name -> RenameSource("$index", "p", name, isFolder = false) }
        assertEquals(listOf("01.mkv", "02.mkv", "03.mkv"), FindReplaceRule(options, 0).apply(items, items.map { it.name }))
    }
}
