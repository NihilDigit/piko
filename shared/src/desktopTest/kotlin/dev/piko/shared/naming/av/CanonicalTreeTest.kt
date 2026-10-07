package dev.piko.shared.naming.av

import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalTreeTest {

    private fun folder(id: String, parent: String, name: String) = AvTreeItem(id, parent, name, isFolder = true)
    private fun file(id: String, parent: String, name: String) = AvTreeItem(id, parent, name, isFolder = false)

    private fun names(items: List<AvTreeItem>, titles: Map<String, String> = emptyMap()): List<String> =
        canonicalAvTree(items, titles).map { it.name }

    @Test
    fun `a single-code folder carries the title and its files only the code`() {
        val items = listOf(
            folder("r", "root", "[site.net] abc-123 某片名 中文字幕"),
            file("a", "r", "abc00123hhb1.mp4"),
            file("b", "r", "abc00123hhb2.mp4"),
            file("s", "r", "abc00123hhb1.zh.srt"),
            folder("x", "r", "extras"),
            file("t", "x", "ABC-123-trailer.jpg"),
        )
        assertEquals(
            listOf("ABC-123-C 某片名", "ABC-123-CD1.mp4", "ABC-123-CD2.mp4", "ABC-123-CD1.zh.srt", "extras", "ABC-123-trailer.jpg"),
            names(items),
        )
        assertEquals(listOf("ABC-123"), canonicalAvTree(items).mapNotNull { it.code }.distinct())
    }

    @Test
    fun `a folder of several works keeps its name and the files keep their titles`() {
        val items = listOf(
            folder("r", "root", "合集"),
            file("a", "r", "abc-123 甲片名.mp4"),
            file("b", "r", "xyz-456 乙片名.mp4"),
        )
        assertEquals(listOf("合集", "ABC-123 甲片名.mp4", "XYZ-456 乙片名.mp4"), names(items))
        // 名字里没有番号的文件夹是用户自己的归类，只放着一部也不改
        val shelf = listOf(folder("r", "root", "收藏"), file("a", "r", "abc-123 甲片名.mp4"))
        assertEquals(listOf("收藏", "ABC-123 甲片名.mp4"), names(shelf))
    }

    @Test
    fun `titles from elsewhere name the folder and the result is stable`() {
        val titles = mapOf("ABC-123" to "查到的片名")
        val items = listOf(
            folder("lib", "root", "library"),
            folder("r", "lib", "ABC-123"),
            folder("n", "r", "ABC-123"),
            file("a", "n", "ABC-123 原名里的片名.mp4"),
        )
        val once = names(items, titles)
        // 外层是资源文件夹；同番号的子文件夹不再套一层同样的名字
        assertEquals(listOf("library", "ABC-123 查到的片名", "ABC-123", "ABC-123.mp4"), once)
        val again = items.zip(once).map { (item, name) -> item.copy(name = name) }
        assertEquals(once, names(again, titles))
    }
}
