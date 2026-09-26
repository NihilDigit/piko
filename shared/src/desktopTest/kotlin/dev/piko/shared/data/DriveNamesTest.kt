package dev.piko.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 用例取自 2026-09-26 对 PikPak 重命名接口的实测，见 [DriveNames]。 */
class DriveNamesTest {

    // 服务端拒绝的名称：清理后的结果不能再被拒
    private val rejected = listOf(
        ".", "..", "...", "a.", "a..", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\tb", "a\nb", "a\rb", "ab\r",
        "第 1 话: 标题.", "a. .",
    )

    // 服务端接受的名称：不该弹框打扰用户
    private val accepted = listOf(".a", "a%b", "a#b", "a&b", "a+b", "a'b", "CON", "nul.txt", "日本語　全角空格", "a\u0001b", "a\u007fb", "😀")

    @Test
    fun `cleaned names pass the rules the server enforces`() {
        for (name in rejected) {
            val cleaned = DriveNames.clean(name)
            if (cleaned.isEmpty()) continue
            assertTrue(DriveNames.unsupportedParts(cleaned).isEmpty(), "'$name' -> '$cleaned'")
            assertTrue(!cleaned.endsWith('.') && cleaned == cleaned.trim(' '), "'$name' -> '$cleaned'")
        }
    }

    @Test
    fun `names the server accepts are left alone`() {
        for (name in accepted) {
            assertTrue(DriveNames.unsupportedParts(name).isEmpty(), name)
            assertEquals(name, DriveNames.clean(name))
        }
    }

    // 界面按这些位置标出改动，删掉它们必须恰好得到 clean 的结果，否则标出来的与实际保存的对不上
    @Test
    fun `removed positions account for exactly what clean drops`() {
        for (name in rejected + accepted + listOf("  a:b. . ", " :. ")) {
            val removed = DriveNames.removedIndices(name)
            val rest = name.filterIndexed { index, _ -> index !in removed }
            assertEquals(DriveNames.clean(name), rest, "'$name'")
        }
    }

    // 去掉结尾句点后露出空格，去掉空格后又露出句点
    @Test
    fun `dots and spaces at the end are peeled until stable`() {
        assertEquals("a", DriveNames.clean("a. . ."))
        assertEquals("第 1 话 标题", DriveNames.clean("第 1 话: 标题."))
    }
}
