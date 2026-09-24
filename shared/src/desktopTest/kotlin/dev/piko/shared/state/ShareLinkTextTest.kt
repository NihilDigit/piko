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

    @Test
    fun `other links are not shares`() {
        assertNull(InstantSheetState.findShareLink("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
        assertNull(InstantSheetState.findShareLink("https://toapp.mypikpak.com/toapp?deepLink=%252Fdrive"))
        assertNull(InstantSheetState.findSharePassCode("https://mypikpak.com/s/VPxyz"))
    }
}
