package dev.piko.shared.state

import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals

class HideFilesTest {
    private fun work(key: String) = DriveListItem.WorkHeader(key, key, emptyList())
    private fun section(key: String, expanded: Boolean = true) = DriveListItem.SectionHeader(key, key, key, key, expanded)
    private fun file(id: String) = DriveListItem.File(FileStat(id = id, name = id), null)

    private fun keys(items: List<DriveListItem>) = items.map { it.key }

    // 收起的分区本来就只有标题，不能当成「文件全被拿掉」连标题删掉
    @Test
    fun `collapsed sections keep their header`() {
        val items = listOf(section("collapsed", expanded = false), section("open"), file("a.txt"), file("b.jpg"))
        assertEquals(listOf("collapsed", "open", "b.jpg"), keys(hideFiles(items) { it.id.endsWith(".txt") }))
    }

    @Test
    fun `sections and works left empty lose their header`() {
        val items = listOf(
            work("w1"), section("s1"), file("a.txt"),
            work("w2"), section("s2"), file("b.jpg"), section("s3"), file("c.txt"),
        )
        assertEquals(listOf("w2", "s2", "b.jpg"), keys(hideFiles(items) { it.id.endsWith(".txt") }))
    }
}
