package dev.piko.shared.state

import kotlin.test.Test
import kotlin.test.assertEquals

class NameTreeTest {

    private fun NameNode.render(depth: Int = 0): List<String> {
        val line = "  ".repeat(depth) + label
        return when (this) {
            is NameLeaf -> listOf("$line #$index")
            is NameGroup -> listOf(if (suffix.isEmpty()) line else "$line …$suffix") + children.flatMap { it.render(depth + 1) }
        }
    }

    private fun tree(vararg names: String) = buildNameTree(names.toList().withIndex().toList()).flatMap { it.render() }

    @Test
    fun `fansub release folds into series then episode then suffix`() {
        val ep = "[DBD-Raws][Steins;Gate]"
        assertEquals(
            listOf(
                ep,
                "  [01][1080P][FLAC].mkv #1",
                "  [02][1080P][FLAC]",
                "    .mkv #0",
                "    .sc.ass #2",
                "    .tc.ass #3",
            ),
            tree(
                "$ep[02][1080P][FLAC].mkv",
                "$ep[01][1080P][FLAC].mkv",
                "$ep[02][1080P][FLAC].sc.ass",
                "$ep[02][1080P][FLAC].tc.ass",
            ),
        )
    }

    @Test
    fun `prefix is cut on token boundaries, never mid-bracket`() {
        // 字符级公共前缀是「[Group][0」，按记号切应停在「[Group]」
        assertEquals(
            listOf("[Group]", "  [01].mkv #0", "  [02].mkv #1"),
            tree("[Group][01].mkv", "[Group][02].mkv"),
        )
    }

    @Test
    fun `short forks are spliced back instead of opening a level`() {
        // 「.sc」只有三个字符，不值得单开一层
        assertEquals(
            listOf("Movie.2024", "  .mkv #0", "  .sc.ass #1", "  .sc.srt #2"),
            tree("Movie.2024.mkv", "Movie.2024.sc.ass", "Movie.2024.sc.srt"),
        )
    }

    @Test
    fun `unrelated names stay flat`() {
        assertEquals(listOf("a.mkv #0", "readme.txt #1"), tree("a.mkv", "readme.txt"))
    }

    @Test
    fun `episodes keep only what differs once the shared tail is lifted to the group`() {
        assertEquals(
            listOf("[Show] …[1080P][FLAC].mkv", "  [01] #1", "  [02] #0", "  [10] #2"),
            tree("[Show][02][1080P][FLAC].mkv", "[Show][01][1080P][FLAC].mkv", "[Show][10][1080P][FLAC].mkv"),
        )
        // 字符级公共结尾是「FLAC][1080P].mkv」，从半个括号里切开；应退到记号边界上的「[1080P].mkv」
        assertEquals(
            listOf("[Show] …[1080P].mkv", "  [01][xFLAC] #0", "  [02][yFLAC] #1"),
            tree("[Show][01][xFLAC][1080P].mkv", "[Show][02][yFLAC][1080P].mkv"),
        )
    }

    @Test
    fun `an odd file out does not stop the episodes from folding`() {
        // 同组的 PV 是 mp4，结尾不同；正片照样剥，PV 显示全名
        assertEquals(
            listOf("[Show] …[1080P][FLAC].mkv", "  [01] #0", "  [02] #1", "  [03] #2", "  [PV].mp4 #3"),
            tree("[Show][01][1080P][FLAC].mkv", "[Show][02][1080P][FLAC].mkv", "[Show][03][1080P][FLAC].mkv", "[Show][PV].mp4"),
        )
    }

    @Test
    fun `spaces and dashes inside brackets do not split a token`() {
        assertEquals(
            listOf("[Show]", "  [Game Mode].mkv #0", "  [Game-Over].mkv #1"),
            tree("[Show][Game Mode].mkv", "[Show][Game-Over].mkv"),
        )
    }

    @Test
    fun `subtitles bundle with the video whose stem they extend`() {
        // Ep1 不能认领 Ep10 的字幕
        assertEquals(
            mapOf(0 to listOf(2), 1 to listOf(3, 4)),
            subtitleBundles(listOf("Ep1.mkv", "Ep10.mkv", "Ep1.sc.ass", "Ep10.sc.ass", "Ep10.tc.srt")),
        )
        // 两个视频都能配上时归名字更长的那个：正片名是花絮名的前缀
        assertEquals(
            mapOf(1 to listOf(2)),
            subtitleBundles(listOf("Show.mkv", "Show.NCED.mkv", "Show.NCED.ass")),
        )
    }
}
