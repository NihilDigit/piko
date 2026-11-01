package dev.piko.shared.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 批量粘贴的文本来自论坛帖、聊天记录与种子站，同一个种子常以几种写法重复出现。 */
class MagnetLinkTextTest {
    @Test
    fun `app and web shares normalize and preserve their own pass codes in mixed input`() {
        val links = extractLinks("""
            pikpakapp://mypikpak.com/drive/share_watch?share_id=first&pass_code=1234
            https://mypikpak.net/s/second 提取码：abcd
            magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567
        """.trimIndent())
        assertEquals(listOf("https://mypikpak.com/s/first?pwd=1234", "https://mypikpak.com/s/second?pwd=abcd",
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"), links.map { it.uri })
    }

    @Test
    fun `official share aliases deduplicate after normalization`() {
        assertEquals(1, extractLinks("https://mypikpak.com/s/abc https://pikpakdrive.com/s/abc").size)
    }

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
    fun `ed2k names may contain spaces and percent encoded pipes`() {
        val ed2k = "ed2k://|file|Some Movie 1080p.mkv|123456|0123456789ABCDEF0123456789ABCDEF|/"
        assertEquals(listOf(ed2k), extractLinks("下载 $ed2k 谢谢").map { it.uri })

        val encoded = "ed2k://%7Cfile%7C%E7%94%B5%E5%BD%B1%2001.mkv%7C987654321%7C7683295B023BD0D52F8FC24FA381AA53%7Ch=65543252C2F0F1C2A653D3270296A476%7C/"
        val extracted = extractLinks("复制自浏览器：$encoded").single()
        assertEquals(
            "ed2k://|file|%E7%94%B5%E5%BD%B1%2001.mkv|987654321|7683295B023BD0D52F8FC24FA381AA53|h=65543252C2F0F1C2A653D3270296A476|/",
            extracted.uri,
        )
        val resolved = dev.piko.shared.data.ed2kResource(extracted.uri)!!
        assertEquals("电影 01.mkv", resolved.resource.name)
        assertEquals(987654321L, resolved.items.single().file.size)
    }

    @Test
    fun `thunder links unwrap into underlying magnet or ed2k`() {
        val rawEd2k = "ed2k://|file|Ep01.mkv|4096|0123456789ABCDEF0123456789ABCDEF|/"
        val thunderEd2k = "thunder://" + java.util.Base64.getEncoder().encodeToString("AA${rawEd2k}ZZ".toByteArray())
        assertEquals(listOf(rawEd2k), extractLinks(thunderEd2k).map { it.uri })

        val rawMagnet = "magnet:?xt=urn:btih:$hex"
        val thunderMagnet = "thunder://" + java.util.Base64.getEncoder().encodeToString("AA${rawMagnet}ZZ".toByteArray())
        val unwrapped = extractLinks(thunderMagnet).single()
        assertEquals(hex, unwrapped.infoHash)
        assertEquals(rawMagnet, unwrapped.uri)
    }

    @Test
    fun `a single link keeps the single-link path`() {
        assertEquals("magnet:?xt=urn:btih:$hex", InstantSheetState.normalizeMagnet(hex))
        assertEquals("magnet:?xt=urn:btih:$hex", InstantSheetState.normalizeMagnet("看这个 $base32"))
        assertNull(InstantSheetState.normalizeMagnet("$hex https://example.com/a.mp4"))
    }
}
