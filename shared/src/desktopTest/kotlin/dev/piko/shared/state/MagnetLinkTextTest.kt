package dev.piko.shared.state

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MagnetLinkTextTest {
    private val hex = "c9e15763f722f23e98a29decdfae341b98d53056"
    private val base32 = "ZHQVOY7XELZD5GFCTXWN7LRUDOMNKMCW"

    private val torrent = Base64.getDecoder().decode(
        "ZDg6YW5ub3VuY2UyNDp1ZHA6Ly90cmFja2VyLmV4YW1wbGU6ODA0OmluZm9kNTpmaWxlc2xkNjpsZW5ndGhpMWU0OnBhdGhsODplcDAxLmFz" +
            "c2VlZDY6bGVuZ3RoaTEwZTQ6cGF0aGw4OmVwMDEubWt2ZWVlNDpuYW1lMTA655Wq5YmnIFMwMTEyOnBpZWNlIGxlbmd0aGkxNjM4NGU2OnBp" +
            "ZWNlczIwOs01YLZsKz6P31CQa7ytHSfULefdNzpwcml2YXRlaTBlZWU=",
    )

    @Test
    fun `app and web shares normalize and preserve their own pass codes in mixed input`() {
        val links = extractLinks("""
            pikpakapp://mypikpak.com/drive/share_watch?share_id=first&pass_code=1234
            https://mypikpak.net/s/second 提取码：abcd
            magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567
        """.trimIndent())
        assertEquals(
            listOf(
                "https://mypikpak.com/s/first?pwd=1234",
                "https://mypikpak.com/s/second?pwd=abcd",
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            ),
            links.map { it.uri },
        )
    }

    @Test
    fun `official share aliases deduplicate after normalization`() {
        assertEquals(1, extractLinks("https://mypikpak.com/s/abc https://pikpakdrive.com/s/abc").size)
    }

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
        assertEquals(emptyList(), extractLinks("${hex}0 x$hex"))
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
        val thunderEd2k = "thunder://" + Base64.getEncoder().encodeToString("AA${rawEd2k}ZZ".toByteArray())
        assertEquals(listOf(rawEd2k), extractLinks(thunderEd2k).map { it.uri })

        val rawMagnet = "magnet:?xt=urn:btih:$hex"
        val thunderMagnet = "thunder://" + Base64.getEncoder().encodeToString("AA${rawMagnet}ZZ".toByteArray())
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

    @Test
    fun `torrent file bytes derive infohash magnet and reject malformed input`() {
        assertEquals(
            "magnet:?xt=urn:btih:500a68f42941ca214ba78490c1f5aafbaec5581e&dn=%E7%95%AA%E5%89%A7%20S01",
            TorrentMagnet.fromBytes(torrent),
        )
        assertNull(TorrentMagnet.fromBytes("hello".toByteArray()))
        assertNull(TorrentMagnet.fromBytes(torrent.copyOf(40)))
    }
}
