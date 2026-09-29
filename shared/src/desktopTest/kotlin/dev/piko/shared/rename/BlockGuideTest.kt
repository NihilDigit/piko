package dev.piko.shared.rename

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockGuideTest {
    @Test
    fun `every example in the block guide does what it says`() {
        for (entry in BlockGuide) {
            val example = entry.example ?: continue
            val options = FindReplaceOptions(
                search = findBlocksToRegex(example.find),
                replacement = replaceBlocksToTemplate(example.replace),
                useRegex = true,
                scope = RenameScope.FULL,
            )
            val item = RenameSource("1", "p", example.input, isFolder = true)
            assertEquals(example.result, FindReplaceRule(options, 0).apply(listOf(item), listOf(item.name)).single(), entry.title)
        }
    }
}
