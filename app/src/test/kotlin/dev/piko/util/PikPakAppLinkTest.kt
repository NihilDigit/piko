package dev.piko.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PikPakAppLinkTest {

    @Test
    fun `bot link with doubly encoded route resolves to drive`() {
        // Telegram 机器人实际发出的链接
        val link = "https://toapp.mypikpak.com/toapp?deepLink=%252Fdrive%252Fmain_tab%253Fhash%253DGQR95L938wuFTOxGxlqrzw%2526tab%253D1" +
            "%2526uid%253DZCcFVC1NsCqjRpV3%2526from%253Dother%25252Fbot%2526result%253Dsuccess"
        assertEquals(PikPakAppLink.Target.Drive, PikPakAppLink.parse(link))
    }

    @Test
    fun `app routes open drive without requiring a known internal path`() {
        listOf(
            "https://toapp.mypikpak.com/toapp",
            "https://toapp.mypikpak.net/anything",
            "https://toapp.pickpackapp.com/",
            "https://toapp.mypikpak.com/toapp?deepLink=%252Fvip%252Fpay",
            "pikpakapp://mypikpak.com/drive/main_tab?tab=1&hash=GQR95L938wuFTOxGxlqrzw",
            "pikpakapp://mypikpak.com/xpan/main_tab?tab=1",
            "pikpakapp://other-host/arbitrary/path",
            "pikpakapp://mypikpak.com/drive?add_url=",
            "https://toapp.mypikpak.com/toapp?__add_url=unrecognized",
        ).forEach { assertEquals(it, PikPakAppLink.Target.Drive, PikPakAppLink.parse(it)) }
    }

    @Test
    fun `official domains accept toapp and deeplink routes`() {
        listOf("mypikpak.com", "mypikpak.net", "pikpak.me", "pikpakdrive.com", "pickpackapp.com").forEach { host ->
            listOf(host, "www.$host").forEach { domain ->
                listOf("/toapp", "/deeplink/drive").forEach { path ->
                    assertEquals(PikPakAppLink.Target.Drive, PikPakAppLink.parse("https://$domain$path"))
                }
            }
        }
    }

    @Test
    fun `shares and additions are forwarded for link parsing`() {
        val shareWatch = "pikpakapp://mypikpak.com/drive/share_watch?share_id=VPabc&pass_code=1234"
        assertEquals(PikPakAppLink.Target.Import(shareWatch), PikPakAppLink.parse(shareWatch))
        assertEquals(
            PikPakAppLink.Target.Import("https://pikpak.me/s/VPabc"),
            PikPakAppLink.parse("pikpakapp://mypikpak.com/drive?add_url=https%3A%2F%2Fpikpak.me%2Fs%2FVPabc"),
        )
        val rawMagnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        val magnet = "magnet%3A%3Fxt%3Durn%3Abtih%3A0123456789abcdef0123456789abcdef01234567"
        assertEquals(
            PikPakAppLink.Target.Import(rawMagnet),
            PikPakAppLink.parse("pikpakapp://mypikpak.com/drive?__add_url=$magnet"),
        )
        assertEquals(
            PikPakAppLink.Target.Import(rawMagnet),
            PikPakAppLink.parse("https://toapp.mypikpak.com/toapp?__add_url=$magnet"),
        )
    }

    @Test
    fun `unrelated and malformed links are ignored`() {
        assertNull(PikPakAppLink.parse("https://example.com/toapp?deepLink=%252Fdrive"))
        assertNull(PikPakAppLink.parse("https://mypikpak.com/s/VPabc"))
        assertNull(PikPakAppLink.parse("http://toapp.mypikpak.com/toapp"))
        assertNull(PikPakAppLink.parse("not a URI"))
    }
}
