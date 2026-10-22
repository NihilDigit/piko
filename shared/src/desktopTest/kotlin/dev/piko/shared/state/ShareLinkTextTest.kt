package dev.piko.shared.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 分享常以整段话转发，链接不在开头、提取码跟在后面，写法各不相同。 */
class ShareLinkTextTest {

    @Test
    fun `finds the link and pass code inside a forwarded message`() {
        val text = "我用 PikPak 分享了「某个合集」，链接：https://mypikpak.com/s/VPabc_123-x 提取码：k3f9 复制这段内容后打开 App"
        assertEquals("https://mypikpak.com/s/VPabc_123-x", InstantSheetState.findShareLink(text))
        assertEquals("k3f9", InstantSheetState.findSharePassCode(text))
    }

    @Test
    fun `pass code may sit on the link or after a plain label`() {
        assertEquals("ab12", InstantSheetState.findSharePassCode("https://mypikpak.com/s/VPxyz?pwd=ab12"))
        assertEquals("zq47", InstantSheetState.findSharePassCode("https://www.mypikpak.com/s/VPxyz 密码 zq47"))
    }

    // 自定义提取码最长 10 位，截成 8 位就打不开
    @Test
    fun `text piko copies out is read back whole`() {
        val text = ShareCreateState.shareText("某个合集 等 3 项", "https://mypikpak.com/s/VPabc_123-x", "abcde12345")
        assertEquals("https://mypikpak.com/s/VPabc_123-x", InstantSheetState.findShareLink(text))
        assertEquals("abcde12345", InstantSheetState.findSharePassCode(text))
    }

    @Test
    fun `official share domains normalize to the SDK domain`() {
        listOf("mypikpak.com", "mypikpak.net", "pikpak.me", "pikpakdrive.com", "pickpackapp.com").forEach { host ->
            listOf(host, "www.$host", "share.$host").forEach { domain ->
                listOf("http", "https").forEach { scheme ->
                    assertEquals("https://mypikpak.com/s/VPabc_123-x",
                        InstantSheetState.findShareLink("链接：$scheme://$domain/s/VPabc_123-x?pwd=k3f9"))
                }
            }
        }
    }

    @Test
    fun `custom app share links expose share ID and pass code`() {
        val link = "pikpakapp://mypikpak.com/drive/share_watch?share_id=VPabc_123&pass_code=k3f9&from=web"
        assertEquals("https://mypikpak.com/s/VPabc_123", InstantSheetState.findShareLink(link))
        assertEquals("k3f9", InstantSheetState.findSharePassCode(link))
        assertEquals("https://mypikpak.com/s/VPabc_123", InstantSheetState.findShareLink(
            "pikpakapp://mypikpak.com/drive/share_watch?from=web&share_id=VPabc_123"))
    }

    @Test
    fun `empty query pass code does not consume the following parameter`() {
        val link = "pikpakapp://mypikpak.com/drive/share_watch?share_id=VPabc_123&pass_code=&from=web"
        assertEquals("https://mypikpak.com/s/VPabc_123", InstantSheetState.findShareLink(link))
        assertNull(InstantSheetState.findSharePassCode(link))
        assertNull(InstantSheetState.findSharePassCode("https://mypikpak.com/s/VPabc?pass_code&from=web"))
        assertNull(InstantSheetState.findSharePassCode("https://mypikpak.com/s/VPabc?pass_code=from=web"))
        assertEquals("abcd", InstantSheetState.findSharePassCode("pass_code: abcd"))
    }

    @Test
    fun `other links are not shares`() {
        assertNull(InstantSheetState.findShareLink("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
        assertNull(InstantSheetState.findShareLink("https://toapp.mypikpak.com/toapp?deepLink=%252Fdrive"))
        assertNull(InstantSheetState.findSharePassCode("https://mypikpak.com/s/VPxyz"))
    }
}
