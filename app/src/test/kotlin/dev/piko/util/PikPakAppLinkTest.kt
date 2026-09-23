package dev.piko.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PikPakAppLinkTest {

    @Test
    fun `bot link with doubly encoded route resolves to drive`() {
        // Telegram 机器人实际发出的链接
        val link = "https://toapp.mypikpak.com/toapp?deepLink=%252Fdrive%252Fmain_tab%253Ftab%253D1" +
            "%2526uid%253DZCcFVC1NsCqjRpV3%2526from%253Dother%25252Fbot%2526result%253Dsuccess"
        assertEquals(PikPakAppLink.Target.Drive, PikPakAppLink.parse(link))
    }

    @Test
    fun `other hosts and unknown routes are ignored`() {
        assertNull(PikPakAppLink.parse("https://example.com/toapp?deepLink=%252Fdrive"))
        assertNull(PikPakAppLink.parse("https://toapp.mypikpak.com/toapp?deepLink=%252Fvip%252Fpay"))
        assertNull(PikPakAppLink.parse("https://toapp.mypikpak.com/toapp"))
    }
}
