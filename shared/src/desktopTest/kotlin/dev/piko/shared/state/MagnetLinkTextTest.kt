package dev.piko.shared.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 批量粘贴的文本来自论坛帖、聊天记录与种子站，同一个种子常以几种写法重复出现。 */
class MagnetLinkTextTest {
    private val hex = "c9e15763f722f23e98a29decdfae341b98d53056"

    // 同一个 hash 的 Base32 写法，用 coreutils 的 base32 另行算出
    private val base32 = "ZHQVOY7XELZD5GFCTXWN7LRUDOMNKMCW"

    @Test
    fun `base32 and hex forms of one torrent collapse into one hex magnet`() {
        val text = """
            第一集 magnet:?xt=urn:btih:$base32&dn=Show%20E01
            备用 ${hex.uppercase()}
            再贴一遍 $base32
        """.trimIndent()
        val links = extractLinks(text)
        assertEquals(1, links.size)
        assertEquals(hex, links.single().infoHash)
        // 第一次出现的写法保留参数，hash 换成十六进制
        assertEquals("magnet:?xt=urn:btih:$hex&dn=Show%20E01", links.single().uri)
    }

    @Test
    fun `links end at chinese punctuation and trailing sentence marks`() {
        val other = "0123456789abcdef0123456789abcdef01234567"
        val text = "资源：magnet:?xt=urn:btih:$hex&dn=某剧，另一个见 https://example.com/a.mp4. 还有 magnet:?xt=urn:btih:$other。"
        assertEquals(
            listOf(
                "magnet:?xt=urn:btih:$hex&dn=某剧",
                "https://example.com/a.mp4",
                "magnet:?xt=urn:btih:$other",
            ),
            extractLinks(text).map { it.uri },
        )
    }

    @Test
    fun `hashes glued to other characters are not extracted`() {
        // 41 位与夹在长串中间的都不算，掐掉一位凑出来的 hash 指向的是不存在的种子
        assertEquals(emptyList(), extractLinks("${hex}0 x$hex"))
        // 网址里的 hash 属于网址本身
        assertEquals(listOf(null), extractLinks("https://example.com/torrent/$hex").map { it.infoHash })
    }

    @Test
    fun `ed2k names may contain spaces`() {
        val ed2k = "ed2k://|file|Some Movie 1080p.mkv|123456|0123456789ABCDEF0123456789ABCDEF|/"
        assertEquals(listOf(ed2k), extractLinks("下载 $ed2k 谢谢").map { it.uri })
    }

    @Test
    fun `a single link keeps the single-link path`() {
        assertEquals("magnet:?xt=urn:btih:$hex", InstantSheetState.normalizeMagnet(hex))
        assertEquals("magnet:?xt=urn:btih:$hex", InstantSheetState.normalizeMagnet("看这个 $base32"))
        assertNull(InstantSheetState.normalizeMagnet("$hex https://example.com/a.mp4"))
    }
}
