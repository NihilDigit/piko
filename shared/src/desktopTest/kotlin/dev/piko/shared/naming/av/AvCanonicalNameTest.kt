package dev.piko.shared.naming.av

import dev.piko.shared.naming.parseMediaName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvCanonicalNameTest {

    private val samples: List<Pair<String, String>> by lazy {
        val stream = AvCanonicalNameTest::class.java.getResourceAsStream("/naming/av-names.tsv") ?: error("缺少夹具 av-names")
        stream.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').let { (original, expected) -> original to expected } }
    }

    private fun canonical(name: String) = canonicalAvNames(listOf(AvNamingItem(name, group = ""))).single()

    @Test
    fun `samples get their canonical names`() {
        val wrong = samples.mapNotNull { (original, expected) ->
            canonical(original).takeIf { it != expected }?.let { "$original → $it（应为 $expected）" }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `canonical names parse back to the same code flags part and title`() {
        samples.forEach { (original, _) ->
            val before = parseMediaName(original).av ?: return@forEach
            val after = parseMediaName(canonical(original)).av
            val context = "$original → ${canonical(original)}"
            assertEquals(before.code, after?.code, context)
            assertEquals(before.chineseSubtitles, after?.chineseSubtitles, context)
            assertEquals(before.uncensored, after?.uncensored, context)
            assertEquals(before.part, after?.part, context)
            assertEquals(sanitizeAvTitle(after?.title), after?.title, context)
        }
    }

    @Test
    fun `canonical names are their own canonical names`() {
        samples.forEach { (original, _) ->
            val once = canonical(original)
            assertEquals(once, canonical(once), original)
        }
    }

    @Test
    fun `titles from elsewhere are cleaned and cut to length`() {
        val info = parseMediaName("ABC-123-C.mp4").av!!
        assertEquals("ABC-123-C 上集／下集？-CD1.mp4", canonicalAvName("x.mp4", info.copy(part = "CD1"), "上集/下集?\n"))
        val long = canonicalAvName("x.mp4", info, "很长".repeat(200))
        assertTrue(long.encodeToByteArray().size <= CANONICAL_NAME_MAX_BYTES, long)
        assertTrue(long.endsWith("….mp4") && long.startsWith("ABC-123-C 很长"), long)
        assertEquals(long, canonical(long), "截短的名字也不再变")
        // 片名开头恰是标签词时，按再解析的结果收敛
        assertEquals("ABC-123 片名.mp4", canonicalAvName("x.mp4", parseMediaName("ABC-123.mp4").av!!, "4K 片名"))
    }

    @Test
    fun `a batch keeps parts, followers and versions apart`() {
        val names = canonicalAvNames(
            listOf(
                AvNamingItem("abc-123-A.mp4", "d"),
                AvNamingItem("abc-123-B.mp4", "d"),
                AvNamingItem("abc-123-C.mp4", "d"),
                AvNamingItem("abc-123-C.zh.srt", "d"),
                AvNamingItem("xyz-456 片名 1080p.mp4", "e"),
                AvNamingItem("xyz-456 片名 4K.mp4", "e"),
                AvNamingItem("XYZ-456-zh.srt", "e"),
                AvNamingItem("[site.net] XYZ-456 片名", "f", isFolder = true),
            ),
            titles = mapOf("XYZ-456" to "查到的片名"),
        )
        assertEquals(
            listOf(
                "ABC-123-CD1.mp4", "ABC-123-CD2.mp4", "ABC-123-CD3.mp4", "ABC-123-CD3.zh.srt",
                "XYZ-456 查到的片名 [1080p].mp4", "XYZ-456 查到的片名 [4K].mp4", "XYZ-456 查到的片名.zh.srt",
                "XYZ-456 查到的片名",
            ),
            names,
        )
    }

    @Test
    fun `withAvFlags toggles -U and -C cleanly across files parts folders and raw names`() {
        // 标准文件：空 -> -C -> -UC -> -U -> 空，保留用户自定义文本
        val base = "ABC-123 某片名 [个人备注].mp4"
        val withC = withAvFlags(base, isFolder = false, uncensored = false, chineseSubtitles = true)
        assertEquals("ABC-123-C 某片名 [个人备注].mp4", withC)
        val withUC = withAvFlags(withC!!, isFolder = false, uncensored = true, chineseSubtitles = true)
        assertEquals("ABC-123-UC 某片名 [个人备注].mp4", withUC)
        val withU = withAvFlags(withUC!!, isFolder = false, uncensored = true, chineseSubtitles = false)
        assertEquals("ABC-123-U 某片名 [个人备注].mp4", withU)
        val cleared = withAvFlags(withU!!, isFolder = false, uncensored = false, chineseSubtitles = false)
        assertEquals("ABC-123 某片名 [个人备注].mp4", cleared)

        // 无片名分段文件：不误吃 -CD1 的 -C
        assertEquals(
            "ABC-123-C-CD1.mp4",
            withAvFlags("ABC-123-CD1.mp4", isFolder = false, uncensored = false, chineseSubtitles = true),
        )
        assertEquals(
            "ABC-123-UC-CD1.mp4",
            withAvFlags("ABC-123-C-CD1.mp4", isFolder = false, uncensored = true, chineseSubtitles = true),
        )
        assertEquals(
            "ABC-123-CD1.mp4",
            withAvFlags("ABC-123-C-CD1.mp4", isFolder = false, uncensored = false, chineseSubtitles = false),
        )

        // 文件夹与尚未规范化的原始文件名
        assertEquals(
            "FC2-PPV-1234567-U 标题",
            withAvFlags("FC2-PPV-1234567 标题", isFolder = true, uncensored = true, chineseSubtitles = false),
        )
        assertEquals(
            "ABC-123-UC.mp4",
            withAvFlags("xxx.com@abc00123-c_4k.mp4", isFolder = false, uncensored = true, chineseSubtitles = true),
        )
        assertEquals(
            "ABC-123 片名.mp4",
            withAvFlags("[中文字幕] ABC-123 片名.mp4", isFolder = false, uncensored = false, chineseSubtitles = false),
        )
    }
}
