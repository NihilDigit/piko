package dev.piko.shared.state

import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals

class ReinsertRemovedTest {
    private fun file(id: String) = FileStat(id = id, name = id)
    private fun ids(files: List<FileStat>) = files.map { it.id }

    // 先移除 a、再移除 c，c 成功而 a 失败：a 回到原位，c 不能跟着 a 的旧快照回来
    @Test
    fun `a failed removal does not bring back one that succeeded later`() {
        val initial = listOf("a", "b", "c", "d").map(::file)
        val snapshotA = initial
        val afterA = initial.filterNot { it.id == "a" }
        val afterC = afterA.filterNot { it.id == "c" }

        assertEquals(listOf("a", "b", "d"), ids(reinsertRemoved(afterC, snapshotA, setOf("a"))))
    }

    @Test
    fun `restored items keep their order around surviving neighbours`() {
        val snapshot = listOf("a", "b", "c", "d", "e").map(::file)
        val current = listOf("a", "e").map(::file)

        assertEquals(listOf("a", "b", "d", "e"), ids(reinsertRemoved(current, snapshot, setOf("b", "d"))))
    }

    // 期间重列过，失败的那一项已经在列表里了
    @Test
    fun `items already back in the list are not duplicated`() {
        val snapshot = listOf("a", "b").map(::file)

        assertEquals(listOf("a", "b"), ids(reinsertRemoved(snapshot, snapshot, setOf("a"))))
    }
}
