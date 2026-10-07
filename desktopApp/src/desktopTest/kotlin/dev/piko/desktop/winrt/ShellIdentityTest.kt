package dev.piko.desktop.winrt

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellIdentityTest {

    /**
     * 正式包的名字已随 1.1.0 写进用户的注册表，UserChoice 与通知设置认的就是它们。期望值照抄 v1.1.0 的
     * WindowsLinkAssociation 与 WinRTSupport，不从 ShellIdentity 推出来：派生规则一改，这里就对不上。
     */
    @Test
    fun releaseNamesMatchWhat110Registered() {
        val identity = ShellIdentity(ShellIdentity.DEFAULT)
        assertEquals("dev.piko.Piko", identity.appUserModelId)
        assertEquals("Piko.Magnet", identity.magnetProgId)
        assertEquals("Piko.Torrent", identity.torrentProgId)
        assertEquals("Software\\Piko", identity.vendorKey)
        assertEquals("Software\\Piko\\Capabilities", identity.capabilitiesKey)
        assertEquals("Piko", identity.registeredName)
    }
}
